package com.example.constructionaddon.asset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** A sound played while a machine's upper structure is WORK-swinging (slewing on its own, not
 * the vehicle turning) - the travel/swing alarm many real machines sound. Optional per vehicle
 * ("construction.swing_sound"); absent means silent.
 *
 * <ul>
 *   <li>sound - any sound event id: vanilla ("minecraft:block.note_block.bit"), another mod's,
 *       or one a resource pack defines in its own sounds.json;</li>
 *   <li>volume / pitch - as for any sound;</li>
 *   <li>interval - ticks between repeats while the swing continues (a beeping alarm); the first
 *       one plays as soon as the swing starts.</li>
 * </ul> */
public record SwingSound(String sound, float volume, float pitch, int interval) {

	public static final Codec<SwingSound> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("sound").forGetter(SwingSound::sound),
			Codec.FLOAT.optionalFieldOf("volume", 1.0f).forGetter(SwingSound::volume),
			Codec.FLOAT.optionalFieldOf("pitch", 1.0f).forGetter(SwingSound::pitch),
			Codec.INT.optionalFieldOf("interval", 10).forGetter(SwingSound::interval)
	).apply(i, SwingSound::new));
}
