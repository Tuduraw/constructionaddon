package com.example.constructionaddon.asset;

import com.example.constructionaddon.machine.MachineType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** This addon's part of one vehicle JSON - the "construction" object:
 *
 * <pre>
 * "construction": {
 *   "machine": "excavator",
 *   "joints": [ ... ],
 *   "work_points": { "bucket_tip": { "part": "$bucket", "x": 0.0, "y": 0.4, "z": 5.1 } },
 *   "seat_parts": [ { "seat": 0, "part": "$upper" } ],
 *   "swing_sound": { "sound": "minecraft:block.note_block.bit", "pitch": 1.8, "interval": 8 },
 *   "excavator": { ...machine-specific settings... }
 * }
 * </pre>
 *
 * The base mod's VehicleDefinition ignores keys it doesn't know, so one vehicle stays described by
 * one file. The machine-specific object is keyed by the machine's own id and parsed by that
 * machine's own codec (see MachineType); every field of it is optional. */
public record ConstructionSettings(
		MachineType machine,
		List<Joint> joints,
		Map<String, WorkPoint> workPoints,
		List<SeatPart> seatParts,
		Optional<SwingSound> swingSound,
		Object machineSettings
) {

	public static final ConstructionSettings DEFAULT =
			new ConstructionSettings(MachineType.NONE, List.of(), Map.of(), List.of(), Optional.empty(), null);

	/** The machine-independent half, parsed first; the machine-specific object is parsed after the
	 * machine type is known. */
	public record Common(String machine, List<Joint> joints, Map<String, WorkPoint> workPoints, List<SeatPart> seatParts,
			Optional<SwingSound> swingSound) {
		public static final Codec<Common> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.optionalFieldOf("machine", "none").forGetter(Common::machine),
				Joint.CODEC.listOf().optionalFieldOf("joints", List.of()).forGetter(Common::joints),
				Codec.unboundedMap(Codec.STRING, WorkPoint.CODEC).optionalFieldOf("work_points", Map.of())
						.forGetter(Common::workPoints),
				SeatPart.CODEC.listOf().optionalFieldOf("seat_parts", List.of()).forGetter(Common::seatParts),
				SwingSound.CODEC.optionalFieldOf("swing_sound").forGetter(Common::swingSound)
		).apply(instance, Common::new));
	}

	/** This machine's own settings record, or the fallback when absent or of another type. */
	public <T> T machineSettings(Class<T> type, T fallback) {
		return type.isInstance(this.machineSettings) ? type.cast(this.machineSettings) : fallback;
	}

	public Optional<Joint> joint(String part) {
		for (Joint joint : this.joints) {
			if (joint.part().equals(part)) {
				return Optional.of(joint);
			}
		}
		return Optional.empty();
	}

	public WorkPoint workPoint(String name) {
		return this.workPoints.getOrDefault(name, WorkPoint.ORIGIN);
	}
}
