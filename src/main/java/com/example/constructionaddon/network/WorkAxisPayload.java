package com.example.constructionaddon.network;

import com.example.constructionaddon.ConstructionAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: the operator's dedicated work axes - default Up/Down for vertical, Left/
 * Right for horizontal, and , / . for a third ("arm") axis used only by machines with more than
 * two independent joints to drive at once (a backhoe's arm, alongside boom on vertical and swing
 * on horizontal). All three are THIS ADDON'S OWN key bindings, entirely separate from the base
 * mod's own key bindings (including its arrow-key level ascend/descend feature, which the
 * vertical axis defaults to the same physical keys as but never reads from or writes to) and from
 * driving (WASD). Reassigning any of this addon's own bindings in the controls menu therefore
 * never affects, or is affected by, any other binding - direct or indirect. */
public record WorkAxisPayload(float vertical, float horizontal, float arm) implements CustomPayload {

	public static final CustomPayload.Id<WorkAxisPayload> ID =
			new CustomPayload.Id<>(Identifier.of(ConstructionAddon.MOD_ID, "work_axis"));

	public static final PacketCodec<RegistryByteBuf, WorkAxisPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.FLOAT, WorkAxisPayload::vertical,
			PacketCodecs.FLOAT, WorkAxisPayload::horizontal,
			PacketCodecs.FLOAT, WorkAxisPayload::arm,
			WorkAxisPayload::new
	);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
