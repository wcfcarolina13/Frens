package net.shasankp000.Network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: indicates whether a bot is awaiting a resume/stop decision. */
public record ResumeDecisionPayload(boolean active, String botName) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "resume_decision");
    public static final CustomPayload.Id<ResumeDecisionPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

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

    public static final PacketCodec<PacketByteBuf, ResumeDecisionPayload> CODEC =
            PacketCodec.tuple(BOOL_CODEC, ResumeDecisionPayload::active,
                    STRING_CODEC, ResumeDecisionPayload::botName,
                    ResumeDecisionPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
