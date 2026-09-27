package com.example.constructionaddon.machine;

import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.UUID;

/** Tractor unit. Primary (Z) couples to a trailer whose kingpin is within couple_radius of the
 * fifth wheel (hitch_x/y/z), or uncouples. While coupled, the hatch key opens the trailer's ramps
 * and top speed is scaled by towing_speed_factor. */
public final class TractorModule extends MachineModule {

	private static final String[] CHANNELS = {"coupled"};
	private static final int COUPLED = 0;

	public record Settings(float hitchX, float hitchY, float hitchZ, float coupleRadius, float towingSpeedFactor) {
		public static final Codec<Settings> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.FLOAT.optionalFieldOf("hitch_x", 0f).forGetter(Settings::hitchX),
				Codec.FLOAT.optionalFieldOf("hitch_y", 1.1f).forGetter(Settings::hitchY),
				Codec.FLOAT.optionalFieldOf("hitch_z", -1.2f).forGetter(Settings::hitchZ),
				Codec.FLOAT.optionalFieldOf("couple_radius", 2.0f).forGetter(Settings::coupleRadius),
				Codec.FLOAT.optionalFieldOf("towing_speed_factor", 0.7f).forGetter(Settings::towingSpeedFactor)
		).apply(i, Settings::new));

		static final Settings DEFAULT = MachineModule.defaults(CODEC);
	}

	private UUID trailerUuid;
	private boolean nothingToCouple;

	public TractorModule(ConstructionMachineEntity machine) {
		super(machine);
	}

	@Override
	public MachineType type() {
		return MachineType.TRACTOR;
	}

	@Override
	public String[] floatChannels() {
		return CHANNELS;
	}

	Settings settings() {
		return this.machine.settings().machineSettings(Settings.class, Settings.DEFAULT);
	}

	/** Fifth-wheel position in the world - yaw only, so a trailer isn't pitched about by the
	 * tractor's cosmetic terrain tilt. Valid on both sides. */
	public Vec3d hitchWorld() {
		Settings s = this.settings();
		float scale = this.machine.getDefinition().scale();
		return TrailerModule.yawTransform(this.machine.getX(), this.machine.getY(), this.machine.getZ(),
				this.machine.getYaw(), s.hitchX() * scale, s.hitchY() * scale, s.hitchZ() * scale);
	}

	/** The coupled trailer (server), or null. */
	private ConstructionMachineEntity trailer(ServerWorld world) {
		if (this.trailerUuid == null) {
			return null;
		}
		Entity entity = world.getEntity(this.trailerUuid);
		if (entity instanceof ConstructionMachineEntity trailer && !trailer.isRemoved()
				&& trailer.module() instanceof TrailerModule module && module.isCoupledTo(this.machine)) {
			return trailer;
		}
		return null;
	}

	@Override
	public void serverTick(ServerWorld world, ServerPlayerEntity operator) {
		ConstructionMachineEntity trailer = this.trailer(world);
		// A trailer in an unloaded chunk is simply not found yet; only a trailer that exists but
		// no longer points back at us (uncoupled from its side, destroyed) breaks the link.
		if (this.trailerUuid != null && trailer == null && world.getEntity(this.trailerUuid) != null) {
			this.trailerUuid = null;
		}
		this.machine.setFloatChannel(COUPLED, this.trailerUuid != null ? 1f : 0f);

		if (operator == null || !this.machine.isPrimaryPressed()) {
			return;
		}
		if (trailer != null) {
			((TrailerModule) trailer.module()).uncouple();
			this.trailerUuid = null;
			this.sound(world, SoundEvents.BLOCK_PISTON_CONTRACT);
			return;
		}
		Settings s = this.settings();
		Vec3d hitch = this.hitchWorld();
		List<ConstructionMachineEntity> candidates = world.getEntitiesByClass(ConstructionMachineEntity.class,
				new Box(hitch, hitch).expand(s.coupleRadius() + 16.0),
				e -> e != this.machine && !e.isRemoved() && !e.tudursvehiclemod$isDestroyed()
						&& e.module() instanceof TrailerModule module && !module.isCoupled());
		ConstructionMachineEntity best = null;
		double bestDist = (double) s.coupleRadius() * s.coupleRadius();
		for (ConstructionMachineEntity candidate : candidates) {
			double dist = ((TrailerModule) candidate.module()).kingpinWorld().squaredDistanceTo(hitch);
			if (dist <= bestDist) {
				best = candidate;
				bestDist = dist;
			}
		}
		this.nothingToCouple = best == null;
		if (best != null) {
			((TrailerModule) best.module()).coupleTo(this.machine);
			this.trailerUuid = best.getUuid();
			this.sound(world, SoundEvents.BLOCK_PISTON_EXTEND);
		}
	}

	private void sound(ServerWorld world, net.minecraft.sound.SoundEvent sound) {
		Vec3d hitch = this.hitchWorld();
		world.playSound(null, hitch.x, hitch.y, hitch.z, sound, SoundCategory.BLOCKS, 0.8f, 0.7f);
	}

	@Override
	public boolean handleHatchToggle() {
		if (this.machine.getEntityWorld() instanceof ServerWorld world) {
			ConstructionMachineEntity trailer = this.trailer(world);
			if (trailer != null) {
				trailer.toggleHatch();
				return true;
			}
		}
		return false;
	}

	@Override
	public float maxSpeedFactor() {
		return this.machine.getFloatChannel(COUPLED) > 0.5f ? this.settings().towingSpeedFactor() : 1f;
	}

	@Override
	public Text status() {
		// Names keys: built client-side (ConstructionAddonClient).
		if (this.trailerUuid != null) {
			return null;
		}
		return this.nothingToCouple ? Text.translatable("status.constructionaddon.tractor.nothing") : null;
	}

	@Override
	public void writeData(WriteView view) {
		view.putString("TractorTrailer", this.trailerUuid != null ? this.trailerUuid.toString() : "");
	}

	@Override
	public void readData(ReadView view) {
		String value = view.getString("TractorTrailer", "");
		try {
			this.trailerUuid = value.isEmpty() ? null : UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			this.trailerUuid = null;
		}
	}
}
