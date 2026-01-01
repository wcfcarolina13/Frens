package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: save the entire config JSON */
public record SaveConfigPayload(String configJson) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "save_config");
    public static final CustomPayload.Id<SaveConfigPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, SaveConfigPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, SaveConfigPayload::configJson, SaveConfigPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
