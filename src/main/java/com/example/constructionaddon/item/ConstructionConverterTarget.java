package com.example.constructionaddon.item;

import com.example.constructionaddon.ConstructionAddon;
import com.example.tudursvehiclemod.item.VehicleConverterTarget;
import net.minecraft.item.Item;
import net.minecraft.util.Identifier;

/** Opts every construction machine into the base mod's tiered base item -> spawner item
 * converter and vehicle packing. One target covers all machines, since they share one entity
 * type; the vehicle selection screen then lists every machine of the spawner's tier or below. */
public class ConstructionConverterTarget implements VehicleConverterTarget {

	@Override
	public Identifier entityTypeId() {
		return Identifier.of(ConstructionAddon.MOD_ID, "construction_machine");
	}

	@Override
	public String translationKey() {
		return "item.constructionaddon.category.construction_machine";
	}

	@Override
	public Identifier id() {
		return Identifier.of(ConstructionAddon.MOD_ID, "construction_machine");
	}

	@Override
	public Item[] tieredSpawnerItems() {
		return ConstructionItems.SPAWNERS;
	}
}
