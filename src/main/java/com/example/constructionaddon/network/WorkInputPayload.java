package com.example.constructionaddon.network;

import com.example.constructionaddon.ConstructionAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: whether the operator is holding the primary / secondary work keys. Held
 * states, sent only when they change (see ConstructionAddonClient). */
public record WorkInputPayload(boolean primary, boolean secondary) implements CustomPayload {

	public static final CustomPayload.Id<WorkInputPayload> ID =
			new CustomPayload.Id<>(Identifier.of(ConstructionAddon.MOD_ID, "work_input"));

	public static final PacketCodec<RegistryByteBuf, WorkInputPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.BOOLEAN, WorkInputPayload::primary,
			PacketCodecs.BOOLEAN, WorkInputPayload::secondary,
			WorkInputPayload::new
	);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
