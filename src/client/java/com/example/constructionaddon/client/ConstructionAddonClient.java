package com.example.constructionaddon.client;

import com.example.constructionaddon.ConstructionAddon;
import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.constructionaddon.machine.BulldozerModule;
import com.example.constructionaddon.machine.ExcavatorModule;
import com.example.constructionaddon.machine.MachineType;
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
 * <p>Every machine control lives on this addon's OWN key bindings (defaulting to the arrow keys,
 * plus , / . for a third axis) and Z/X - none of it ever touches WASD, so driving and operating a
 * machine always work at the same time:
 * <ul>
 *   <li>Up / Down, Left / Right - this addon's own vertical/horizontal work axes;</li>
 *   <li>, / . - this addon's own third ("arm") axis, only used by machines with more than two
 *       independent joints to drive at once (a backhoe's arm, alongside boom and swing above);</li>
 *   <li>Z (primary) / X (secondary) - the two work action keys (dig/curl, grab/release, pour,
 *       raise/lower a bed, drive a pile, couple/uncouple), and also modifiers on the vertical
 *       axis where a module needs more than one vertical concept (a crane's luff/extend/hoist) -
 *       see CraneModule for the exact mapping.</li>
 * </ul>
 * All of this addon's own bindings default to the same physical keys as certain base mod
 * bindings (Up/Down = the base mod's own level ascend/descend) but are entirely separate
 * bindings under this addon's own category - rebinding one here never touches, or is touched by,
 * the other, or by anything else. Work MODE itself has no key of its own - it is the base mod's
 * manual-mode key (hold M), used by a crane/pile driver to deploy outriggers/mast; it never
 * affects WASD either. */
public class ConstructionAddonClient implements ClientModInitializer {

	private static KeyBinding primaryKey;
	private static KeyBinding secondaryKey;
	private static KeyBinding axisUpKey;
	private static KeyBinding axisDownKey;
	private static KeyBinding axisLeftKey;
	private static KeyBinding axisRightKey;
	private static KeyBinding armInKey;
	private static KeyBinding armOutKey;

	private static boolean lastPrimary;
	private static boolean lastSecondary;
	private static float lastVertical;
	private static float lastHorizontal;
	private static float lastArm;

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ConstructionAddon.CONSTRUCTION_MACHINE, VehicleEntityRenderer::new);

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(ConstructionAddon.MOD_ID, "construction"));
		primaryKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.constructionaddon.work_primary", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, category));
		secondaryKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.constructionaddon.work_secondary", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, category));
		axisUpKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.constructionaddon.work_axis_up", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UP, category));
		axisDownKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.constructionaddon.work_axis_down", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_DOWN, category));
		axisLeftKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.constructionaddon.work_axis_left", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT, category));
		axisRightKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.constructionaddon.work_axis_right", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT, category));
		armInKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.constructionaddon.work_axis_arm_in", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_COMMA, category));
		armOutKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.constructionaddon.work_axis_arm_out", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_PERIOD, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null) {
				lastPrimary = false;
				lastSecondary = false;
				lastVertical = 0f;
				lastHorizontal = 0f;
				lastArm = 0f;
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

			float vertical = operating ? (axisUpKey.isPressed() ? 1f : 0f) - (axisDownKey.isPressed() ? 1f : 0f) : 0f;
			float horizontal = operating ? (axisLeftKey.isPressed() ? 1f : 0f) - (axisRightKey.isPressed() ? 1f : 0f) : 0f;
			float arm = operating ? (armInKey.isPressed() ? 1f : 0f) - (armOutKey.isPressed() ? 1f : 0f) : 0f;
			if (vertical != lastVertical || horizontal != lastHorizontal || arm != lastArm) {
				lastVertical = vertical;
				lastHorizontal = horizontal;
				lastArm = arm;
				ClientPlayNetworking.send(new WorkAxisPayload(vertical, horizontal, arm));
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
		if (type == MachineType.TRACTOR && machine.channel("coupled") > 0.5f) {
			return Text.translatable("status.constructionaddon.tractor.coupled",
					KeyBindingUtil.boundKeyText("key.constructionaddon.work_primary"),
					KeyBindingUtil.boundKeyText("key.tudursvehiclemod.hatch_toggle"));
		}
		return null;
	}
}
