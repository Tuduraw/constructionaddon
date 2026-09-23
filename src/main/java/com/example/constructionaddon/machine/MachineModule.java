package com.example.constructionaddon.machine;

import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.tudursvehiclemod.asset.VehicleDefinition;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/** What makes one machine an excavator, a crane, a pile driver...
 *
 * <p>Every machine is the same entity type (ConstructionMachineEntity, a CarEntity) - driving,
 * wheels/tracks, fuel, HUD, inventory, seats, destruction all come from the base mod unchanged.
 * The module chosen by the vehicle JSON's "construction.machine" adds the work function on top.
 * Keeping it composition rather than one subclass per machine means one entity type, one
 * converter category and one set of spawner items cover every machine, and an addon pack can
 * build a new machine out of any module with nothing but JSON and a model.
 *
 * <p>State the client must SEE (joint positions, load level) goes through the entity's generic
 * synced channels - named per module by {@link #floatChannels()} and referenced by name from the
 * JSON's joints. Everything else stays in plain fields on the server. */
public abstract class MachineModule {

	protected final ConstructionMachineEntity machine;

	protected MachineModule(ConstructionMachineEntity machine) {
		this.machine = machine;
	}

	public abstract MachineType type();

	/** Names of the synced float channels this machine drives, by index (at most
	 * ConstructionMachineEntity.FLOAT_CHANNELS). Joints refer to these names. */
	public abstract String[] floatChannels();

	/** True while this machine cannot physically move - default false, since driving (WASD) and
	 * operating the machine (its own dedicated axes and work keys) are otherwise independent and
	 * run at the same time. Only a genuine physical constraint should return true here (a
	 * crane's outriggers actually down, a pile driver's mast actually up) - never work mode
	 * itself, which by itself never blocks driving. When true, the entity feeds the base mod's
	 * driving code zero input and holds the brake. */
	public boolean suppressesDriving() {
		return false;
	}

	/** True when this module moves the vehicle itself instead of CarEntity's driving physics
	 * (a trailer being towed). */
	public boolean overridesMovement() {
		return false;
	}

	public void updateMovement(VehicleDefinition def) {
	}

	/** Every tick, both sides, after the base mod's own tick. */
	public void tick() {
	}

	/** Every tick, server only, while the machine is intact. {@code operator} is the seated driver,
	 * or null. */
	public void serverTick(ServerWorld world, ServerPlayerEntity operator) {
	}

	/** Once per world tick after every entity has ticked - only for modules that asked for it via
	 * {@link #wantsEndWorldTick()} (a crane positioning its load after that load's own physics). */
	public void endWorldTick(ServerWorld world) {
	}

	public boolean wantsEndWorldTick() {
		return false;
	}

	/** Action-bar status for the operator, or null for none. Server side. */
	public Text status() {
		return null;
	}

	public void writeData(WriteView view) {
	}

	public void readData(ReadView view) {
	}

	/** Multiplies the base mod's effective max speed (towing a trailer, pushing a full blade). */
	public float maxSpeedFactor() {
		return 1f;
	}

	/** Lets a module take over the hatch key (a tractor opening its trailer's ramps). */
	public boolean handleHatchToggle() {
		return false;
	}

	/** Client-side rendering override of the vehicle's absolute position, or null. */
	public Vec3d renderPositionOverride(float tickDelta) {
		return null;
	}

	/** Client-side rendering override of the vehicle's yaw, or null. */
	public Float renderYawOverride(float tickDelta) {
		return null;
	}

	public void onRemoved() {
	}
}
