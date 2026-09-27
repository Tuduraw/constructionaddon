package com.example.constructionaddon.machine;

import com.example.constructionaddon.entity.ConstructionMachineEntity;
import com.example.tudursvehiclemod.asset.VehicleDefinition;
import com.example.constructionaddon.network.WorkAxis;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** The work function of one machine (excavator, crane, pile driver...), chosen by the vehicle
 * JSON's "construction.machine". All machines share one entity type; driving, fuel, seats,
 * inventory and destruction are the base mod's own.
 *
 * <p>State the client must see (joint positions, load level) goes through the entity's synced
 * channels, named per module by {@link #floatChannels()} and referenced by the JSON joints. */
public abstract class MachineModule {

	protected final ConstructionMachineEntity machine;

	/** A settings codec's all-defaults value (every field is optional). */
	public static <T> T defaults(Codec<T> codec) {
		return codec.parse(JsonOps.INSTANCE, new JsonObject()).result().orElseThrow();
	}

	protected MachineModule(ConstructionMachineEntity machine) {
		this.machine = machine;
	}

	public abstract MachineType type();

	/** Moves a float channel by input x speed, clamped to [min, max] (either order). Returns the
	 * change actually applied. */
	protected float nudge(int channel, float input, float speed, float min, float max) {
		if (input == 0f) {
			return 0f;
		}
		float value = this.machine.getFloatChannel(channel);
		float next = MathHelper.clamp(value + input * speed, Math.min(min, max), Math.max(min, max));
		this.machine.setFloatChannel(channel, next);
		return next - value;
	}

	/** Moves a float channel toward target at speed per tick; returns the new value. */
	protected float approach(int channel, float target, float speed) {
		float value = this.machine.getFloatChannel(channel);
		float next = value < target ? Math.min(target, value + speed) : Math.max(target, value - speed);
		this.machine.setFloatChannel(channel, next);
		return next;
	}

	/** Work swing from the swing keys: limit 0 = unlimited. Turns joint-seat riders and plays the
	 * swing sound through ConstructionMachineEntity#workSwung. */
	protected void workSwing(int channel, float speed, float limit) {
		float bound = limit > 0f ? limit : Float.MAX_VALUE;
		this.machine.workSwung(this.nudge(channel, this.machine.workAxis(WorkAxis.SWING), speed, -bound, bound));
	}

	/** Names of the synced float channels this machine drives, by index (at most
	 * ConstructionMachineEntity.FLOAT_CHANNELS). Joints refer to these names. */
	public abstract String[] floatChannels();

	/** True while the machine physically can't move (outriggers / jacks down). Work mode alone
	 * never blocks driving. When true, driving input is zeroed and the brake held. */
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
}
