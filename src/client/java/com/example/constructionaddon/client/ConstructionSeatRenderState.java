package com.example.constructionaddon.client;

/** Added to LivingEntityRenderState by LivingEntityRenderStateMixin, carrying the joint-seat yaw
 * from updateRenderState() (has the entity) to setupTransforms() (has only the state). */
public interface ConstructionSeatRenderState {

	/** Degrees, positive = left; NaN when the entity isn't on a joint seat. */
	float constructionaddon$getSeatYawOffset();

	void constructionaddon$setSeatYawOffset(float value);
}
