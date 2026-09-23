package com.example.constructionaddon.asset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/** A named point of the model where a machine actually acts - a bucket's cutting edge, a crane's
 * boom tip, a mixer chute's outlet, a pile's centre line. In MODEL coordinates of the rest pose;
 * when {@code part} names a joint, the point moves with that joint (and its whole parent chain)
 * exactly as drawn. */
public record WorkPoint(Optional<String> part, float x, float y, float z) {

	public static final WorkPoint ORIGIN = new WorkPoint(Optional.empty(), 0f, 0f, 0f);

	public static final Codec<WorkPoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.optionalFieldOf("part").forGetter(WorkPoint::part),
			Codec.FLOAT.optionalFieldOf("x", 0f).forGetter(WorkPoint::x),
			Codec.FLOAT.optionalFieldOf("y", 0f).forGetter(WorkPoint::y),
			Codec.FLOAT.optionalFieldOf("z", 0f).forGetter(WorkPoint::z)
	).apply(instance, WorkPoint::new));
}
