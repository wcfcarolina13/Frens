package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: current companion quest stage/permanent state (no dialogue lines). */
public record CompanionQuestStatePayload(String botAlias, int stage, boolean permanent) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "companion_quest_state");
    public static final CustomPayload.Id<CompanionQuestStatePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

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

    public static final PacketCodec<PacketByteBuf, CompanionQuestStatePayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, CompanionQuestStatePayload::botAlias,
                    INT_CODEC, CompanionQuestStatePayload::stage,
                    BOOL_CODEC, CompanionQuestStatePayload::permanent,
                    CompanionQuestStatePayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
