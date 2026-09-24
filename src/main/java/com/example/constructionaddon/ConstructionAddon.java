package com.example.constructionaddon;

import com.example.constructionaddon.asset.ConstructionSettingsRegistry;
import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.constructionaddon.item.ConstructionItems;
import com.example.constructionaddon.network.WorkAxisPayload;
import com.example.constructionaddon.network.WorkInputPayload;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import com.example.tudursvehiclemod.item.VehicleConverterTargets;
import com.example.tudursvehiclemod.registry.ModEntityTypes;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.entity.EntityType;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.util.Iterator;

/** Working construction machinery for Tudur's Vehicle Mod.
 *
 * Everything goes through the base mod's public addon API (addon vehicle type, converter
 * target, tiered spawner items, custom part transforms, body frame offset) - no mixins into it. */
public class ConstructionAddon implements ModInitializer {

	public static final String MOD_ID = "constructionaddon";

	/** One entity type for every machine; the vehicle JSON's "construction.machine" picks the
	 * function (see MachineModule). */
	public static EntityType<ConstructionMachineEntity> CONSTRUCTION_MACHINE;

	@Override
	public void onInitialize() {
		ConstructionServerConfig.get();

		CONSTRUCTION_MACHINE = ModEntityTypes.registerAddonVehicleType(
				Identifier.of(MOD_ID, "construction_machine"),
				ConstructionMachineEntity::new,
				1.6f, 1.0f);

		ConstructionItems.register();
		VehicleConverterTargets.register(ConstructionItems.TARGET);

		ResourceManagerHelper.get(ResourceType.SERVER_DATA)
				.registerReloadListener(new ConstructionSettingsRegistry());

		PayloadTypeRegistry.playC2S().register(WorkInputPayload.ID, WorkInputPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(WorkInputPayload.ID, (payload, context) ->
				context.server().execute(() -> {
					AbstractVehicleEntity vehicle = AbstractVehicleEntity.tudursvehiclemod$getEffectiveVehicle(context.player());
					// Only the driver operates the machine.
					if (vehicle instanceof ConstructionMachineEntity machine
							&& machine.getControllingPassenger() == context.player()) {
						machine.setWorkInputs(payload.primary(), payload.secondary());
					}
				}));

		PayloadTypeRegistry.playC2S().register(WorkAxisPayload.ID, WorkAxisPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(WorkAxisPayload.ID, (payload, context) ->
				context.server().execute(() -> {
					AbstractVehicleEntity vehicle = AbstractVehicleEntity.tudursvehiclemod$getEffectiveVehicle(context.player());
					if (vehicle instanceof ConstructionMachineEntity machine
							&& machine.getControllingPassenger() == context.player()) {
						machine.setWorkAxisMask(payload.mask());
					}
				}));

		// Crane loads are positioned after every entity in the world has ticked, so the load's
		// own physics can't pull it off the hook in between.
		ServerTickEvents.END_WORLD_TICK.register(world -> {
			Iterator<ConstructionMachineEntity> it = ConstructionMachineEntity.END_TICK_MACHINES.iterator();
			while (it.hasNext()) {
				ConstructionMachineEntity machine = it.next();
				if (machine.isRemoved() || !machine.module().wantsEndWorldTick()) {
					it.remove();
					continue;
				}
				if (machine.getEntityWorld() == world) {
					machine.module().endWorldTick(world);
				}
			}
		});
	}
}
