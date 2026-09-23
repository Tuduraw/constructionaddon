package com.example.constructionaddon.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;

/** Finds any registered key binding - this addon's own, or another mod's (such as the base mod's
 * hatch toggle) - by its translation key, and returns the CURRENTLY bound key as localized text.
 *
 * <p>Key bindings are entirely client-local: the server never learns how a player has rebound
 * anything, so any status message that needs to name a key correctly after a rebind has to be
 * built here, client-side, from this - never assembled on the server with the key's default
 * baked into the translation string. */
public final class KeyBindingUtil {

	private KeyBindingUtil() {
	}

	/** The localized name of whatever key is currently bound to the key binding registered under
	 * this translation key (e.g. "key.constructionaddon.work_primary" or, for a binding this
	 * addon doesn't own, "key.tudursvehiclemod.hatch_toggle"). Falls back to the raw translation
	 * key as plain text if no such binding is registered - should not normally happen, since the
	 * caller is expected to pass a translation key that some loaded mod has actually registered. */
	public static Text boundKeyText(String translationKey) {
		for (KeyBinding binding : MinecraftClient.getInstance().options.allKeys) {
			if (binding.getId().equals(translationKey)) {
				return binding.getBoundKeyLocalizedText();
			}
		}
		return Text.literal(translationKey);
	}
}
