package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: player has selected a chest block for the bot to deposit items into. JSON with botName + x/y/z. */
public record StoreTargetPayload(String json) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "store_target");
    public static final CustomPayload.Id<StoreTargetPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, StoreTargetPayload> CODEC =
            PacketCodec.tuple(new StringCodec(32767), StoreTargetPayload::json, StoreTargetPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
