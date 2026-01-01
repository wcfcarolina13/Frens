package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: set/add a base at the player's current position with the provided label. */
public record BaseSetPayload(String label) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "base_set");
    public static final CustomPayload.Id<BaseSetPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, BaseSetPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, BaseSetPayload::label, BaseSetPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
