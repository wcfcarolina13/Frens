package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.shasankp000.network.StringCodec;

/** Client -> Server: request huntable list. */
public record RequestHuntablesPayload(String query) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "request_huntables");
    public static final CustomPayload.Id<RequestHuntablesPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
    public static final PacketCodec<PacketByteBuf, RequestHuntablesPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, RequestHuntablesPayload::query, RequestHuntablesPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
