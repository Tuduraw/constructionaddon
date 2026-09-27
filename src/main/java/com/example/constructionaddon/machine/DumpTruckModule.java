package com.example.constructionaddon.machine;

import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.constructionaddon.work.BlockWork;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.List;

/** Dump truck. The bed is the vehicle inventory, so hand-loaded items and excavator loads are
 * the same thing. An excavator dumping with its cutting edge inside bed_min / bed_max loads it.
 * Primary (Z) raises the bed, secondary (X) lowers it; past dump_start_angle block items leave the
 * discharge point, faster the steeper the bed. Non-block items stay in the bed. */
public final class DumpTruckModule extends MachineModule {

	private static final String[] CHANNELS = {"bed", "load"};
	private static final int BED = 0;
	private static final int LOAD = 1;

	public record Settings(float bedMinX, float bedMinY, float bedMinZ, float bedMaxX, float bedMaxY, float bedMaxZ,
			float bedSpeed, float bedMaxAngle, float dumpStartAngle, int dumpIntervalSlow, int dumpIntervalFast,
			int capacity, String dischargePoint, int dumpSpreadRadius, float dumpSlope) {
		public static final Codec<Settings> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.FLOAT.optionalFieldOf("bed_min_x", -1.1f).forGetter(Settings::bedMinX),
				Codec.FLOAT.optionalFieldOf("bed_min_y", 1.0f).forGetter(Settings::bedMinY),
				Codec.FLOAT.optionalFieldOf("bed_min_z", -2.5f).forGetter(Settings::bedMinZ),
				Codec.FLOAT.optionalFieldOf("bed_max_x", 1.1f).forGetter(Settings::bedMaxX),
				Codec.FLOAT.optionalFieldOf("bed_max_y", 4.0f).forGetter(Settings::bedMaxY),
				Codec.FLOAT.optionalFieldOf("bed_max_z", 1.0f).forGetter(Settings::bedMaxZ),
				Codec.FLOAT.optionalFieldOf("bed_speed", 1.5f).forGetter(Settings::bedSpeed),
				Codec.FLOAT.optionalFieldOf("bed_max_angle", 55f).forGetter(Settings::bedMaxAngle),
				Codec.FLOAT.optionalFieldOf("dump_start_angle", 20f).forGetter(Settings::dumpStartAngle),
				Codec.INT.optionalFieldOf("dump_interval_slow", 8).forGetter(Settings::dumpIntervalSlow),
				Codec.INT.optionalFieldOf("dump_interval_fast", 2).forGetter(Settings::dumpIntervalFast),
				Codec.INT.optionalFieldOf("capacity", 0).forGetter(Settings::capacity),
				Codec.STRING.optionalFieldOf("discharge_point", "discharge").forGetter(Settings::dischargePoint),
				Codec.INT.optionalFieldOf("dump_spread_radius", 3).forGetter(Settings::dumpSpreadRadius),
				Codec.FLOAT.optionalFieldOf("dump_slope", 0.75f).forGetter(Settings::dumpSlope)
		).apply(i, Settings::new));

		static final Settings DEFAULT = MachineModule.defaults(CODEC);
	}

	private int dumpCooldown;
	private int loadCount;
	private boolean dumpedThisTick;

	public DumpTruckModule(ConstructionMachineEntity machine) {
		super(machine);
	}

	@Override
	public MachineType type() {
		return MachineType.DUMP_TRUCK;
	}

	@Override
	public String[] floatChannels() {
		return CHANNELS;
	}

	private Settings settings() {
		return this.machine.settings().machineSettings(Settings.class, Settings.DEFAULT);
	}

	/** Load count the fill level (and the load heap drawn in the bed) is measured against. */
	private int capacity() {
		int configured = this.settings().capacity();
		return configured > 0 ? configured : Math.max(1, (this.machine.cargoEnd() - this.machine.cargoStart()) * 64);
	}

	@Override
	public void serverTick(ServerWorld world, ServerPlayerEntity operator) {
		Settings s = this.settings();
		if (this.machine.age % 5 == 0) {
			this.loadCount = this.countBlockItems();
			this.machine.setFloatChannel(LOAD, MathHelper.clamp(this.loadCount / (float) this.capacity(), 0f, 1f));
		}

		float bedInput = (this.machine.isPrimaryHeld() ? 1f : 0f) - (this.machine.isSecondaryHeld() ? 1f : 0f);
		this.nudge(BED, bedInput, s.bedSpeed(), 0f, s.bedMaxAngle());
		float bed = this.machine.getFloatChannel(BED);

		this.dumpedThisTick = false;
		if (operator != null && bed >= s.dumpStartAngle() && this.loadCount > 0) {
			if (--this.dumpCooldown <= 0) {
				float steepness = MathHelper.clamp((bed - s.dumpStartAngle()) / Math.max(1f, s.bedMaxAngle() - s.dumpStartAngle()), 0f, 1f);
				this.dumpCooldown = Math.max(1, Math.round(MathHelper.lerp(steepness, s.dumpIntervalSlow(), s.dumpIntervalFast())));
				this.dumpOne(world, operator, s);
			}
		}
	}

	private int countBlockItems() {
		int count = 0;
		for (int slot = this.machine.cargoStart(); slot < this.machine.cargoEnd(); slot++) {
			ItemStack stack = this.machine.getStack(slot);
			if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/** Takes from the LAST occupied block-item slot first - the top of the heap. */
	private void dumpOne(ServerWorld world, ServerPlayerEntity operator, Settings s) {
		for (int slot = this.machine.cargoEnd() - 1; slot >= this.machine.cargoStart(); slot--) {
			ItemStack stack = this.machine.getStack(slot);
			if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
				continue;
			}
			Block block = blockItem.getBlock();
			stack.decrement(1);
			if (stack.isEmpty()) {
				this.machine.setStack(slot, ItemStack.EMPTY);
			}
			this.loadCount = Math.max(0, this.loadCount - 1);
			Vec3d point = this.machine.workPoint(s.dischargePoint());
			BlockWork.drop(world, operator, BlockPos.ofFloored(point), block, s.dumpSpreadRadius(), s.dumpSlope());
			world.playSound(null, point.x, point.y, point.z, SoundEvents.BLOCK_GRAVEL_FALL, SoundCategory.BLOCKS, 0.7f, 0.9f);
			this.dumpedThisTick = true;
			return;
		}
	}

	/** Whether a world point lies over this truck's bed (bed tilt ignored - an excavator loads a
	 * lowered bed). */
	public boolean bedContains(Vec3d world) {
		Settings s = this.settings();
		Vector3f local = this.machine.worldToModel(world);
		return local.x >= Math.min(s.bedMinX(), s.bedMaxX()) && local.x <= Math.max(s.bedMinX(), s.bedMaxX())
				&& local.y >= Math.min(s.bedMinY(), s.bedMaxY()) && local.y <= Math.max(s.bedMinY(), s.bedMaxY())
				&& local.z >= Math.min(s.bedMinZ(), s.bedMaxZ()) && local.z <= Math.max(s.bedMinZ(), s.bedMaxZ());
	}

	/** The dump truck (other than {@code exclude}) whose bed contains the point, or null. */
	public static ConstructionMachineEntity findReceiver(ServerWorld world, Vec3d point, ConstructionMachineEntity exclude) {
		List<ConstructionMachineEntity> trucks = world.getEntitiesByClass(ConstructionMachineEntity.class,
				new Box(point, point).expand(12.0),
				e -> e != exclude && !e.isRemoved() && !e.tudursvehiclemod$isDestroyed()
						&& e.module() instanceof DumpTruckModule);
		for (ConstructionMachineEntity truck : trucks) {
			if (((DumpTruckModule) truck.module()).bedContains(point)) {
				return truck;
			}
		}
		return null;
	}

	@Override
	public Text status() {
		float bed = this.machine.getFloatChannel(BED);
		if (bed <= 0.01f && !this.machine.isPrimaryHeld()) {
			return Text.translatable("status.constructionaddon.dump_truck.load", this.loadCount, this.capacity());
		}
		return Text.translatable(this.dumpedThisTick || bed >= this.settings().dumpStartAngle()
						? "status.constructionaddon.dump_truck.dumping" : "status.constructionaddon.dump_truck.raising",
				Math.round(bed), this.loadCount);
	}
}
