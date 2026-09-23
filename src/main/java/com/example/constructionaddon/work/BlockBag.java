package com.example.constructionaddon.work;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** A loose load of blocks - what's sitting in an excavator bucket or piled against a dozer blade.
 * Kept as counts per block (default state), which is all a heap of earth needs. */
public final class BlockBag {

	private final LinkedHashMap<Block, Integer> counts = new LinkedHashMap<>();
	private int total;

	public int total() {
		return this.total;
	}

	public boolean isEmpty() {
		return this.total <= 0;
	}

	public void add(Block block) {
		this.counts.merge(normalize(block), 1, Integer::sum);
		this.total++;
	}

	/** Removes and returns one block, oldest kind first, or null if empty. */
	public Block takeOne() {
		Iterator<Map.Entry<Block, Integer>> it = this.counts.entrySet().iterator();
		if (!it.hasNext()) {
			return null;
		}
		Map.Entry<Block, Integer> entry = it.next();
		Block block = entry.getKey();
		if (entry.getValue() <= 1) {
			it.remove();
		} else {
			entry.setValue(entry.getValue() - 1);
		}
		this.total--;
		return block;
	}

	/** Puts a block taken with takeOne() back (placement turned out to be impossible). */
	public void putBack(Block block) {
		this.counts.merge(block, 1, Integer::sum);
		this.total++;
	}

	public void clear() {
		this.counts.clear();
		this.total = 0;
	}

	/** "minecraft:dirt=3;minecraft:sand=1" */
	public String encode() {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Block, Integer> entry : this.counts.entrySet()) {
			if (!sb.isEmpty()) {
				sb.append(';');
			}
			sb.append(Registries.BLOCK.getId(entry.getKey())).append('=').append(entry.getValue());
		}
		return sb.toString();
	}

	public void decode(String encoded) {
		this.clear();
		if (encoded == null || encoded.isEmpty()) {
			return;
		}
		for (String part : encoded.split(";")) {
			int eq = part.lastIndexOf('=');
			if (eq <= 0) {
				continue;
			}
			Identifier id = Identifier.tryParse(part.substring(0, eq));
			int count;
			try {
				count = Integer.parseInt(part.substring(eq + 1));
			} catch (NumberFormatException e) {
				continue;
			}
			if (id == null || count <= 0) {
				continue;
			}
			Registries.BLOCK.getOptionalValue(id).ifPresent(block -> {
				this.counts.merge(block, count, Integer::sum);
				this.total += count;
			});
		}
	}

	/** Dug-up turf and trodden ground come out as plain earth, the way it does when a player digs
	 * it without Silk Touch - otherwise a bucket could relocate grass blocks, farmland and paths. */
	public static Block normalize(Block block) {
		if (block == Blocks.GRASS_BLOCK || block == Blocks.PODZOL || block == Blocks.MYCELIUM
				|| block == Blocks.DIRT_PATH || block == Blocks.FARMLAND || block == Blocks.ROOTED_DIRT) {
			return Blocks.DIRT;
		}
		return block;
	}
}
