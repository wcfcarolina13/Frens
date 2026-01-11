package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: player accepted the recruitment and wants the bot to spawn. */
public record ConfirmRecruitmentPayload(String botAlias) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "recruitment_confirm");
    public static final CustomPayload.Id<ConfirmRecruitmentPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
    public static final PacketCodec<PacketByteBuf, ConfirmRecruitmentPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, ConfirmRecruitmentPayload::botAlias, ConfirmRecruitmentPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
