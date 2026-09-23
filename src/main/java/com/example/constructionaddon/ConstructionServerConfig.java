package com.example.constructionaddon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Server-side switches for this addon (config/constructionaddon-server.json). Same load-once
 * pattern as the base mod's own VehicleModServerConfig. */
public final class ConstructionServerConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("constructionaddon-server.json");

	/** Master switch for every machine function that breaks or places blocks (digging, grading,
	 * pile driving, dumping, pouring). False turns the machines into props that still drive, lift
	 * and tow but never alter terrain - for servers that don't want this at all. */
	public boolean allowTerrainEditing = true;

	/** Whether terrain may be altered with no player operator to check permissions against.
	 * Every machine function is operated by a seated player today, so this only matters for
	 * future unmanned operation; false keeps protection checks meaningful. */
	public boolean allowUnmannedTerrainEditing = false;

	/** Whether a crane hook may pick up players. Off by default - a held player is teleported
	 * every tick, which is safe but can be unpleasant for them. */
	public boolean craneCanLiftPlayers = false;

	/** Whether a crane hook may pick up other vehicles (including other machines). */
	public boolean craneCanLiftVehicles = true;

	/** Whether the operator sees the machine's status (load, depth, blows, rpm, refusal...) in the
	 * action bar. */
	public boolean showStatusMessages = true;

	private static ConstructionServerConfig instance;

	public static ConstructionServerConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static ConstructionServerConfig load() {
		if (Files.exists(CONFIG_PATH)) {
			try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
				ConstructionServerConfig loaded = GSON.fromJson(reader, ConstructionServerConfig.class);
				if (loaded != null) {
					loaded.save(); // writes back any fields added since the file was created
					return loaded;
				}
			} catch (IOException | RuntimeException ignored) {
				// Malformed or unreadable - fall through to defaults.
			}
		}
		ConstructionServerConfig defaults = new ConstructionServerConfig();
		defaults.save();
		return defaults;
	}

	public void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException ignored) {
			// Not worth crashing over.
		}
	}
}
