package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: rename a protected zone (admin only). */
public record ZoneEditPayload(String label, String newName) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "zone_edit");
    public static final CustomPayload.Id<ZoneEditPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, ZoneEditPayload> CODEC =
            PacketCodec.tuple(
                    new StringCodec(256), ZoneEditPayload::label,
                    new StringCodec(256), ZoneEditPayload::newName,
                    ZoneEditPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
