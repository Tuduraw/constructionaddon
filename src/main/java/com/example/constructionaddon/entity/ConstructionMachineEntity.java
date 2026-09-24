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

/** Every construction machine: a CarEntity (so driving, tracks/wheels, fuel, inventory, seats,
 * HUD and destruction all stay the base mod's own) plus one MachineModule chosen by the vehicle
 * JSON's "construction.machine".
 *
 * <p>This class supplies what every module shares:
 * <ul>
 *   <li>generic SYNCED CHANNELS - {@link #FLOAT_CHANNELS} floats and {@link #INT_CHANNELS} ints,
 *       written by the module on the server, read on the client. Floats are interpolated
 *       between ticks for drawing and drive the JSON-defined joints;</li>
 *   <li>the joint system: {@link #tudursvehiclemod$getCustomPartTransforms(float)} for drawing and
 *       {@link #workPoint(String)} for acting in the world, from the very same matrices;</li>
 *   <li>WORK MODE (the base mod's own manual-mode flag, hold M): while a module says so, the
 *       base driving code sees zero input and a held brake, and the raw inputs - W/S, A/D,
 *       arrow up/down - are read by the module as work axes instead;</li>
 *   <li>the two work keys (primary / secondary) from this addon's own payload;</li>
 *   <li>seats that ride on a joint (an excavator cab on its slewing upper structure), including
 *       the rider's camera.</li>
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
	private int swingSoundCooldown;
	private boolean lastPrimaryHeld;
	private boolean lastSecondaryHeld;
	private boolean primaryPressed;
	private boolean secondaryPressed;

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
			if (this.module != null) {
				this.module.onRemoved();
			}
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

	/** Work mode = the base mod's own manual-mode flag (hold M), reused rather than duplicated.
	 * A per-machine "systems engaged" lever - it enables a module's own arm/chute controls (and,
	 * for a crane or pile driver, drives outriggers/mast toward deployed) but by itself never
	 * blocks driving: WASD always drives regardless of this flag. Only an actual physical state
	 * (outriggers down, mast up) ever makes a module suppress driving - see
	 * MachineModule#suppressesDriving(). */
	public boolean isWorkMode() {
		return this.isManualMode();
	}

	/** A work axis value (-1/0/+1) from this addon's own key bindings - independent of driving
	 * (WASD) and of every other binding, each axis rebindable on its own (see WorkAxis). */
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
			// Cancels the base mod's cruise-control throttle so a machine put into work mode while
			// rolling actually stops, instead of holding whatever throttle it had.
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
		if (this.module != null) {
			this.module.onRemoved();
		}
	}

	// ------------------------------------------------------------------
	// Seats riding on joints
	// ------------------------------------------------------------------

	/** World-space shift of a passenger whose seat rides on a joint, relative to where the base
	 * mod would put it, or null for an ordinary seat. */
	private Vec3d seatPartShift(Entity passenger, float tickDelta, boolean eye) {
		ConstructionSettings settings = this.settings();
		if (settings.seatParts().isEmpty()) {
			return null;
		}
		int seatIndex = this.tudursvehiclemod$getAssignedSeatIndex(passenger);
		VehicleDefinition def = this.getDefinition();
		if (seatIndex < 0 || seatIndex >= def.seats().size()) {
			return null;
		}
		String part = null;
		for (SeatPart seatPart : settings.seatParts()) {
			if (seatPart.seat() == seatIndex) {
				part = seatPart.part();
				break;
			}
		}
		if (part == null) {
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

	/** For third-person rendering (LivingEntityRendererMixin): the extra yaw this passenger's own
	 * joint-mounted seat currently carries relative to this machine's own hull, in this addon's
	 * own "positive = left" convention (matching rotateJointSeatRiders()'s degreesLeft) - what
	 * that mixin adds on top of the base mod's own "body matches vehicle hull" render override.
	 * Null if this passenger isn't seated on one of this machine's rotating joints. Client side
	 * only (jointMatrices() needs render-interpolated channel values, which only exist there). */
	public Float renderedSeatYawOffset(Entity passenger, float tickDelta) {
		String part = this.jointSeatPart(passenger);
		if (part == null) {
			return null;
		}
		Matrix4f matrix = this.jointMatrices(tickDelta).get(part);
		return matrix == null ? null : jointHeading(matrix);
	}

	/** Called by a module every tick its WORK swing (upper structure slewing on its own, not the
	 * vehicle turning) actually moves, by degreesLeft (positive = left). Turns joint-seat riders
	 * with it and plays the vehicle's own swing alarm, if its JSON defines one
	 * ("construction.swing_sound") - most machines don't, and then nothing plays. */
	public void workSwung(float degreesLeft) {
		if (Math.abs(degreesLeft) < 1.0e-4f) {
			return;
		}
		this.rotateJointSeatRiders(degreesLeft);
		this.playSwingSound();
	}

	private void playSwingSound() {
		if (this.swingSoundCooldown > 0 || !(this.getEntityWorld() instanceof ServerWorld world)) {
			return;
		}
		SwingSound sound = this.settings().swingSound().orElse(null);
		if (sound == null) {
			return;
		}
		Identifier id = Identifier.tryParse(sound.sound());
		if (id == null) {
			return;
		}
		// Any registered sound event, or - for a resource-pack-only sound the registry doesn't
		// know - a direct reference to its id, which the client resolves from sounds.json.
		SoundEvent event = Registries.SOUND_EVENT.getOptionalValue(id).orElseGet(() -> SoundEvent.of(id));
		world.playSound(null, this.getX(), this.getY() + 1.0, this.getZ(), event, SoundCategory.NEUTRAL,
				sound.volume(), sound.pitch());
		this.swingSoundCooldown = Math.max(1, sound.interval());
	}

	/** SERVER side of turning joint-seat riders with a slewing upper structure. Positive =
	 * counter-clockwise from above (left). Called by the modules whenever their swing channel
	 * changes.
	 *
	 * <p>This alone does NOT turn a real player's view: a riding player's look direction is
	 * client-authoritative. It exists so the server's own copy (what OTHER players see, via entity
	 * tracking) stays in step; the rider's own view is turned by followJointSeatYawClient() on
	 * their own client. This is exactly how the base mod's CarEntity turns passengers with the
	 * car: its updateVehicleMovement() runs on both sides, and the client-side
	 * passenger.setYaw() is what actually moves the view - the server-side half here mirrors that
	 * method's own server-side half (setYaw + requestTeleport) line for line.
	 *
	 * <p>Only yaw is touched - NOT headYaw. Vanilla already keeps a player's own headYaw in step
	 * with yaw on its own; explicitly nudging headYaw by this SAME delta on top of that double
	 * counts it, so the head visibly over-rotates past the body instead of turning with it (the
	 * body itself is corrected separately, once, by LivingEntityRendererMixin). */
	public void rotateJointSeatRiders(float degreesLeft) {
		if (Math.abs(degreesLeft) < 1.0e-4f || this.settings().seatParts().isEmpty()) {
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
	}

	/** CLIENT side of the above - the part that actually turns a riding player's view (and, with
	 * it, their body, which vanilla turns toward the view on its own).
	 *
	 * <p>The client never runs a module's work logic (swing is decided on the server and arrives
	 * as a synced channel), so the turn is measured here instead, generically: how far
	 * renderedSeatYawOffset() moved between the previous and the current channel values - the
	 * same interpolation endpoints the renderer draws from. Works for any seat_parts joint chain,
	 * not just a single swing channel.
	 *
	 * <p>Only yaw is touched here too, for the same reason as rotateJointSeatRiders() - see that
	 * method's own doc. Turning the player's own view (yaw) is a SEPARATE concern from turning
	 * their rendered third-person BODY - LivingEntityRendererMixin handles the body, straight
	 * from renderedSeatYawOffset(), every frame, with no dependency on this method or on any
	 * per-tick state at all. */
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
			if (Math.abs(delta) < 1.0e-4f) {
				continue;
			}
			// Positive heading = turned toward model +X = left; Minecraft yaw grows clockwise.
			passenger.setYaw(passenger.getYaw() - delta);
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
