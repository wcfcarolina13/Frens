package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: request to open the bot's anvil screen. */
public record BotAnvilOpenPayload(String botAlias) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "bot_anvil_open");
    public static final CustomPayload.Id<BotAnvilOpenPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, BotAnvilOpenPayload> CODEC =
            PacketCodec.tuple(
                    new StringCodec(32767), BotAnvilOpenPayload::botAlias,
                    BotAnvilOpenPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
