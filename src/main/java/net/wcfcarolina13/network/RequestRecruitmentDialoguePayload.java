package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: player pressed the "initiate contact" key while eligible. */
public record RequestRecruitmentDialoguePayload() implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "recruitment_request_open");
    public static final CustomPayload.Id<RequestRecruitmentDialoguePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, RequestRecruitmentDialoguePayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, RequestRecruitmentDialoguePayload value) {
            // no fields
        }

        @Override
        public RequestRecruitmentDialoguePayload decode(PacketByteBuf buf) {
            return new RequestRecruitmentDialoguePayload();
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
