package com.example.constructionaddon.entity;

import com.example.constructionaddon.machine.CraneModule;
import com.example.tudursvehiclemod.entity.AircraftEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.UUID;

/** The invisible hook a crane hangs its load from. The load RIDES this entity, so Minecraft itself
 * places it under the hook every tick, on the server and on every client, whatever the load's own
 * movement code does in between (a vehicle's flight physics, a mob's AI, an item's drift). Teleporting
 * the load each tick instead fought that code and showed up as sinking, nosing down and snapping back.
 *
 * <p>The crane that owns the hook moves it (see {@link #tick()}); a hook whose crane is gone for a
 * couple of seconds lets its load go and removes itself, so nothing is ever left hanging. The hook is
 * saved with its load as a passenger like any vehicle, so a held load survives the chunk being saved. */
public final class CraneHookEntity extends Entity {

	private static final int LOST_OWNER_TICKS = 40;

	private UUID owner;
	private int ownerMissingTicks;

	public CraneHookEntity(EntityType<? extends CraneHookEntity> type, World world) {
		super(type, world);
		this.noClip = true;
		this.setNoGravity(true);
	}

	public void setOwner(UUID owner) {
		this.owner = owner;
	}

	public UUID owner() {
		return this.owner;
	}

	public Entity load() {
		return this.getFirstPassenger();
	}

	@Override
	public void tick() {
		super.tick();
		this.setVelocity(Vec3d.ZERO);
		if (!(this.getEntityWorld() instanceof ServerWorld world)) {
			return;
		}
		Entity ownerEntity = this.owner == null ? null : world.getEntity(this.owner);
		CraneModule crane = ownerEntity instanceof ConstructionMachineEntity machine && !machine.isRemoved()
				&& machine.module() instanceof CraneModule module && module.isHook(this) ? module : null;
		if (crane == null) {
			if (++this.ownerMissingTicks >= LOST_OWNER_TICKS || !this.hasPassengers()) {
				this.removeAllPassengers();
				this.discard();
			}
			return;
		}
		this.ownerMissingTicks = 0;
		Vec3d hook = crane.hookPosition();
		this.setPosition(hook.x, hook.y, hook.z);
		Entity load = this.load();
		if (load instanceof AircraftEntity aircraft) {
			// Held by something external: the aircraft must not decide it is stalling (that noses it
			// down and makes it sink while it hangs) - the same call the base mod uses for an aircraft
			// carried on a carrier deck.
			aircraft.tudursvehiclemod$suppressStallWhileCarried();
		}
	}

	@Override
	protected void updatePassengerPosition(Entity passenger, PositionUpdater positionUpdater) {
		// The load hangs with its top just under the hook block.
		double y = this.getY() - passenger.getHeight() - 0.1;
		// With the hook lowered into the ground the load rests on it instead of being pulled under.
		if (!this.getEntityWorld().isBlockSpaceEmpty(passenger, passenger.getDimensions(passenger.getPose())
				.getBoxAt(this.getX(), y, this.getZ()).contract(1.0E-4))) {
			double top = Math.min(this.getY(), y + passenger.getHeight() + 2.0);
			for (double up = y; up <= top; up += 0.0625) {
				y = up;
				if (this.getEntityWorld().isBlockSpaceEmpty(passenger, passenger.getDimensions(passenger.getPose())
						.getBoxAt(this.getX(), up, this.getZ()).contract(1.0E-4))) {
					break;
				}
			}
		}
		positionUpdater.accept(passenger, this.getX(), y, this.getZ());
	}

	@Override
	public Vec3d updatePassengerForDismount(LivingEntity passenger) {
		// Let go where it hangs (it then falls on its own), not on top of the hook.
		return passenger.getEntityPos();
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return !this.hasPassengers();
	}

	@Override
	public boolean damage(ServerWorld world, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean isAttackable() {
		return false;
	}

	@Override
	public boolean canHit() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean isCollidable(Entity other) {
		return false;
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
	}

	@Override
	protected void readCustomData(ReadView view) {
		String id = view.getString("CraneOwner", "");
		try {
			this.owner = id.isEmpty() ? null : UUID.fromString(id);
		} catch (IllegalArgumentException e) {
			this.owner = null;
		}
	}

	@Override
	protected void writeCustomData(WriteView view) {
		view.putString("CraneOwner", this.owner == null ? "" : this.owner.toString());
	}
}
