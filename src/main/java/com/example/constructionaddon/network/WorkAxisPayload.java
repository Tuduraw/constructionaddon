package com.example.constructionaddon.network;

import com.example.constructionaddon.ConstructionAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: every WorkAxis value (-1/0/+1) packed 2 bits each into one int (see
 * WorkAxis#pack). All axes are THIS ADDON'S OWN key bindings - separate from driving (WASD) and
 * from every base mod binding, even where they default to the same physical keys. Sent only
 * when the mask changes. */
public record WorkAxisPayload(int mask) implements CustomPayload {

	public static final CustomPayload.Id<WorkAxisPayload> ID =
			new CustomPayload.Id<>(Identifier.of(ConstructionAddon.MOD_ID, "work_axis"));

	public static final PacketCodec<RegistryByteBuf, WorkAxisPayload> CODEC =
			PacketCodec.tuple(PacketCodecs.VAR_INT, WorkAxisPayload::mask, WorkAxisPayload::new);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
