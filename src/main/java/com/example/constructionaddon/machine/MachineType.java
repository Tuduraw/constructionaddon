package com.example.constructionaddon.machine;

import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/** Every machine function this addon provides, by the id a vehicle JSON names in
 * "construction.machine". Each carries the codec for its own settings object (keyed by the same
 * id inside "construction") and the factory for its module. */
public enum MachineType {
	NONE("none", null, NoneModule::new),
	EXCAVATOR("excavator", ExcavatorModule.Settings.CODEC, ExcavatorModule::new),
	CRANE("crane", CraneModule.Settings.CODEC, CraneModule::new),
	BULLDOZER("bulldozer", BulldozerModule.Settings.CODEC, BulldozerModule::new),
	PILE_DRIVER("pile_driver", PileDriverModule.Settings.CODEC, PileDriverModule::new),
	TRACTOR("tractor", TractorModule.Settings.CODEC, TractorModule::new),
	TRAILER("trailer", TrailerModule.Settings.CODEC, TrailerModule::new),
	DUMP_TRUCK("dump_truck", DumpTruckModule.Settings.CODEC, DumpTruckModule::new),
	MIXER_TRUCK("mixer_truck", MixerTruckModule.Settings.CODEC, MixerTruckModule::new);

	private final String id;
	private final Codec<?> settingsCodec;
	private final Function<ConstructionMachineEntity, MachineModule> factory;

	MachineType(String id, Codec<?> settingsCodec, Function<ConstructionMachineEntity, MachineModule> factory) {
		this.id = id;
		this.settingsCodec = settingsCodec;
		this.factory = factory;
	}

	public String id() {
		return this.id;
	}

	public MachineModule create(ConstructionMachineEntity machine) {
		return this.factory.apply(machine);
	}

	public static MachineType byId(String id) {
		String key = id == null ? "" : id.toLowerCase(Locale.ROOT);
		for (MachineType type : values()) {
			if (type.id.equals(key)) {
				return type;
			}
		}
		return NONE;
	}

	/** Parses this machine's own settings object; malformed input logs and falls back to all
	 * defaults rather than disabling the machine. */
	public Object parseSettings(JsonElement json, Consumer<String> onError) {
		return this.settingsCodec == null ? null : parse(this.settingsCodec, json, onError);
	}

	private static <T> Object parse(Codec<T> codec, JsonElement json, Consumer<String> onError) {
		return codec.parse(JsonOps.INSTANCE, json).resultOrPartial(onError)
				.orElseGet(() -> MachineModule.defaults(codec));
	}
}
