package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> Server: send me the Companions roster. Sent when the roster opens on a server the
 * client is not hosting; answered with {@link RosterListPayload}.
 */
public record RosterRequestPayload() implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "roster_request");
    public static final CustomPayload.Id<RosterRequestPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final RosterRequestPayload INSTANCE = new RosterRequestPayload();

    public static final PacketCodec<PacketByteBuf, RosterRequestPayload> CODEC = PacketCodec.unit(INSTANCE);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
