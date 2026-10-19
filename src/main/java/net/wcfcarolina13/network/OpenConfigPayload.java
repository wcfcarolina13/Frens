package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: request client to open config UI. */
public record OpenConfigPayload(String configData) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "open_config");
    public static final CustomPayload.Id<OpenConfigPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(ConfigPayloadSizePolicy.MAX_CONFIG_JSON_CHARS);

    public static final PacketCodec<PacketByteBuf, OpenConfigPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, OpenConfigPayload::configData, OpenConfigPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
