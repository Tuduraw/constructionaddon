package com.example.constructionaddon.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Piles a rotary pile driver has let go of, per dimension: one per block column. A pile's blocks
 * are ordinary world blocks; this only remembers which column of them is a pile (bottom..top, the
 * ground level it was driven from), so a machine can grip it again - to drive it further, pull it
 * out, or join a new pile onto its head. */
public final class PileRegistry extends PersistentState {

	/** bottom / top: the lowest and highest pile block. surface: the top ground block of the column
	 * (pile blocks above it stand out of the ground). */
	public record Pile(int x, int z, int bottom, int top, String block, int surface) {
		static final Codec<Pile> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.INT.fieldOf("x").forGetter(Pile::x),
				Codec.INT.fieldOf("z").forGetter(Pile::z),
				Codec.INT.fieldOf("bottom").forGetter(Pile::bottom),
				Codec.INT.fieldOf("top").forGetter(Pile::top),
				Codec.STRING.fieldOf("block").forGetter(Pile::block),
				Codec.INT.fieldOf("surface").forGetter(Pile::surface)
		).apply(i, Pile::new));

		public int length() {
			return this.top - this.bottom + 1;
		}

		public Block blockType() {
			Identifier id = Identifier.tryParse(this.block);
			return id == null ? null : Registries.BLOCK.getOptionalValue(id).orElse(null);
		}
	}

	private static final Codec<PileRegistry> CODEC = Pile.CODEC.listOf()
			.xmap(PileRegistry::new, registry -> new ArrayList<>(registry.piles.values()));

	private static final PersistentStateType<PileRegistry> TYPE =
			new PersistentStateType<>("constructionaddon_piles", PileRegistry::new, CODEC, null);

	private final Map<Long, Pile> piles = new HashMap<>();

	public PileRegistry() {
	}

	private PileRegistry(List<Pile> list) {
		for (Pile pile : list) {
			this.piles.put(key(pile.x(), pile.z()), pile);
		}
	}

	public static PileRegistry get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(TYPE);
	}

	private static long key(int x, int z) {
		return BlockPos.asLong(x, 0, z);
	}

	/** The pile in this column, checked against the world: its top is trimmed down to the highest
	 * block that is still there (someone may have broken some), and an entry with nothing left is
	 * dropped. Null if none. */
	public Pile at(ServerWorld world, int x, int z) {
		Pile pile = this.piles.get(key(x, z));
		if (pile == null) {
			return null;
		}
		Block block = pile.blockType();
		int top = pile.top();
		while (block != null && top >= pile.bottom() && world.getBlockState(new BlockPos(x, top, z)).getBlock() != block) {
			top--;
		}
		if (block == null || top < pile.bottom()) {
			this.remove(x, z);
			return null;
		}
		if (top != pile.top()) {
			pile = new Pile(x, z, pile.bottom(), top, pile.block(), pile.surface());
			this.put(pile);
		}
		return pile;
	}

	public void put(Pile pile) {
		this.piles.put(key(pile.x(), pile.z()), pile);
		this.markDirty();
	}

	public void remove(int x, int z) {
		if (this.piles.remove(key(x, z)) != null) {
			this.markDirty();
		}
	}
}
