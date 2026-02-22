package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: compact looked-at bot task state for top-of-screen hints. */
public record BotTaskPeekStatusPayload(String botUuid,
                                       String botAlias,
                                       boolean active,
                                       boolean paused,
                                       boolean returningHome,
                                       String taskLabel) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "bot_task_peek_status");
    public static final CustomPayload.Id<BotTaskPeekStatusPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, Boolean> BOOL_CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, Boolean value) {
            buf.writeBoolean(value != null && value);
        }

        @Override
        public Boolean decode(PacketByteBuf buf) {
            return buf.readBoolean();
        }
    };

    public static final PacketCodec<PacketByteBuf, BotTaskPeekStatusPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, BotTaskPeekStatusPayload::botUuid,
                    STRING_CODEC, BotTaskPeekStatusPayload::botAlias,
                    BOOL_CODEC, BotTaskPeekStatusPayload::active,
                    BOOL_CODEC, BotTaskPeekStatusPayload::paused,
                    BOOL_CODEC, BotTaskPeekStatusPayload::returningHome,
                    STRING_CODEC, BotTaskPeekStatusPayload::taskLabel,
                    BotTaskPeekStatusPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
