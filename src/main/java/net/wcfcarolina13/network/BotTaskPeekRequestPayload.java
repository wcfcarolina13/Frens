package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: request lightweight current task state for a looked-at bot. */
public record BotTaskPeekRequestPayload(String botUuid) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "bot_task_peek_request");
    public static final CustomPayload.Id<BotTaskPeekRequestPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, BotTaskPeekRequestPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, BotTaskPeekRequestPayload::botUuid, BotTaskPeekRequestPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
