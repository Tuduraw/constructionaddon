package com.example.constructionaddon.machine;

import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.tudursvehiclemod.asset.VehicleDefinition;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;
import java.util.function.Supplier;

/** Being towed by a tractor: the kingpin sits on the fifth wheel and the rear axle (axle_z) trails
 * behind, limited by max_articulation. Positioned every tick on both sides from the tractor's own
 * position, so there is no lag behind a player-driven tractor. Uncoupled, the machine is an ordinary
 * unmanned CarEntity. Used by {@link TrailerModule} and by any machine that is also a trailer. */
public final class TowLink {

	public record Settings(float kingpinX, float kingpinY, float kingpinZ, float axleZ, float maxArticulation) {
		public static final Codec<Settings> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.FLOAT.optionalFieldOf("kingpin_x", 0f).forGetter(Settings::kingpinX),
				Codec.FLOAT.optionalFieldOf("kingpin_y", 1.1f).forGetter(Settings::kingpinY),
				Codec.FLOAT.optionalFieldOf("kingpin_z", 4.5f).forGetter(Settings::kingpinZ),
				Codec.FLOAT.optionalFieldOf("axle_z", -3.5f).forGetter(Settings::axleZ),
				Codec.FLOAT.optionalFieldOf("max_articulation", 75f).forGetter(Settings::maxArticulation)
		).apply(i, Settings::new));

		public static final Settings DEFAULT = MachineModule.defaults(CODEC);
	}

	private final ConstructionMachineEntity machine;
	/** Int channel holding the tractor's network id + 1 (0 = not coupled). */
	private final int tractorChannel;
	private final Supplier<Settings> settings;

	private UUID tractorUuid;

	// Client-side kinematic result, for drawing between ticks.
	private boolean kinematicValid;
	private Vec3d prevKinematicPos = Vec3d.ZERO;
	private Vec3d kinematicPos = Vec3d.ZERO;
	private float prevKinematicYaw;
	private float kinematicYaw;

	public TowLink(ConstructionMachineEntity machine, int tractorChannel, Supplier<Settings> settings) {
		this.machine = machine;
		this.tractorChannel = tractorChannel;
		this.settings = settings;
	}

	/** Rotates a local offset by a Minecraft yaw (model +Z forward, +X left) and adds it to a base. */
	public static Vec3d yawTransform(double baseX, double baseY, double baseZ, float yaw, double localX, double localY,
			double localZ) {
		double rad = Math.toRadians(yaw);
		double fx = -Math.sin(rad);
		double fz = Math.cos(rad);
		double lx = Math.cos(rad);
		double lz = Math.sin(rad);
		return new Vec3d(baseX + localX * lx + localZ * fx, baseY + localY, baseZ + localX * lz + localZ * fz);
	}

	public Vec3d kingpinWorld() {
		Settings s = this.settings.get();
		float scale = this.machine.getDefinition().scale();
		return yawTransform(this.machine.getX(), this.machine.getY(), this.machine.getZ(), this.machine.getYaw(),
				s.kingpinX() * scale, s.kingpinY() * scale, s.kingpinZ() * scale);
	}

	public boolean isCoupled() {
		return this.tractorUuid != null || this.machine.getIntChannel(this.tractorChannel) != 0;
	}

	public boolean isCoupledTo(ConstructionMachineEntity tractor) {
		return tractor.getUuid().equals(this.tractorUuid);
	}

	/** Server. */
	public void coupleTo(ConstructionMachineEntity tractor) {
		this.tractorUuid = tractor.getUuid();
		this.machine.setIntChannel(this.tractorChannel, tractor.getId() + 1);
	}

	/** Server. */
	public void uncouple() {
		this.tractorUuid = null;
		this.machine.setIntChannel(this.tractorChannel, 0);
	}

	/** The coupled tractor as seen on THIS side, or null. */
	public ConstructionMachineEntity tractor() {
		int id = this.machine.getIntChannel(this.tractorChannel) - 1;
		if (id < 0) {
			return null;
		}
		Entity entity = this.machine.getEntityWorld().getEntityById(id);
		if (entity instanceof ConstructionMachineEntity tractor && !tractor.isRemoved()
				&& tractor.module() instanceof TractorModule) {
			return tractor;
		}
		return null;
	}

	/** Server, every tick while intact: keeps the link (or drops it when the tractor is gone). */
	public void serverTick(ServerWorld world) {
		if (this.tractorUuid == null) {
			return;
		}
		Entity entity = world.getEntity(this.tractorUuid);
		if (entity == null) {
			// Not loaded (yet) - keep the link, but don't follow anything meanwhile.
			this.machine.setIntChannel(this.tractorChannel, 0);
			return;
		}
		if (!(entity instanceof ConstructionMachineEntity tractor) || !(tractor.module() instanceof TractorModule)
				|| tractor.tudursvehiclemod$isDestroyed() || tractor.squaredDistanceTo(this.machine) > 24.0 * 24.0) {
			this.uncouple();
			return;
		}
		this.machine.setIntChannel(this.tractorChannel, tractor.getId() + 1);
	}

	/** Every tick, both sides: a destroyed machine lets go. */
	public void tick() {
		if (!this.machine.getEntityWorld().isClient() && this.machine.tudursvehiclemod$isDestroyed() && this.isCoupled()) {
			this.uncouple();
		}
	}

	/** Whether the machine is being towed right now (on this side). */
	public boolean towing() {
		return this.tractor() != null;
	}

	/** Moves the machine behind its tractor; returns false (nothing done) when not coupled. */
	public boolean updateMovement(VehicleDefinition def) {
		ConstructionMachineEntity tractor = this.tractor();
		if (tractor == null) {
			this.kinematicValid = false;
			return false;
		}
		Settings s = this.settings.get();
		float scale = def.scale();
		Vec3d hitch = ((TractorModule) tractor.module()).hitchWorld();

		// Turn so the rear axle trails straight behind the kingpin.
		float yaw = this.machine.getYaw();
		Vec3d axle = yawTransform(this.machine.getX(), this.machine.getY(), this.machine.getZ(), yaw,
				0.0, 0.0, s.axleZ() * scale);
		double dx = hitch.x - axle.x;
		double dz = hitch.z - axle.z;
		if (dx * dx + dz * dz > 1.0e-6) {
			yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		}
		float relative = MathHelper.wrapDegrees(yaw - tractor.getYaw());
		float limit = Math.max(1f, s.maxArticulation());
		yaw = tractor.getYaw() + MathHelper.clamp(relative, -limit, limit);

		// Then place it so its kingpin sits exactly on the fifth wheel.
		Vec3d kingpinOffset = yawTransform(0.0, 0.0, 0.0, yaw, s.kingpinX() * scale, s.kingpinY() * scale,
				s.kingpinZ() * scale);
		Vec3d position = hitch.subtract(kingpinOffset);
		Vec3d delta = position.subtract(this.machine.getX(), this.machine.getY(), this.machine.getZ());

		this.machine.setYaw(yaw);
		this.machine.setPosition(position.x, position.y, position.z);
		this.machine.setVelocity(delta);
		this.machine.prevWheelRotation = this.machine.wheelRotation;
		double travelled = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		double forward = delta.x * -Math.sin(Math.toRadians(yaw)) + delta.z * Math.cos(Math.toRadians(yaw));
		this.machine.wheelRotation += (float) (Math.signum(forward) * travelled * 20.0);

		if (this.machine.getEntityWorld().isClient()) {
			this.prevKinematicPos = this.kinematicValid ? this.kinematicPos : position;
			this.prevKinematicYaw = this.kinematicValid ? this.kinematicYaw : yaw;
			this.kinematicPos = position;
			this.kinematicYaw = yaw;
			this.kinematicValid = true;
		}
		return true;
	}

	public Vec3d renderPositionOverride(float tickDelta) {
		if (!this.kinematicValid || this.tractor() == null) {
			return null;
		}
		return this.prevKinematicPos.lerp(this.kinematicPos, tickDelta);
	}

	public Float renderYawOverride(float tickDelta) {
		if (!this.kinematicValid || this.tractor() == null) {
			return null;
		}
		return this.prevKinematicYaw + MathHelper.wrapDegrees(this.kinematicYaw - this.prevKinematicYaw) * tickDelta;
	}

	public void writeData(WriteView view) {
		view.putString("TrailerTractor", this.tractorUuid != null ? this.tractorUuid.toString() : "");
	}

	public void readData(ReadView view) {
		String value = view.getString("TrailerTractor", "");
		try {
			this.tractorUuid = value.isEmpty() ? null : UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			this.tractorUuid = null;
		}
	}
}
