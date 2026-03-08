package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: send bot to collect from a chest at given position. JSON with botName + x/y/z. */
public record ChestCollectPayload(String json) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "chest_collect");
    public static final CustomPayload.Id<ChestCollectPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, ChestCollectPayload> CODEC =
            PacketCodec.tuple(new StringCodec(32767), ChestCollectPayload::json, ChestCollectPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
