package com.example.constructionaddon.asset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Locale;
import java.util.Optional;

/** One moving OBJ group of a construction machine: a boom, an arm, a bucket, a telescopic boom
 * section, a tipping bed, a spinning drum, ...
 *
 * <p>A joint is driven by one of its machine's named CHANNELS (see MachineModule's own
 * channelNames()) - the machine's own logic decides the channel value (server-side, synced), and
 * this joint only decides how that value moves the part:
 *
 * <ul>
 *   <li>{@code rotate} - angle (degrees) = offset + factor * value, about axis through pivot;</li>
 *   <li>{@code slide} - distance (model units) = offset + factor * value, along axis;</li>
 *   <li>{@code scale} - scale factor = offset + factor * value, along axis about pivot (a load
 *       heap growing in a bed, a crane rope lengthening);</li>
 *   <li>{@code spin} - continuous rotation, value * factor degrees added EVERY TICK (a mixer
 *       drum, an auger).</li>
 * </ul>
 *
 * <p>The result is clamped to [min, max] (spin is never clamped). A joint may name a parent joint;
 * its transform is then applied on top of the parent's, to any depth (bucket on arm on boom on
 * the swinging upper structure). With inherit_rotation = false only the parent's DISPLACEMENT of
 * this joint's pivot is inherited, not its rotation - a crane rope or hook hanging straight down
 * from a luffing boom tip.
 *
 * <p>Purely a description of motion: the same resolved matrices are used both for drawing (via the
 * base mod's tudursvehiclemod$getCustomPartTransforms()) and server-side for locating work points
 * (the bucket's cutting edge, the hook, the chute outlet), so what the player sees and where the
 * machine actually acts can never drift apart. */
public record Joint(
		String part,
		Optional<String> parent,
		float pivotX, float pivotY, float pivotZ,
		float axisX, float axisY, float axisZ,
		Mode mode,
		Optional<String> channel,
		float factor,
		float offset,
		float min,
		float max,
		boolean inheritRotation
) {

	public enum Mode {
		ROTATE, SLIDE, SCALE, SPIN;

		static final Codec<Mode> CODEC = Codec.STRING.xmap(
				s -> {
					try {
						return Mode.valueOf(s.toUpperCase(Locale.ROOT));
					} catch (IllegalArgumentException e) {
						return ROTATE;
					}
				},
				m -> m.name().toLowerCase(Locale.ROOT));
	}

	/** Grouped only to stay within RecordCodecBuilder's field limit - still flat keys in JSON. */
	private record Geometry(float pivotX, float pivotY, float pivotZ, float axisX, float axisY, float axisZ) {
		static final MapCodec<Geometry> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Codec.FLOAT.optionalFieldOf("pivot_x", 0f).forGetter(Geometry::pivotX),
				Codec.FLOAT.optionalFieldOf("pivot_y", 0f).forGetter(Geometry::pivotY),
				Codec.FLOAT.optionalFieldOf("pivot_z", 0f).forGetter(Geometry::pivotZ),
				Codec.FLOAT.optionalFieldOf("axis_x", 1f).forGetter(Geometry::axisX),
				Codec.FLOAT.optionalFieldOf("axis_y", 0f).forGetter(Geometry::axisY),
				Codec.FLOAT.optionalFieldOf("axis_z", 0f).forGetter(Geometry::axisZ)
		).apply(instance, Geometry::new));
	}

	public static final Codec<Joint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("part").forGetter(Joint::part),
			Codec.STRING.optionalFieldOf("parent").forGetter(Joint::parent),
			Geometry.MAP_CODEC.forGetter(j -> new Geometry(j.pivotX, j.pivotY, j.pivotZ, j.axisX, j.axisY, j.axisZ)),
			Mode.CODEC.optionalFieldOf("mode", Mode.ROTATE).forGetter(Joint::mode),
			Codec.STRING.optionalFieldOf("channel").forGetter(Joint::channel),
			Codec.FLOAT.optionalFieldOf("factor", 1f).forGetter(Joint::factor),
			Codec.FLOAT.optionalFieldOf("offset", Float.NaN).forGetter(Joint::offset),
			Codec.FLOAT.optionalFieldOf("min", -1.0e6f).forGetter(Joint::min),
			Codec.FLOAT.optionalFieldOf("max", 1.0e6f).forGetter(Joint::max),
			Codec.BOOL.optionalFieldOf("inherit_rotation", true).forGetter(Joint::inheritRotation)
	).apply(instance, (part, parent, g, mode, channel, factor, offset, min, max, inherit) ->
			new Joint(part, parent, g.pivotX, g.pivotY, g.pivotZ, g.axisX, g.axisY, g.axisZ,
					mode, channel, factor,
					// Absent offset: 1 for scale (unscaled at value 0 would be invisible), 0 otherwise.
					Float.isNaN(offset) ? (mode == Mode.SCALE ? 1f : 0f) : offset,
					min, max, inherit)));
}
