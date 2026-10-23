package com.example.constructionaddon.asset;

import com.example.constructionaddon.ConstructionAddon;
import com.example.constructionaddon.machine.MachineType;
import com.example.tudursvehiclemod.asset.AddonPaths;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** Reads the "construction" object from the same vehicle JSONs the base mod loads, keyed by the same
 * vehicle id: data/<namespace>/vehicles/*.json from data/resource packs, then the base mod's external
 * addon folder (tudursvehiclemod-addons/<pack>/data/<namespace>/vehicles/), which - as in the base
 * mod - wins for the same id. A vehicle without a "construction" object gets DEFAULT (it drives,
 * without machine functions). */
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
				read(vehicleId, reader, result);
			} catch (Exception e) {
				LOGGER.error("Failed to read construction settings for {}", vehicleId, e);
			}
		}
		int fromResources = result.size();
		int fromAddons = loadFromAddonsFolder(result);
		loaded = Map.copyOf(result);
		LOGGER.info("Loaded construction settings for {} vehicle(s) ({} from resources, {} from tudursvehiclemod-addons/)",
				loaded.size(), fromResources, fromAddons);
	}

	/** Same folder layout and id derivation as the base mod's VehicleDefinitionReloadListener. A vehicle
	 * there replaces one with the same id from the resources - with or without a "construction" object,
	 * just as its definition does. Returns how many machines the folder provided. Never throws. */
	private static int loadFromAddonsFolder(Map<Identifier, ConstructionSettings> result) {
		int count = 0;
		for (Path addonDir : AddonPaths.listSubdirectories(AddonPaths.getAddonsRoot())) {
			for (Path namespaceDir : AddonPaths.listSubdirectories(addonDir.resolve("data"))) {
				String namespace = namespaceDir.getFileName().toString();
				Path vehiclesDir = namespaceDir.resolve(DIRECTORY);
				if (!Files.isDirectory(vehiclesDir)) {
					continue;
				}
				try (Stream<Path> files = Files.walk(vehiclesDir)) {
					for (Path jsonFile : (Iterable<Path>) files.filter(p -> p.toString().endsWith(SUFFIX))::iterator) {
						String relative = vehiclesDir.relativize(jsonFile).toString().replace('\\', '/');
						Identifier vehicleId = Identifier.of(namespace, relative.substring(0, relative.length() - SUFFIX.length()));
						result.remove(vehicleId);
						try (Reader reader = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8)) {
							if (read(vehicleId, reader, result)) {
								count++;
							}
						} catch (Exception e) {
							LOGGER.error("Failed to read construction settings for {} ({})", vehicleId, jsonFile, e);
						}
					}
				} catch (Exception e) {
					LOGGER.error("Failed to scan {}", vehiclesDir, e);
				}
			}
		}
		return count;
	}

	/** Parses one vehicle JSON; returns whether it defined a machine. */
	private static boolean read(Identifier vehicleId, Reader reader, Map<Identifier, ConstructionSettings> result) {
		JsonElement json = JsonParser.parseReader(reader);
		if (!json.isJsonObject() || !json.getAsJsonObject().has(SETTINGS_KEY)) {
			return false;
		}
		JsonElement settingsJson = json.getAsJsonObject().get(SETTINGS_KEY);
		if (!settingsJson.isJsonObject()) {
			return false;
		}
		ConstructionSettings settings = parse(vehicleId, settingsJson.getAsJsonObject());
		if (settings == null) {
			return false;
		}
		result.put(vehicleId, settings);
		return true;
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
		return new ConstructionSettings(type, common.joints(), common.crawlers(), common.workPoints(), common.seatParts(),
				common.swingSound(), machineSettings, common.workSeat());
	}
}
