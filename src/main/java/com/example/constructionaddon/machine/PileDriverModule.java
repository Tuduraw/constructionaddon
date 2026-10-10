package com.example.constructionaddon.machine;

import com.example.constructionaddon.asset.ResistanceProfile;
import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.constructionaddon.network.WorkAxis;
import com.example.constructionaddon.network.WorkButton;
import com.example.constructionaddon.work.BlockWork;
import com.example.constructionaddon.work.GroundResistance;
import com.example.constructionaddon.work.PileRegistry;
import com.example.constructionaddon.work.PourSpreader;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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

/** Pile driver: a real pile of set length, set under the head from the cargo, gripped, driven in or
 * pulled out, let go and gripped again, joined onto a pile already in the ground, and driven below the
 * head's reach with a drive rod / follower (see "The rig" below). The driving method ("mode") is the
 * only difference:
 * <ul>
 *   <li>rotary - the head turns the pile and presses it in (rpm falls with the ground's resistance;
 *       refusal below min_rpm);</li>
 *   <li>hammer - a drop hammer resting on the cap is lifted and dropped; every blow drives the pile a
 *       share of a block (blows_per_block x the ground's factor blows per block; refusal above
 *       max_blows_per_block). Pulling a pile out is done by the hammer's winch.</li>
 * </ul>
 * Both: travel with the leader up is fine (with crawler_extension the crawlers widen first); work
 * mode deploys the jacks (driving is held while they are down); swing keys slew the upper structure.
 *
 * <p>The leader's shape, crawler widening ("track_width") and roller spin ("travel", blocks per
 * tick) are purely JSON joints: the leader pivots on its foot pin (with backstay rams following the
 * same channel). */
public final class PileDriverModule extends MachineModule {

	private static final String[] CHANNELS = {"mast", "hammer", "rpm", "feed", "swing", "outrigger", "track_width", "travel",
			"head", "pile_top", "pile_length", "tool", "steady"};
	private static final int MAST = 0;
	private static final int HAMMER = 1;
	private static final int RPM = 2;
	private static final int FEED = 3;
	private static final int SWING = 4;
	private static final int OUTRIGGER = 5;
	private static final int TRACK_WIDTH = 6;
	private static final int TRAVEL = 7;
	/** Rotary: height of the head's grip above the machine's ground (model units). */
	private static final int HEAD = 8;
	/** Rotary: height of the held pile's top above the machine's ground (model units). */
	private static final int PILE_TOP = 9;
	/** Rotary: length of the held pile (model units; 0 = none). */
	private static final int PILE_LENGTH = 10;
	/** Rotary: drive rod lowered / follower fitted (0..1). */
	private static final int TOOL = 11;
	/** Rotary: pile steady (振れ止め) at the leader foot opened (0 = closed round the pile, 1 = open). */
	private static final int STEADY = 12;

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
			String pileBlock, int surfaceSearch) {
		static final MapCodec<Common> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.STRING.optionalFieldOf("mode", "hammer").forGetter(Common::mode),
				Codec.STRING.optionalFieldOf("pile_point", "pile_point").forGetter(Common::pilePoint),
				Codec.INT.optionalFieldOf("max_depth", 32).forGetter(Common::maxDepth),
				Codec.INT.optionalFieldOf("default_depth", 8).forGetter(Common::defaultDepth),
				Codec.FLOAT.optionalFieldOf("mast_speed", 0.02f).forGetter(Common::mastSpeed),
				Codec.STRING.optionalFieldOf("pile_block", "minecraft:iron_block").forGetter(Common::pileBlock),
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

	/** Rotary piling: the pile set under the head (pile_length_min..max blocks, chosen on board, as
	 * many cargo blocks consumed), the head's travel along the leader (grip height above the machine's
	 * ground, model units), and the tool that lets the head push the pile below its own reach: a
	 * "drive_rod" (always fitted; lowered by tool_length when switched) or a "follower" (ヤットコ,
	 * tool_length long, set like a pile when switched). */
	public record Piling(int pileLengthMin, int pileLengthMax, int defaultPileLength, float headMin, float headMax,
			float headSpeed, float capOffset, String tool, float toolLength, float toolSpeed, int loadTicksPerBlock,
			float extractFactor, float gripTolerance, float steadyTop, float steadyMargin, float steadySpeed) {
		static final MapCodec<Piling> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.INT.optionalFieldOf("pile_length_min", 2).forGetter(Piling::pileLengthMin),
				Codec.INT.optionalFieldOf("pile_length_max", 6).forGetter(Piling::pileLengthMax),
				Codec.INT.optionalFieldOf("default_pile_length", 4).forGetter(Piling::defaultPileLength),
				Codec.FLOAT.optionalFieldOf("head_min", 1.4f).forGetter(Piling::headMin),
				Codec.FLOAT.optionalFieldOf("head_max", 6.0f).forGetter(Piling::headMax),
				Codec.FLOAT.optionalFieldOf("head_speed", 0.1f).forGetter(Piling::headSpeed),
				Codec.FLOAT.optionalFieldOf("cap_offset", 0.25f).forGetter(Piling::capOffset),
				Codec.STRING.optionalFieldOf("tool", "none").forGetter(Piling::tool),
				Codec.FLOAT.optionalFieldOf("tool_length", 2.0f).forGetter(Piling::toolLength),
				Codec.FLOAT.optionalFieldOf("tool_speed", 0.05f).forGetter(Piling::toolSpeed),
				Codec.INT.optionalFieldOf("load_ticks_per_block", 6).forGetter(Piling::loadTicksPerBlock),
				Codec.FLOAT.optionalFieldOf("extract_factor", 0.6f).forGetter(Piling::extractFactor),
				Codec.FLOAT.optionalFieldOf("grip_tolerance", 0.6f).forGetter(Piling::gripTolerance),
				Codec.FLOAT.optionalFieldOf("steady_top", -1f).forGetter(Piling::steadyTop),
				Codec.FLOAT.optionalFieldOf("steady_margin", 1.0f).forGetter(Piling::steadyMargin),
				Codec.FLOAT.optionalFieldOf("steady_speed", 0.06f).forGetter(Piling::steadySpeed)
		).apply(i, Piling::new));

		boolean hasTool() {
			return "drive_rod".equalsIgnoreCase(this.tool) || "follower".equalsIgnoreCase(this.tool);
		}

		boolean follower() {
			return "follower".equalsIgnoreCase(this.tool);
		}
	}

	public record Settings(Common common, Setup setup, Hammer hammer, Rotary rotary, Piling piling,
			ResistanceProfile resistance) {
		public static final Codec<Settings> CODEC = RecordCodecBuilder.create(i -> i.group(
				Common.MAP_CODEC.forGetter(Settings::common),
				Setup.MAP_CODEC.forGetter(Settings::setup),
				Hammer.MAP_CODEC.forGetter(Settings::hammer),
				Rotary.MAP_CODEC.forGetter(Settings::rotary),
				Piling.MAP_CODEC.forGetter(Settings::piling),
				ResistanceProfile.field(1.0f, 20.0f).forGetter(Settings::resistance)
		).apply(i, Settings::new));

		public static final Settings DEFAULT = MachineModule.defaults(CODEC);

		public boolean rotaryMode() {
			return "rotary".equalsIgnoreCase(this.common.mode());
		}
	}

	private enum Status { IDLE, MAST, LEADER_DOWN, JACKS, READY, DRIVING, DONE, REFUSED, NO_PILE, NO_GROUND, PROTECTED,
		FREE, LOADING, HOLDING, EXTRACTING, HEAD_BOTTOM, HEAD_TOP, TOO_LONG, LEADER_LOCKED, MAX_DEPTH, JOINED }

	/** Leader up (target) - toggled by the secondary key. */
	private boolean leaderUp;

	/** Hammer: ticks into the current blow (0 = resting on the cap), the ground factor of the block the
	 * pile is entering, blows struck into it so far. */
	private int hammerTick;
	private float blowFactor;
	private int blowsDone;
	private float rpm;
	private Status status = Status.IDLE;
	private float refusedHardness;
	private Text refusedBlock = Text.empty();

	// Rotary: the pile held by the head (bottom block, length, ground level of its column).
	private boolean holding;
	private int holdX;
	private int holdZ;
	private int holdBottom;
	private int holdLength;
	private int holdSurface;
	private Block holdBlock;
	/** Rotary: how far the held pile has moved toward its next block (+ down, - up), -1..1. */
	private float stepOffset;
	private int pileLengthSet = -1;
	private boolean toolOut;
	// Rotary: setting a new pile (blocks already taken from the cargo).
	private boolean loading;
	private int loadLength;
	private Block loadBlock;
	private int loadTicks;
	private float loadHead;
	private int lengthKeyCooldown;
	private boolean headPlaced;
	/** Rotary: a one-off message shown for a while over the running status. */
	private Status notice;
	private int noticeTicks;

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
		// Only the jacks being physically down (or a pile gripped in the ground) hold the machine -
		// the leader being up does not; a real pile driver travels around a site with its leader raised.
		return this.machine.getFloatChannel(OUTRIGGER) > 0.01f || this.pileInGround();
	}

	private boolean pileInGround() {
		return this.holding && this.holdBottom <= this.holdSurface;
	}

	@Override
	public void serverTick(ServerWorld world, ServerPlayerEntity operator) {
		this.tickRig(world, operator, this.settings());
	}

	/** Leader (raised / folded, with crawler widening first) and jacks - shared by both modes. Returns
	 * the mast value. */
	private float tickLeaderAndJacks(Settings s) {
		Setup setup = s.setup();
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
		this.approach(OUTRIGGER, this.machine.isWorkMode() ? 1f : 0f, setup.outriggerSpeed());
		this.machine.setFloatChannel(TRAVEL, this.machine.getCruiseSpeedValue());
		return mast;
	}

	// ------------------------------------------------------------------
	// The rig (both modes): a real pile of set length
	//
	// The head (HEAD) runs up and down the leader between head_min and head_max. With no pile held,
	// Z sets a new pile under it (pile_length blocks from the cargo, like reloading; the head first
	// rises far enough for the pile to hang clear of the ground), or grips the head of a pile already
	// there. Holding a pile, PENETRATE drives it in (rotary: turns it in; hammer: blow by blow) and
	// EXTRACT pulls it back out (rotary: turns it out; hammer: its winch); a block of pile goes
	// into / comes out of the world for every block the pile moves, and the head follows the pile's
	// top, so the pile stops when the head reaches head_min. Z lets go: the part standing out of the
	// ground becomes blocks too, and the pile is remembered (PileRegistry) so it can be gripped again.
	// A pile driven down onto another pile's head joins it into one pile. The tool (drive rod /
	// follower) puts the cap tool_length lower, so a pile can be driven below the head's reach.
	// X raises and folds the leader. The head is the rotary head, or the drop hammer's helmet (cap) with
	// the hammer resting on it ("hammer" = how high the hammer is lifted off it).
	// ------------------------------------------------------------------

	private void tickRig(ServerWorld world, ServerPlayerEntity operator, Settings s) {
		Piling p = s.piling();
		Rotary r = s.rotary();
		int minLength = Math.max(1, p.pileLengthMin());
		int maxLength = Math.max(minLength, p.pileLengthMax());
		if (this.pileLengthSet < 0) {
			this.pileLengthSet = MathHelper.clamp(p.defaultPileLength(), minLength, maxLength);
		}
		if (!this.headPlaced) {
			this.headPlaced = true;
			if (this.machine.getFloatChannel(HEAD) < p.headMin()) {
				this.machine.setFloatChannel(HEAD, p.headMax());
			}
		}

		// Leader: X, the same key as the hammer rig. It stays up while a pile is gripped or being set.
		if (operator != null && this.machine.isSecondaryPressed()) {
			if (this.leaderUp && (this.holding || this.loading)) {
				this.notice(Status.LEADER_LOCKED);
			} else {
				this.leaderUp = !this.leaderUp;
			}
		}
		float mast = this.tickLeaderAndJacks(s);
		boolean leaderReady = mast >= 0.999f;
		boolean jacksReady = !s.setup().requireOutriggers() || this.machine.getFloatChannel(OUTRIGGER) >= 0.999f;

		if (operator != null) {
			if (!this.pileInGround()) {
				this.workSwing(SWING, s.setup().swingSpeed(), s.setup().swingLimit());
			}
			int change = (int) this.machine.workAxis(WorkAxis.VERTICAL);
			if (change != 0 && --this.lengthKeyCooldown <= 0) {
				this.lengthKeyCooldown = 4;
				this.pileLengthSet = MathHelper.clamp(this.pileLengthSet + change, minLength, maxLength);
			} else if (change == 0) {
				this.lengthKeyCooldown = 0;
			}
		}
		this.approach(TOOL, p.hasTool() && this.toolOut ? 1f : 0f, Math.max(0.005f, p.toolSpeed()));
		// The steady holds the pile closed round it, and opens out of the way once the cap (or the
		// lowered tool) comes down within steady_margin of it.
		if (p.steadyTop() < 0f) {
			this.machine.setFloatChannel(STEADY, 0f);
		} else {
			boolean open = this.capOf(this.machine.getFloatChannel(HEAD), p) < p.steadyTop() + p.steadyMargin();
			this.approach(STEADY, open ? 1f : 0f, Math.max(0.005f, p.steadySpeed()));
		}

		Vec3d point = this.machine.workPoint(s.common().pilePoint());
		int x = MathHelper.floor(point.x);
		int z = MathHelper.floor(point.z);
		if (this.holding && (x != this.holdX || z != this.holdZ)) {
			if (this.pileInGround()) {
				// Pushed off a pile standing in the ground: it stays there.
				this.release(world, operator);
			} else {
				this.holdX = x;
				this.holdZ = z;
				this.holdSurface = this.surfaceAt(world, x, MathHelper.floor(point.y), z, s);
			}
		}

		if (this.noticeTicks > 0) {
			this.noticeTicks--;
		}
		if (!leaderReady) {
			this.spinDown(r, 0f);
			this.status = this.leaderUp ? Status.MAST : (this.machine.isWorkMode() ? Status.LEADER_DOWN : Status.IDLE);
			this.updatePileChannels(p);
			return;
		}

		if (operator != null && this.machine.isPrimaryPressed() && !this.loading) {
			if (this.holding) {
				this.release(world, operator);
			} else if (!this.toolMoving(p) && !this.tryGrip(world, x, z, p)) {
				this.startLoad(world, operator, x, MathHelper.floor(point.y), z, s);
			}
		}
		if (operator != null && this.machine.isPressed(WorkButton.TOOL) && p.hasTool() && !this.holding && !this.loading) {
			// Lowering it needs room under the head.
			float room = this.machine.getFloatChannel(HEAD) - this.lowestFreeHead(world, x, z, p);
			if (this.toolOut || p.headMax() - this.machine.getFloatChannel(HEAD) + room >= p.toolLength() - 1.0e-3f) {
				this.toolOut = !this.toolOut;
			} else {
				this.notice(Status.HEAD_TOP);
			}
		}

		if (!this.holding) {
			this.hammerTick = 0;
			this.machine.setFloatChannel(HAMMER, 0f);
		}
		if (this.loading) {
			this.spinDown(r, 0f);
			this.tickLoad(p);
		} else if (this.holding) {
			boolean down = operator != null && this.machine.isHeld(WorkButton.PENETRATE);
			boolean up = operator != null && this.machine.isHeld(WorkButton.EXTRACT);
			int dir = down == up ? 0 : (down ? 1 : -1);
			if (dir != 0 && !jacksReady) {
				this.notice(Status.JACKS);
				dir = 0;
			}
			this.tickHeldPile(world, operator, s, dir);
		} else {
			this.spinDown(r, 0f);
			float move = operator == null ? 0f
					: (this.machine.isHeld(WorkButton.EXTRACT) ? 1f : 0f) - (this.machine.isHeld(WorkButton.PENETRATE) ? 1f : 0f);
			// The cap (with the tool, if lowered) never goes into what is under it: the head is pushed up
			// instead, e.g. while a drive rod is being lowered.
			float lowest = Math.min(p.headMax(), Math.max(p.headMin(), this.lowestFreeHead(world, x, z, p)));
			float g = this.machine.getFloatChannel(HEAD);
			this.machine.setFloatChannel(HEAD, MathHelper.clamp(g + move * p.headSpeed(), lowest, p.headMax()));
			this.status = Status.FREE;
		}
		this.updatePileChannels(p);
	}

	private void notice(Status notice) {
		this.notice = notice;
		this.noticeTicks = 40;
	}

	private float scale() {
		return Math.max(1.0e-3f, this.machine.getDefinition().scale());
	}

	/** World Y of a height above the machine's ground (model units). */
	private double worldY(float g) {
		return this.machine.getY() + g * this.scale();
	}

	private float modelY(double worldY) {
		return (float) ((worldY - this.machine.getY()) / this.scale());
	}

	private float toolOffset(Piling p) {
		return p.hasTool() ? p.toolLength() * this.machine.getFloatChannel(TOOL) : 0f;
	}

	private boolean toolMoving(Piling p) {
		float t = this.machine.getFloatChannel(TOOL);
		return p.hasTool() && t > 0.001f && t < 0.999f;
	}

	/** Cap (where a pile's head sits) height above the machine's ground, for a head height. */
	private float capOf(float head, Piling p) {
		return head - p.capOffset() - this.toolOffset(p);
	}

	private float headFor(float cap, Piling p) {
		return cap + p.capOffset() + this.toolOffset(p);
	}

	/** Lowest head height (model) for which the cap (and the tool) stays clear of whatever is under
	 * the head. */
	private float lowestFreeHead(ServerWorld world, int x, int z, Piling p) {
		double start = this.worldY(this.machine.getFloatChannel(HEAD) - p.capOffset());
		int y = MathHelper.floor(start - 1.0e-3);
		for (int i = 0; i < 64; i++, y--) {
			BlockPos pos = new BlockPos(x, y, z);
			if (world.isOutOfHeightLimit(pos) || !PourSpreader.free(world, pos)) {
				return this.headFor(this.modelY(y + 1), p);
			}
		}
		return -1.0e6f;
	}

	/** The top ground block of a column, searching down from fromY; a pile standing out of the ground
	 * counts by the ground it was driven from. MIN_VALUE if there is none within surface_search. */
	private int surfaceAt(ServerWorld world, int x, int fromY, int z, Settings s) {
		PileRegistry.Pile pile = PileRegistry.get(world).at(world, x, z);
		for (int dy = 0; dy <= s.common().surfaceSearch() + 64; dy++) {
			BlockPos pos = new BlockPos(x, fromY - dy, z);
			if (world.isOutOfHeightLimit(pos)) {
				break;
			}
			if (!PourSpreader.free(world, pos)) {
				if (pile != null && pos.getY() <= pile.top() && pos.getY() > pile.surface()) {
					return pile.surface();
				}
				return pos.getY();
			}
		}
		return Integer.MIN_VALUE;
	}

	private void updatePileChannels(Piling p) {
		float scale = this.scale();
		if (this.holding) {
			float top = this.modelY(this.holdBottom + this.holdLength - this.stepOffset);
			this.machine.setFloatChannel(PILE_TOP, top);
			this.machine.setFloatChannel(PILE_LENGTH, this.holdLength / scale);
			this.machine.setFloatChannel(HEAD, this.headFor(top, p));
		} else {
			// Nothing held: the (zero-length) pile is tucked away inside the head.
			this.machine.setFloatChannel(PILE_LENGTH, 0f);
			this.machine.setFloatChannel(PILE_TOP, this.machine.getFloatChannel(HEAD) + 0.3f);
		}
	}

	private void spinDown(Rotary r, float target) {
		this.rpm += (target - this.rpm) * MathHelper.clamp(r.rpmResponse(), 0.01f, 1f);
		if (Math.abs(this.rpm) < 0.05f && target == 0f) {
			this.rpm = 0f;
		}
		this.machine.setFloatChannel(RPM, this.rpm);
	}

	/** Embedment: pile blocks at or below the ground. */
	private int embedment() {
		if (!this.holding) {
			return 0;
		}
		return Math.max(0, Math.min(this.holdSurface, this.holdBottom + this.holdLength - 1) - this.holdBottom + 1);
	}

	// --- setting a new pile

	private void startLoad(ServerWorld world, ServerPlayerEntity operator, int x, int fromY, int z, Settings s) {
		Piling p = s.piling();
		int length = this.pileLengthSet;
		// The pile hangs from the cap: its foot must clear whatever is under the leader.
		float floorCap = this.lowestFreeHead(world, x, z, p) - p.capOffset() - this.toolOffset(p);
		float need = this.headFor(floorCap + length / this.scale(), p);
		if (need > p.headMax() + 1.0e-3f) {
			this.notice(Status.TOO_LONG);
			return;
		}
		Block block = this.findPileBlock(operator, s);
		if (block == null || (!ConstructionMachineEntity.isCreative(operator) && this.countBlocks(block) < length)) {
			this.notice(Status.NO_PILE);
			return;
		}
		if (!ConstructionMachineEntity.isCreative(operator)) {
			this.takeBlocks(block, length);
		}
		this.loading = true;
		this.loadLength = length;
		this.loadBlock = block;
		this.loadTicks = Math.max(1, s.piling().loadTicksPerBlock()) * length;
		this.loadHead = Math.max(need, this.machine.getFloatChannel(HEAD));
		this.holdX = x;
		this.holdZ = z;
		this.holdSurface = this.surfaceAt(world, x, fromY, z, s);
		this.status = Status.LOADING;
	}

	private void tickLoad(Piling p) {
		this.status = Status.LOADING;
		if (Float.isNaN(this.loadHead)) {
			this.loadHead = this.machine.getFloatChannel(HEAD);
		}
		float g = this.approach(HEAD, this.loadHead, Math.max(0.02f, p.headSpeed() * 2f));
		if (Math.abs(g - this.loadHead) > 1.0e-3f) {
			return;
		}
		if (--this.loadTicks > 0) {
			return;
		}
		this.loading = false;
		this.holding = true;
		this.holdBlock = this.loadBlock;
		this.holdLength = this.loadLength;
		this.stepOffset = 0f;
		double cap = this.worldY(this.capOf(g, p));
		// The pile's head sits at (or just under) the cap, never above it.
		this.holdBottom = MathHelper.floor(cap + 1.0e-3) - this.holdLength;
		this.machine.getEntityWorld().playSound(null, this.machine.getX(), this.machine.getY() + 1, this.machine.getZ(),
				SoundEvents.BLOCK_CHAIN_PLACE, SoundCategory.BLOCKS, 1.0f, 0.8f);
	}

	private int loadProgressPercent(Settings s) {
		int total = Math.max(1, s.piling().loadTicksPerBlock()) * Math.max(1, this.loadLength);
		return Math.round(100f * (total - this.loadTicks) / total);
	}

	// --- gripping / letting go

	/** Grips the head of a pile standing under the cap (within grip_tolerance). */
	private boolean tryGrip(ServerWorld world, int x, int z, Piling p) {
		PileRegistry registry = PileRegistry.get(world);
		PileRegistry.Pile pile = registry.at(world, x, z);
		if (pile == null) {
			return false;
		}
		double cap = this.worldY(this.capOf(this.machine.getFloatChannel(HEAD), p));
		if (Math.abs(cap - (pile.top() + 1)) > p.gripTolerance() * this.scale()) {
			return false;
		}
		Block block = pile.blockType();
		// The part standing out of the ground is carried by the head again (drawn, not blocks).
		for (int y = Math.max(pile.bottom(), pile.surface() + 1); y <= pile.top(); y++) {
			BlockPos pos = new BlockPos(x, y, z);
			if (world.getBlockState(pos).getBlock() == block) {
				world.setBlockState(pos, Blocks.AIR.getDefaultState());
			}
		}
		registry.remove(x, z);
		this.holding = true;
		this.holdX = x;
		this.holdZ = z;
		this.holdBottom = pile.bottom();
		this.holdLength = pile.length();
		this.holdSurface = pile.surface();
		this.holdBlock = block;
		this.stepOffset = 0f;
		world.playSound(null, x + 0.5, pile.top() + 1.0, z + 0.5, SoundEvents.BLOCK_CHAIN_PLACE, SoundCategory.BLOCKS, 1.0f, 1.2f);
		return true;
	}

	/** Lets go: the pile stays where it is (a pile hanging clear of the ground first drops onto what
	 * is under it), the part above the ground becomes blocks, and it is remembered. */
	private void release(ServerWorld world, ServerPlayerEntity operator) {
		if (!this.holding) {
			return;
		}
		PileRegistry registry = PileRegistry.get(world);
		int x = this.holdX;
		int z = this.holdZ;
		int bottom = this.holdBottom;
		int top = bottom + this.holdLength - 1;
		PileRegistry.Pile below = null;
		if (bottom > this.holdSurface) {
			int landing = bottom - 1;
			while (landing > this.holdSurface && PourSpreader.free(world, new BlockPos(x, landing, z))) {
				landing--;
			}
			PileRegistry.Pile under = registry.at(world, x, z);
			if (under != null && under.top() == landing) {
				below = under;
			}
			top -= bottom - (landing + 1);
			bottom = landing + 1;
		}
		int placedTop = bottom - 1;
		for (int y = bottom; y <= top; y++) {
			BlockPos pos = new BlockPos(x, y, z);
			if (y <= this.holdSurface) {
				placedTop = y; // already in the world
				continue;
			}
			if (!PourSpreader.free(world, pos) || !BlockWork.canPlace(world, operator, pos)) {
				break;
			}
			world.setBlockState(pos, this.holdBlock.getDefaultState());
			placedTop = y;
		}
		int leftover = top - placedTop;
		if (leftover > 0) {
			this.returnBlocks(world, this.holdBlock, leftover);
		}
		if (placedTop >= bottom) {
			String id = Registries.BLOCK.getId(this.holdBlock).toString();
			registry.put(below != null
					? new PileRegistry.Pile(x, z, below.bottom(), placedTop, id, below.surface())
					: new PileRegistry.Pile(x, z, bottom, placedTop, id, this.holdSurface));
		}
		this.holding = false;
		this.stepOffset = 0f;
		world.playSound(null, x + 0.5, placedTop + 1.0, z + 0.5, SoundEvents.BLOCK_CHAIN_BREAK, SoundCategory.BLOCKS, 1.0f, 1.0f);
	}

	// --- turning the held pile in / out

	private void tickHeldPile(ServerWorld world, ServerPlayerEntity operator, Settings s, int dir) {
		Piling p = s.piling();
		Rotary r = s.rotary();
		if (!s.rotaryMode() && (dir > 0 || this.hammerTick > 0)) {
			// Drop hammer: driven blow by blow (a blow under way always finishes).
			this.tickBlows(world, operator, s, dir > 0);
			return;
		}
		if (dir == 0) {
			this.spinDown(r, 0f);
			this.status = Status.HOLDING;
			return;
		}
		// Moving back toward the last whole block needs no check; moving on into the next one does.
		float factor = 0f;
		boolean returning = dir > 0 ? this.stepOffset < 0f : this.stepOffset > 0f;
		if (!returning) {
			factor = dir > 0 ? this.checkDown(world, operator, s) : this.checkUp(world, operator, s);
			if (factor <= 0f) {
				this.spinDown(r, 0f);
				return;
			}
		} else {
			factor = 0.5f;
		}
		float rpmNeeded = r.rpm() / Math.max(1f, factor);
		float speedRatio = 1f;
		if (s.rotaryMode()) {
			this.spinDown(r, dir * rpmNeeded);
			speedRatio = MathHelper.clamp(Math.abs(this.rpm) / Math.max(0.01f, rpmNeeded), 0f, 1f);
		}
		float ticks = Math.max(1f, r.ticksPerBlock() * factor * (dir < 0 ? p.extractFactor() : 1f));
		this.stepOffset += dir * speedRatio / ticks;
		this.status = dir > 0 ? Status.DRIVING : Status.EXTRACTING;
		if (this.machine.age % 8 == 0) {
			if (s.rotaryMode()) {
				world.playSound(null, this.holdX + 0.5, this.holdSurface + 1.0, this.holdZ + 0.5,
						SoundEvents.BLOCK_GRINDSTONE_USE, SoundCategory.BLOCKS, 0.6f,
						0.5f + 0.5f * MathHelper.clamp(Math.abs(this.rpm) / Math.max(1f, r.rpm()), 0f, 1f));
			} else {
				// the hammer's winch pulls the pile out
				world.playSound(null, this.holdX + 0.5, this.holdSurface + 1.0, this.holdZ + 0.5,
						SoundEvents.BLOCK_CHAIN_STEP, SoundCategory.BLOCKS, 0.6f, 0.7f);
			}
		}
		// A finished block starts the next one from scratch: it has its own checks.
		if (this.stepOffset >= 1f) {
			this.stepDown(world);
			this.stepOffset = 0f;
		} else if (this.stepOffset <= -1f) {
			this.stepUp(world);
			this.stepOffset = 0f;
		}
	}

	private int blowsNeeded(Hammer h) {
		return Math.max(1, MathHelper.ceil(h.blowsPerBlock() * Math.max(this.blowFactor, 0.25f)));
	}

	/** Drop hammer: lift the hammer off the cap and drop it; each blow drives the held pile a share of a
	 * block (blows_per_block x the ground's factor blows per block). A blow only starts when the pile can
	 * go on into the next block (same checks as turning it in); one under way always falls. */
	private void tickBlows(ServerWorld world, ServerPlayerEntity operator, Settings s, boolean drive) {
		Hammer h = s.hammer();
		int lift = Math.max(1, h.liftTicks());
		int drop = Math.max(1, h.dropTicks());
		if (this.hammerTick == 0) {
			if (!drive) {
				this.machine.setFloatChannel(HAMMER, 0f);
				this.status = Status.HOLDING;
				return;
			}
			float factor = this.stepOffset < 0f ? 0.5f : this.checkDown(world, operator, s);
			if (factor <= 0f) {
				this.machine.setFloatChannel(HAMMER, 0f);
				return;
			}
			this.blowFactor = factor;
			// (after pulling the pile up a little, the blows already struck into this block are fewer)
			this.blowsDone = Math.max(0, Math.round(Math.max(0f, this.stepOffset) * this.blowsNeeded(h)));
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
		this.status = Status.DRIVING;
		if (this.hammerTick < lift + drop) {
			return;
		}
		// Impact.
		this.hammerTick = 0;
		this.machine.setFloatChannel(HAMMER, 0f);
		BlockPos head = new BlockPos(this.holdX, Math.max(this.holdBottom + this.holdLength - 1, this.holdSurface), this.holdZ);
		world.playSound(null, head.getX() + 0.5, head.getY() + 1.0, head.getZ() + 0.5,
				SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.BLOCKS, 0.9f, 0.55f);
		BlockWork.effects(world, new BlockPos(this.holdX, this.holdSurface, this.holdZ),
				world.getBlockState(new BlockPos(this.holdX, this.holdSurface, this.holdZ)), 12);
		this.blowsDone++;
		this.stepOffset += 1f / this.blowsNeeded(h);
		if (this.stepOffset >= 1f - 1.0e-4f) {
			this.stepDown(world);
			this.stepOffset = 0f;
			this.blowsDone = 0;
		}
	}

	/** Can the held pile go one block further down? Its resistance factor, or 0 (status set). Joins a
	 * pile met head-on. */
	private float checkDown(ServerWorld world, ServerPlayerEntity operator, Settings s) {
		Piling p = s.piling();
		int next = this.holdBottom - 1;
		float headAfter = this.headFor(this.modelY(this.holdBottom + this.holdLength - 1), p);
		if (headAfter < p.headMin() - 1.0e-3f) {
			this.status = Status.HEAD_BOTTOM;
			return 0f;
		}
		if (this.holdSurface == Integer.MIN_VALUE) {
			this.status = Status.NO_GROUND;
			return 0f;
		}
		if (this.holdSurface - next + 1 > s.common().maxDepth()) {
			this.status = Status.MAX_DEPTH;
			return 0f;
		}
		BlockPos pos = new BlockPos(this.holdX, next, this.holdZ);
		if (world.isOutOfHeightLimit(pos)) {
			this.status = Status.REFUSED;
			this.refusedHardness = -1f;
			this.refusedBlock = Text.translatable("status.constructionaddon.pile_driver.world_bottom");
			return 0f;
		}
		PileRegistry registry = PileRegistry.get(world);
		PileRegistry.Pile met = registry.at(world, this.holdX, this.holdZ);
		if (met != null && met.top() == next && met.blockType() != null) {
			// Driven onto another pile's head: joined into one pile from here on.
			for (int y = Math.max(met.bottom(), met.surface() + 1); y <= met.top(); y++) {
				BlockPos above = new BlockPos(this.holdX, y, this.holdZ);
				if (world.getBlockState(above).getBlock() == met.blockType()) {
					world.setBlockState(above, Blocks.AIR.getDefaultState());
				}
			}
			registry.remove(this.holdX, this.holdZ);
			this.holdLength += met.length();
			this.holdBottom = met.bottom();
			this.holdSurface = met.surface();
			this.notice(Status.JOINED);
			world.playSound(null, this.holdX + 0.5, next + 1.0, this.holdZ + 0.5, SoundEvents.BLOCK_ANVIL_PLACE,
					SoundCategory.BLOCKS, 0.7f, 1.4f);
			return 0f;
		}
		BlockState state = world.getBlockState(pos);
		if (PourSpreader.free(world, pos)) {
			if (next <= this.holdSurface && !BlockWork.canPlace(world, operator, pos)) {
				this.status = Status.PROTECTED;
				return 0f;
			}
			return 0.25f;
		}
		if (next > this.holdSurface) {
			// Something stands on the ground here: it is the ground now.
			this.holdSurface = next;
		}
		GroundResistance.Result resistance = GroundResistance.evaluate(world, pos, state, s.resistance());
		float factor = resistance.passable() ? 0.25f : resistance.factor();
		boolean tooHard = s.rotaryMode()
				? s.rotary().rpm() / Math.max(1f, factor) < s.rotary().minRpm()
				: MathHelper.ceil(s.hammer().blowsPerBlock() * factor) > s.hammer().maxBlowsPerBlock();
		if (resistance.refused() || tooHard) {
			this.status = Status.REFUSED;
			this.refusedHardness = resistance.hardness();
			this.refusedBlock = state.getBlock().getName();
			return 0f;
		}
		if (!BlockWork.canBreak(world, operator, pos, state) || !BlockWork.canPlace(world, operator, pos)) {
			this.status = Status.PROTECTED;
			return 0f;
		}
		return factor;
	}

	/** Can the held pile come one block up? Its resistance factor, or 0 (status set). */
	private float checkUp(ServerWorld world, ServerPlayerEntity operator, Settings s) {
		Piling p = s.piling();
		float headAfter = this.headFor(this.modelY(this.holdBottom + this.holdLength + 1), p);
		if (headAfter > p.headMax() + 1.0e-3f) {
			this.status = Status.HEAD_TOP;
			return 0f;
		}
		BlockPos bottom = new BlockPos(this.holdX, this.holdBottom, this.holdZ);
		if (this.holdBottom <= this.holdSurface && !BlockWork.canBreak(world, operator, bottom, world.getBlockState(bottom))) {
			this.status = Status.PROTECTED;
			return 0f;
		}
		BlockPos newTop = new BlockPos(this.holdX, this.holdBottom + this.holdLength, this.holdZ);
		if (newTop.getY() <= this.holdSurface && !BlockWork.canPlace(world, operator, newTop)) {
			this.status = Status.PROTECTED;
			return 0f;
		}
		if (this.holdBottom > this.holdSurface) {
			return 0.25f; // hanging clear of the ground
		}
		BlockPos under = bottom.down();
		BlockState state = world.getBlockState(under);
		GroundResistance.Result resistance = GroundResistance.evaluate(world, under, state, s.resistance());
		return resistance.passable() || resistance.refused() ? 0.5f : Math.max(0.25f, resistance.factor());
	}

	/** The pile moves one block down: a pile block appears under its foot (in the ground) and its old
	 * top block, if that was in the ground, is left empty. */
	private void stepDown(ServerWorld world) {
		int next = this.holdBottom - 1;
		BlockPos foot = new BlockPos(this.holdX, next, this.holdZ);
		if (next <= this.holdSurface) {
			BlockState displaced = world.getBlockState(foot);
			world.setBlockState(foot, this.holdBlock.getDefaultState());
			BlockWork.effects(world, new BlockPos(this.holdX, this.holdSurface, this.holdZ), displaced, 8);
		}
		int oldTop = this.holdBottom + this.holdLength - 1;
		if (oldTop <= this.holdSurface) {
			BlockPos pos = new BlockPos(this.holdX, oldTop, this.holdZ);
			if (world.getBlockState(pos).getBlock() == this.holdBlock) {
				world.setBlockState(pos, Blocks.AIR.getDefaultState());
			}
		}
		this.holdBottom = next;
	}

	/** The pile moves one block up: its foot block is pulled out of the world, and a block appears
	 * over its old top if that is in the ground. */
	private void stepUp(ServerWorld world) {
		BlockPos foot = new BlockPos(this.holdX, this.holdBottom, this.holdZ);
		if (this.holdBottom <= this.holdSurface && world.getBlockState(foot).getBlock() == this.holdBlock) {
			world.setBlockState(foot, Blocks.AIR.getDefaultState());
		}
		int newTop = this.holdBottom + this.holdLength;
		if (newTop <= this.holdSurface) {
			world.setBlockState(new BlockPos(this.holdX, newTop, this.holdZ), this.holdBlock.getDefaultState());
		}
		this.holdBottom++;
	}

	// --- cargo

	private int countBlocks(Block block) {
		int count = 0;
		for (int slot = this.machine.cargoStart(); slot < this.machine.cargoEnd(); slot++) {
			ItemStack stack = this.machine.getStack(slot);
			if (!stack.isEmpty() && stack.getItem() instanceof BlockItem item && item.getBlock() == block) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private void takeBlocks(Block block, int count) {
		for (int slot = this.machine.cargoStart(); slot < this.machine.cargoEnd() && count > 0; slot++) {
			ItemStack stack = this.machine.getStack(slot);
			if (!stack.isEmpty() && stack.getItem() instanceof BlockItem item && item.getBlock() == block) {
				int take = Math.min(count, stack.getCount());
				stack.decrement(take);
				count -= take;
				if (stack.isEmpty()) {
					this.machine.setStack(slot, ItemStack.EMPTY);
				}
			}
		}
	}

	private void returnBlocks(ServerWorld world, Block block, int count) {
		while (count > 0) {
			ItemStack stack = new ItemStack(block.asItem(), Math.min(count, 64));
			count -= stack.getCount();
			ItemStack rest = this.machine.insertCargo(stack);
			if (!rest.isEmpty()) {
				this.machine.dropStack(world, rest);
			}
		}
	}

	/** The block piles are made of (pile_block: iron for steel pipe piles, concrete for concrete
	 * piles - whatever the model shows). */
	private Block pileBlock(Settings s) {
		Identifier id = Identifier.tryParse(s.common().pileBlock());
		Block block = id == null ? null : Registries.BLOCK.getOptionalValue(id).orElse(null);
		return block == null || block == Blocks.AIR ? Blocks.IRON_BLOCK : block;
	}

	/** The pile block, if there is any to drive (a creative operator always has it). Null if none. */
	private Block findPileBlock(ServerPlayerEntity operator, Settings s) {
		Block block = this.pileBlock(s);
		return ConstructionMachineEntity.isCreative(operator) || this.countBlocks(block) > 0 ? block : null;
	}

	@Override
	public Text status() {
		return this.rigStatus(this.settings());
	}

	private Text rigStatus(Settings s) {
		Piling p = s.piling();
		Text tool = !p.hasTool() ? Text.empty()
				: Text.translatable("status.constructionaddon.pile_driver.tool." + (p.follower() ? "follower" : "drive_rod")
						+ (this.toolOut ? "_out" : "_in"));
		int pct = Math.round(Math.abs(this.stepOffset) * 100f);
		Status shown = this.noticeTicks > 0 && this.notice != null ? this.notice : this.status;
		return switch (shown) {
			case IDLE, LEADER_DOWN -> null;
			case MAST -> Text.translatable("status.constructionaddon.pile_driver.rotary.mast");
			case LEADER_LOCKED -> Text.translatable("status.constructionaddon.pile_driver.rotary.leader_locked");
			case JACKS -> Text.translatable("status.constructionaddon.pile_driver.rotary.jacks");
			case FREE -> Text.translatable("status.constructionaddon.pile_driver.rotary.free", this.pileLengthSet,
					p.pileLengthMin(), p.pileLengthMax(), tool);
			case LOADING -> Text.translatable("status.constructionaddon.pile_driver.rotary.loading", this.loadLength,
					this.loadProgressPercent(s));
			case HOLDING, JOINED -> Text.translatable("status.constructionaddon.pile_driver.rotary."
					+ (shown == Status.JOINED ? "joined" : "holding"), this.holdLength, this.embedment(), tool);
			case DRIVING -> s.rotaryMode()
					? Text.translatable("status.constructionaddon.pile_driver.rotary.driving", this.holdLength,
							this.embedment(), Math.round(Math.abs(this.rpm)), pct)
					: Text.translatable("status.constructionaddon.pile_driver.rotary.hammering", this.holdLength,
							this.embedment(), this.blowsDone, this.blowsNeeded(s.hammer()));
			case EXTRACTING -> s.rotaryMode()
					? Text.translatable("status.constructionaddon.pile_driver.rotary.extracting", this.holdLength,
							this.embedment(), Math.round(Math.abs(this.rpm)), pct)
					: Text.translatable("status.constructionaddon.pile_driver.rotary.pulling", this.holdLength,
							this.embedment(), pct);
			case HEAD_BOTTOM -> Text.translatable("status.constructionaddon.pile_driver.rotary.head_bottom", this.embedment());
			case HEAD_TOP -> Text.translatable("status.constructionaddon.pile_driver.rotary.head_top");
			case TOO_LONG -> Text.translatable("status.constructionaddon.pile_driver.rotary.too_long", this.pileLengthSet);
			case MAX_DEPTH -> Text.translatable("status.constructionaddon.pile_driver.rotary.max_depth", s.common().maxDepth());
			case NO_PILE -> Text.translatable("status.constructionaddon.pile_driver.rotary.no_pile", this.pileBlock(s).getName(), this.pileLengthSet);
			case NO_GROUND -> Text.translatable("status.constructionaddon.pile_driver.no_ground");
			case PROTECTED -> Text.translatable("status.constructionaddon.protected");
			case REFUSED -> Text.translatable("status.constructionaddon.pile_driver.rotary.refused", this.refusedBlock,
					this.refusedHardness < 0f ? "-" : String.format(Locale.ROOT, "%.1f", this.refusedHardness),
					this.embedment());
			default -> null;
		};
	}

	@Override
	public void writeData(WriteView view) {
		view.putBoolean("PileLeaderUp", this.leaderUp);
		view.putInt("PileLengthSet", this.pileLengthSet);
		view.putBoolean("PileToolOut", this.toolOut);
		Block held = this.holding ? this.holdBlock : this.loading ? this.loadBlock : null;
		view.putBoolean("PileHolding", held != null);
		if (held != null) {
			view.putString("PileHoldBlock", Registries.BLOCK.getId(held).toString());
			view.putInt("PileHoldX", this.holdX);
			view.putInt("PileHoldZ", this.holdZ);
			view.putInt("PileHoldBottom", this.holding ? this.holdBottom : Integer.MIN_VALUE);
			view.putInt("PileHoldLength", this.holding ? this.holdLength : this.loadLength);
			view.putInt("PileHoldSurface", this.holdSurface);
		}
	}

	@Override
	public void readData(ReadView view) {
		this.leaderUp = view.getBoolean("PileLeaderUp", false);
		this.pileLengthSet = view.getInt("PileLengthSet", -1);
		this.toolOut = view.getBoolean("PileToolOut", false);
		this.holding = false;
		this.loading = false;
		if (view.getBoolean("PileHolding", false)) {
			Identifier id = Identifier.tryParse(view.getString("PileHoldBlock", ""));
			Block block = id == null ? null : Registries.BLOCK.getOptionalValue(id).orElse(null);
			int bottom = view.getInt("PileHoldBottom", Integer.MIN_VALUE);
			if (block != null) {
				this.holdX = view.getInt("PileHoldX", 0);
				this.holdZ = view.getInt("PileHoldZ", 0);
				this.holdSurface = view.getInt("PileHoldSurface", Integer.MIN_VALUE);
				if (bottom == Integer.MIN_VALUE) {
					// Saved while setting a pile: finish setting it where the head is.
					this.loading = true;
					this.loadBlock = block;
					this.loadLength = view.getInt("PileHoldLength", 1);
					this.loadTicks = 1;
					this.loadHead = Float.NaN;
				} else {
					this.holding = true;
					this.holdBlock = block;
					this.holdBottom = bottom;
					this.holdLength = view.getInt("PileHoldLength", 1);
				}
			}
		}
	}
}
