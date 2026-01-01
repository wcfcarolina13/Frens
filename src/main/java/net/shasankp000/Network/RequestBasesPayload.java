package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: request bases list; simple string payload (query). */
public record RequestBasesPayload(String query) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "request_bases");
    public static final CustomPayload.Id<RequestBasesPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, RequestBasesPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, RequestBasesPayload::query, RequestBasesPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
