package com.example.constructionaddon.machine;

import com.example.constructionaddon.entity.ConstructionMachineEntity;
import net.minecraft.util.math.Vec3d;

/** A machine module a tractor can couple to: the low-bed trailer, or a trailer-mounted machine
 * (a towable aerial platform). The towing itself is {@link TowLink}. */
public interface Towable {

	TowLink towLink();

	/** Whether it can be coupled right now (e.g. not while its outriggers are down). */
	default boolean canCouple() {
		return true;
	}

	default boolean isCoupled() {
		return this.towLink().isCoupled();
	}

	default boolean isCoupledTo(ConstructionMachineEntity tractor) {
		return this.towLink().isCoupledTo(tractor);
	}

	default void coupleTo(ConstructionMachineEntity tractor) {
		this.towLink().coupleTo(tractor);
	}

	default void uncouple() {
		this.towLink().uncouple();
	}

	default Vec3d kingpinWorld() {
		return this.towLink().kingpinWorld();
	}
}
