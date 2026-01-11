package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> Server: request to reset/replay the survival recruitment flow.
 *
 * <p>Server enforces authorization. On dedicated servers this is restricted; on integrated
 * (singleplayer/LAN) servers it can be allowed for convenience.
 */
public record RequestRecruitmentReplayPayload(String botAlias) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "recruitment_replay_request");
    public static final CustomPayload.Id<RequestRecruitmentReplayPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
    public static final PacketCodec<PacketByteBuf, RequestRecruitmentReplayPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, RequestRecruitmentReplayPayload::botAlias, RequestRecruitmentReplayPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
