package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: request the list of protected zones for the current world. */
public record RequestZoneListPayload(String query) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "request_zone_list");
    public static final CustomPayload.Id<RequestZoneListPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, RequestZoneListPayload> CODEC =
            PacketCodec.tuple(new StringCodec(256), RequestZoneListPayload::query, RequestZoneListPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
