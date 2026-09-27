package com.example.constructionaddon.work;

import com.example.constructionaddon.ConstructionServerConfig;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
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

/** Every block a machine breaks or places goes through here, subject to the same rules: the
 * server config's allowTerrainEditing, the operating player's own right to edit (adventure mode,
 * spawn protection, world border) and, for breaking, Fabric's PlayerBlockBreakEvents.BEFORE
 * (used by land-claim mods). With no operator nothing is edited. Removed blocks never drop -
 * they go into the machine's own load. */
public final class BlockWork {

	private BlockWork() {
	}

	private static boolean mayEdit(ServerWorld world, PlayerEntity operator, BlockPos pos) {
		return ConstructionServerConfig.get().allowTerrainEditing && operator != null
				&& !world.isOutOfHeightLimit(pos) && operator.canModifyBlocks() && world.canEntityModifyAt(operator, pos);
	}

	public static boolean canBreak(ServerWorld world, PlayerEntity operator, BlockPos pos, BlockState state) {
		return mayEdit(world, operator, pos)
				&& PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(world, operator, pos, state, world.getBlockEntity(pos));
	}

	public static boolean canPlace(ServerWorld world, PlayerEntity operator, BlockPos pos) {
		return mayEdit(world, operator, pos);
	}

	/** Removes a block without drops. False (nothing changed) when not permitted. */
	public static boolean remove(ServerWorld world, PlayerEntity operator, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return !state.isAir() && canBreak(world, operator, pos, state) && world.breakBlock(pos, false, operator);
	}

	/** Places a block's default state on an air/replaceable spot, if permitted. */
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

	/** Drops a block as a falling block where it settles into a mound around pos (PourSpreader
	 * with a positive slope); as an item only if there is nowhere to put it. */
	public static void drop(ServerWorld world, PlayerEntity operator, BlockPos pos, Block block, int spreadRadius, float slope) {
		BlockPos target = PourSpreader.findTarget(world, Vec3d.ofCenter(pos), Math.max(0, spreadRadius), slope);
		if (target != null && canPlace(world, operator, target)) {
			FallingBlockEntity.spawnFromBlock(world, target, block.getDefaultState());
		} else {
			ItemScatterer.spawn(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(block));
		}
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
