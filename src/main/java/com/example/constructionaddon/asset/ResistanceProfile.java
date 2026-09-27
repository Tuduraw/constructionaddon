package com.example.constructionaddon.asset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Map;

/** A machine's "resistance" settings (see GroundResistance): hardness_scale, refusal_hardness,
 * and per-block hardness overrides ("minecraft:clay": 2.0). */
public record ResistanceProfile(float hardnessScale, float refusalHardness, Map<String, Float> overrides) {

	/** The optional "resistance" field, with this machine's own defaults. */
	public static MapCodec<ResistanceProfile> field(float defaultScale, float defaultRefusal) {
		return codec(defaultScale, defaultRefusal).optionalFieldOf("resistance",
				new ResistanceProfile(defaultScale, defaultRefusal, Map.of()));
	}

	private static Codec<ResistanceProfile> codec(float defaultScale, float defaultRefusal) {
		return RecordCodecBuilder.create(instance -> instance.group(
				Codec.FLOAT.optionalFieldOf("hardness_scale", defaultScale).forGetter(ResistanceProfile::hardnessScale),
				Codec.FLOAT.optionalFieldOf("refusal_hardness", defaultRefusal).forGetter(ResistanceProfile::refusalHardness),
				Codec.unboundedMap(Codec.STRING, Codec.FLOAT).optionalFieldOf("overrides", Map.of())
						.forGetter(ResistanceProfile::overrides)
		).apply(instance, ResistanceProfile::new));
	}
}
