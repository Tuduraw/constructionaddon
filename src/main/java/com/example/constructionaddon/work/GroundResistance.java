package com.example.constructionaddon.work;

import com.example.constructionaddon.asset.ResistanceProfile;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** The one place that decides how hard a block is to push a tool into - shared by every machine
 * that works the ground, so a pile, a bucket and a blade all agree on what "hard" means:
 *
 * <ul>
 *   <li>hammer pile driving: blows needed per block = base blows x {@link Result#factor()};</li>
 *   <li>rotary pile driving: time per block = base time x factor, rotation speed = base rpm / factor;</li>
 *   <li>excavator digging: ticks per block = base ticks x factor;</li>
 *   <li>bulldozer cutting: the drag slowing the machine grows with factor.</li>
 * </ul>
 *
 * <p>Each machine applies its OWN extra stop condition on top of {@link Result#refused()} (a hammer
 * also refuses when the blows needed exceed its limit, a rotary head when its rotation would
 * stall below its minimum), but the hardness itself, the unbreakable / block-entity refusal and
 * the per-machine overrides always come from here. */
public final class GroundResistance {

	private GroundResistance() {
	}

	/** Outcome for one block.
	 *
	 * @param passable air, fluids, plants and other replaceable blocks - no resistance, simply
	 *                 displaced (factor 0)
	 * @param refused  the tool cannot enter this block at all
	 * @param hardness the effective hardness used (after overrides); negative when unbreakable
	 * @param factor   1 + hardness x hardness_scale (0 when passable, infinite when refused) */
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
