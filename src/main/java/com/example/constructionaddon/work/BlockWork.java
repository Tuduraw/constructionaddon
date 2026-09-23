package com.example.constructionaddon.work;

import com.example.constructionaddon.ConstructionServerConfig;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Every block a machine breaks or places goes through here, so all machines respect the same
 * rules:
 *
 * <ul>
 *   <li>the server config's own master switch (allowTerrainEditing);</li>
 *   <li>the operating player's own right to modify blocks (adventure mode, spawn protection,
 *       world border - the same checks a player's own hand is subject to);</li>
 *   <li>Fabric's PlayerBlockBreakEvents.BEFORE, which land-claim/protection mods hook into, so a
 *       bucket can't reach into a claim its operator couldn't dig with a shovel.</li>
 * </ul>
 *
 * Breaking itself is World.breakBlock(pos, false, operator) - the same call the base mod's own
 * tank-type tree felling uses - with no drops: whatever the machine removes goes into its own
 * load instead (bucket, blade, nothing at all for a pile displacing soil). */
public final class BlockWork {

	private BlockWork() {
	}

	public static boolean canBreak(ServerWorld world, PlayerEntity operator, BlockPos pos, BlockState state) {
		if (!ConstructionServerConfig.get().allowTerrainEditing) {
			return false;
		}
		if (operator == null) {
			return ConstructionServerConfig.get().allowUnmannedTerrainEditing;
		}
		if (!operator.canModifyBlocks() || !world.canEntityModifyAt(operator, pos)) {
			return false;
		}
		return PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(world, operator, pos, state, world.getBlockEntity(pos));
	}

	public static boolean canPlace(ServerWorld world, PlayerEntity operator, BlockPos pos) {
		if (!ConstructionServerConfig.get().allowTerrainEditing) {
			return false;
		}
		if (world.isOutOfHeightLimit(pos)) {
			return false;
		}
		if (operator == null) {
			return ConstructionServerConfig.get().allowUnmannedTerrainEditing;
		}
		return operator.canModifyBlocks() && world.canEntityModifyAt(operator, pos);
	}

	/** Removes a block without drops. Returns false (and changes nothing) when not permitted. */
	public static boolean remove(ServerWorld world, PlayerEntity operator, BlockPos pos, Entity machine) {
		BlockState state = world.getBlockState(pos);
		if (state.isAir() || !canBreak(world, operator, pos, state)) {
			return false;
		}
		return world.breakBlock(pos, false, operator != null ? operator : machine);
	}

	/** Places a block's default state if the spot is air/replaceable and permitted. */
	public static boolean place(ServerWorld world, PlayerEntity operator, BlockPos pos, Block block) {
		BlockState existing = world.getBlockState(pos);
		if (!(existing.isAir() || existing.isReplaceable()) || !canPlace(world, operator, pos)) {
			return false;
		}
		BlockState state = block.getDefaultState();
		if (!world.setBlockState(pos, state)) {
			return false;
		}
		BlockSoundGroup sounds = state.getSoundGroup();
		world.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
				sounds.getPlaceSound(), SoundCategory.BLOCKS, (sounds.getVolume() + 1f) / 2f, sounds.getPitch() * 0.8f);
		return true;
	}

	/** Drops a block so it piles up naturally near pos rather than only stacking straight above
	 * it: spreads sideways and mounds up with roughly {@code slope}'s steepness (see
	 * PourSpreader's own doc - the same algorithm a mixer truck's pour uses, with a positive
	 * slope instead of pure liquid behaviour). Falls back to stacking directly above pos (the
	 * original, narrower search) if the spread search finds nothing, and to dropping the block as
	 * an item only if even that fails - so a full itemization is now a rare last resort rather
	 * than the common case once a small pile has already built up underneath. */
	public static void drop(ServerWorld world, PlayerEntity operator, BlockPos pos, Block block, int spreadRadius, float slope) {
		BlockPos target = PourSpreader.findTarget(world, Vec3d.ofCenter(pos), Math.max(0, spreadRadius), slope);
		if (target != null && canPlace(world, operator, target)) {
			FallingBlockEntity.spawnFromBlock(world, target, block.getDefaultState());
			return;
		}
		for (int dy = 0; dy <= 3; dy++) {
			BlockPos candidate = pos.up(dy);
			BlockState existing = world.getBlockState(candidate);
			if ((existing.isAir() || existing.isReplaceable()) && canPlace(world, operator, candidate)) {
				FallingBlockEntity.spawnFromBlock(world, candidate, block.getDefaultState());
				return;
			}
		}
		ItemScatterer.spawn(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(block));
	}

	/** Dust and a break sound, for work done without World.breakBlock()'s own effects. */
	public static void effects(ServerWorld world, BlockPos pos, BlockState state, int particles) {
		world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state),
				pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, particles, 0.4, 0.2, 0.4, 0.05);
		BlockSoundGroup sounds = state.getSoundGroup();
		world.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
				sounds.getBreakSound(), SoundCategory.BLOCKS, 0.8f, sounds.getPitch() * 0.7f);
	}
}
