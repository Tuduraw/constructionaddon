package com.example.constructionaddon.machine;

import com.example.constructionaddon.asset.ResistanceProfile;
import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.constructionaddon.network.WorkAxis;
import com.example.constructionaddon.work.BlockWork;
import com.example.constructionaddon.work.GroundResistance;
import com.example.constructionaddon.work.PourSpreader;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/** Pile driver: drives the first block item in its cargo, block by block, into the ground under
 * the leader. The driving method ("mode") decides how hardness (GroundResistance) slows it:
 * <ul>
 *   <li>hammer - blows needed per block = blows_per_block x factor; refusal above
 *       max_blows_per_block;</li>
 *   <li>rotary - ticks per block = ticks_per_block x factor, rotation = rpm / factor; refusal
 *       below min_rpm.</li>
 * </ul>
 * Controls (driving always works except with the jacks down): secondary (X) raises / folds the
 * leader (travel with it up is fine; with crawler_extension the crawlers widen first); work mode
 * deploys the jacks; swing keys slew the upper structure; vertical keys set the target depth;
 * primary (Z) drives once the leader is up and (unless require_outriggers is false) the jacks are
 * down. A different column under the leader starts a new pile.
 *
 * <p>The leader's shape, crawler widening ("track_width") and roller spin ("travel", blocks per
 * tick) are purely JSON joints: a small machine folds the whole leader, a large one only its upper
 * section above a hinge. */
public final class PileDriverModule extends MachineModule {

	private static final String[] CHANNELS = {"mast", "hammer", "rpm", "feed", "swing", "outrigger", "track_width", "travel"};
	private static final int MAST = 0;
	private static final int HAMMER = 1;
	private static final int RPM = 2;
	private static final int FEED = 3;
	private static final int SWING = 4;
	private static final int OUTRIGGER = 5;
	private static final int TRACK_WIDTH = 6;
	private static final int TRAVEL = 7;

	public record Setup(float swingSpeed, float swingLimit, float outriggerSpeed, boolean requireOutriggers,
			boolean crawlerExtension, float trackWidthSpeed) {
		static final MapCodec<Setup> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.FLOAT.optionalFieldOf("swing_speed", 1.5f).forGetter(Setup::swingSpeed),
				Codec.FLOAT.optionalFieldOf("swing_limit", 0f).forGetter(Setup::swingLimit),
				Codec.FLOAT.optionalFieldOf("outrigger_speed", 0.04f).forGetter(Setup::outriggerSpeed),
				Codec.BOOL.optionalFieldOf("require_outriggers", true).forGetter(Setup::requireOutriggers),
				Codec.BOOL.optionalFieldOf("crawler_extension", false).forGetter(Setup::crawlerExtension),
				Codec.FLOAT.optionalFieldOf("track_width_speed", 0.02f).forGetter(Setup::trackWidthSpeed)
		).apply(i, Setup::new));
	}

	public record Common(String mode, String pilePoint, int maxDepth, int defaultDepth, float mastSpeed,
			String defaultPileBlock, int surfaceSearch) {
		static final MapCodec<Common> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.STRING.optionalFieldOf("mode", "hammer").forGetter(Common::mode),
				Codec.STRING.optionalFieldOf("pile_point", "pile_point").forGetter(Common::pilePoint),
				Codec.INT.optionalFieldOf("max_depth", 32).forGetter(Common::maxDepth),
				Codec.INT.optionalFieldOf("default_depth", 8).forGetter(Common::defaultDepth),
				Codec.FLOAT.optionalFieldOf("mast_speed", 0.02f).forGetter(Common::mastSpeed),
				Codec.STRING.optionalFieldOf("creative_pile_block", "minecraft:oak_log").forGetter(Common::defaultPileBlock),
				Codec.INT.optionalFieldOf("surface_search", 12).forGetter(Common::surfaceSearch)
		).apply(i, Common::new));
	}

	public record Hammer(float blowsPerBlock, int maxBlowsPerBlock, int liftTicks, int dropTicks, float liftHeight) {
		static final MapCodec<Hammer> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.FLOAT.optionalFieldOf("blows_per_block", 2f).forGetter(Hammer::blowsPerBlock),
				Codec.INT.optionalFieldOf("max_blows_per_block", 40).forGetter(Hammer::maxBlowsPerBlock),
				Codec.INT.optionalFieldOf("lift_ticks", 20).forGetter(Hammer::liftTicks),
				Codec.INT.optionalFieldOf("drop_ticks", 5).forGetter(Hammer::dropTicks),
				Codec.FLOAT.optionalFieldOf("lift_height", 2.0f).forGetter(Hammer::liftHeight)
		).apply(i, Hammer::new));
	}

	public record Rotary(float ticksPerBlock, float rpm, float minRpm, float rpmResponse) {
		static final MapCodec<Rotary> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.FLOAT.optionalFieldOf("ticks_per_block", 40f).forGetter(Rotary::ticksPerBlock),
				Codec.FLOAT.optionalFieldOf("rpm", 30f).forGetter(Rotary::rpm),
				Codec.FLOAT.optionalFieldOf("min_rpm", 5f).forGetter(Rotary::minRpm),
				Codec.FLOAT.optionalFieldOf("rpm_response", 0.15f).forGetter(Rotary::rpmResponse)
		).apply(i, Rotary::new));
	}

	public record Settings(Common common, Setup setup, Hammer hammer, Rotary rotary, ResistanceProfile resistance) {
		public static final Codec<Settings> CODEC = RecordCodecBuilder.create(i -> i.group(
				Common.MAP_CODEC.forGetter(Settings::common),
				Setup.MAP_CODEC.forGetter(Settings::setup),
				Hammer.MAP_CODEC.forGetter(Settings::hammer),
				Rotary.MAP_CODEC.forGetter(Settings::rotary),
				ResistanceProfile.field(1.0f, 20.0f).forGetter(Settings::resistance)
		).apply(i, Settings::new));

		static final Settings DEFAULT = MachineModule.defaults(CODEC);

		boolean rotaryMode() {
			return "rotary".equalsIgnoreCase(this.common.mode());
		}
	}

	private enum Status { IDLE, MAST, LEADER_DOWN, JACKS, READY, DRIVING, DONE, REFUSED, NO_PILE, NO_GROUND, PROTECTED }

	// Current pile ("session").
	private boolean hasSession;
	private int pileX;
	private int pileZ;
	private int topY;
	private int depth;
	private int targetDepth = -1;
	/** Leader up (target) - toggled by the secondary key. */
	private boolean leaderUp;

	// The block the pile is currently entering.
	private BlockPos currentPos;
	private float currentFactor;
	private int requiredBlows;
	private float targetRpm;
	private int blows;
	private float progress;

	private int hammerTick;
	private float rpm;
	private int depthKeyCooldown;
	private Status status = Status.IDLE;
	private float refusedHardness;
	private Text refusedBlock = Text.empty();

	public PileDriverModule(ConstructionMachineEntity machine) {
		super(machine);
	}

	@Override
	public MachineType type() {
		return MachineType.PILE_DRIVER;
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
		// Only the jacks being physically down hold the machine - the leader being up does not;
		// a real pile driver travels around a site with its leader raised.
		return this.machine.getFloatChannel(OUTRIGGER) > 0.01f;
	}

	@Override
	public void serverTick(ServerWorld world, ServerPlayerEntity operator) {
		Settings s = this.settings();
		Setup setup = s.setup();
		if (this.targetDepth < 0) {
			this.targetDepth = MathHelper.clamp(s.common().defaultDepth(), 1, Math.max(1, s.common().maxDepth()));
		}

		if (operator != null && this.machine.isSecondaryPressed()) {
			this.leaderUp = !this.leaderUp;
		}

		// Crawlers widen before the leader rises and retract only after it has folded (no
		// widening at all without crawler_extension).
		float mast = this.machine.getFloatChannel(MAST);
		float width = this.machine.getFloatChannel(TRACK_WIDTH);
		if (!setup.crawlerExtension()) {
			this.machine.setFloatChannel(TRACK_WIDTH, 0f);
			mast = this.approach(MAST, this.leaderUp ? 1f : 0f, s.common().mastSpeed());
		} else if (this.leaderUp) {
			if (width < 1f) {
				this.approach(TRACK_WIDTH, 1f, setup.trackWidthSpeed());
			} else {
				mast = this.approach(MAST, 1f, s.common().mastSpeed());
			}
		} else if (mast > 0f) {
			mast = this.approach(MAST, 0f, s.common().mastSpeed());
		} else {
			this.approach(TRACK_WIDTH, 0f, setup.trackWidthSpeed());
		}

		float outrigger = this.approach(OUTRIGGER, this.machine.isWorkMode() ? 1f : 0f, setup.outriggerSpeed());

		// Rollers: signed travel, blocks per tick.
		this.machine.setFloatChannel(TRAVEL, this.machine.getCruiseSpeedValue());

		if (operator != null) {
			this.workSwing(SWING, setup.swingSpeed(), setup.swingLimit());
			int lift = (int) this.machine.workAxis(WorkAxis.VERTICAL);
			if (lift != 0 && --this.depthKeyCooldown <= 0) {
				this.depthKeyCooldown = 4;
				this.targetDepth = MathHelper.clamp(this.targetDepth + lift, 1, Math.max(1, s.common().maxDepth()));
			} else if (lift == 0) {
				this.depthKeyCooldown = 0;
			}
		}

		boolean leaderReady = mast >= 0.999f;
		boolean jacksReady = !setup.requireOutriggers() || outrigger >= 0.999f;
		if (!leaderReady) {
			this.status = this.leaderUp ? Status.MAST : (this.machine.isWorkMode() ? Status.LEADER_DOWN : Status.IDLE);
		} else if (!jacksReady) {
			this.status = Status.JACKS;
		} else if (!this.machine.isPrimaryHeld()) {
			this.status = Status.READY;
		}
		boolean driving = operator != null && leaderReady && jacksReady && this.machine.isPrimaryHeld();

		if (s.rotaryMode()) {
			this.tickRotary(world, operator, s, driving);
		} else {
			this.tickHammer(world, operator, s, driving);
		}
	}

	// ------------------------------------------------------------------
	// Hammer
	// ------------------------------------------------------------------

	private void tickHammer(ServerWorld world, ServerPlayerEntity operator, Settings s, boolean driving) {
		Hammer h = s.hammer();
		int lift = Math.max(1, h.liftTicks());
		int drop = Math.max(1, h.dropTicks());
		if (this.hammerTick == 0) {
			// A new blow only starts if the pile can actually advance.
			if (!driving || !this.prepare(world, operator, s)) {
				this.machine.setFloatChannel(HAMMER, 0f);
				return;
			}
		}
		this.hammerTick++;
		float height;
		if (this.hammerTick <= lift) {
			height = h.liftHeight() * this.hammerTick / lift;
		} else {
			float t = (this.hammerTick - lift) / (float) drop;
			height = h.liftHeight() * (1f - t * t); // free fall
		}
		this.machine.setFloatChannel(HAMMER, Math.max(0f, height));
		if (this.hammerTick >= lift + drop) {
			this.hammerTick = 0;
			this.machine.setFloatChannel(HAMMER, 0f);
			this.blow(world, operator, s);
		}
	}

	private void blow(ServerWorld world, ServerPlayerEntity operator, Settings s) {
		if (this.currentPos == null) {
			return;
		}
		this.blows++;
		BlockPos head = new BlockPos(this.pileX, this.topY, this.pileZ);
		world.playSound(null, head.getX() + 0.5, head.getY() + 1.0, head.getZ() + 0.5,
				SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.BLOCKS, 0.9f, 0.55f);
		BlockWork.effects(world, head, world.getBlockState(head), 12);
		if (this.blows >= this.requiredBlows) {
			this.advance(world, operator, s);
		}
	}

	// ------------------------------------------------------------------
	// Rotary
	// ------------------------------------------------------------------

	private void tickRotary(ServerWorld world, ServerPlayerEntity operator, Settings s, boolean driving) {
		Rotary r = s.rotary();
		boolean turning = driving && this.prepare(world, operator, s);
		float target = turning ? this.targetRpm : 0f;
		this.rpm += (target - this.rpm) * MathHelper.clamp(r.rpmResponse(), 0.01f, 1f);
		if (Math.abs(this.rpm) < 0.05f && target == 0f) {
			this.rpm = 0f;
		}
		this.machine.setFloatChannel(RPM, this.rpm);
		if (!turning) {
			return;
		}
		// Advance scales with how close the head actually is to its target speed, so spin-up and
		// the slow grind in hard ground both show in the rate of penetration.
		float speedRatio = this.targetRpm > 0f ? MathHelper.clamp(this.rpm / this.targetRpm, 0f, 1f) : 1f;
		float ticksForBlock = Math.max(1f, r.ticksPerBlock() * this.currentFactor);
		this.progress += speedRatio / ticksForBlock;
		this.machine.setFloatChannel(FEED, MathHelper.clamp(this.progress, 0f, 1f));
		if (this.machine.age % 8 == 0) {
			BlockPos head = new BlockPos(this.pileX, this.topY, this.pileZ);
			world.playSound(null, head.getX() + 0.5, head.getY() + 1.0, head.getZ() + 0.5,
					SoundEvents.BLOCK_GRINDSTONE_USE, SoundCategory.BLOCKS, 0.6f,
					0.5f + 0.5f * MathHelper.clamp(this.rpm / Math.max(1f, r.rpm()), 0f, 1f));
		}
		if (this.progress >= 1f) {
			BlockWork.effects(world, new BlockPos(this.pileX, this.topY, this.pileZ),
					world.getBlockState(this.currentPos), 8);
			this.advance(world, operator, s);
		}
	}

	// ------------------------------------------------------------------
	// Shared: which block is next, can it be entered, and entering it
	// ------------------------------------------------------------------

	/** Sets up (or keeps) the block the pile will enter next. False, with a status, when it can't. */
	private boolean prepare(ServerWorld world, ServerPlayerEntity operator, Settings s) {
		Vec3d point = this.machine.workPoint(s.common().pilePoint());
		int x = MathHelper.floor(point.x);
		int z = MathHelper.floor(point.z);
		if (!this.hasSession || x != this.pileX || z != this.pileZ) {
			this.startSession(world, x, MathHelper.floor(point.y), z, s);
			if (!this.hasSession) {
				this.status = Status.NO_GROUND;
				return false;
			}
		}
		for (int guard = 0; guard < 4; guard++) {
			if (this.depth >= this.targetDepth) {
				this.status = Status.DONE;
				return false;
			}
			BlockPos pos = new BlockPos(this.pileX, this.topY - this.depth, this.pileZ);
			if (world.isOutOfHeightLimit(pos)) {
				this.status = Status.REFUSED;
				this.refusedHardness = -1f;
				this.refusedBlock = Text.translatable("status.constructionaddon.pile_driver.world_bottom");
				return false;
			}
			Block pileBlock = this.findPileBlock(operator, s);
			if (pileBlock == null) {
				this.status = Status.NO_PILE;
				return false;
			}
			BlockState state = world.getBlockState(pos);
			if (state.getBlock() == pileBlock) {
				// Already part of this (or an earlier) pile - carry on below it.
				this.depth++;
				this.resetBlockProgress();
				continue;
			}
			if (pos.equals(this.currentPos)) {
				this.status = Status.DRIVING;
				return true;
			}
			GroundResistance.Result resistance = GroundResistance.evaluate(world, pos, state, s.resistance());
			float factor = resistance.passable() ? 0.25f : resistance.factor();
			int blowsNeeded = Math.max(1, MathHelper.ceil(s.hammer().blowsPerBlock() * factor));
			float rpmNeeded = s.rotary().rpm() / Math.max(1f, factor);
			boolean refused = resistance.refused()
					|| (!s.rotaryMode() && blowsNeeded > s.hammer().maxBlowsPerBlock())
					|| (s.rotaryMode() && rpmNeeded < s.rotary().minRpm());
			if (refused) {
				this.status = Status.REFUSED;
				this.refusedHardness = resistance.hardness();
				this.refusedBlock = state.getBlock().getName();
				return false;
			}
			boolean permitted = resistance.passable()
					? BlockWork.canPlace(world, operator, pos)
					: BlockWork.canBreak(world, operator, pos, state) && BlockWork.canPlace(world, operator, pos);
			if (!permitted) {
				this.status = Status.PROTECTED;
				return false;
			}
			this.resetBlockProgress();
			this.currentPos = pos;
			this.currentFactor = factor;
			this.requiredBlows = blowsNeeded;
			this.targetRpm = rpmNeeded;
			this.status = Status.DRIVING;
			return true;
		}
		return false;
	}

	/** Finds the ground under the mast: the first solid block within surface_search below it. The
	 * pile's head will sit flush in place of that block. */
	private void startSession(ServerWorld world, int x, int fromY, int z, Settings s) {
		this.hasSession = false;
		this.resetBlockProgress();
		for (int dy = 0; dy <= s.common().surfaceSearch(); dy++) {
			BlockPos pos = new BlockPos(x, fromY - dy, z);
			if (!PourSpreader.free(world, pos) && !world.isOutOfHeightLimit(pos)) {
				this.hasSession = true;
				this.pileX = x;
				this.pileZ = z;
				this.topY = pos.getY();
				this.depth = 0;
				return;
			}
		}
	}

	private void resetBlockProgress() {
		this.currentPos = null;
		this.blows = 0;
		this.progress = 0f;
		this.machine.setFloatChannel(FEED, 0f);
	}

	private void advance(ServerWorld world, ServerPlayerEntity operator, Settings s) {
		if (this.currentPos == null) {
			return;
		}
		Block pileBlock = this.consumePileBlock(operator, s);
		if (pileBlock == null) {
			this.status = Status.NO_PILE;
			return;
		}
		// The ground the pile displaces is pushed aside, not collected - nothing drops.
		world.setBlockState(this.currentPos, pileBlock.getDefaultState());
		this.depth++;
		this.resetBlockProgress();
	}

	/** The first block item in the cargo, or - for a creative operator with none - the
	 * configured creative pile block. Null when there is nothing to drive. */
	private Block findPileBlock(ServerPlayerEntity operator, Settings s) {
		for (int slot = this.machine.cargoStart(); slot < this.machine.cargoEnd(); slot++) {
			ItemStack stack = this.machine.getStack(slot);
			if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
				return blockItem.getBlock();
			}
		}
		if (ConstructionMachineEntity.isCreative(operator)) {
			Identifier id = Identifier.tryParse(s.common().defaultPileBlock());
			return id == null ? null : Registries.BLOCK.getOptionalValue(id).orElse(null);
		}
		return null;
	}

	private Block consumePileBlock(ServerPlayerEntity operator, Settings s) {
		for (int slot = this.machine.cargoStart(); slot < this.machine.cargoEnd(); slot++) {
			ItemStack stack = this.machine.getStack(slot);
			if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
				Block block = blockItem.getBlock();
				if (!ConstructionMachineEntity.isCreative(operator)) {
					stack.decrement(1);
					if (stack.isEmpty()) {
						this.machine.setStack(slot, ItemStack.EMPTY);
					}
				}
				return block;
			}
		}
		return this.findPileBlock(operator, s);
	}

	@Override
	public Text status() {
		Settings s = this.settings();
		return switch (this.status) {
			case IDLE -> null;
			case MAST -> Text.translatable("status.constructionaddon.pile_driver.mast", this.targetDepth);
			// Names a key: built client-side (ConstructionAddonClient).
			case LEADER_DOWN -> null;
			case JACKS -> Text.translatable("status.constructionaddon.pile_driver.jacks", this.depth, this.targetDepth);
			case READY -> Text.translatable("status.constructionaddon.pile_driver.ready", this.depth, this.targetDepth);
			case DONE -> Text.translatable("status.constructionaddon.pile_driver.done", this.depth, this.targetDepth);
			case NO_PILE -> Text.translatable("status.constructionaddon.pile_driver.no_pile");
			case NO_GROUND -> Text.translatable("status.constructionaddon.pile_driver.no_ground");
			case PROTECTED -> Text.translatable("status.constructionaddon.protected");
			case REFUSED -> Text.translatable("status.constructionaddon.pile_driver.refused", this.refusedBlock,
					this.refusedHardness < 0f ? "-" : String.format(Locale.ROOT, "%.1f", this.refusedHardness),
					this.depth, this.targetDepth);
			case DRIVING -> s.rotaryMode()
					? Text.translatable("status.constructionaddon.pile_driver.rotary", this.depth, this.targetDepth,
							Math.round(this.rpm), Math.round(this.progress * 100f))
					: Text.translatable("status.constructionaddon.pile_driver.hammer", this.depth, this.targetDepth,
							this.blows, this.requiredBlows);
		};
	}

	@Override
	public void writeData(WriteView view) {
		view.putInt("PileTargetDepth", this.targetDepth);
		view.putBoolean("PileLeaderUp", this.leaderUp);
		view.putBoolean("PileSession", this.hasSession);
		view.putInt("PileX", this.pileX);
		view.putInt("PileZ", this.pileZ);
		view.putInt("PileTopY", this.topY);
		view.putInt("PileDepth", this.depth);
	}

	@Override
	public void readData(ReadView view) {
		this.targetDepth = view.getInt("PileTargetDepth", -1);
		this.leaderUp = view.getBoolean("PileLeaderUp", false);
		this.hasSession = view.getBoolean("PileSession", false);
		this.pileX = view.getInt("PileX", 0);
		this.pileZ = view.getInt("PileZ", 0);
		this.topY = view.getInt("PileTopY", 0);
		this.depth = view.getInt("PileDepth", 0);
	}
}
