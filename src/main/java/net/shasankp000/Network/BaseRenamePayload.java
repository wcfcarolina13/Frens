package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: rename an existing base label. */
public record BaseRenamePayload(String oldLabel, String newLabel) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "base_rename");
    public static final CustomPayload.Id<BaseRenamePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, BaseRenamePayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, BaseRenamePayload::oldLabel,
                    STRING_CODEC, BaseRenamePayload::newLabel,
                    BaseRenamePayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
