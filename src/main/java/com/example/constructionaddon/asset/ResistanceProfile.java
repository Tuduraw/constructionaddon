package com.example.constructionaddon.asset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Map;

/** How hard the ground is to work, for one machine - shared by every machine that pushes a tool
 * into blocks (pile driving by hammer or by rotation, bucket digging, blade cutting). See
 * work.GroundResistance for how it is evaluated.
 *
 * <ul>
 *   <li>hardness_scale - resistance factor = 1 + hardness * hardness_scale. Dirt (0.5) at the
 *       default 1.0 costs 1.5x the base effort; stone (1.5) 2.5x; deepslate (3.0) 4x.</li>
 *   <li>refusal_hardness - at or above this hardness the tool cannot penetrate at all
 *       (refusal). Unbreakable blocks (bedrock, hardness -1) always refuse.</li>
 *   <li>overrides - per-block hardness replacing the vanilla value, e.g. treating
 *       "minecraft:clay" as harder than vanilla says, or letting a pile pass through
 *       "minecraft:gravel" easily.</li>
 * </ul> */
public record ResistanceProfile(float hardnessScale, float refusalHardness, Map<String, Float> overrides) {

	public static final ResistanceProfile DEFAULT = new ResistanceProfile(1.0f, 10.0f, Map.of());

	public static Codec<ResistanceProfile> codec(float defaultScale, float defaultRefusal) {
		return RecordCodecBuilder.create(instance -> instance.group(
				Codec.FLOAT.optionalFieldOf("hardness_scale", defaultScale).forGetter(ResistanceProfile::hardnessScale),
				Codec.FLOAT.optionalFieldOf("refusal_hardness", defaultRefusal).forGetter(ResistanceProfile::refusalHardness),
				Codec.unboundedMap(Codec.STRING, Codec.FLOAT).optionalFieldOf("overrides", Map.of())
						.forGetter(ResistanceProfile::overrides)
		).apply(instance, ResistanceProfile::new));
	}
}
