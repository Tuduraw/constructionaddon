package com.example.constructionaddon.machine;

import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.constructionaddon.network.WorkAxis;
import com.example.constructionaddon.network.WorkButton;
import com.example.tudursvehiclemod.asset.VehicleDefinition;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.Locale;
import java.util.Optional;

/** Aerial work platform (高所作業車): a basket on a boom or a scissor / mast lift, worked by whoever rides
 * the basket (construction.work_seat; without one, the driver). Every axis is optional - a range of 0
 * leaves it out - so one module covers:
 * <ul>
 *   <li>a scissor (or mast) lift: "lift" 0..1 on the generic up / down keys; it may drive while raised,
 *       slowly (stow_to_drive false, raised_speed_factor);</li>
 *   <li>a truck-mounted telescopic boom: swing, luff, telescope and basket rotation, on outriggers;</li>
 *   <li>an articulated boom, e.g. on a towable trailer ("tow" - coupled to a tractor like the low-bed
 *       trailer): lower boom (luff), upper boom (knuckle), telescope, basket rotation.</li>
 * </ul>
 * The basket is kept level by the "level" channel (= -(luff + knuckle), degrees) - give the basket a
 * rotate joint on it about the boom's luffing axis. X puts the outriggers out / in (in only with the boom
 * stowed); Z held stows everything (swing 0, booms down, telescope in, basket straight, lift down).
 * With stow_to_drive the machine can only drive once stowed with its outriggers in; a towable one can only
 * be coupled then. Blocks are worked by the basket's rider with the ordinary hands. */
public final class AerialPlatformModule extends MachineModule implements Towable {

	private static final String[] CHANNELS = {"swing", "luff", "extend", "knuckle", "basket", "lift", "outrigger", "level"};
	private static final int SWING = 0;
	private static final int LUFF = 1;
	private static final int EXTEND = 2;
	private static final int KNUCKLE = 3;
	private static final int BASKET = 4;
	private static final int LIFT = 5;
	private static final int OUTRIGGER = 6;
	private static final int LEVEL = 7;
	/** Int channel 0: the tractor's network id + 1 when towed. */
	private static final int TRACTOR_ID = 0;

	/** Ranges and speeds of the work axes (degrees, model units, or 0..1 for lift). */
	public record Boom(float swingSpeed, float swingLimit, float luffSpeed, float luffMin, float luffMax,
			float extendSpeed, float extendMax, float knuckleSpeed, float knuckleMin, float knuckleMax,
			float basketSpeed, float basketLimit, float liftSpeed) {
		static final MapCodec<Boom> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.FLOAT.optionalFieldOf("swing_speed", 1.0f).forGetter(Boom::swingSpeed),
				Codec.FLOAT.optionalFieldOf("swing_limit", 0f).forGetter(Boom::swingLimit),
				Codec.FLOAT.optionalFieldOf("luff_speed", 0.5f).forGetter(Boom::luffSpeed),
				Codec.FLOAT.optionalFieldOf("luff_min", 0f).forGetter(Boom::luffMin),
				Codec.FLOAT.optionalFieldOf("luff_max", 0f).forGetter(Boom::luffMax),
				Codec.FLOAT.optionalFieldOf("extend_speed", 0.04f).forGetter(Boom::extendSpeed),
				Codec.FLOAT.optionalFieldOf("extend_max", 0f).forGetter(Boom::extendMax),
				Codec.FLOAT.optionalFieldOf("knuckle_speed", 0.6f).forGetter(Boom::knuckleSpeed),
				Codec.FLOAT.optionalFieldOf("knuckle_min", 0f).forGetter(Boom::knuckleMin),
				Codec.FLOAT.optionalFieldOf("knuckle_max", 0f).forGetter(Boom::knuckleMax),
				Codec.FLOAT.optionalFieldOf("basket_speed", 1.5f).forGetter(Boom::basketSpeed),
				Codec.FLOAT.optionalFieldOf("basket_limit", 0f).forGetter(Boom::basketLimit),
				Codec.FLOAT.optionalFieldOf("lift_speed", 0f).forGetter(Boom::liftSpeed)
		).apply(i, Boom::new));

		boolean hasSwing() {
			return this.swingSpeed > 0f;
		}

		boolean hasLift() {
			return this.liftSpeed > 0f;
		}
	}

	/** swing_clear_luff / swing_low_limit: below that luff the boom may only swing within +-swing_low_limit
	 * of straight ahead (it would hit the cab / the machine), and swung outside that it can't be lowered
	 * below it. extend_clear_luff / extend_front_sector: while the boom points within +-extend_front_sector of
	 * straight ahead below that luff, it can't telescope out, be lowered while out, or swing in while out
	 * (a boom stowed forward over the cab). 0 = no such limit. */
	public record Setup(boolean outriggers, float outriggerSpeed, boolean requireOutriggers, boolean stowToDrive,
			float raisedSpeedFactor, String basketPoint, float swingClearLuff, float swingLowLimit,
			float extendClearLuff, float extendFrontSector) {
		static final MapCodec<Setup> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.BOOL.optionalFieldOf("outriggers", false).forGetter(Setup::outriggers),
				Codec.FLOAT.optionalFieldOf("outrigger_speed", 0.04f).forGetter(Setup::outriggerSpeed),
				Codec.BOOL.optionalFieldOf("require_outriggers", true).forGetter(Setup::requireOutriggers),
				Codec.BOOL.optionalFieldOf("stow_to_drive", true).forGetter(Setup::stowToDrive),
				Codec.FLOAT.optionalFieldOf("raised_speed_factor", 0.3f).forGetter(Setup::raisedSpeedFactor),
				Codec.STRING.optionalFieldOf("basket_point", "basket").forGetter(Setup::basketPoint),
				Codec.FLOAT.optionalFieldOf("swing_clear_luff", 0f).forGetter(Setup::swingClearLuff),
				Codec.FLOAT.optionalFieldOf("swing_low_limit", 180f).forGetter(Setup::swingLowLimit),
				Codec.FLOAT.optionalFieldOf("extend_clear_luff", 0f).forGetter(Setup::extendClearLuff),
				Codec.FLOAT.optionalFieldOf("extend_front_sector", 30f).forGetter(Setup::extendFrontSector)
		).apply(i, Setup::new));
	}

	public record Settings(Boom boom, Setup setup, Optional<TowLink.Settings> tow) {
		public static final Codec<Settings> CODEC = RecordCodecBuilder.create(i -> i.group(
				Boom.MAP_CODEC.forGetter(Settings::boom),
				Setup.MAP_CODEC.forGetter(Settings::setup),
				TowLink.Settings.CODEC.optionalFieldOf("tow").forGetter(Settings::tow)
		).apply(i, Settings::new));

		public static final Settings DEFAULT = MachineModule.defaults(CODEC);
	}

	private enum Status { WORKING, OUTRIGGERS_FIRST, STOW_FIRST, COUPLED, DEPLOYING, STOWING_OUTRIGGERS, STOWING, RAISE_TO_SWING,
		RAISE_TO_EXTEND }

	private final TowLink link;
	private boolean outriggersOut;
	private Status notice;
	private int noticeTicks;
	private boolean stowing;

	public AerialPlatformModule(ConstructionMachineEntity machine) {
		super(machine);
		this.link = new TowLink(machine, TRACTOR_ID, () -> this.settings().tow().orElse(TowLink.Settings.DEFAULT));
	}

	@Override
	public MachineType type() {
		return MachineType.AERIAL_PLATFORM;
	}

	@Override
	public String[] floatChannels() {
		return CHANNELS;
	}

	private Settings settings() {
		return this.machine.settings().machineSettings(Settings.class, Settings.DEFAULT);
	}

	// ------------------------------------------------------------------ towing

	@Override
	public TowLink towLink() {
		return this.link;
	}

	@Override
	public boolean canCouple() {
		return this.settings().tow().isPresent() && this.machine.getFloatChannel(OUTRIGGER) < 0.01f && this.stowed();
	}

	@Override
	public boolean overridesMovement() {
		return this.settings().tow().isPresent();
	}

	@Override
	public void updateMovement(VehicleDefinition def) {
		if (!this.link.updateMovement(def)) {
			this.machine.runCarMovement(def);
		}
	}

	@Override
	public Vec3d renderPositionOverride(float tickDelta) {
		return this.settings().tow().isPresent() ? this.link.renderPositionOverride(tickDelta) : null;
	}

	@Override
	public Float renderYawOverride(float tickDelta) {
		return this.settings().tow().isPresent() ? this.link.renderYawOverride(tickDelta) : null;
	}

	// ------------------------------------------------------------------ driving

	/** Every axis at its rest position (swing straight ahead, booms down, telescope in, lift down). */
	private boolean stowed() {
		Settings s = this.settings();
		Boom b = s.boom();
		return Math.abs(MathHelper.wrapDegrees(this.machine.getFloatChannel(SWING))) <= 1.0f
				&& this.machine.getFloatChannel(LUFF) <= b.luffMin() + 0.5f
				&& this.machine.getFloatChannel(EXTEND) <= 0.02f
				&& this.machine.getFloatChannel(KNUCKLE) <= b.knuckleMin() + 0.5f
				&& Math.abs(this.machine.getFloatChannel(BASKET)) <= 1.0f
				&& this.machine.getFloatChannel(LIFT) <= 0.01f;
	}

	@Override
	public boolean suppressesDriving() {
		Setup setup = this.settings().setup();
		return this.machine.getFloatChannel(OUTRIGGER) > 0.01f || (setup.stowToDrive() && !this.stowed());
	}

	@Override
	public float maxSpeedFactor() {
		return this.stowed() ? 1f : MathHelper.clamp(this.settings().setup().raisedSpeedFactor(), 0f, 1f);
	}

	// ------------------------------------------------------------------ work

	@Override
	public void tick() {
		this.link.tick();
	}

	@Override
	public void serverTick(ServerWorld world, ServerPlayerEntity operator) {
		Settings s = this.settings();
		Boom b = s.boom();
		Setup setup = s.setup();
		if (s.tow().isPresent()) {
			this.link.serverTick(world);
		}
		if (this.noticeTicks > 0) {
			this.noticeTicks--;
		}
		this.clampAxes(b);

		// Outriggers: X out / in. Never while coupled; back in only with the boom stowed.
		if (setup.outriggers() && operator != null && this.machine.isSecondaryPressed()) {
			if (!this.outriggersOut && this.link.isCoupled()) {
				this.notice(Status.COUPLED);
			} else if (this.outriggersOut && !this.stowed()) {
				this.notice(Status.STOW_FIRST);
			} else {
				this.outriggersOut = !this.outriggersOut;
				world.playSound(null, this.machine.getX(), this.machine.getY() + 0.5, this.machine.getZ(),
						SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.BLOCKS, 0.7f, this.outriggersOut ? 0.8f : 0.6f);
			}
		}
		if (!setup.outriggers()) {
			this.outriggersOut = false;
		}
		float outrigger = this.approach(OUTRIGGER, this.outriggersOut ? 1f : 0f, setup.outriggerSpeed());
		boolean ready = !setup.outriggers() || !setup.requireOutriggers() || outrigger >= 0.999f;

		this.stowing = operator != null && this.machine.isHeld(WorkButton.PRIMARY);
		if (this.stowing) {
			this.stow(b);
		} else if (operator != null) {
			boolean input = this.machine.workAxis(WorkAxis.SWING) != 0f || this.machine.workAxis(WorkAxis.LUFF) != 0f
					|| this.machine.workAxis(WorkAxis.TELESCOPE) != 0f || this.machine.workAxis(WorkAxis.KNUCKLE) != 0f
					|| this.machine.workAxis(WorkAxis.BASKET) != 0f || this.machine.workAxis(WorkAxis.VERTICAL) != 0f;
			if (!ready) {
				if (input) {
					this.notice(Status.OUTRIGGERS_FIRST);
				}
			} else {
				boolean low = setup.swingClearLuff() > 0f && this.machine.getFloatChannel(LUFF) < setup.swingClearLuff();
				if (b.hasSwing()) {
					float swingInput = this.machine.workAxis(WorkAxis.SWING);
					float swing = this.machine.getFloatChannel(SWING);
					float next = MathHelper.wrapDegrees(swing + swingInput * b.swingSpeed());
					boolean extendedLow = setup.extendClearLuff() > 0f && this.machine.getFloatChannel(EXTEND) > 0.02f
							&& this.machine.getFloatChannel(LUFF) < setup.extendClearLuff();
					if (low && swingInput != 0f && Math.abs(next) > setup.swingLowLimit()
							&& Math.abs(next) > Math.abs(MathHelper.wrapDegrees(swing))) {
						this.notice(Status.RAISE_TO_SWING);
					} else if (extendedLow && swingInput != 0f && Math.abs(next) <= setup.extendFrontSector()
							&& Math.abs(next) < Math.abs(MathHelper.wrapDegrees(swing))) {
						this.notice(Status.RAISE_TO_EXTEND);
					} else {
						this.workSwing(SWING, b.swingSpeed(), b.swingLimit());
					}
				}
				float luffInput = this.machine.workAxis(WorkAxis.LUFF);
				boolean swungOut = setup.swingClearLuff() > 0f
						&& Math.abs(MathHelper.wrapDegrees(this.machine.getFloatChannel(SWING))) > setup.swingLowLimit();
				if (swungOut && luffInput < 0f && this.machine.getFloatChannel(LUFF) - b.luffSpeed() < setup.swingClearLuff()) {
					this.notice(Status.RAISE_TO_SWING);
					luffInput = 0f;
				}
				boolean front = setup.extendClearLuff() > 0f
						&& Math.abs(MathHelper.wrapDegrees(this.machine.getFloatChannel(SWING))) <= setup.extendFrontSector();
				if (front && luffInput < 0f && this.machine.getFloatChannel(EXTEND) > 0.02f
						&& this.machine.getFloatChannel(LUFF) - b.luffSpeed() < setup.extendClearLuff()) {
					this.notice(Status.RAISE_TO_EXTEND);
					luffInput = 0f;
				}
				this.nudge(LUFF, luffInput, b.luffSpeed(), b.luffMin(), b.luffMax());
				float extendInput = this.machine.workAxis(WorkAxis.TELESCOPE);
				if (front && extendInput > 0f && this.machine.getFloatChannel(LUFF) < setup.extendClearLuff()) {
					this.notice(Status.RAISE_TO_EXTEND);
					extendInput = 0f;
				}
				this.nudge(EXTEND, extendInput, b.extendSpeed(), 0f, b.extendMax());
				this.nudge(KNUCKLE, this.machine.workAxis(WorkAxis.KNUCKLE), b.knuckleSpeed(), b.knuckleMin(), b.knuckleMax());
				this.nudge(BASKET, this.machine.workAxis(WorkAxis.BASKET), b.basketSpeed(), -b.basketLimit(), b.basketLimit());
				if (b.hasLift()) {
					this.nudge(LIFT, this.machine.workAxis(WorkAxis.VERTICAL), b.liftSpeed(), 0f, 1f);
				}
			}
		}
		this.machine.setFloatChannel(LEVEL, -(this.machine.getFloatChannel(LUFF) + this.machine.getFloatChannel(KNUCKLE)));
	}

	/** Z held: every axis back toward rest at its own speed. */
	private void stow(Boom b) {
		Setup setup = this.settings().setup();
		float swing = this.machine.getFloatChannel(SWING);
		boolean swungOut = setup.swingClearLuff() > 0f && Math.abs(MathHelper.wrapDegrees(swing)) > setup.swingLowLimit();
		if (b.hasSwing() && Math.abs(MathHelper.wrapDegrees(swing)) > 1.0e-3f) {
			float target = swing - MathHelper.wrapDegrees(swing);
			float before = swing;
			float after = this.approach(SWING, target, b.swingSpeed());
			this.machine.workSwung(after - before);
		}
		boolean extended = this.machine.getFloatChannel(EXTEND) > 0.02f;
		if (swungOut && this.machine.getFloatChannel(LUFF) < setup.swingClearLuff()) {
			this.approach(LUFF, setup.swingClearLuff(), b.luffSpeed());   // clear of the machine first
		} else if (!swungOut && !(setup.extendClearLuff() > 0f && extended)) {
			this.approach(LUFF, b.luffMin(), b.luffSpeed());               // (a boom stowed forward: telescope in first)
		}
		this.approach(EXTEND, 0f, b.extendSpeed());
		this.approach(KNUCKLE, b.knuckleMin(), b.knuckleSpeed());
		this.approach(BASKET, 0f, b.basketSpeed());
		if (b.hasLift()) {
			this.approach(LIFT, 0f, b.liftSpeed());
		}
	}

	/** Keeps every channel inside its range (a fresh machine starts at 0 everywhere). */
	private void clampAxes(Boom b) {
		this.clamp(LUFF, b.luffMin(), b.luffMax());
		this.clamp(EXTEND, 0f, b.extendMax());
		this.clamp(KNUCKLE, b.knuckleMin(), b.knuckleMax());
		this.clamp(BASKET, -b.basketLimit(), b.basketLimit());
		this.clamp(LIFT, 0f, b.hasLift() ? 1f : 0f);
	}

	private void clamp(int channel, float min, float max) {
		float value = this.machine.getFloatChannel(channel);
		float clamped = MathHelper.clamp(value, Math.min(min, max), Math.max(min, max));
		if (clamped != value) {
			this.machine.setFloatChannel(channel, clamped);
		}
	}

	private void notice(Status status) {
		this.notice = status;
		this.noticeTicks = 40;
	}

	/** Height of the basket floor above the machine's ground (blocks). */
	private float basketHeight() {
		Vector3f p = this.machine.workPointModel(this.settings().setup().basketPoint());
		return p.y * this.machine.getDefinition().scale();
	}

	@Override
	public Text status() {
		Settings s = this.settings();
		Status shown = this.noticeTicks > 0 && this.notice != null ? this.notice : null;
		if (shown == null) {
			float outrigger = this.machine.getFloatChannel(OUTRIGGER);
			if (s.setup().outriggers() && outrigger > 0.001f && outrigger < 0.999f) {
				shown = this.outriggersOut ? Status.DEPLOYING : Status.STOWING_OUTRIGGERS;
			} else if (this.stowing) {
				shown = Status.STOWING;
			} else {
				shown = Status.WORKING;
			}
		}
		return switch (shown) {
			case OUTRIGGERS_FIRST -> Text.translatable("status.constructionaddon.aerial.outriggers_first");
			case STOW_FIRST -> Text.translatable("status.constructionaddon.aerial.stow_first");
			case COUPLED -> Text.translatable("status.constructionaddon.aerial.coupled");
			case DEPLOYING -> Text.translatable("status.constructionaddon.aerial.deploying");
			case STOWING_OUTRIGGERS -> Text.translatable("status.constructionaddon.aerial.stowing_outriggers");
			case STOWING -> Text.translatable("status.constructionaddon.aerial.stowing");
			case RAISE_TO_SWING -> Text.translatable("status.constructionaddon.aerial.raise_to_swing",
					Math.round(s.setup().swingClearLuff()));
			case RAISE_TO_EXTEND -> Text.translatable("status.constructionaddon.aerial.raise_to_extend",
					Math.round(s.setup().extendClearLuff()));
			case WORKING -> Text.translatable("status.constructionaddon.aerial.working",
					String.format(Locale.ROOT, "%.1f", this.basketHeight()),
					Text.translatable(this.stowed() ? "status.constructionaddon.aerial.stowed"
							: (s.setup().stowToDrive() || this.machine.getFloatChannel(OUTRIGGER) > 0.01f
									? "status.constructionaddon.aerial.no_drive" : "status.constructionaddon.aerial.slow_drive")));
		};
	}

	@Override
	public void writeData(WriteView view) {
		view.putBoolean("AerialOutriggers", this.outriggersOut);
		this.link.writeData(view);
	}

	@Override
	public void readData(ReadView view) {
		this.outriggersOut = view.getBoolean("AerialOutriggers", false);
		this.link.readData(view);
	}
}
