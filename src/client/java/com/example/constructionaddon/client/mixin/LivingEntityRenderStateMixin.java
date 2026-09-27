package com.example.constructionaddon.client.mixin;

import com.example.constructionaddon.client.ConstructionSeatRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LivingEntityRenderState.class)
public class LivingEntityRenderStateMixin implements ConstructionSeatRenderState {

	@Unique
	private float constructionaddon$seatYawOffset = Float.NaN;

	@Override
	public float constructionaddon$getSeatYawOffset() {
		return this.constructionaddon$seatYawOffset;
	}

	@Override
	public void constructionaddon$setSeatYawOffset(float value) {
		this.constructionaddon$seatYawOffset = value;
	}
}
