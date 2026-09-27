package com.example.constructionaddon.entity;

import com.example.constructionaddon.ConstructionAddon;
import com.example.constructionaddon.ConstructionServerConfig;
import com.example.constructionaddon.asset.ConstructionSettings;
import com.example.constructionaddon.asset.ConstructionSettingsRegistry;
import com.example.constructionaddon.asset.Joint;
import com.example.constructionaddon.asset.SeatPart;
import com.example.constructionaddon.asset.SwingSound;
import com.example.constructionaddon.asset.WorkPoint;
import com.example.constructionaddon.machine.JointSolver;
import com.example.constructionaddon.machine.MachineModule;
import com.example.constructionaddon.machine.MachineType;
import com.example.constructionaddon.network.WorkAxis;
import com.example.tudursvehiclemod.asset.SeatDefinition;
import com.example.tudursvehiclemod.asset.VehicleDefinition;
import com.example.tudursvehiclemod.entity.CarEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Every construction machine: a CarEntity (driving, tracks/wheels, fuel, inventory, seats, HUD
 * and destruction stay the base mod's own) plus one MachineModule chosen by the vehicle JSON's
 * "construction.machine".
 *
 * <p>Shared by every module:
 * <ul>
 *   <li>synced channels ({@link #FLOAT_CHANNELS} floats, {@link #INT_CHANNELS} ints), written by
 *       the module on the server; floats drive the JSON joints and are interpolated for drawing;</li>
 *   <li>the joint matrices, used both for drawing and for {@link #workPoint(String)};</li>
 *   <li>work inputs: the work axes and the two work keys, from this addon's own payloads;</li>
 *   <li>seats riding on a joint (a cab on a slewing upper structure), including the rider's view.</li>
 * </ul> */
public class ConstructionMachineEntity extends CarEntity {

	public static final int FLOAT_CHANNELS = 8;
	public static final int INT_CHANNELS = 4;

	@SuppressWarnings("unchecked")
	private static final TrackedData<Float>[] FLOAT_DATA = new TrackedData[FLOAT_CHANNELS];
	@SuppressWarnings("unchecked")
	private static final TrackedData<Integer>[] INT_DATA = new TrackedData[INT_CHANNELS];

	static {
		for (int i = 0; i < FLOAT_CHANNELS; i++) {
			FLOAT_DATA[i] = DataTracker.registerData(ConstructionMachineEntity.class, TrackedDataHandlerRegistry.FLOAT);
		}
		for (int i = 0; i < INT_CHANNELS; i++) {
			INT_DATA[i] = DataTracker.registerData(ConstructionMachineEntity.class, TrackedDataHandlerRegistry.INTEGER);
		}
	}

	/** Machines whose module asked to run after every entity in the world ticked. */
	public static final Set<ConstructionMachineEntity> END_TICK_MACHINES = ConcurrentHashMap.newKeySet();

	private MachineModule module;

	/** Client: channel values at the previous and current tick, for render interpolation. */
	private final float[] prevChannels = new float[FLOAT_CHANNELS];
	private final float[] currentChannels = new float[FLOAT_CHANNELS];
	private boolean clientChannelsPrimed;
	/** Client: accumulated angle of every spin-mode joint, by part name. */
	private final Map<String, Float> spinPhase = new HashMap<>();
	private final Map<String, Float> prevSpinPhase = new HashMap<>();

	private boolean primaryHeld;
	private boolean secondaryHeld;
	private final float[] workAxes = new float[WorkAxis.values().length];
	private boolean lastPrimaryHeld;
	private boolean lastSecondaryHeld;
	private boolean primaryPressed;
	private boolean secondaryPressed;
	private int swingSoundCooldown;

	public ConstructionMachineEntity(EntityType<?> type, World world) {
		super(type, world);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		for (TrackedData<Float> data : FLOAT_DATA) {
			builder.add(data, 0f);
		}
		for (TrackedData<Integer> data : INT_DATA) {
			builder.add(data, 0);
		}
	}

	@Override
	protected Identifier defaultDefinitionId() {
		return Identifier.of(ConstructionAddon.MOD_ID, "backhoe");
	}

	/** CarEntity pins its box at 1.6 x 1.0; this restores the base mod's own general behaviour of
	 * honouring force_bounding_box, since machines vary so much in size. */
	@Override
	public EntityDimensions getDimensions(EntityPose pose) {
		VehicleDefinition def = this.getDefinition();
		float rawWidth = def.forceBoundingBox() ? def.width() : 1.6f;
		float rawHeight = def.forceBoundingBox() ? def.height() : 1.0f;
		return EntityDimensions.changing(Math.max(0.1f, rawWidth * def.scale()), Math.max(0.1f, rawHeight * def.scale()));
	}

	// ------------------------------------------------------------------
	// Settings and module
	// ------------------------------------------------------------------

	public ConstructionSettings settings() {
		return ConstructionSettingsRegistry.get(this.getVehicleDefinitionId());
	}

	/** This machine's module, (re)created whenever the definition names a different machine. */
	public MachineModule module() {
		MachineType type = this.settings().machine();
		if (this.module == null || this.module.type() != type) {
			this.module = type.create(this);
		}
		return this.module;
	}

	// ------------------------------------------------------------------
	// Channels
	// ------------------------------------------------------------------

	public float getFloatChannel(int index) {
		return this.dataTracker.get(FLOAT_DATA[index]);
	}

	/** Server only - the client just receives. */
	public void setFloatChannel(int index, float value) {
		if (!this.getEntityWorld().isClient()) {
			this.dataTracker.set(FLOAT_DATA[index], value);
		}
	}

	public int getIntChannel(int index) {
		return this.dataTracker.get(INT_DATA[index]);
	}

	public void setIntChannel(int index, int value) {
		if (!this.getEntityWorld().isClient()) {
			this.dataTracker.set(INT_DATA[index], value);
		}
	}

	private int channelIndex(String name) {
		String[] names = this.module().floatChannels();
		for (int i = 0; i < names.length && i < FLOAT_CHANNELS; i++) {
			if (names[i].equals(name)) {
				return i;
			}
		}
		return -1;
	}

	/** Current value of a named channel (either side). Unknown names read 0. */
	public float channel(String name) {
		int index = this.channelIndex(name);
		return index < 0 ? 0f : this.getFloatChannel(index);
	}

	/** Render-interpolated value of a named channel (client). */
	private float channelLerp(String name, float tickDelta) {
		int index = this.channelIndex(name);
		if (index < 0) {
			return 0f;
		}
		if (!this.clientChannelsPrimed) {
			return this.getFloatChannel(index);
		}
		return MathHelper.lerp(tickDelta, this.prevChannels[index], this.currentChannels[index]);
	}

	private void updateClientChannels() {
		for (int i = 0; i < FLOAT_CHANNELS; i++) {
			float value = this.getFloatChannel(i);
			this.prevChannels[i] = this.clientChannelsPrimed ? this.currentChannels[i] : value;
			this.currentChannels[i] = value;
		}
		this.clientChannelsPrimed = true;
		for (Joint joint : this.settings().joints()) {
			if (joint.mode() != Joint.Mode.SPIN) {
				continue;
			}
			float speed = joint.channel().map(this::channel).orElse(1f) * joint.factor();
			float phase = this.spinPhase.getOrDefault(joint.part(), 0f);
			this.prevSpinPhase.put(joint.part(), phase);
			float next = phase + speed;
			// Kept bounded; prev is shifted by the same whole turn so interpolation never jumps.
			if (Math.abs(next) > 3600f) {
				float wrap = 3600f * Math.signum(next);
				next -= wrap;
				this.prevSpinPhase.put(joint.part(), phase - wrap);
			}
			this.spinPhase.put(joint.part(), next);
		}
	}

	// ------------------------------------------------------------------
	// Joints and work points
	// ------------------------------------------------------------------

	@Override
	public Map<String, Matrix4f> tudursvehiclemod$getCustomPartTransforms(float tickDelta) {
		return this.jointMatrices(tickDelta);
	}

	/** Client: interpolated for drawing. Server: current values. */
	public Map<String, Matrix4f> jointMatrices(float tickDelta) {
		ConstructionSettings settings = this.settings();
		if (settings.joints().isEmpty()) {
			return Map.of();
		}
		if (this.getEntityWorld().isClient()) {
			return JointSolver.solve(settings.joints(),
					name -> this.channelLerp(name, tickDelta),
					joint -> MathHelper.lerp(tickDelta,
							this.prevSpinPhase.getOrDefault(joint.part(), 0f),
							this.spinPhase.getOrDefault(joint.part(), 0f)));
		}
		return JointSolver.solve(settings.joints(), this::channel, joint -> 0f);
	}

	/** A named work point in world space, following its joint chain. */
	public Vec3d workPoint(String name) {
		return this.workPoint(this.settings().workPoint(name));
	}

	public Vec3d workPoint(WorkPoint point) {
		return this.modelToWorld(this.workPointModel(point));
	}

	/** A work point in model coordinates, after its joint chain has moved it. */
	public Vector3f workPointModel(String name) {
		return this.workPointModel(this.settings().workPoint(name));
	}

	public Vector3f workPointModel(WorkPoint point) {
		Vector3f model = new Vector3f(point.x(), point.y(), point.z());
		if (point.part().isPresent()) {
			Matrix4f matrix = this.jointMatrices(1f).get(point.part().get());
			if (matrix != null) {
				matrix.transformPosition(model);
			}
		}
		return model;
	}

	/** Model coordinates (rest frame, before scale) to world. */
	public Vec3d modelToWorld(Vector3f model) {
		Vector3f v = new Vector3f(model).mul(this.getDefinition().scale());
		this.tudursvehiclemod$getBodyOrientation().transform(v);
		Vec3d offset = this.tudursvehiclemod$getBodyFrameOffset(1f);
		return new Vec3d(this.getX() + offset.x + v.x, this.getY() + offset.y + v.y, this.getZ() + offset.z + v.z);
	}

	/** World to model coordinates (rest frame, before scale). */
	public Vector3f worldToModel(Vec3d world) {
		Vec3d offset = this.tudursvehiclemod$getBodyFrameOffset(1f);
		Vector3f v = new Vector3f(
				(float) (world.x - this.getX() - offset.x),
				(float) (world.y - this.getY() - offset.y),
				(float) (world.z - this.getZ() - offset.z));
		this.tudursvehiclemod$getBodyOrientation().transformInverse(v);
		return v.div(Math.max(1.0e-4f, this.getDefinition().scale()));
	}

	// ------------------------------------------------------------------
	// Work inputs
	// ------------------------------------------------------------------

	/** From WorkInputPayload (server) - only the driver's keys count. */
	public void setWorkInputs(boolean primary, boolean secondary) {
		this.primaryHeld = primary;
		this.secondaryHeld = secondary;
	}

	/** From WorkAxisPayload (server) - only the driver's keys count. */
	public void setWorkAxisMask(int mask) {
		for (WorkAxis axis : WorkAxis.values()) {
			this.workAxes[axis.ordinal()] = WorkAxis.unpack(mask, axis);
		}
	}

	public boolean isPrimaryHeld() {
		return this.primaryHeld;
	}

	public boolean isSecondaryHeld() {
		return this.secondaryHeld;
	}

	/** True only on the tick the primary key went down. */
	public boolean isPrimaryPressed() {
		return this.primaryPressed;
	}

	public boolean isSecondaryPressed() {
		return this.secondaryPressed;
	}

	/** Work mode = the base mod's manual-mode flag (hold M). Never blocks driving by itself. */
	public boolean isWorkMode() {
		return this.isManualMode();
	}

	/** -1 / 0 / +1 from this addon's own key bindings (see WorkAxis). */
	public float workAxis(WorkAxis axis) {
		return this.workAxes[axis.ordinal()];
	}

	private boolean drivingSuppressed() {
		return this.module().suppressesDriving();
	}

	@Override
	public float getSyncedThrottleInput() {
		return this.drivingSuppressed() ? 0f : super.getSyncedThrottleInput();
	}

	@Override
	public float getSyncedSidewaysInput() {
		return this.drivingSuppressed() ? 0f : super.getSyncedSidewaysInput();
	}

	@Override
	public boolean getSyncedBrakeInput() {
		return this.drivingSuppressed() || super.getSyncedBrakeInput();
	}

	/** The seated driver as a server player, or null. */
	public ServerPlayerEntity operator() {
		return this.getControllingPassenger() instanceof ServerPlayerEntity player ? player : null;
	}

	public static boolean isCreative(PlayerEntity player) {
		return player != null && player.isCreative();
	}

	// ------------------------------------------------------------------
	// Movement hooks
	// ------------------------------------------------------------------

	@Override
	protected void updateVehicleMovement(VehicleDefinition def) {
		MachineModule module = this.module();
		if (module.overridesMovement()) {
			module.updateMovement(def);
			return;
		}
		if (module.suppressesDriving()) {
			// Cancels the base mod's cruise-control throttle so the machine actually stops.
			this.setThrottleDirect(0f);
		}
		super.updateVehicleMovement(def);
	}

	/** CarEntity's own driving physics, for a module that only sometimes takes over movement. */
	public void runCarMovement(VehicleDefinition def) {
		super.updateVehicleMovement(def);
	}

	/** Extra drag from the machine's work (a loaded blade biting into the ground). */
	public void scaleCruiseSpeed(float factor) {
		this.cruiseSpeed *= factor;
	}

	public float getCruiseSpeedValue() {
		return this.cruiseSpeed;
	}

	@Override
	public float tudursvehiclemod$getEffectiveMaxSpeed() {
		return super.tudursvehiclemod$getEffectiveMaxSpeed() * this.module().maxSpeedFactor();
	}

	@Override
	public boolean tudursvehiclemod$tryToggleHatch() {
		if (this.module().handleHatchToggle()) {
			return true;
		}
		return super.tudursvehiclemod$tryToggleHatch();
	}

	// ------------------------------------------------------------------
	// Tick
	// ------------------------------------------------------------------

	@Override
	public void tick() {
		super.tick();
		if (this.isRemoved()) {
			return;
		}
		MachineModule module = this.module();
		if (this.getEntityWorld().isClient()) {
			this.updateClientChannels();
			this.followJointSeatYawClient();
		}
		module.tick();
		if (!(this.getEntityWorld() instanceof ServerWorld serverWorld)) {
			return;
		}

		ServerPlayerEntity operator = this.operator();
		if (operator == null) {
			this.primaryHeld = false;
			this.secondaryHeld = false;
			java.util.Arrays.fill(this.workAxes, 0f);
		}
		this.primaryPressed = this.primaryHeld && !this.lastPrimaryHeld;
		this.secondaryPressed = this.secondaryHeld && !this.lastSecondaryHeld;
		this.lastPrimaryHeld = this.primaryHeld;
		this.lastSecondaryHeld = this.secondaryHeld;

		if (this.swingSoundCooldown > 0) {
			this.swingSoundCooldown--;
		}
		if (!this.tudursvehiclemod$isDestroyed()) {
			module.serverTick(serverWorld, operator);
		}
		if (module.wantsEndWorldTick()) {
			END_TICK_MACHINES.add(this);
		}

		if (operator != null && this.age % 10 == 0 && ConstructionServerConfig.get().showStatusMessages) {
			Text status = module.status();
			if (status != null) {
				operator.sendMessage(status, true);
			}
		}
	}

	@Override
	public void onRemoved() {
		super.onRemoved();
		END_TICK_MACHINES.remove(this);
	}

	// ------------------------------------------------------------------
	// Seats riding on joints
	// ------------------------------------------------------------------

	/** World-space shift of a passenger whose seat rides on a joint, relative to where the base
	 * mod would put it, or null for an ordinary seat. */
	private Vec3d seatPartShift(Entity passenger, float tickDelta, boolean eye) {
		String part = this.jointSeatPart(passenger);
		int seatIndex = this.tudursvehiclemod$getAssignedSeatIndex(passenger);
		VehicleDefinition def = this.getDefinition();
		if (part == null || seatIndex < 0 || seatIndex >= def.seats().size()) {
			return null;
		}
		Matrix4f matrix = this.jointMatrices(tickDelta).get(part);
		if (matrix == null) {
			return null;
		}
		SeatDefinition seat = def.seats().get(seatIndex);
		float scale = def.scale();
		float eyeHeight = eye ? passenger.getStandingEyeHeight() / Math.max(1.0e-4f, scale) : 0f;
		Vector3f rest = new Vector3f((float) seat.offsetX(), (float) seat.offsetY() + eyeHeight, (float) seat.offsetZ());
		Vector3f moved = matrix.transformPosition(new Vector3f(rest));
		Vector3f shift = moved.sub(rest).mul(scale);
		Quaternionf rotation = eye ? this.getSeatRotationInterpolated(tickDelta) : this.getSeatRotationCurrent();
		rotation.transform(shift);
		return new Vec3d(shift.x, shift.y, shift.z);
	}

	@Override
	public void updatePassengerPosition(Entity passenger, Entity.PositionUpdater positionUpdater) {
		Vec3d shift = this.hasPassenger(passenger) ? this.seatPartShift(passenger, 1f, false) : null;
		if (shift == null) {
			super.updatePassengerPosition(passenger, positionUpdater);
			return;
		}
		super.updatePassengerPosition(passenger,
				(entity, x, y, z) -> positionUpdater.accept(entity, x + shift.x, y + shift.y, z + shift.z));
	}

	@Override
	public Vec3d getRotatedEyePos(Entity passenger, float tickDelta) {
		Vec3d base = super.getRotatedEyePos(passenger, tickDelta);
		Vec3d shift = this.hasPassenger(passenger) ? this.seatPartShift(passenger, tickDelta, true) : null;
		return shift == null ? base : base.add(shift);
	}

	/** The joint this passenger's seat rides (seat_parts), or null if it isn't on one. */
	private String jointSeatPart(Entity passenger) {
		int seatIndex = this.tudursvehiclemod$getAssignedSeatIndex(passenger);
		for (SeatPart seatPart : this.settings().seatParts()) {
			if (seatPart.seat() == seatIndex) {
				return seatPart.part();
			}
		}
		return null;
	}

	/** Yaw (degrees, positive = left) of this passenger's joint-mounted seat relative to the hull,
	 * or null for an ordinary seat. Used for the rider's view and third-person body. */
	public Float renderedSeatYawOffset(Entity passenger, float tickDelta) {
		String part = this.jointSeatPart(passenger);
		if (part == null) {
			return null;
		}
		Matrix4f matrix = this.jointMatrices(tickDelta).get(part);
		return matrix == null ? null : jointHeading(matrix);
	}

	/** Called by a module whenever its work swing (the upper structure slewing on its own, not the
	 * vehicle turning) moves, by degreesLeft. Turns joint-seat riders and plays the vehicle's
	 * swing_sound, if it has one.
	 *
	 * <p>Server side: a riding player's view is client-authoritative, so this only keeps the
	 * server's copy in step for other players (same as CarEntity: setYaw + requestTeleport). The
	 * rider's own view is turned client-side by followJointSeatYawClient(). Only yaw is changed;
	 * vanilla keeps headYaw following it, and the body is handled by LivingEntityRendererMixin. */
	public void workSwung(float degreesLeft) {
		if (Math.abs(degreesLeft) < 1.0e-4f) {
			return;
		}
		for (Entity passenger : this.tudursvehiclemod$getRealPassengerList()) {
			if (this.jointSeatPart(passenger) == null) {
				continue;
			}
			// Minecraft yaw grows clockwise, so turning left lowers it.
			passenger.setYaw(passenger.getYaw() - degreesLeft);
			if (passenger instanceof ServerPlayerEntity player) {
				player.networkHandler.requestTeleport(player.getX(), player.getY(), player.getZ(),
						player.getYaw(), player.getPitch());
			}
		}
		this.playSwingSound();
	}

	private void playSwingSound() {
		if (this.swingSoundCooldown > 0 || !(this.getEntityWorld() instanceof ServerWorld world)) {
			return;
		}
		SwingSound sound = this.settings().swingSound().orElse(null);
		Identifier id = sound == null ? null : Identifier.tryParse(sound.sound());
		if (id == null) {
			return;
		}
		// Unregistered ids (resource-pack-only sounds) are referenced directly.
		SoundEvent event = Registries.SOUND_EVENT.getOptionalValue(id).orElseGet(() -> SoundEvent.of(id));
		world.playSound(null, this.getX(), this.getY() + 1.0, this.getZ(), event, SoundCategory.NEUTRAL,
				sound.volume(), sound.pitch());
		this.swingSoundCooldown = Math.max(1, sound.interval());
	}

	/** Client side of workSwung(): the client never runs module logic, so the seat's turn is
	 * measured from the synced channels (previous vs current tick) and applied to the rider's yaw. */
	private void followJointSeatYawClient() {
		if (this.settings().seatParts().isEmpty()) {
			return;
		}
		for (Entity passenger : this.tudursvehiclemod$getRealPassengerList()) {
			Float previous = this.renderedSeatYawOffset(passenger, 0f);
			Float current = this.renderedSeatYawOffset(passenger, 1f);
			if (previous == null || current == null) {
				continue;
			}
			float delta = MathHelper.wrapDegrees(current - previous);
			if (Math.abs(delta) >= 1.0e-4f) {
				passenger.setYaw(passenger.getYaw() - delta);
			}
		}
	}

	/** Heading (degrees, positive = toward model +X = left) of a joint matrix's forward axis. */
	private static float jointHeading(Matrix4f matrix) {
		Vector3f forward = matrix.transformDirection(new Vector3f(0f, 0f, 1f));
		return (float) Math.toDegrees(Math.atan2(forward.x, forward.z));
	}

	// ------------------------------------------------------------------
	// Rendering overrides (towed trailer)
	// ------------------------------------------------------------------

	@Override
	public Vec3d tudursvehiclemod$getBodyFrameOffset(float tickDelta) {
		Vec3d base = super.tudursvehiclemod$getBodyFrameOffset(tickDelta);
		if (!this.getEntityWorld().isClient() || this.module == null) {
			return base;
		}
		Vec3d absolute = this.module.renderPositionOverride(tickDelta);
		if (absolute == null) {
			return base;
		}
		Vec3d drawn = new Vec3d(
				MathHelper.lerp(tickDelta, this.lastRenderX, this.getX()),
				MathHelper.lerp(tickDelta, this.lastRenderY, this.getY()),
				MathHelper.lerp(tickDelta, this.lastRenderZ, this.getZ()));
		return base.add(absolute.subtract(drawn));
	}

	@Override
	public Quaternionf tudursvehiclemod$getBodyOrientation(float tickDelta) {
		if (!this.getEntityWorld().isClient() || this.module == null) {
			return super.tudursvehiclemod$getBodyOrientation(tickDelta);
		}
		Float yaw = this.module.renderYawOverride(tickDelta);
		if (yaw == null) {
			return super.tudursvehiclemod$getBodyOrientation(tickDelta);
		}
		return new Quaternionf()
				.rotationY((float) Math.toRadians(-yaw))
				.rotateX((float) Math.toRadians(this.getPitch(tickDelta)))
				.rotateZ((float) Math.toRadians(this.getRoll(tickDelta)));
	}

	// ------------------------------------------------------------------
	// Cargo helpers (the base mod's own vehicle inventory; slot 0 is the fuel can)
	// ------------------------------------------------------------------

	public int cargoStart() {
		return 1;
	}

	public int cargoEnd() {
		return this.size();
	}

	/** Inserts into cargo slots, merging first. Returns what did NOT fit (empty if all did). */
	public ItemStack insertCargo(ItemStack stack) {
		ItemStack remaining = stack.copy();
		for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
			for (int slot = this.cargoStart(); slot < this.cargoEnd() && !remaining.isEmpty(); slot++) {
				ItemStack existing = this.getStack(slot);
				if (pass == 0) {
					if (!existing.isEmpty() && ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
						int move = Math.min(remaining.getCount(), existing.getMaxCount() - existing.getCount());
						if (move > 0) {
							existing.increment(move);
							remaining.decrement(move);
						}
					}
				} else if (existing.isEmpty()) {
					this.setStack(slot, remaining.copy());
					remaining = ItemStack.EMPTY;
				}
			}
		}
		return remaining;
	}

	// ------------------------------------------------------------------
	// Persistence
	// ------------------------------------------------------------------

	@Override
	protected void writeCustomData(WriteView view) {
		super.writeCustomData(view);
		StringBuilder floats = new StringBuilder();
		for (int i = 0; i < FLOAT_CHANNELS; i++) {
			if (i > 0) {
				floats.append(',');
			}
			floats.append(this.getFloatChannel(i));
		}
		view.putString("ConstructionChannels", floats.toString());
		StringBuilder ints = new StringBuilder();
		for (int i = 0; i < INT_CHANNELS; i++) {
			if (i > 0) {
				ints.append(',');
			}
			ints.append(this.getIntChannel(i));
		}
		view.putString("ConstructionIntChannels", ints.toString());
		this.module().writeData(view);
	}

	@Override
	protected void readCustomData(ReadView view) {
		super.readCustomData(view);
		String[] floats = view.getString("ConstructionChannels", "").split(",");
		for (int i = 0; i < floats.length && i < FLOAT_CHANNELS; i++) {
			try {
				this.setFloatChannel(i, Float.parseFloat(floats[i]));
			} catch (NumberFormatException ignored) {
				// leave default
			}
		}
		String[] ints = view.getString("ConstructionIntChannels", "").split(",");
		for (int i = 0; i < ints.length && i < INT_CHANNELS; i++) {
			try {
				this.setIntChannel(i, Integer.parseInt(ints[i]));
			} catch (NumberFormatException ignored) {
				// leave default
			}
		}
		this.module = null;
		this.module().readData(view);
	}
}
