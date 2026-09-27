package com.example.constructionaddon.client.mixin;

import com.example.constructionaddon.client.ConstructionSeatRenderState;
import com.example.constructionaddon.entity.ConstructionMachineEntity;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Turns the third-person body of a rider on a joint-mounted seat (a slewing cab) with that joint.
 * The base mod's own mixin renders every vehicle rider's body at the hull's yaw; this one runs
 * after it (higher priority, same @ModifyVariable chain) and adds the seat's extra swing. Riders
 * of ordinary seats and other vehicles are left untouched. */
@Mixin(value = LivingEntityRenderer.class, priority = 2000)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState, M extends EntityModel<? super S>> {

	@Inject(method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
	private void constructionaddon$captureSeatYaw(T entity, S state, float tickProgress, CallbackInfo ci) {
		if ((Object) state instanceof ConstructionSeatRenderState seatState) {
			Float offset = entity.getVehicle() instanceof ConstructionMachineEntity machine
					? machine.renderedSeatYawOffset(entity, tickProgress) : null;
			seatState.constructionaddon$setSeatYawOffset(offset != null ? offset : Float.NaN);
		}
	}

	@ModifyVariable(method = "setupTransforms(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;FF)V",
			at = @At("HEAD"), ordinal = 0, argsOnly = true)
	private float constructionaddon$applySeatYawOffset(float bodyYaw, S state, MatrixStack matrices, float baseHeight) {
		if ((Object) state instanceof ConstructionSeatRenderState seatState
				&& !Float.isNaN(seatState.constructionaddon$getSeatYawOffset())) {
			// Offset is positive = left; Minecraft yaw grows clockwise.
			return bodyYaw - seatState.constructionaddon$getSeatYawOffset();
		}
		return bodyYaw;
	}
}
