package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: JSON array of zone data for the Base Manager UI. */
public record ZoneListPayload(String zonesJson) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "zone_list");
    public static final CustomPayload.Id<ZoneListPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, ZoneListPayload> CODEC =
            PacketCodec.tuple(new StringCodec(32767), ZoneListPayload::zonesJson, ZoneListPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
