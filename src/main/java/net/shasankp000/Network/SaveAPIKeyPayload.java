package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: save API key for a provider */
public record SaveAPIKeyPayload(String provider, String apiKey) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "save_api_key");
    public static final CustomPayload.Id<SaveAPIKeyPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, SaveAPIKeyPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, SaveAPIKeyPayload::provider,
                    STRING_CODEC, SaveAPIKeyPayload::apiKey,
                    SaveAPIKeyPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
