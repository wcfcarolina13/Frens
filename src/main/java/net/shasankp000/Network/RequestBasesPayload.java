package net.shasankp000.Network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: request the current list of saved bases for this world. */
public record RequestBasesPayload(String request) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "bases_request");
    public static final CustomPayload.Id<RequestBasesPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, RequestBasesPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, RequestBasesPayload::request, RequestBasesPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
