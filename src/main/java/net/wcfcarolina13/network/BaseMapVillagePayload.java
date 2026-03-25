package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: map the local village using the provided label. */
public record BaseMapVillagePayload(String label) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "base_map_village");
    public static final CustomPayload.Id<BaseMapVillagePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, BaseMapVillagePayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, BaseMapVillagePayload::label, BaseMapVillagePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
