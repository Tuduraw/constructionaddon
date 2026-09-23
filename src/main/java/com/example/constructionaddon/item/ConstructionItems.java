package com.example.constructionaddon.item;

import com.example.constructionaddon.ConstructionAddon;
import com.example.tudursvehiclemod.item.TieredVehicleSpawnerItem;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Tier 1-5 spawner items - the base mod's own TieredVehicleSpawnerItem, so right-clicking opens
 * the base mod's own vehicle selection screen listing the machines of that tier or below. */
public class ConstructionItems {

	/** [tier - 1]. */
	public static final Item[] SPAWNERS = new Item[5];

	public static final ConstructionConverterTarget TARGET = new ConstructionConverterTarget();

	public static void register() {
		for (int tier = 1; tier <= 5; tier++) {
			RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM,
					Identifier.of(ConstructionAddon.MOD_ID, "construction_machine_spawner_t" + tier));
			SPAWNERS[tier - 1] = Registry.register(Registries.ITEM, key,
					new TieredVehicleSpawnerItem(new Item.Settings().registryKey(key).maxCount(1), TARGET, tier));
		}

		RegistryKey<ItemGroup> groupKey =
				RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of(ConstructionAddon.MOD_ID, "construction_machines"));
		Registry.register(Registries.ITEM_GROUP, groupKey, FabricItemGroup.builder()
				.icon(() -> new ItemStack(SPAWNERS[1]))
				.displayName(Text.translatable("itemGroup.constructionaddon.construction_machines"))
				.build());
		ItemGroupEvents.modifyEntriesEvent(groupKey).register(entries -> {
			for (Item item : SPAWNERS) {
				entries.add(item);
			}
		});
	}
}
