package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: send a specific bot to a selected saved base label. */
public record BaseGoToPayload(String botAlias, String label) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "base_go_to");
    public static final CustomPayload.Id<BaseGoToPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
    public static final PacketCodec<PacketByteBuf, BaseGoToPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, BaseGoToPayload::botAlias,
                    STRING_CODEC, BaseGoToPayload::label,
                    BaseGoToPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
