package com.example.constructionaddon.asset;

import com.example.constructionaddon.ConstructionAddon;
import com.example.constructionaddon.machine.MachineType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/** Per-vehicle ConstructionSettings, keyed by the same vehicle Identifier the base mod's own
 * VehicleRegistry uses. Reads the same data/&lt;namespace&gt;/vehicles/*.json files the base mod
 * parses and keeps only the "construction" object (the pattern the base mod's sample addons use).
 *
 * <p>Like those addons, this sees the resource-pack/datapack tree only: a vehicle supplied through
 * the base mod's external tudursvehiclemod-addons/ folder still loads and drives, but gets
 * DEFAULT (no machine functions). */
public final class ConstructionSettingsRegistry implements SimpleSynchronousResourceReloadListener {

	private static final Logger LOGGER = LoggerFactory.getLogger("ConstructionAddon/Settings");
	private static final String DIRECTORY = "vehicles";
	private static final String SUFFIX = ".json";
	private static final String SETTINGS_KEY = "construction";

	private static Map<Identifier, ConstructionSettings> loaded = Map.of();

	/** Never null. */
	public static ConstructionSettings get(Identifier vehicleId) {
		if (vehicleId == null) {
			return ConstructionSettings.DEFAULT;
		}
		return loaded.getOrDefault(vehicleId, ConstructionSettings.DEFAULT);
	}

	@Override
	public Identifier getFabricId() {
		return Identifier.of(ConstructionAddon.MOD_ID, "construction_settings");
	}

	@Override
	public void reload(ResourceManager manager) {
		Map<Identifier, ConstructionSettings> result = new HashMap<>();
		for (Map.Entry<Identifier, Resource> entry :
				manager.findResources(DIRECTORY, id -> id.getPath().endsWith(SUFFIX)).entrySet()) {
			Identifier fileId = entry.getKey();
			String path = fileId.getPath();
			// Same id derivation as the base mod's own listener, so keys line up exactly.
			Identifier vehicleId = Identifier.of(fileId.getNamespace(),
					path.substring(DIRECTORY.length() + 1, path.length() - SUFFIX.length()));
			try (Reader reader = entry.getValue().getReader()) {
				JsonElement json = JsonParser.parseReader(reader);
				if (!json.isJsonObject() || !json.getAsJsonObject().has(SETTINGS_KEY)) {
					continue;
				}
				JsonElement settingsJson = json.getAsJsonObject().get(SETTINGS_KEY);
				if (!settingsJson.isJsonObject()) {
					continue;
				}
				ConstructionSettings settings = parse(vehicleId, settingsJson.getAsJsonObject());
				if (settings != null) {
					result.put(vehicleId, settings);
				}
			} catch (Exception e) {
				LOGGER.error("Failed to read construction settings for {}", vehicleId, e);
			}
		}
		loaded = Map.copyOf(result);
		LOGGER.info("Loaded construction settings for {} vehicle(s)", loaded.size());
	}

	private static ConstructionSettings parse(Identifier vehicleId, JsonObject obj) {
		ConstructionSettings.Common common = ConstructionSettings.Common.CODEC.parse(JsonOps.INSTANCE, obj)
				.resultOrPartial(error -> LOGGER.error("Invalid construction settings for '{}': {}", vehicleId, error))
				.orElse(null);
		if (common == null) {
			return null;
		}
		MachineType type = MachineType.byId(common.machine());
		if (type == MachineType.NONE && !"none".equals(common.machine())) {
			LOGGER.error("Unknown construction machine '{}' in '{}'", common.machine(), vehicleId);
		}
		JsonElement machineJson = obj.has(type.id()) ? obj.get(type.id()) : new JsonObject();
		Object machineSettings = type.parseSettings(machineJson,
				error -> LOGGER.error("Invalid '{}' settings for '{}': {}", type.id(), vehicleId, error));
		return new ConstructionSettings(type, common.joints(), common.workPoints(), common.seatParts(), machineSettings);
	}
}
