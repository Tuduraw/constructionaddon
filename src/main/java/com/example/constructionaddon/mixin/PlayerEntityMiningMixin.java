package com.example.constructionaddon.mixin;

import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.constructionaddon.machine.MachineType;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A player working from an aerial platform's basket stands on its floor: blocks break at the normal
 * speed instead of the 1/5 vanilla gives a player who is not on the ground. */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMiningMixin {

	@Redirect(method = "getBlockBreakingSpeed", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/entity/player/PlayerEntity;isOnGround()Z"))
	private boolean constructionaddon$basketFloor(PlayerEntity player) {
		if (player.getVehicle() instanceof ConstructionMachineEntity machine
				&& machine.settings().machine() == MachineType.AERIAL_PLATFORM) {
			return true;
		}
		return player.isOnGround();
	}
}
