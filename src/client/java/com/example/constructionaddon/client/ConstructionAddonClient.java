package com.example.constructionaddon.client;

import com.example.constructionaddon.ConstructionAddon;
import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.constructionaddon.machine.BulldozerModule;
import com.example.constructionaddon.machine.ExcavatorModule;
import com.example.constructionaddon.machine.MachineType;
import com.example.constructionaddon.network.WorkAxis;
import com.example.constructionaddon.network.WorkAxisPayload;
import com.example.constructionaddon.network.WorkInputPayload;
import com.example.tudursvehiclemod.client.render.VehicleEntityRenderer;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/** Client side: the base mod's own VehicleEntityRenderer draws every machine (the moving parts
 * come from ConstructionMachineEntity's custom part transforms), so the client concern here is
 * just the machine's own keys.
 *
 * <p>Every machine control lives on this addon's OWN key bindings - one pair per WorkAxis
 * (swing, generic vertical, backhoe boom / arm, crane luff / telescope / hoist) plus Z / X - none
 * of it ever touches WASD, so driving and operating a machine always work at the same time.
 * Several axes share default keys (boom and luff both Up / Down; arm and telescope both , / .)
 * but each is its own binding, rebindable independently; Up / Down also match the base mod's own
 * level ascend/descend by default, again as a separate binding. Minecraft marks bindings sharing
 * a key red in the controls menu; that is expected here and harmless, since each only acts on its
 * own machine. Work MODE has no key of its own - it is the base mod's manual-mode key (hold M). */
public class ConstructionAddonClient implements ClientModInitializer {

	private static KeyBinding primaryKey;
	private static KeyBinding secondaryKey;
	/** [axis.ordinal()][0 = positive, 1 = negative]. */
	private static final KeyBinding[][] AXIS_KEYS = new KeyBinding[WorkAxis.values().length][2];

	private static boolean lastPrimary;
	private static boolean lastSecondary;
	private static int lastAxisMask;

	/** Default keys per axis: {positive, negative}. Several axes deliberately share defaults (a
	 * backhoe's boom and a crane's luff are both Up/Down) - each is still its own binding. */
	private static int[] defaultKeys(WorkAxis axis) {
		return switch (axis) {
			case SWING -> new int[] {GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT};
			case VERTICAL, BOOM, LUFF -> new int[] {GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN};
			case ARM, TELESCOPE -> new int[] {GLFW.GLFW_KEY_COMMA, GLFW.GLFW_KEY_PERIOD};
			case HOIST -> new int[] {GLFW.GLFW_KEY_SEMICOLON, GLFW.GLFW_KEY_SLASH};
		};
	}

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ConstructionAddon.CONSTRUCTION_MACHINE, VehicleEntityRenderer::new);

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(ConstructionAddon.MOD_ID, "construction"));
		primaryKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.constructionaddon.work_primary", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, category));
		secondaryKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.constructionaddon.work_secondary", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, category));
		for (WorkAxis axis : WorkAxis.values()) {
			int[] keys = defaultKeys(axis);
			AXIS_KEYS[axis.ordinal()][0] = KeyBindingHelper.registerKeyBinding(new KeyBinding(
					axis.positiveTranslationKey(), InputUtil.Type.KEYSYM, keys[0], category));
			AXIS_KEYS[axis.ordinal()][1] = KeyBindingHelper.registerKeyBinding(new KeyBinding(
					axis.negativeTranslationKey(), InputUtil.Type.KEYSYM, keys[1], category));
		}

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null) {
				lastPrimary = false;
				lastSecondary = false;
				lastAxisMask = 0;
				return;
			}
			AbstractVehicleEntity vehicle = AbstractVehicleEntity.tudursvehiclemod$getEffectiveVehicle(client.player);
			ConstructionMachineEntity machine = vehicle instanceof ConstructionMachineEntity m ? m : null;
			boolean operating = machine != null && machine.getControllingPassenger() == client.player
					&& client.currentScreen == null;

			boolean primary = operating && primaryKey.isPressed();
			boolean secondary = operating && secondaryKey.isPressed();
			if (primary != lastPrimary || secondary != lastSecondary) {
				lastPrimary = primary;
				lastSecondary = secondary;
				ClientPlayNetworking.send(new WorkInputPayload(primary, secondary));
			}

			float[] axes = new float[WorkAxis.values().length];
			if (operating) {
				for (WorkAxis axis : WorkAxis.values()) {
					KeyBinding[] pair = AXIS_KEYS[axis.ordinal()];
					axes[axis.ordinal()] = (pair[0].isPressed() ? 1f : 0f) - (pair[1].isPressed() ? 1f : 0f);
				}
			}
			int mask = WorkAxis.pack(axes);
			if (mask != lastAxisMask) {
				lastAxisMask = mask;
				ClientPlayNetworking.send(new WorkAxisPayload(mask));
			}

			if (operating && machine.age % 10 == 0) {
				Text hint = buildKeyAwareStatusHint(machine, primary);
				if (hint != null) {
					client.player.sendMessage(hint, true);
				}
			}
		});
	}

	/** Status hints that need to name a key correctly after a rebind can't be built on the
	 * server at all (see KeyBindingUtil's own doc) - the server instead suppresses these three
	 * specific messages (returns null from the module's own status()) and this reconstructs them
	 * here, client-side, from the same synced channels and JSON-defined settings the server
	 * itself used, so the numbers still match exactly. */
	private static Text buildKeyAwareStatusHint(ConstructionMachineEntity machine, boolean primaryHeld) {
		MachineType type = machine.settings().machine();
		if (type == MachineType.EXCAVATOR && machine.isWorkMode() && primaryHeld
				&& machine.channel("load") >= 0.999f) {
			ExcavatorModule.Settings s = machine.settings().machineSettings(ExcavatorModule.Settings.class, ExcavatorModule.Settings.DEFAULT);
			int capacity = s.work().capacity();
			int total = Math.round(machine.channel("load") * capacity);
			return Text.translatable("status.constructionaddon.excavator.full", total, capacity,
					KeyBindingUtil.boundKeyText("key.constructionaddon.work_secondary"));
		}
		if (type == MachineType.BULLDOZER && machine.channel("load") >= 0.999f) {
			BulldozerModule.Settings s = machine.settings().machineSettings(BulldozerModule.Settings.class, BulldozerModule.Settings.DEFAULT);
			int capacity = s.capacity();
			int total = Math.round(machine.channel("load") * capacity);
			String blade = String.format(java.util.Locale.ROOT, "%+.2f", machine.channel("blade"));
			return Text.translatable("status.constructionaddon.bulldozer.full", blade, total, capacity,
					KeyBindingUtil.boundKeyText("key.constructionaddon.work_primary"));
		}
		if (type == MachineType.PILE_DRIVER && machine.isWorkMode()
				&& machine.channel("mast") <= 0.001f && machine.channel("track_width") <= 0.001f) {
			return Text.translatable("status.constructionaddon.pile_driver.leader_down",
					KeyBindingUtil.boundKeyText("key.constructionaddon.work_secondary"));
		}
		if (type == MachineType.TRACTOR && machine.channel("coupled") > 0.5f) {
			return Text.translatable("status.constructionaddon.tractor.coupled",
					KeyBindingUtil.boundKeyText("key.constructionaddon.work_primary"),
					KeyBindingUtil.boundKeyText("key.tudursvehiclemod.hatch_toggle"));
		}
		return null;
	}
}
