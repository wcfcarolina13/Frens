package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: current hunt config for a bot (JSON). */
public record HuntConfigPayload(String configJson) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "hunt_config");
    public static final CustomPayload.Id<HuntConfigPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, HuntConfigPayload> CODEC =
            PacketCodec.tuple(new StringCodec(32767), HuntConfigPayload::configJson, HuntConfigPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
