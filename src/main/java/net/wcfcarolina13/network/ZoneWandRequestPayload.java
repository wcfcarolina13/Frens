package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: request a zone wand item (admin only). */
public record ZoneWandRequestPayload(String query) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "zone_wand_request");
    public static final CustomPayload.Id<ZoneWandRequestPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, ZoneWandRequestPayload> CODEC =
            PacketCodec.tuple(new StringCodec(256), ZoneWandRequestPayload::query, ZoneWandRequestPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
