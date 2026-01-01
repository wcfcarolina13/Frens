package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: remove a base by label. */
public record BaseRemovePayload(String label) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "base_remove");
    public static final CustomPayload.Id<BaseRemovePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, BaseRemovePayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, BaseRemovePayload::label, BaseRemovePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
