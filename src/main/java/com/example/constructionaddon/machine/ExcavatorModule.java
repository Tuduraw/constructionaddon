package com.example.constructionaddon.machine;

import com.example.constructionaddon.asset.ResistanceProfile;
import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.constructionaddon.work.BlockBag;
import com.example.constructionaddon.work.BlockWork;
import com.example.constructionaddon.work.GroundResistance;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Backhoe / hydraulic excavator.
 *
 * <p>Driving (WASD) and operating the arm are entirely independent - the arm never touches the
 * driving keys, so both work at once. Work mode (hold M) is only the arm's own on/off lever, the
 * cab controls being:
 * <ul>
 *   <li>Left / Right - slew the upper structure (and the cab with its operator) left / right;</li>
 *   <li>arrow Up / Down - raise / lower the BOOM;</li>
 *   <li>the arm-axis keys (default , / .) - curl the ARM in / out;</li>
 * </ul>
 * Boom and arm each have their OWN dedicated input and move fully independently at their own
 * configured speeds - not solved together from a single target the way an earlier version of
 * this module did (a two-link IK giving straight-line bucket control). That gave smoother motion
 * but no way to move just one joint precisely; direct per-joint control trades the smoothness for
 * the fine, independent adjustment a real excavator's separate boom and arm levers give, and lets
 * both be driven at once since they're on separate keys.
 * <ul>
 *   <li>primary (Z) held - curl the bucket in and dig: blocks at the cutting edge are broken and
 *       loaded, harder ground taking longer (shared GroundResistance);</li>
 *   <li>secondary (X) held - open the bucket; once it passes the dump angle the load pours out
 *       at the cutting edge - straight into a dump truck's bed when the edge is over one,
 *       otherwise spreading out and mounding up nearby (see BlockWork#drop()).</li>
 * </ul> */
public final class ExcavatorModule extends MachineModule {

	private static final String[] CHANNELS = {"swing", "boom", "arm", "bucket", "load"};
	private static final int SWING = 0;
	private static final int BOOM = 1;
	private static final int ARM = 2;
	private static final int BUCKET = 3;
	private static final int LOAD = 4;

	public record Geometry(String tipPoint) {
		static final MapCodec<Geometry> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.STRING.optionalFieldOf("tip_point", "bucket_tip").forGetter(Geometry::tipPoint)
		).apply(i, Geometry::new));
	}

	public record Motion(float swingSpeed, float swingLimit, float boomSpeed, float armSpeed,
			float boomMin, float boomMax, float armMin, float armMax, float bucketMin, float bucketMax,
			float curlSpeed) {
		static final MapCodec<Motion> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.FLOAT.optionalFieldOf("swing_speed", 2.5f).forGetter(Motion::swingSpeed),
				Codec.FLOAT.optionalFieldOf("swing_limit", 0f).forGetter(Motion::swingLimit),
				Codec.FLOAT.optionalFieldOf("boom_speed", 1.5f).forGetter(Motion::boomSpeed),
				Codec.FLOAT.optionalFieldOf("arm_speed", 2.0f).forGetter(Motion::armSpeed),
				Codec.FLOAT.optionalFieldOf("boom_min", -45f).forGetter(Motion::boomMin),
				Codec.FLOAT.optionalFieldOf("boom_max", 45f).forGetter(Motion::boomMax),
				Codec.FLOAT.optionalFieldOf("arm_min", -60f).forGetter(Motion::armMin),
				Codec.FLOAT.optionalFieldOf("arm_max", 100f).forGetter(Motion::armMax),
				Codec.FLOAT.optionalFieldOf("bucket_min", -60f).forGetter(Motion::bucketMin),
				Codec.FLOAT.optionalFieldOf("bucket_max", 110f).forGetter(Motion::bucketMax),
				Codec.FLOAT.optionalFieldOf("curl_speed", 4.0f).forGetter(Motion::curlSpeed)
		).apply(i, Motion::new));
	}

	public record Work(int capacity, float digRadius, float baseDigTicks, float dumpAngle, int dumpInterval,
			int dumpSpreadRadius, float dumpSlope) {
		static final MapCodec<Work> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.INT.optionalFieldOf("capacity", 4).forGetter(Work::capacity),
				Codec.FLOAT.optionalFieldOf("dig_radius", 0.8f).forGetter(Work::digRadius),
				Codec.FLOAT.optionalFieldOf("base_dig_ticks", 4f).forGetter(Work::baseDigTicks),
				Codec.FLOAT.optionalFieldOf("dump_angle", -20f).forGetter(Work::dumpAngle),
				Codec.INT.optionalFieldOf("dump_interval", 3).forGetter(Work::dumpInterval),
				Codec.INT.optionalFieldOf("dump_spread_radius", 4).forGetter(Work::dumpSpreadRadius),
				Codec.FLOAT.optionalFieldOf("dump_slope", 0.75f).forGetter(Work::dumpSlope)
		).apply(i, Work::new));
	}

	public record Settings(Geometry geometry, Motion motion, Work work, ResistanceProfile resistance) {
		public static final Codec<Settings> CODEC = RecordCodecBuilder.create(i -> i.group(
				Geometry.MAP_CODEC.forGetter(Settings::geometry),
				Motion.MAP_CODEC.forGetter(Settings::motion),
				Work.MAP_CODEC.forGetter(Settings::work),
				ResistanceProfile.codec(1.0f, 3.5f).optionalFieldOf("resistance", new ResistanceProfile(1.0f, 3.5f, java.util.Map.of()))
						.forGetter(Settings::resistance)
		).apply(i, Settings::new));

		public static final Settings DEFAULT = CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, new com.google.gson.JsonObject())
				.result().orElseThrow();
	}

	private enum Status { IDLE, WORKING, FULL, REFUSED, PROTECTED, DUMPING, TRUCK }

	private final BlockBag bag = new BlockBag();
	private BlockPos digTarget;
	private float digProgress;
	private int dumpCooldown;
	private Status status = Status.IDLE;
	private float lastHardness;

	public ExcavatorModule(ConstructionMachineEntity machine) {
		super(machine);
	}

	@Override
	public MachineType type() {
		return MachineType.EXCAVATOR;
	}

	@Override
	public String[] floatChannels() {
		return CHANNELS;
	}

	private Settings settings() {
		return this.machine.settings().machineSettings(Settings.class, Settings.DEFAULT);
	}

	// Driving is never suppressed here - the arm has its own dedicated axes (default I/K/J/L),
	// so digging and driving work at the same time; there is nothing for the two to fight over.

	@Override
	public void serverTick(ServerWorld world, ServerPlayerEntity operator) {
		Settings s = this.settings();
		this.machine.setFloatChannel(LOAD, MathHelper.clamp(this.bag.total() / (float) Math.max(1, s.work().capacity()), 0f, 1f));
		if (!this.machine.isWorkMode() || operator == null) {
			this.status = Status.IDLE;
			return;
		}
		this.status = Status.WORKING;

		this.updateSwing(s);
		this.updateBoomAndArm(s);

		float bucket = this.machine.getFloatChannel(BUCKET);
		float curl = 0f;
		if (this.machine.isPrimaryHeld()) {
			curl += s.motion().curlSpeed();
		}
		if (this.machine.isSecondaryHeld()) {
			curl -= s.motion().curlSpeed();
		}
		float newBucket = MathHelper.clamp(bucket + curl,
				Math.min(s.motion().bucketMin(), s.motion().bucketMax()), Math.max(s.motion().bucketMin(), s.motion().bucketMax()));
		this.machine.setFloatChannel(BUCKET, newBucket);

		if (this.machine.isPrimaryHeld() && newBucket > bucket) {
			this.dig(world, operator, s);
		} else {
			this.digTarget = null;
			this.digProgress = 0f;
		}

		if (newBucket <= s.work().dumpAngle() && !this.bag.isEmpty()) {
			if (--this.dumpCooldown <= 0) {
				this.dumpCooldown = Math.max(1, s.work().dumpInterval());
				this.releaseOne(world, operator, s);
			}
		}
	}

	// ------------------------------------------------------------------
	// Slewing
	// ------------------------------------------------------------------

	private void updateSwing(Settings s) {
		float input = this.machine.workHorizontal();
		if (input == 0f) {
			return;
		}
		float swing = this.machine.getFloatChannel(SWING);
		float next = swing + input * s.motion().swingSpeed();
		if (s.motion().swingLimit() > 0f) {
			next = MathHelper.clamp(next, -s.motion().swingLimit(), s.motion().swingLimit());
		}
		this.machine.setFloatChannel(SWING, next);
		this.machine.rotateJointSeatRiders(next - swing);
	}

	// ------------------------------------------------------------------
	// Boom / arm: independent direct control, each on its own dedicated axis
	// ------------------------------------------------------------------

	/** Boom and arm each have their own dedicated input and move independently - the operator
	 * decides exactly how much of each to use, rather than the two being solved together from a
	 * single "bucket position" target. Both can be driven at once (they're separate physical
	 * levers on a real excavator too), each simply adding to its own joint's current angle at its
	 * own configured speed. A joint angle is measured the way the JSON joints are written for
	 * these machines: positive = raising (rotation about -X). */
	private void updateBoomAndArm(Settings s) {
		float boomInput = this.machine.workVertical();
		if (boomInput != 0f) {
			float boom = this.machine.getFloatChannel(BOOM);
			float next = MathHelper.clamp(boom + boomInput * s.motion().boomSpeed(),
					Math.min(s.motion().boomMin(), s.motion().boomMax()), Math.max(s.motion().boomMin(), s.motion().boomMax()));
			this.machine.setFloatChannel(BOOM, next);
		}
		float armInput = this.machine.workArm();
		if (armInput != 0f) {
			float arm = this.machine.getFloatChannel(ARM);
			float next = MathHelper.clamp(arm + armInput * s.motion().armSpeed(),
					Math.min(s.motion().armMin(), s.motion().armMax()), Math.max(s.motion().armMin(), s.motion().armMax()));
			this.machine.setFloatChannel(ARM, next);
		}
	}

	// ------------------------------------------------------------------
	// Digging and dumping
	// ------------------------------------------------------------------

	private void dig(ServerWorld world, ServerPlayerEntity operator, Settings s) {
		if (this.bag.total() >= s.work().capacity()) {
			this.status = Status.FULL;
			return;
		}
		Vec3d tip = this.machine.workPoint(s.geometry().tipPoint());
		BlockPos target = this.findDigTarget(world, tip, s.work().digRadius());
		if (target == null) {
			this.digTarget = null;
			this.digProgress = 0f;
			return;
		}
		if (!target.equals(this.digTarget)) {
			this.digTarget = target;
			this.digProgress = 0f;
		}
		BlockState state = world.getBlockState(target);
		GroundResistance.Result resistance = GroundResistance.evaluate(world, target, state, s.resistance());
		this.lastHardness = resistance.hardness();
		if (resistance.passable()) {
			// Plants, snow layers and the like are simply cleared out of the way.
			BlockWork.remove(world, operator, target, this.machine);
			return;
		}
		if (resistance.refused()) {
			this.status = Status.REFUSED;
			return;
		}
		if (!BlockWork.canBreak(world, operator, target, state)) {
			this.status = Status.PROTECTED;
			return;
		}
		this.digProgress += 1f / Math.max(0.05f, s.work().baseDigTicks() * resistance.factor());
		if (this.digProgress >= 1f) {
			this.digProgress = 0f;
			Block block = state.getBlock();
			if (BlockWork.remove(world, operator, target, this.machine)) {
				this.bag.add(block);
			}
		}
	}

	/** The nearest diggable block within the cutting radius of the bucket edge, or null. Fluids
	 * are never dug. */
	private BlockPos findDigTarget(ServerWorld world, Vec3d tip, float radius) {
		int r = MathHelper.ceil(radius);
		BlockPos center = BlockPos.ofFloored(tip);
		List<BlockPos> candidates = new ArrayList<>();
		for (BlockPos pos : BlockPos.iterate(center.add(-r, -r, -r), center.add(r, r, r))) {
			if (Vec3d.ofCenter(pos).squaredDistanceTo(tip) > (radius + 0.5) * (radius + 0.5)) {
				continue;
			}
			BlockState state = world.getBlockState(pos);
			if (state.isAir() || !state.getFluidState().isEmpty()) {
				continue;
			}
			candidates.add(pos.toImmutable());
		}
		return candidates.stream()
				.min(Comparator.comparingDouble(pos -> Vec3d.ofCenter(pos).squaredDistanceTo(tip)))
				.orElse(null);
	}

	private void releaseOne(ServerWorld world, ServerPlayerEntity operator, Settings s) {
		Block block = this.bag.takeOne();
		if (block == null) {
			return;
		}
		Vec3d tip = this.machine.workPoint(s.geometry().tipPoint());
		this.status = Status.DUMPING;
		if (block.asItem() != Items.AIR) {
			ConstructionMachineEntity truck = DumpTruckModule.findReceiver(world, tip, this.machine);
			if (truck != null) {
				ItemStack leftover = truck.insertCargo(new ItemStack(block));
				if (leftover.isEmpty()) {
					this.status = Status.TRUCK;
					world.playSound(null, tip.x, tip.y, tip.z, SoundEvents.BLOCK_GRAVEL_PLACE, SoundCategory.BLOCKS, 0.8f, 0.8f);
					return;
				}
			}
		}
		BlockWork.drop(world, operator, BlockPos.ofFloored(tip), block, s.work().dumpSpreadRadius(), s.work().dumpSlope());
	}

	@Override
	public Text status() {
		int capacity = this.settings().work().capacity();
		return switch (this.status) {
			case IDLE -> null;
			// FULL needs to name the release key - shown client-side instead (see
			// ConstructionAddonClient), so it always names whatever key the player has that
			// bound to right now, not just the default.
			case FULL -> null;
			case REFUSED -> Text.translatable("status.constructionaddon.excavator.refused",
					String.format(java.util.Locale.ROOT, "%.1f", this.lastHardness));
			case PROTECTED -> Text.translatable("status.constructionaddon.protected");
			case DUMPING -> Text.translatable("status.constructionaddon.excavator.dumping", this.bag.total(), capacity);
			case TRUCK -> Text.translatable("status.constructionaddon.excavator.truck", this.bag.total(), capacity);
			case WORKING -> Text.translatable("status.constructionaddon.excavator.working", this.bag.total(), capacity);
		};
	}

	@Override
	public void writeData(WriteView view) {
		view.putString("ExcavatorBucket", this.bag.encode());
	}

	@Override
	public void readData(ReadView view) {
		this.bag.decode(view.getString("ExcavatorBucket", ""));
	}
}
