package com.example.constructionaddon.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;

/** Localized name of the key currently bound to a key binding (this addon's or another mod's),
 * found by its id. */
public final class KeyBindingUtil {

	private KeyBindingUtil() {
	}

	/** Falls back to the id itself if no such binding is registered. */
	public static Text boundKeyText(String translationKey) {
		for (KeyBinding binding : MinecraftClient.getInstance().options.allKeys) {
			if (binding.getId().equals(translationKey)) {
				return binding.getBoundKeyLocalizedText();
			}
		}
		return Text.literal(translationKey);
	}
}
