package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: save custom provider information */
public record SaveCustomProviderPayload(String apiKey, String url) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "save_custom_provider");
    public static final CustomPayload.Id<SaveCustomProviderPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    // CODEC left as null stub to satisfy compile; implement proper codec when restoring networking logic.
    public static final PacketCodec<PacketByteBuf, SaveCustomProviderPayload> CODEC = null;

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
