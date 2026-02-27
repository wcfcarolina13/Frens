package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: learning-mode player input telemetry sample (optional). */
public record LearningInputSamplePayload(long sessionToken,
                                         int sampleSeq,
                                         int flags,
                                         int selectedSlot,
                                         float yaw,
                                         float pitch) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "learning_input_sample");
    public static final CustomPayload.Id<LearningInputSamplePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, LearningInputSamplePayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, LearningInputSamplePayload value) {
            LearningInputSamplePayload v = value != null ? value : new LearningInputSamplePayload(0L, 0, 0, 0, 0.0f, 0.0f);
            buf.writeLong(v.sessionToken());
            buf.writeInt(v.sampleSeq());
            buf.writeInt(v.flags());
            buf.writeInt(v.selectedSlot());
            buf.writeFloat(v.yaw());
            buf.writeFloat(v.pitch());
        }

        @Override
        public LearningInputSamplePayload decode(PacketByteBuf buf) {
            return new LearningInputSamplePayload(
                    buf.readLong(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readFloat(),
                    buf.readFloat()
            );
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
