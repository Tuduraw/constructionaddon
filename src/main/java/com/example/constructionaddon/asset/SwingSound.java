package com.example.constructionaddon.asset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Optional "construction.swing_sound": played while the upper structure work-swings (not when
 * the vehicle turns), repeating every interval ticks. Any sound id works, including ones only a
 * resource pack's sounds.json defines. Absent = silent. */
public record SwingSound(String sound, float volume, float pitch, int interval) {

	public static final Codec<SwingSound> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("sound").forGetter(SwingSound::sound),
			Codec.FLOAT.optionalFieldOf("volume", 1.0f).forGetter(SwingSound::volume),
			Codec.FLOAT.optionalFieldOf("pitch", 1.0f).forGetter(SwingSound::pitch),
			Codec.INT.optionalFieldOf("interval", 10).forGetter(SwingSound::interval)
	).apply(i, SwingSound::new));
}
