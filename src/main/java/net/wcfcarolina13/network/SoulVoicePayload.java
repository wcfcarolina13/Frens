package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Server -> Client: one chunk of a synthesized soul-voice line (16-bit mono PCM). */
public record SoulVoicePayload(UUID correlationId, UUID botId, byte mode, int sampleRate,
                                int chunkIndex, int chunkCount, byte[] data)
        implements CustomPayload {

    public static final byte MODE_POSITIONAL = 0;
    public static final byte MODE_RADIO = 1;

    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "soul_voice");
    public static final CustomPayload.Id<SoulVoicePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, SoulVoicePayload> CODEC =
            PacketCodec.of(SoulVoicePayload::write, SoulVoicePayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeUuid(correlationId);
        buf.writeUuid(botId);
        buf.writeByte(mode);
        buf.writeVarInt(sampleRate);
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(chunkCount);
        buf.writeByteArray(data);
    }

    private static SoulVoicePayload read(PacketByteBuf buf) {
        return new SoulVoicePayload(buf.readUuid(), buf.readUuid(), buf.readByte(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray());
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
