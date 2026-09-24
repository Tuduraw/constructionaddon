package com.example.constructionaddon.machine;

import com.example.constructionaddon.ConstructionServerConfig;
import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.constructionaddon.network.WorkAxis;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import com.example.tudursvehiclemod.entity.CarrierRunwayPlatformEntity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Mobile crane.
 *
 * <p>Work mode (hold M) deploys the outriggers; the crane only operates once they are fully out
 * (unless require_outriggers is false). Driving is blocked only while the outriggers are
 * actually down - not by work mode itself - and the arm's own controls never touch WASD, so a
 * crane can be driven into position and worked without ever fighting the driving keys:
 * <ul>
 *   <li>swing keys (default Left / Right) - slew;</li>
 *   <li>luff keys (default Up / Down) - boom angle;</li>
 *   <li>telescope keys (default , / .) - extend / retract the boom;</li>
 *   <li>hoist keys (default ; / /) - hook up / down;</li>
 *   <li>primary (Z) press - take hold of the nearest liftable entity at the hook (mobs, dropped
 *       items, other vehicles; players only if the server allows), or release the load.</li>
 * </ul>
 * Each has its own key binding, so all can be worked at once and rebound independently.
 * The load hangs below the hook and is positioned after every entity in the world has ticked,
 * so its own physics can't drag it away from the hook. */
public final class CraneModule extends MachineModule {

	private static final String[] CHANNELS = {"swing", "luff", "extend", "rope", "outrigger", "hook"};
	private static final int SWING = 0;
	private static final int LUFF = 1;
	private static final int EXTEND = 2;
	private static final int ROPE = 3;
	private static final int OUTRIGGER = 4;
	private static final int HOOK = 5;

	public record Settings(String tipPoint, float swingSpeed, float swingLimit, float luffSpeed, float luffMin, float luffMax,
			float extendSpeed, float extendMax, float ropeSpeed, float ropeMin, float ropeMax,
			float outriggerSpeed, boolean requireOutriggers, float grabRadius, float maxLiftWidth, float hookDrop) {
		public static final Codec<Settings> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.optionalFieldOf("tip_point", "boom_tip").forGetter(Settings::tipPoint),
				Codec.FLOAT.optionalFieldOf("swing_speed", 1.5f).forGetter(Settings::swingSpeed),
				Codec.FLOAT.optionalFieldOf("swing_limit", 0f).forGetter(Settings::swingLimit),
				Codec.FLOAT.optionalFieldOf("luff_speed", 0.6f).forGetter(Settings::luffSpeed),
				Codec.FLOAT.optionalFieldOf("luff_min", 0f).forGetter(Settings::luffMin),
				Codec.FLOAT.optionalFieldOf("luff_max", 78f).forGetter(Settings::luffMax),
				Codec.FLOAT.optionalFieldOf("extend_speed", 0.06f).forGetter(Settings::extendSpeed),
				Codec.FLOAT.optionalFieldOf("extend_max", 8f).forGetter(Settings::extendMax),
				Codec.FLOAT.optionalFieldOf("rope_speed", 0.15f).forGetter(Settings::ropeSpeed),
				Codec.FLOAT.optionalFieldOf("rope_min", 0.5f).forGetter(Settings::ropeMin),
				Codec.FLOAT.optionalFieldOf("rope_max", 40f).forGetter(Settings::ropeMax),
				Codec.FLOAT.optionalFieldOf("outrigger_speed", 0.04f).forGetter(Settings::outriggerSpeed),
				Codec.BOOL.optionalFieldOf("require_outriggers", true).forGetter(Settings::requireOutriggers),
				Codec.FLOAT.optionalFieldOf("grab_radius", 1.5f).forGetter(Settings::grabRadius),
				Codec.FLOAT.optionalFieldOf("max_lift_width", 6.0f).forGetter(Settings::maxLiftWidth),
				Codec.FLOAT.optionalFieldOf("hook_drop", 0.9f).forGetter(Settings::hookDrop)
		).apply(i, Settings::new));

		static final Settings DEFAULT = CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, new com.google.gson.JsonObject())
				.result().orElseThrow();
	}

	private UUID heldUuid;
	private Text heldName;
	private boolean nothingToGrab;

	public CraneModule(ConstructionMachineEntity machine) {
		super(machine);
	}

	@Override
	public MachineType type() {
		return MachineType.CRANE;
	}

	@Override
	public String[] floatChannels() {
		return CHANNELS;
	}

	private Settings settings() {
		return this.machine.settings().machineSettings(Settings.class, Settings.DEFAULT);
	}

	@Override
	public boolean suppressesDriving() {
		// The only reason a crane can't drive is its outriggers physically being down - toggling
		// work mode itself never blocks WASD; the arm has its own dedicated axes instead.
		return this.machine.getFloatChannel(OUTRIGGER) > 0.01f;
	}

	@Override
	public void serverTick(ServerWorld world, ServerPlayerEntity operator) {
		Settings s = this.settings();
		float outrigger = this.machine.getFloatChannel(OUTRIGGER);
		float outriggerTarget = this.machine.isWorkMode() ? 1f : 0f;
		if (outrigger != outriggerTarget) {
			outrigger = outrigger < outriggerTarget
					? Math.min(outriggerTarget, outrigger + s.outriggerSpeed())
					: Math.max(outriggerTarget, outrigger - s.outriggerSpeed());
			this.machine.setFloatChannel(OUTRIGGER, outrigger);
		}
		// Keeps the rope within range even for a freshly placed crane (channels start at 0).
		float rope = this.machine.getFloatChannel(ROPE);
		if (rope < s.ropeMin() || rope > s.ropeMax()) {
			this.machine.setFloatChannel(ROPE, MathHelper.clamp(rope, s.ropeMin(), s.ropeMax()));
		}
		this.machine.setFloatChannel(HOOK, this.heldUuid != null ? 1f : 0f);

		boolean ready = !s.requireOutriggers() || outrigger >= 0.999f;
		if (!this.machine.isWorkMode() || operator == null || !ready) {
			return;
		}

		float sideways = this.machine.workAxis(WorkAxis.SWING);
		if (sideways != 0f) {
			float swing = this.machine.getFloatChannel(SWING);
			float next = swing + sideways * s.swingSpeed();
			if (s.swingLimit() > 0f) {
				next = MathHelper.clamp(next, -s.swingLimit(), s.swingLimit());
			}
			this.machine.setFloatChannel(SWING, next);
			this.machine.workSwung(next - swing);
		}

		// Luff, telescope and hoist each have their OWN key binding (WorkAxis LUFF / TELESCOPE /
		// HOIST), so all three - and swing - can be worked at once, and rebound independently.
		float luffInput = this.machine.workAxis(WorkAxis.LUFF);
		if (luffInput != 0f) {
			float luff = this.machine.getFloatChannel(LUFF);
			this.machine.setFloatChannel(LUFF, MathHelper.clamp(luff + luffInput * s.luffSpeed(),
					Math.min(s.luffMin(), s.luffMax()), Math.max(s.luffMin(), s.luffMax())));
		}
		float telescopeInput = this.machine.workAxis(WorkAxis.TELESCOPE);
		if (telescopeInput != 0f) {
			float extend = this.machine.getFloatChannel(EXTEND);
			this.machine.setFloatChannel(EXTEND, MathHelper.clamp(extend + telescopeInput * s.extendSpeed(), 0f, s.extendMax()));
		}
		float hoistInput = this.machine.workAxis(WorkAxis.HOIST);
		if (hoistInput != 0f) {
			rope = this.machine.getFloatChannel(ROPE);
			this.machine.setFloatChannel(ROPE, MathHelper.clamp(rope - hoistInput * s.ropeSpeed(), s.ropeMin(), s.ropeMax()));
		}

		if (this.machine.isPrimaryPressed()) {
			if (this.heldUuid != null) {
				this.release(world);
			} else {
				this.grab(world, s);
			}
		}
	}

	/** Where a load hangs from: the boom tip, then straight down (in the machine's own frame) by
	 * the rope, plus hook_drop for the hook block itself. */
	public Vec3d hookPosition() {
		Settings s = this.settings();
		Vector3f tip = this.machine.workPointModel(s.tipPoint());
		tip.y -= this.machine.getFloatChannel(ROPE) + s.hookDrop();
		return this.machine.modelToWorld(tip);
	}

	private boolean canLift(Entity entity, Settings s) {
		if (entity == this.machine || entity.isRemoved() || entity.hasVehicle() || this.machine.hasPassenger(entity)) {
			return false;
		}
		if (entity instanceof CarrierRunwayPlatformEntity || entity.getWidth() > s.maxLiftWidth()) {
			return false;
		}
		ConstructionServerConfig config = ConstructionServerConfig.get();
		if (entity instanceof PlayerEntity player) {
			return config.craneCanLiftPlayers && !player.isSpectator() && player.isAlive();
		}
		if (entity instanceof AbstractVehicleEntity vehicle) {
			// Never wrest a vehicle out from under a player who is driving it.
			return config.craneCanLiftVehicles && !(vehicle.getControllingPassenger() instanceof PlayerEntity);
		}
		if (entity instanceof LivingEntity living) {
			return living.isAlive();
		}
		return entity instanceof ItemEntity;
	}

	private void grab(ServerWorld world, Settings s) {
		Vec3d hook = this.hookPosition();
		double r = s.grabRadius();
		Box box = new Box(hook.x - r, hook.y - r - 2.0, hook.z - r, hook.x + r, hook.y + r, hook.z + r);
		List<Entity> candidates = world.getOtherEntities(this.machine, box, e -> this.canLift(e, s));
		Entity nearest = candidates.stream()
				.min(Comparator.comparingDouble(e -> e.getBoundingBox().getCenter().squaredDistanceTo(hook)))
				.orElse(null);
		this.nothingToGrab = nearest == null;
		if (nearest == null) {
			return;
		}
		this.heldUuid = nearest.getUuid();
		this.heldName = nearest.getDisplayName();
		world.playSound(null, hook.x, hook.y, hook.z, SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE, SoundCategory.BLOCKS, 1.0f, 0.8f);
	}

	private void release(ServerWorld world) {
		Entity held = this.heldUuid == null ? null : world.getEntity(this.heldUuid);
		if (held != null) {
			held.setVelocity(Vec3d.ZERO);
			held.fallDistance = 0;
			Vec3d hook = this.hookPosition();
			world.playSound(null, hook.x, hook.y, hook.z, SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN, SoundCategory.BLOCKS, 1.0f, 1.0f);
		}
		this.heldUuid = null;
		this.heldName = null;
	}

	@Override
	public boolean wantsEndWorldTick() {
		return this.heldUuid != null;
	}

	@Override
	public void endWorldTick(ServerWorld world) {
		if (this.heldUuid == null || this.machine.isRemoved()) {
			return;
		}
		Entity held = world.getEntity(this.heldUuid);
		Vec3d hook = this.hookPosition();
		if (held == null || held.isRemoved() || held.hasVehicle() || held.squaredDistanceTo(hook) > 64.0 * 64.0
				|| this.machine.tudursvehiclemod$isDestroyed()) {
			this.heldUuid = null;
			this.heldName = null;
			return;
		}
		double x = hook.x;
		double y = hook.y - held.getHeight() - 0.1;
		double z = hook.z;
		held.fallDistance = 0;
		if (held instanceof ServerPlayerEntity player) {
			player.networkHandler.requestTeleport(x, y, z, player.getYaw(), player.getPitch());
		} else {
			held.setPosition(x, y, z);
			held.setVelocity(Vec3d.ZERO);
			held.velocityDirty = true;
		}
	}

	@Override
	public Text status() {
		String rope = String.format(Locale.ROOT, "%.1f", this.machine.getFloatChannel(ROPE));
		String luff = String.format(Locale.ROOT, "%.0f", this.machine.getFloatChannel(LUFF));
		String extend = String.format(Locale.ROOT, "%.1f", this.machine.getFloatChannel(EXTEND));
		if (!this.machine.isWorkMode()) {
			return this.machine.getFloatChannel(OUTRIGGER) > 0.01f
					? Text.translatable("status.constructionaddon.crane.stowing") : null;
		}
		if (this.machine.getFloatChannel(OUTRIGGER) < 0.999f && this.settings().requireOutriggers()) {
			return Text.translatable("status.constructionaddon.crane.deploying");
		}
		Text load = this.heldName != null ? this.heldName
				: Text.translatable(this.nothingToGrab ? "status.constructionaddon.crane.nothing" : "status.constructionaddon.crane.empty");
		return Text.translatable("status.constructionaddon.crane", load, rope, luff, extend);
	}

	@Override
	public void writeData(WriteView view) {
		view.putString("CraneHeld", this.heldUuid != null ? this.heldUuid.toString() : "");
	}

	@Override
	public void readData(ReadView view) {
		String held = view.getString("CraneHeld", "");
		try {
			this.heldUuid = held.isEmpty() ? null : UUID.fromString(held);
		} catch (IllegalArgumentException e) {
			this.heldUuid = null;
		}
	}
}
