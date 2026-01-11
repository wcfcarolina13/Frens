package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: server-auth dialogue lines in response to a companion quest topic click. */
public record CompanionQuestResponsePayload(String botAlias, String linesJoined, int stage, boolean permanent) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "companion_quest_response");
    public static final CustomPayload.Id<CompanionQuestResponsePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

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

    public static final PacketCodec<PacketByteBuf, CompanionQuestResponsePayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, CompanionQuestResponsePayload::botAlias,
                    STRING_CODEC, CompanionQuestResponsePayload::linesJoined,
                    INT_CODEC, CompanionQuestResponsePayload::stage,
                    BOOL_CODEC, CompanionQuestResponsePayload::permanent,
                    CompanionQuestResponsePayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
