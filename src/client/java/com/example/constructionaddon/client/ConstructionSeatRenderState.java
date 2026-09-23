package com.example.constructionaddon.client;

/** Added to every LivingEntityRenderState by LivingEntityRenderStateMixin, so
 * LivingEntityRendererMixin can carry a value captured during updateRenderState() (which has the
 * entity) forward to setupTransforms() (which only has the render state). */
public interface ConstructionSeatRenderState {

	boolean constructionaddon$hasSeatYaw();

	float constructionaddon$getSeatYawOffset();

	void constructionaddon$setSeatYawOffset(float value);

	void constructionaddon$clearSeatYawOffset();
}
