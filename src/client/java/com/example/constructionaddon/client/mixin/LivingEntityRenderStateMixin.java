package com.example.constructionaddon.client.mixin;

import com.example.constructionaddon.client.ConstructionSeatRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** See ConstructionSeatRenderState's own doc for why this data lives here. */
@Mixin(LivingEntityRenderState.class)
public class LivingEntityRenderStateMixin implements ConstructionSeatRenderState {

	@Unique
	private boolean constructionaddon$hasSeatYaw = false;
	@Unique
	private float constructionaddon$seatYawOffset = 0f;

	@Override
	public boolean constructionaddon$hasSeatYaw() {
		return this.constructionaddon$hasSeatYaw;
	}

	@Override
	public float constructionaddon$getSeatYawOffset() {
		return this.constructionaddon$seatYawOffset;
	}

	@Override
	public void constructionaddon$setSeatYawOffset(float value) {
		this.constructionaddon$seatYawOffset = value;
		this.constructionaddon$hasSeatYaw = true;
	}

	@Override
	public void constructionaddon$clearSeatYawOffset() {
		this.constructionaddon$hasSeatYaw = false;
	}
}
