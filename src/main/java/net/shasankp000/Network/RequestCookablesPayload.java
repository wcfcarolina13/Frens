package net.shasankp000.Network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: request cookables list for the active bot. */
public record RequestCookablesPayload(String query) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "request_cookables");
    public static final CustomPayload.Id<RequestCookablesPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
    public static final PacketCodec<PacketByteBuf, RequestCookablesPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, RequestCookablesPayload::query, RequestCookablesPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
