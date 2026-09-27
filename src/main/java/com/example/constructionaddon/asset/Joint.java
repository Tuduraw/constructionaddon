package com.example.constructionaddon.asset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Locale;
import java.util.Optional;

/** One moving OBJ group, driven by a named channel of its machine (see
 * MachineModule#floatChannels). Value = offset + factor x channel, clamped to [min, max]:
 * <ul>
 *   <li>rotate - degrees about axis through pivot; slide - model units along axis;</li>
 *   <li>scale - factor along axis about pivot (a load heap, a rope);</li>
 *   <li>spin - value x factor degrees added every tick, unclamped (drum, auger, roller).</li>
 * </ul>
 * A joint without a channel stays still but can parent others. Children apply on top of their
 * parent to any depth; inherit_rotation = false takes only the parent's displacement (a hook
 * hanging straight down). The same matrices drive drawing and work points. */
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

	/** Grouped for RecordCodecBuilder's field limit; still flat keys in JSON. */
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
