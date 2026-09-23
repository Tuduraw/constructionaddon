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

/** Corrects a construction machine operator's rendered BODY orientation for a joint-mounted seat
 * (a cab that swings with an excavator's upper structure or a crane's slewing platform).
 *
 * <p>The base mod's own client mixin (LivingEntityRendererMixin there) makes any rider of ANY
 * AbstractVehicleEntity render bolt-rigid to that vehicle's own hull yaw
 * (tudursvehiclemod$overrideBodyYaw, a @ModifyVariable at that mixin's default priority 1000) -
 * exactly correct for an ordinary seat, but for a seat that itself rides a rotating joint, the
 * rendered body should follow the JOINT's own current heading instead, not the bare hull's -
 * otherwise a player only sees their own view (and, previously, nothing else) turn with the cab
 * while their own third-person body stays facing the hull's fixed direction.
 *
 * <p>Higher priority than that mixin's default (1000) so this one's @ModifyVariable runs strictly
 * AFTER it on the same chain, receiving the hull yaw it already computed as this method's own
 * input and adding this seat's own extra swing on top - refining that result, never racing or
 * overriding it outright. Stands aside entirely (changes nothing) for a seat that isn't on a
 * joint, an entity not seated in one of this addon's machines, or any other vehicle - the base
 * mod's own hull-yaw behavior is exactly right for all of those. */
@Mixin(value = LivingEntityRenderer.class, priority = 2000)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState, M extends EntityModel<? super S>> {

	@Inject(method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
	private void constructionaddon$captureSeatYaw(T entity, S state, float tickProgress, CallbackInfo ci) {
		if (!((Object) state instanceof ConstructionSeatRenderState seatState)) {
			return;
		}
		if (entity.getVehicle() instanceof ConstructionMachineEntity machine) {
			Float offset = machine.renderedSeatYawOffset(entity, tickProgress);
			if (offset != null) {
				seatState.constructionaddon$setSeatYawOffset(offset);
				return;
			}
		}
		seatState.constructionaddon$clearSeatYawOffset();
	}

	@ModifyVariable(method = "setupTransforms(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;FF)V",
			at = @At("HEAD"), ordinal = 0, argsOnly = true)
	private float constructionaddon$applySeatYawOffset(float bodyYaw, S state, MatrixStack matrices, float baseHeight) {
		if ((Object) state instanceof ConstructionSeatRenderState seatState && seatState.constructionaddon$hasSeatYaw()) {
			// Turning the seat left (a positive offset, this addon's own convention - see
			// ConstructionMachineEntity#renderedSeatYawOffset()) must REDUCE bodyYaw here, exactly
			// like passenger.setYaw(passenger.getYaw() - degreesLeft) elsewhere in this addon:
			// Minecraft's own yaw grows clockwise (turning right), not counter-clockwise.
			return bodyYaw - seatState.constructionaddon$getSeatYawOffset();
		}
		return bodyYaw;
	}
}
