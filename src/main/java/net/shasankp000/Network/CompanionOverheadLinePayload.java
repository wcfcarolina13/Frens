package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Server -> Client: show a short-lived overhead line above a companion/bot entity (not chat). */
public record CompanionOverheadLinePayload(UUID botUuid, String line, int durationMs) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "companion_overhead_line");
    public static final CustomPayload.Id<CompanionOverheadLinePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, UUID> UUID_CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, UUID value) {
            buf.writeUuid(value);
        }

        @Override
        public UUID decode(PacketByteBuf buf) {
            return buf.readUuid();
        }
    };

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, Integer> INT_CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, Integer value) {
            buf.writeInt(value != null ? value : 0);
        }

        @Override
        public Integer decode(PacketByteBuf buf) {
            return buf.readInt();
        }
    };

    public static final PacketCodec<PacketByteBuf, CompanionOverheadLinePayload> CODEC = PacketCodec.tuple(
            UUID_CODEC, CompanionOverheadLinePayload::botUuid,
            STRING_CODEC, CompanionOverheadLinePayload::line,
            INT_CODEC, CompanionOverheadLinePayload::durationMs,
            CompanionOverheadLinePayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
