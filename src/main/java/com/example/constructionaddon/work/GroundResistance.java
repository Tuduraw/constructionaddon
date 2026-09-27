package com.example.constructionaddon.work;

import com.example.constructionaddon.asset.ResistanceProfile;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** How hard a block is to push a tool into - shared by pile driving, digging and blade cutting
 * so they all agree. The resistance factor is 1 + hardness x hardness_scale; unbreakable blocks,
 * block entities and anything at or above refusal_hardness refuse. Machines add their own stop
 * conditions (blow limit, minimum rpm) on top. */
public final class GroundResistance {

	private GroundResistance() {
	}

		/** passable: air/fluid/plant, displaced freely (factor 0). refused: the tool can't enter. */
public record Result(boolean passable, boolean refused, float hardness, float factor) {
		static final Result PASSABLE = new Result(true, false, 0f, 0f);
	}

	public static Result evaluate(World world, BlockPos pos, BlockState state, ResistanceProfile profile) {
		if (state.isAir() || state.isReplaceable()) {
			return Result.PASSABLE;
		}
		// Never grind up a chest, furnace, spawner... - its contents would be lost silently.
		if (state.hasBlockEntity()) {
			return new Result(false, true, -1f, Float.POSITIVE_INFINITY);
		}
		float hardness = state.getHardness(world, pos);
		Float override = profile.overrides().get(Registries.BLOCK.getId(state.getBlock()).toString());
		if (override != null) {
			hardness = override;
		}
		if (hardness < 0f || hardness >= profile.refusalHardness()) {
			return new Result(false, true, hardness, Float.POSITIVE_INFINITY);
		}
		float factor = 1f + hardness * Math.max(0f, profile.hardnessScale());
		return new Result(false, false, hardness, factor);
	}
}
