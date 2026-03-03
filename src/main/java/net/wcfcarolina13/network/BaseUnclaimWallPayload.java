package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: remove ownership claim from a saved fortification wall. */
public record BaseUnclaimWallPayload(String label) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "base_unclaim_wall");
    public static final CustomPayload.Id<BaseUnclaimWallPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
    public static final PacketCodec<PacketByteBuf, BaseUnclaimWallPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, BaseUnclaimWallPayload::label, BaseUnclaimWallPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
