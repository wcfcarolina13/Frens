package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: set selected base as preferred home for a specific bot alias. */
public record BaseSetHomePayload(String botAlias, String label) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "base_set_home");
    public static final CustomPayload.Id<BaseSetHomePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
    public static final PacketCodec<PacketByteBuf, BaseSetHomePayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, BaseSetHomePayload::botAlias,
                    STRING_CODEC, BaseSetHomePayload::label,
                    BaseSetHomePayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
