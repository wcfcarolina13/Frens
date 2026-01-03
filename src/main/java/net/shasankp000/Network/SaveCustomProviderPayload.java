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

    public static final PacketCodec<PacketByteBuf, SaveCustomProviderPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, SaveCustomProviderPayload::apiKey,
                    STRING_CODEC, SaveCustomProviderPayload::url,
                    SaveCustomProviderPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
