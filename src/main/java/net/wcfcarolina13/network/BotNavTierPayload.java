package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: the bot's current navigation tier (0=NONE, 1=BASIC, 2=ENHANCED). */
public record BotNavTierPayload(String botAlias, int tier) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "bot_nav_tier");
    public static final CustomPayload.Id<BotNavTierPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
    public static final PacketCodec<PacketByteBuf, Integer> INT_CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, Integer value) {
            buf.writeInt(value != null ? value : 0);
        }

        @Override
        public Integer decode(PacketByteBuf buf) {
            return buf.readInt();
        }
    };

    public static final PacketCodec<PacketByteBuf, BotNavTierPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, BotNavTierPayload::botAlias,
                    INT_CODEC, BotNavTierPayload::tier,
                    BotNavTierPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
