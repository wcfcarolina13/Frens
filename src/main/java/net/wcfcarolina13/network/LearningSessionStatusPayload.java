package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: tells the client when to stream learning input samples. */
public record LearningSessionStatusPayload(boolean active,
                                           long sessionToken,
                                           int tickHz) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "learning_session_status");
    public static final CustomPayload.Id<LearningSessionStatusPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, LearningSessionStatusPayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, LearningSessionStatusPayload value) {
            LearningSessionStatusPayload v = value != null ? value : new LearningSessionStatusPayload(false, 0L, 0);
            buf.writeBoolean(v.active());
            buf.writeLong(v.sessionToken());
            buf.writeInt(v.tickHz());
        }

        @Override
        public LearningSessionStatusPayload decode(PacketByteBuf buf) {
            return new LearningSessionStatusPayload(
                    buf.readBoolean(),
                    buf.readLong(),
                    buf.readInt()
            );
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
