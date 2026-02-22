package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: request current companion quest stage/permanent state (no dialogue lines). */
public record CompanionQuestStateRequestPayload(String botAlias) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "companion_quest_state_request");
    public static final CustomPayload.Id<CompanionQuestStateRequestPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, CompanionQuestStateRequestPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, CompanionQuestStateRequestPayload::botAlias, CompanionQuestStateRequestPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
