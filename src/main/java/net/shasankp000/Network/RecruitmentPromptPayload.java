package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: show/hide the "recruitment available" prompt while in a village. */
public record RecruitmentPromptPayload(boolean visible, String botAlias) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "recruitment_prompt");
    public static final CustomPayload.Id<RecruitmentPromptPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

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

    public static final PacketCodec<PacketByteBuf, RecruitmentPromptPayload> CODEC =
            PacketCodec.tuple(BOOL_CODEC, RecruitmentPromptPayload::visible,
                    STRING_CODEC, RecruitmentPromptPayload::botAlias,
                    RecruitmentPromptPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
