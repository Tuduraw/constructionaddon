package com.example.constructionaddon.machine;

import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.constructionaddon.work.BlockWork;
import com.example.constructionaddon.work.PourSpreader;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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

import java.util.ArrayList;
import java.util.List;

/** Concrete mixer truck.
 *
 * <p>MIXING: raw material goes into the vehicle's own inventory (K). The drum turns it into
 * ready-mixed material by the "recipes" list - by default each colour of concrete powder plus
 * water becomes that colour of concrete. Recipes are plain data (input item, output block,
 * whether water is used), so a pack can make the same truck lay other materials too. The drum
 * holds one output at a time: a different output only starts once the drum has been emptied.
 *
 * <p>WATER: each water bucket in the inventory is emptied into the tank (the empty bucket stays
 * behind), and a truck standing in water tops its tank up by itself.
 *
 * <p>POURING (the "laying" mechanism): work mode (hold M) enables the chute controls without
 * ever taking WASD away from driving - the work-axis keys (default J / L) swing the chute, arrow
 * up / down raise / lower it; primary (Z) held pours from the chute outlet, so the truck can
 * inch forward while pouring to lay a strip. Poured material settles like a
 * liquid (see PourSpreader): it runs down into the lowest free cells within spread_radius and
 * stops at walls, so it fills formwork layer by layer and finishes level. */
public final class MixerTruckModule extends MachineModule {

	private static final String[] CHANNELS = {"drum", "chute_swing", "chute_tilt", "load"};
	private static final int DRUM = 0;
	private static final int CHUTE_SWING = 1;
	private static final int CHUTE_TILT = 2;
	private static final int LOAD = 3;
	private static final int INT_CONCRETE = 0;
	private static final int INT_WATER = 1;

	public record Recipe(String input, String output, boolean water) {
		static final Codec<Recipe> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("input").forGetter(Recipe::input),
				Codec.STRING.fieldOf("output").forGetter(Recipe::output),
				Codec.BOOL.optionalFieldOf("water", true).forGetter(Recipe::water)
		).apply(i, Recipe::new));
	}

	private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
			"light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

	static List<Recipe> defaultRecipes() {
		List<Recipe> recipes = new ArrayList<>();
		for (String color : COLORS) {
			recipes.add(new Recipe("minecraft:" + color + "_concrete_powder", "minecraft:" + color + "_concrete", true));
		}
		return List.copyOf(recipes);
	}

	public record Chute(String chutePoint, float swingSpeed, float swingLimit, float tiltMin, float tiltMax, float tiltSpeed,
			int pourInterval, int spreadRadius) {
		static final MapCodec<Chute> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.STRING.optionalFieldOf("chute_point", "chute_tip").forGetter(Chute::chutePoint),
				Codec.FLOAT.optionalFieldOf("chute_swing_speed", 3f).forGetter(Chute::swingSpeed),
				Codec.FLOAT.optionalFieldOf("chute_swing_limit", 100f).forGetter(Chute::swingLimit),
				Codec.FLOAT.optionalFieldOf("chute_tilt_min", -25f).forGetter(Chute::tiltMin),
				Codec.FLOAT.optionalFieldOf("chute_tilt_max", 30f).forGetter(Chute::tiltMax),
				Codec.FLOAT.optionalFieldOf("chute_tilt_speed", 1f).forGetter(Chute::tiltSpeed),
				Codec.INT.optionalFieldOf("pour_interval", 4).forGetter(Chute::pourInterval),
				Codec.INT.optionalFieldOf("spread_radius", 4).forGetter(Chute::spreadRadius)
		).apply(i, Chute::new));
	}

	public record Drum(int capacity, int waterCapacity, int waterPerBucket, int mixInterval,
			float mixSpeed, float pourSpeed, float idleSpeed) {
		static final MapCodec<Drum> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.INT.optionalFieldOf("capacity", 64).forGetter(Drum::capacity),
				Codec.INT.optionalFieldOf("water_capacity", 64).forGetter(Drum::waterCapacity),
				Codec.INT.optionalFieldOf("water_per_bucket", 8).forGetter(Drum::waterPerBucket),
				Codec.INT.optionalFieldOf("mix_interval", 5).forGetter(Drum::mixInterval),
				Codec.FLOAT.optionalFieldOf("drum_mix_speed", 6f).forGetter(Drum::mixSpeed),
				Codec.FLOAT.optionalFieldOf("drum_pour_speed", -12f).forGetter(Drum::pourSpeed),
				Codec.FLOAT.optionalFieldOf("drum_idle_speed", 1.5f).forGetter(Drum::idleSpeed)
		).apply(i, Drum::new));
	}

	public record Settings(Chute chute, Drum drum, List<Recipe> recipes) {
		public static final Codec<Settings> CODEC = RecordCodecBuilder.create(i -> i.group(
				Chute.MAP_CODEC.forGetter(Settings::chute),
				Drum.MAP_CODEC.forGetter(Settings::drum),
				Recipe.CODEC.listOf().optionalFieldOf("recipes", defaultRecipes()).forGetter(Settings::recipes)
		).apply(i, Settings::new));

		static final Settings DEFAULT = CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, new com.google.gson.JsonObject())
				.result().orElseThrow();
	}

	private enum Status { IDLE, POURING, EMPTY, NO_TARGET, PROTECTED }

	private Block drumOutput;
	private int pourCooldown;
	private int mixCooldown;
	private boolean mixing;
	private Status status = Status.IDLE;

	public MixerTruckModule(ConstructionMachineEntity machine) {
		super(machine);
	}

	@Override
	public MachineType type() {
		return MachineType.MIXER_TRUCK;
	}

	@Override
	public String[] floatChannels() {
		return CHANNELS;
	}

	private Settings settings() {
		return this.machine.settings().machineSettings(Settings.class, Settings.DEFAULT);
	}

	// Driving is never suppressed - the chute has its own dedicated axes (default I/K/J/L), so
	// pouring while creeping forward (laying a strip) works exactly as it should.

	private int concrete() {
		return this.machine.getIntChannel(INT_CONCRETE);
	}

	private int water() {
		return this.machine.getIntChannel(INT_WATER);
	}

	@Override
	public void serverTick(ServerWorld world, ServerPlayerEntity operator) {
		Settings s = this.settings();
		Drum d = s.drum();

		if (this.machine.age % 20 == 0) {
			this.refillWater(d);
		}
		this.mixing = false;
		if (--this.mixCooldown <= 0) {
			this.mixCooldown = Math.max(1, d.mixInterval());
			this.mixOne(s);
		}

		boolean pouring = false;
		this.status = Status.IDLE;
		if (operator != null) {
			if (this.machine.isWorkMode()) {
				this.updateChute(s.chute());
			}
			if (this.machine.isPrimaryHeld()) {
				pouring = true;
				this.status = Status.POURING;
				if (--this.pourCooldown <= 0) {
					this.pourCooldown = Math.max(1, s.chute().pourInterval());
					this.pourOne(world, operator, s.chute());
				}
			}
		}
		if (this.concrete() <= 0) {
			this.drumOutput = null;
		}

		float drumSpeed = pouring && this.concrete() > 0 ? d.pourSpeed()
				: (this.concrete() > 0 || this.mixing) ? d.mixSpeed() : d.idleSpeed();
		this.machine.setFloatChannel(DRUM, drumSpeed);
		this.machine.setFloatChannel(LOAD, MathHelper.clamp(this.concrete() / (float) Math.max(1, d.capacity()), 0f, 1f));
	}

	private void refillWater(Drum d) {
		int water = this.water();
		if (this.machine.isTouchingWater() && water < d.waterCapacity()) {
			water = Math.min(d.waterCapacity(), water + d.waterPerBucket());
		}
		for (int slot = this.machine.cargoStart(); slot < this.machine.cargoEnd()
				&& water + d.waterPerBucket() <= d.waterCapacity(); slot++) {
			ItemStack stack = this.machine.getStack(slot);
			if (stack.isOf(Items.WATER_BUCKET)) {
				this.machine.setStack(slot, new ItemStack(Items.BUCKET));
				water += d.waterPerBucket();
			}
		}
		this.machine.setIntChannel(INT_WATER, water);
	}

	private void mixOne(Settings s) {
		if (this.concrete() >= s.drum().capacity()) {
			return;
		}
		for (int slot = this.machine.cargoStart(); slot < this.machine.cargoEnd(); slot++) {
			ItemStack stack = this.machine.getStack(slot);
			if (stack.isEmpty()) {
				continue;
			}
			for (Recipe recipe : s.recipes()) {
				Identifier inputId = Identifier.tryParse(recipe.input());
				Identifier outputId = Identifier.tryParse(recipe.output());
				if (inputId == null || outputId == null) {
					continue;
				}
				Item input = Registries.ITEM.getOptionalValue(inputId).orElse(null);
				Block output = Registries.BLOCK.getOptionalValue(outputId).orElse(null);
				if (input == null || output == null || !stack.isOf(input)) {
					continue;
				}
				if (this.drumOutput != null && this.drumOutput != output) {
					continue;
				}
				if (recipe.water() && this.water() <= 0) {
					continue;
				}
				stack.decrement(1);
				if (stack.isEmpty()) {
					this.machine.setStack(slot, ItemStack.EMPTY);
				}
				if (recipe.water()) {
					this.machine.setIntChannel(INT_WATER, this.water() - 1);
				}
				this.drumOutput = output;
				this.machine.setIntChannel(INT_CONCRETE, this.concrete() + 1);
				this.mixing = true;
				return;
			}
		}
	}

	private void updateChute(Chute c) {
		float sideways = this.machine.workHorizontal();
		if (sideways != 0f) {
			float swing = this.machine.getFloatChannel(CHUTE_SWING) + sideways * c.swingSpeed();
			this.machine.setFloatChannel(CHUTE_SWING, MathHelper.clamp(swing, -c.swingLimit(), c.swingLimit()));
		}
		float lift = this.machine.workVertical();
		if (lift != 0f) {
			float tilt = this.machine.getFloatChannel(CHUTE_TILT) + lift * c.tiltSpeed();
			this.machine.setFloatChannel(CHUTE_TILT, MathHelper.clamp(tilt,
					Math.min(c.tiltMin(), c.tiltMax()), Math.max(c.tiltMin(), c.tiltMax())));
		}
	}

	private void pourOne(ServerWorld world, ServerPlayerEntity operator, Chute c) {
		if (this.concrete() <= 0 || this.drumOutput == null) {
			this.status = Status.EMPTY;
			return;
		}
		Vec3d outlet = this.machine.workPoint(c.chutePoint());
		BlockPos target = PourSpreader.findTarget(world, outlet, Math.max(0, c.spreadRadius()));
		if (target == null) {
			this.status = Status.NO_TARGET;
			return;
		}
		if (!BlockWork.place(world, operator, target, this.drumOutput)) {
			this.status = Status.PROTECTED;
			return;
		}
		world.playSound(null, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
				SoundEvents.BLOCK_MUD_PLACE, SoundCategory.BLOCKS, 0.8f, 0.8f);
		this.machine.setIntChannel(INT_CONCRETE, this.concrete() - 1);
	}

	@Override
	public Text status() {
		Settings s = this.settings();
		Text material = this.drumOutput != null ? this.drumOutput.getName()
				: Text.translatable("status.constructionaddon.mixer_truck.nothing");
		return switch (this.status) {
			case EMPTY -> Text.translatable("status.constructionaddon.mixer_truck.empty");
			case NO_TARGET -> Text.translatable("status.constructionaddon.mixer_truck.no_target");
			case PROTECTED -> Text.translatable("status.constructionaddon.protected");
			case IDLE -> this.machine.isWorkMode() ? this.summary(s, material) : null;
			case POURING -> this.summary(s, material);
		};
	}

	private Text summary(Settings s, Text material) {
		return Text.translatable("status.constructionaddon.mixer_truck",
				material, this.concrete(), s.drum().capacity(), this.water(), s.drum().waterCapacity());
	}

	@Override
	public void writeData(WriteView view) {
		view.putString("MixerOutput", this.drumOutput != null ? Registries.BLOCK.getId(this.drumOutput).toString() : "");
	}

	@Override
	public void readData(ReadView view) {
		Identifier id = Identifier.tryParse(view.getString("MixerOutput", ""));
		this.drumOutput = id == null || id.getPath().isEmpty() ? null : Registries.BLOCK.getOptionalValue(id).orElse(null);
	}
}
