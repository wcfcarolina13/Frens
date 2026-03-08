package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: player has selected a specific entity to hunt. JSON with botName + entityUuid. */
public record HuntTargetPayload(String json) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "hunt_target");
    public static final CustomPayload.Id<HuntTargetPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, HuntTargetPayload> CODEC =
            PacketCodec.tuple(new StringCodec(32767), HuntTargetPayload::json, HuntTargetPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
