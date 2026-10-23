package com.example.constructionaddon.asset;

import com.example.constructionaddon.machine.MachineType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The "construction" object of a vehicle JSON (machine, joints, crawlers, work_points, seat_parts,
 * swing_sound, work_seat, and a machine-specific object keyed by the machine id). The base mod ignores
 * unknown keys, so one file describes the whole vehicle.
 *
 * <p>work_seat: the seat whose rider works the machine (an aerial platform's basket). Absent (-1), the
 * driver works it. Driving always stays with the driver. */
public record ConstructionSettings(
		MachineType machine,
		List<Joint> joints,
		List<Crawler> crawlers,
		Map<String, WorkPoint> workPoints,
		List<SeatPart> seatParts,
		Optional<SwingSound> swingSound,
		Object machineSettings,
		int workSeat
) {

	public static final ConstructionSettings DEFAULT =
			new ConstructionSettings(MachineType.NONE, List.of(), List.of(), Map.of(), List.of(), Optional.empty(), null, -1);

	/** The machine-independent half, parsed first; the machine-specific object is parsed after the
	 * machine type is known. */
	public record Common(String machine, List<Joint> joints, List<Crawler> crawlers, Map<String, WorkPoint> workPoints,
			List<SeatPart> seatParts, Optional<SwingSound> swingSound, int workSeat) {
		public static final Codec<Common> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.optionalFieldOf("machine", "none").forGetter(Common::machine),
				Joint.CODEC.listOf().optionalFieldOf("joints", List.of()).forGetter(Common::joints),
				Crawler.CODEC.listOf().optionalFieldOf("crawlers", List.of()).forGetter(Common::crawlers),
				Codec.unboundedMap(Codec.STRING, WorkPoint.CODEC).optionalFieldOf("work_points", Map.of())
						.forGetter(Common::workPoints),
				SeatPart.CODEC.listOf().optionalFieldOf("seat_parts", List.of()).forGetter(Common::seatParts),
				SwingSound.CODEC.optionalFieldOf("swing_sound").forGetter(Common::swingSound),
				Codec.INT.optionalFieldOf("work_seat", -1).forGetter(Common::workSeat)
		).apply(instance, Common::new));
	}

	/** This machine's own settings record, or the fallback when absent or of another type. */
	public <T> T machineSettings(Class<T> type, T fallback) {
		return type.isInstance(this.machineSettings) ? type.cast(this.machineSettings) : fallback;
	}

	public WorkPoint workPoint(String name) {
		return this.workPoints.getOrDefault(name, WorkPoint.ORIGIN);
	}
}
