package net.shasankp000.Network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: cookables list (JSON array of identifiers). */
public record CookablesPayload(String cookablesJson) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "cookables");
    public static final CustomPayload.Id<CookablesPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
    public static final PacketCodec<PacketByteBuf, CookablesPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, CookablesPayload::cookablesJson, CookablesPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
