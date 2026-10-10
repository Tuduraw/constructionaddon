package com.example.constructionaddon.client.mixin;

import com.example.constructionaddon.client.ConstructionAddonClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Holds back attacking, breaking blocks and using items while the operator of a rotary pile driver
 * works its head with the mouse buttons (see ConstructionAddonClient#suppressVanillaMouse). */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMouseMixin {

	@Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
	private void constructionaddon$holdAttack(CallbackInfoReturnable<Boolean> cir) {
		if (ConstructionAddonClient.suppressVanillaMouse()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
	private void constructionaddon$holdUse(CallbackInfo ci) {
		if (ConstructionAddonClient.suppressVanillaMouse()) {
			ci.cancel();
		}
	}

	@Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
	private void constructionaddon$holdBreaking(boolean breaking, CallbackInfo ci) {
		if (ConstructionAddonClient.suppressVanillaMouse()) {
			ci.cancel();
		}
	}
}
