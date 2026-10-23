package com.example.constructionaddon.client.mixin;

import com.example.constructionaddon.entity.ConstructionMachineEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The local player's crosshair (block / entity picking) starts where they actually look from while
 * riding a construction machine - the rider's eye from the seat's camera_positions (a rider shrunk to
 * fit the cab, or riding a basket) - not at standing eye height above the seat. */
@Mixin(Entity.class)
public abstract class EntityCameraPosMixin {

	@Inject(method = "getCameraPosVec", at = @At("HEAD"), cancellable = true)
	private void constructionaddon$seatEye(float tickDelta, CallbackInfoReturnable<Vec3d> cir) {
		if ((Object) this instanceof ClientPlayerEntity player
				&& player.getVehicle() instanceof ConstructionMachineEntity machine) {
			cir.setReturnValue(machine.getRotatedEyePos(player, tickDelta));
		}
	}
}
