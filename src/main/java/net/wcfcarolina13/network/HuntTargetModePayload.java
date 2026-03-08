package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: toggle target-picking mode (enables/disables server-side hunt target particles). JSON with botName + active. */
public record HuntTargetModePayload(String json) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "hunt_target_mode");
    public static final CustomPayload.Id<HuntTargetModePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, HuntTargetModePayload> CODEC =
            PacketCodec.tuple(new StringCodec(32767), HuntTargetModePayload::json, HuntTargetModePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
