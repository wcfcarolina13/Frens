package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server → Client: player killed a huntable mob type for the first time. */
public record HuntDiscoveryPayload(String mobLabel) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "hunt_discovery");
    public static final CustomPayload.Id<HuntDiscoveryPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, HuntDiscoveryPayload> CODEC =
            PacketCodec.tuple(new StringCodec(256), HuntDiscoveryPayload::mobLabel, HuntDiscoveryPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
