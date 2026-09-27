package com.example.constructionaddon.work;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Where the next block of poured or dumped material goes. From the source point it falls to the
 * first support, spreads 4-way through free cells within the radius (stopped by walls), and runs
 * down into any hole it passes. Of all reachable cells, the lowest "height + distance x slope"
 * wins: slope 0 behaves like a liquid (concrete fills a form layer by layer, level on top); a
 * positive slope piles loose material into a mound, steeper the higher the value. */
public final class PourSpreader {

	private PourSpreader() {
	}

	/** Maximum cells examined per search - a safety cap for huge open areas. */
	private static final int MAX_NODES = 400;
	/** How far material may fall from the outlet, or down into a hole. */
	private static final int MAX_DROP = 8;

	/** The cell to fill next, or null if nothing within reach can take material. */
	public static BlockPos findTarget(World world, Vec3d outlet, int radius, float slope) {
		BlockPos start = BlockPos.ofFloored(outlet);
		// Outlet buried in something solid (chute pushed into a wall): look slightly higher.
		int lift = 0;
		while (!free(world, start) && lift < 3) {
			start = start.up();
			lift++;
		}
		if (!free(world, start)) {
			return null;
		}
		BlockPos landing = fall(world, start);
		if (landing == null) {
			return null;
		}

		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		Set<BlockPos> visited = new HashSet<>();
		queue.add(landing);
		visited.add(landing);
		int nodes = 0;
		while (!queue.isEmpty() && nodes < MAX_NODES) {
			BlockPos cell = queue.poll();
			nodes++;
			BlockPos candidate;
			boolean supported = !free(world, cell.down());
			if (supported) {
				candidate = cell;
			} else {
				// Runs down into the hole instead of spreading across its mouth.
				candidate = fall(world, cell);
				if (candidate == null) {
					continue;
				}
			}
			double score = candidate.getY() + Math.sqrt(horizontalDistSq(candidate, landing)) * slope;
			if (best == null || score < bestScore) {
				best = candidate;
				bestScore = score;
			}
			if (!supported) {
				continue;
			}
			for (Direction direction : Direction.Type.HORIZONTAL) {
				BlockPos next = cell.offset(direction);
				if (visited.contains(next) || horizontalDistSq(next, landing) > (double) radius * radius) {
					continue;
				}
				visited.add(next);
				if (free(world, next)) {
					queue.add(next);
				}
			}
		}
		return best;
	}

	private static BlockPos fall(World world, BlockPos from) {
		BlockPos pos = from;
		for (int i = 0; i < MAX_DROP; i++) {
			if (!free(world, pos.down())) {
				return pos;
			}
			pos = pos.down();
		}
		return null;
	}

	/** Air, water, plants... anything a pour can flow into and displace. */
	public static boolean free(World world, BlockPos pos) {
		if (world.isOutOfHeightLimit(pos)) {
			return false;
		}
		BlockState state = world.getBlockState(pos);
		return state.isAir() || state.isReplaceable();
	}

	private static double horizontalDistSq(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX();
		double dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}
}
