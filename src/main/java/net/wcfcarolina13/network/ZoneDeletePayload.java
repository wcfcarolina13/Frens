package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: delete a protected zone (admin only). */
public record ZoneDeletePayload(String label) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "zone_delete");
    public static final CustomPayload.Id<ZoneDeletePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, ZoneDeletePayload> CODEC =
            PacketCodec.tuple(new StringCodec(256), ZoneDeletePayload::label, ZoneDeletePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
