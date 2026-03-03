package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client → Server: request a skin change for a bot.
 * Fields: {@code botAlias} (target bot) and {@code skinPresetId} (preset short id, or "random").
 */
public record BotSkinPayload(String botAlias, String skinPresetId) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "bot_skin");
    public static final CustomPayload.Id<BotSkinPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    private static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, BotSkinPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, BotSkinPayload::botAlias,
                    STRING_CODEC, BotSkinPayload::skinPresetId,
                    BotSkinPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
