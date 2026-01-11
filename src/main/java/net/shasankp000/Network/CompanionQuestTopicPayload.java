package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: request a server-authoritative companion quest response for a dialogue topic key. */
public record CompanionQuestTopicPayload(String botAlias, String topicKey) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "companion_quest_topic");
    public static final CustomPayload.Id<CompanionQuestTopicPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, CompanionQuestTopicPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, CompanionQuestTopicPayload::botAlias,
                    STRING_CODEC, CompanionQuestTopicPayload::topicKey,
                    CompanionQuestTopicPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
