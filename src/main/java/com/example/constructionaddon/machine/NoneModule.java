package com.example.constructionaddon.machine;

import com.example.constructionaddon.entity.ConstructionMachineEntity;

/** A vehicle of this entity type whose JSON names no (or an unknown) machine - it just drives. */
public final class NoneModule extends MachineModule {

	private static final String[] CHANNELS = {};

	public NoneModule(ConstructionMachineEntity machine) {
		super(machine);
	}

	@Override
	public MachineType type() {
		return MachineType.NONE;
	}

	@Override
	public String[] floatChannels() {
		return CHANNELS;
	}
}
