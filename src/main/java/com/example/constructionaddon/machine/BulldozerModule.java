package com.example.constructionaddon.machine;

import com.example.constructionaddon.asset.ResistanceProfile;
import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.constructionaddon.network.WorkAxis;
import com.example.constructionaddon.work.BlockBag;
import com.example.constructionaddon.work.BlockWork;
import com.example.constructionaddon.work.GroundResistance;
import com.example.constructionaddon.work.PourSpreader;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Bulldozer. Grades while driving; no work mode needed.
 * <ul>
 *   <li>vertical keys - raise / lower the blade. Its bottom edge is the grade: 0 = track level,
 *       below 0 cuts down layer by layer;</li>
 *   <li>driving forward cuts blocks at or above grade onto the blade (up to capacity) and fills
 *       holes just below grade from it. Hard ground drags (GroundResistance); refusal-hard ground
 *       can't be cut. A full blade is slower;</li>
 *   <li>primary (Z) held - spill the load in front.</li>
 * </ul> */
public final class BulldozerModule extends MachineModule {

	private static final String[] CHANNELS = {"blade", "load"};
	private static final int BLADE = 0;
	private static final int LOAD = 1;

	public record Dump(int spreadRadius, float slope) {
		static final MapCodec<Dump> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.INT.optionalFieldOf("dump_spread_radius", 3).forGetter(Dump::spreadRadius),
				Codec.FLOAT.optionalFieldOf("dump_slope", 0.75f).forGetter(Dump::slope)
		).apply(i, Dump::new));
	}

	public record Settings(float frontZ, float width, float bottomY, float height, float bladeMin, float bladeMax,
			float bladeSpeed, int capacity, int maxCutsPerTick, float loadDrag, float cutDrag, int unloadInterval,
			float minSpeed, ResistanceProfile resistance, Dump dump) {
		public static final Codec<Settings> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.FLOAT.optionalFieldOf("blade_front_z", 2.3f).forGetter(Settings::frontZ),
				Codec.FLOAT.optionalFieldOf("blade_width", 3.2f).forGetter(Settings::width),
				Codec.FLOAT.optionalFieldOf("blade_bottom_y", 0f).forGetter(Settings::bottomY),
				Codec.FLOAT.optionalFieldOf("blade_height", 1.2f).forGetter(Settings::height),
				Codec.FLOAT.optionalFieldOf("blade_min", -2f).forGetter(Settings::bladeMin),
				Codec.FLOAT.optionalFieldOf("blade_max", 1.5f).forGetter(Settings::bladeMax),
				Codec.FLOAT.optionalFieldOf("blade_speed", 0.04f).forGetter(Settings::bladeSpeed),
				Codec.INT.optionalFieldOf("capacity", 16).forGetter(Settings::capacity),
				Codec.INT.optionalFieldOf("max_cuts_per_tick", 2).forGetter(Settings::maxCutsPerTick),
				Codec.FLOAT.optionalFieldOf("full_load_speed", 0.5f).forGetter(Settings::loadDrag),
				Codec.FLOAT.optionalFieldOf("cut_drag", 0.04f).forGetter(Settings::cutDrag),
				Codec.INT.optionalFieldOf("unload_interval", 3).forGetter(Settings::unloadInterval),
				Codec.FLOAT.optionalFieldOf("min_work_speed", 0.02f).forGetter(Settings::minSpeed),
				ResistanceProfile.field(1.0f, 2.0f).forGetter(Settings::resistance),
				Dump.MAP_CODEC.forGetter(Settings::dump)
		).apply(i, Settings::new));

		public static final Settings DEFAULT = MachineModule.defaults(CODEC);
	}

	private final BlockBag bag = new BlockBag();
	private int unloadCooldown;
	private boolean refusedThisTick;
	private boolean protectedThisTick;

	public BulldozerModule(ConstructionMachineEntity machine) {
		super(machine);
	}

	@Override
	public MachineType type() {
		return MachineType.BULLDOZER;
	}

	@Override
	public String[] floatChannels() {
		return CHANNELS;
	}

	private Settings settings() {
		return this.machine.settings().machineSettings(Settings.class, Settings.DEFAULT);
	}

	@Override
	public float maxSpeedFactor() {
		Settings s = this.settings();
		float fraction = MathHelper.clamp(this.bag.total() / (float) Math.max(1, s.capacity()), 0f, 1f);
		return 1f - (1f - MathHelper.clamp(s.loadDrag(), 0.05f, 1f)) * fraction;
	}

	@Override
	public void serverTick(ServerWorld world, ServerPlayerEntity operator) {
		Settings s = this.settings();
		this.refusedThisTick = false;
		this.protectedThisTick = false;
		if (operator != null) {
			this.nudge(BLADE, this.machine.workAxis(WorkAxis.VERTICAL), s.bladeSpeed(), s.bladeMin(), s.bladeMax());
			if (this.machine.getCruiseSpeedValue() > s.minSpeed()) {
				this.grade(world, operator, s);
			}
			if (this.machine.isPrimaryHeld() && !this.bag.isEmpty() && --this.unloadCooldown <= 0) {
				this.unloadCooldown = Math.max(1, s.unloadInterval());
				Block block = this.bag.takeOne();
				BlockWork.drop(world, operator, this.frontCell(s, 0f, s.frontZ() + 1.0f), block,
						s.dump().spreadRadius(), s.dump().slope());
			}
		}
		this.machine.setFloatChannel(LOAD, MathHelper.clamp(this.bag.total() / (float) Math.max(1, s.capacity()), 0f, 1f));
	}

	/** World block at a model (x, z) position at grade height. Yaw only - the grade must follow
	 * the terrain the tracks stand on, not the cosmetic tilt of the body. */
	private BlockPos frontCell(Settings s, float modelX, float modelZ) {
		float scale = this.machine.getDefinition().scale();
		double yaw = Math.toRadians(this.machine.getYaw());
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		// Model +X is the machine's left.
		double lx = Math.cos(yaw);
		double lz = Math.sin(yaw);
		double x = this.machine.getX() + (modelX * lx + modelZ * fx) * scale;
		double z = this.machine.getZ() + (modelX * lz + modelZ * fz) * scale;
		return BlockPos.ofFloored(x, this.gradeY(s), z);
	}

	private int gradeY(Settings s) {
		float scale = this.machine.getDefinition().scale();
		double bottom = this.machine.getY() + (s.bottomY() + this.machine.getFloatChannel(BLADE)) * scale;
		return MathHelper.floor(bottom + 0.5);
	}

	private void grade(ServerWorld world, ServerPlayerEntity operator, Settings s) {
		float scale = this.machine.getDefinition().scale();
		Set<BlockPos> columns = new LinkedHashSet<>();
		float half = s.width() / 2f;
		// Just ahead of the blade face, sampled every half block across its width.
		for (float x = -half + 0.25f; x <= half - 0.25f + 1.0e-3f; x += 0.5f) {
			columns.add(this.frontCell(s, x, s.frontZ() + 0.35f));
		}
		int layers = Math.max(1, MathHelper.ceil(s.height() * scale));
		int cuts = 0;
		int fills = 0;
		float drag = 0f;
		for (BlockPos column : columns) {
			for (int k = 0; k < layers && cuts < s.maxCutsPerTick(); k++) {
				BlockPos pos = column.up(k);
				BlockState state = world.getBlockState(pos);
				if (state.isAir() || !state.getFluidState().isEmpty()) {
					continue;
				}
				GroundResistance.Result resistance = GroundResistance.evaluate(world, pos, state, s.resistance());
				if (resistance.passable()) {
					BlockWork.remove(world, operator, pos);
					continue;
				}
				if (resistance.refused()) {
					this.refusedThisTick = true;
					continue;
				}
				if (this.bag.total() >= s.capacity()) {
					continue;
				}
				if (!BlockWork.canBreak(world, operator, pos, state)) {
					this.protectedThisTick = true;
					continue;
				}
				Block block = state.getBlock();
				if (BlockWork.remove(world, operator, pos)) {
					this.bag.add(block);
					cuts++;
					drag += resistance.factor();
				}
			}
			BlockPos below = column.down();
			if (fills < s.maxCutsPerTick() && !this.bag.isEmpty() && PourSpreader.free(world, below)) {
				Block block = this.bag.takeOne();
				if (BlockWork.place(world, operator, below, block)) {
					fills++;
				} else {
					this.bag.putBack(block);
				}
			}
		}
		if (drag > 0f) {
			this.machine.scaleCruiseSpeed(MathHelper.clamp(1f - s.cutDrag() * drag, 0.4f, 1f));
		}
	}

	@Override
	public Text status() {
		Settings s = this.settings();
		String blade = String.format(Locale.ROOT, "%+.2f", this.machine.getFloatChannel(BLADE));
		if (this.protectedThisTick) {
			return Text.translatable("status.constructionaddon.protected");
		}
		if (this.refusedThisTick) {
			return Text.translatable("status.constructionaddon.bulldozer.refused", blade, this.bag.total(), s.capacity());
		}
		// Names a key: built client-side (ConstructionAddonClient).
		if (this.bag.total() >= s.capacity()) {
			return null;
		}
		return Text.translatable("status.constructionaddon.bulldozer", blade, this.bag.total(), s.capacity());
	}

	@Override
	public void writeData(WriteView view) {
		view.putString("BulldozerLoad", this.bag.encode());
	}

	@Override
	public void readData(ReadView view) {
		this.bag.decode(view.getString("BulldozerLoad", ""));
	}
}
