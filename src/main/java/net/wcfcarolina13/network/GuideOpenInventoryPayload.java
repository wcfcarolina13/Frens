package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: request the guide to open a bot's inventory screen remotely. */
public record GuideOpenInventoryPayload(String botAlias) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "guide_open_inventory");
    public static final CustomPayload.Id<GuideOpenInventoryPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, GuideOpenInventoryPayload> CODEC =
            PacketCodec.tuple(
                    new StringCodec(32767), GuideOpenInventoryPayload::botAlias,
                    GuideOpenInventoryPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
