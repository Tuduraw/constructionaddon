package com.example.constructionaddon.machine;

import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.tudursvehiclemod.asset.VehicleDefinition;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.Vec3d;

/** Towed trailer. Loading uses the base mod's runways (deck + hatch-gated ramp in the JSON). Coupled,
 * it follows the tractor (see {@link TowLink}); uncoupled, it is an ordinary unmanned CarEntity. */
public final class TrailerModule extends MachineModule implements Towable {

	private static final String[] CHANNELS = {};
	/** Int channel 0: the tractor's network id + 1 (0 = not coupled). */
	private static final int TRACTOR_ID = 0;

	private final TowLink link;

	public TrailerModule(ConstructionMachineEntity machine) {
		super(machine);
		this.link = new TowLink(machine, TRACTOR_ID, this::settings);
	}

	@Override
	public MachineType type() {
		return MachineType.TRAILER;
	}

	@Override
	public String[] floatChannels() {
		return CHANNELS;
	}

	private TowLink.Settings settings() {
		return this.machine.settings().machineSettings(TowLink.Settings.class, TowLink.Settings.DEFAULT);
	}

	@Override
	public TowLink towLink() {
		return this.link;
	}

	@Override
	public void serverTick(ServerWorld world, ServerPlayerEntity operator) {
		// (Runs only while intact; a destroyed trailer is uncoupled in tick().)
		this.link.serverTick(world);
	}

	@Override
	public void tick() {
		this.link.tick();
	}

	@Override
	public boolean overridesMovement() {
		return true;
	}

	@Override
	public void updateMovement(VehicleDefinition def) {
		if (!this.link.updateMovement(def)) {
			this.machine.runCarMovement(def);
		}
	}

	@Override
	public Vec3d renderPositionOverride(float tickDelta) {
		return this.link.renderPositionOverride(tickDelta);
	}

	@Override
	public Float renderYawOverride(float tickDelta) {
		return this.link.renderYawOverride(tickDelta);
	}

	@Override
	public void writeData(WriteView view) {
		this.link.writeData(view);
	}

	@Override
	public void readData(ReadView view) {
		this.link.readData(view);
	}
}
