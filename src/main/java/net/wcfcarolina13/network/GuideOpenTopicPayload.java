package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server -> Client: open the Guide on one card. Sent by {@code /frens guide <topic> [bot]}, which
 * is what the [Open in Guide] link on a fren's chat answer runs.
 */
public record GuideOpenTopicPayload(String topicId, String botAlias) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "guide_open_topic");
    public static final CustomPayload.Id<GuideOpenTopicPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, GuideOpenTopicPayload> CODEC =
            PacketCodec.tuple(
                    new StringCodec(256), GuideOpenTopicPayload::topicId,
                    new StringCodec(64), GuideOpenTopicPayload::botAlias,
                    GuideOpenTopicPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
