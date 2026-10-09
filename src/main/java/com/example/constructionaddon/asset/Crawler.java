package com.example.constructionaddon.asset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Optional;

/** A crawler track drawn by this addon rather than the base mod - for crawlers that move as a
 * whole (widening crawlers), which the base mod's crawler_tracks (drawn where the JSON puts them)
 * cannot follow. Each link is its own OBJ group, {@code link_prefix} + 0, 1, ... in path order,
 * modelled where the base mod would place it at belt travel 0 (same placement rule and the same
 * link orientation: local +Y outward, local -Z along the path). Every link rides on {@code parent}'s
 * joint. The belt travel comes from the base mod's crawler track named {@code belt} - list one in
 * crawler_tracks with that part name and the same path but no geometry, so the base mod measures
 * the travel (and its differential steering) without drawing anything. */
public record Crawler(String belt, Optional<String> parent, String linkPrefix, float linkSpacing,
		List<PathPoint> path) {

	public record PathPoint(float y, float z) {
		public static final Codec<PathPoint> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.FLOAT.fieldOf("y").forGetter(PathPoint::y),
				Codec.FLOAT.fieldOf("z").forGetter(PathPoint::z)
		).apply(i, PathPoint::new));
	}

	public static final Codec<Crawler> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("belt").forGetter(Crawler::belt),
			Codec.STRING.optionalFieldOf("parent").forGetter(Crawler::parent),
			Codec.STRING.fieldOf("link_prefix").forGetter(Crawler::linkPrefix),
			Codec.FLOAT.optionalFieldOf("link_spacing", 0.5f).forGetter(Crawler::linkSpacing),
			PathPoint.CODEC.listOf().fieldOf("path").forGetter(Crawler::path)
	).apply(i, Crawler::new));
}
