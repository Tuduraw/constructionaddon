package com.example.constructionaddon.asset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Makes a seat ride on a joint instead of the fixed chassis - an excavator's or crane's cab sits
 * on the swinging upper structure, so its operator (and camera) must swing with it. */
public record SeatPart(int seat, String part) {

	public static final Codec<SeatPart> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("seat").forGetter(SeatPart::seat),
			Codec.STRING.fieldOf("part").forGetter(SeatPart::part)
	).apply(instance, SeatPart::new));
}
