package com.example.constructionaddon.network;

import com.example.constructionaddon.ConstructionAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: which work buttons (see WorkButton) the operator is holding, one bit each. Held
 * states, sent only when they change (see ConstructionAddonClient). */
public record WorkInputPayload(int buttons) implements CustomPayload {

	public static final CustomPayload.Id<WorkInputPayload> ID =
			new CustomPayload.Id<>(Identifier.of(ConstructionAddon.MOD_ID, "work_input"));

	public static final PacketCodec<RegistryByteBuf, WorkInputPayload> CODEC =
			PacketCodec.tuple(PacketCodecs.VAR_INT, WorkInputPayload::buttons, WorkInputPayload::new);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
