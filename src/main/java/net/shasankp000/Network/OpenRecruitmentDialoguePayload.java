package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: open the recruitment dialogue UI (with a small context hint for branching). */
public record OpenRecruitmentDialoguePayload(String botAlias, String villageFlavor) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "recruitment_open");
    public static final CustomPayload.Id<OpenRecruitmentDialoguePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
    public static final PacketCodec<PacketByteBuf, OpenRecruitmentDialoguePayload> CODEC =
        PacketCodec.tuple(
            STRING_CODEC, OpenRecruitmentDialoguePayload::botAlias,
            STRING_CODEC, OpenRecruitmentDialoguePayload::villageFlavor,
            OpenRecruitmentDialoguePayload::new
        );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
