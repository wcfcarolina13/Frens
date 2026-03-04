package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client → Server: request a skin change for a bot.
 * Fields:
 * <ul>
 *   <li>{@code botAlias} (target bot)</li>
 *   <li>{@code skinSource} ({@code preset} or {@code custom_url})</li>
 *   <li>{@code skinValue} (preset short id / {@code random} / texture URL)</li>
 * </ul>
 */
public record BotSkinPayload(String botAlias, String skinSource, String skinValue) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "bot_skin");
    public static final CustomPayload.Id<BotSkinPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    private static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, BotSkinPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, BotSkinPayload::botAlias,
                STRING_CODEC, BotSkinPayload::skinSource,
                STRING_CODEC, BotSkinPayload::skinValue,
                    BotSkinPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
