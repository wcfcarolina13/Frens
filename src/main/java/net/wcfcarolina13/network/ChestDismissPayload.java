package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: dismiss (remove) a chest record. JSON with botName + x/y/z. */
public record ChestDismissPayload(String json) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "chest_dismiss");
    public static final CustomPayload.Id<ChestDismissPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, ChestDismissPayload> CODEC =
            PacketCodec.tuple(new StringCodec(32767), ChestDismissPayload::json, ChestDismissPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
