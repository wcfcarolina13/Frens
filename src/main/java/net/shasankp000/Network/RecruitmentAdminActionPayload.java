package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: request an operator-only survival recruitment admin action. */
public record RecruitmentAdminActionPayload(String botAlias, String action, int intArg, boolean boolArg) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "recruitment_admin_action");
    public static final CustomPayload.Id<RecruitmentAdminActionPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

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

    public static final PacketCodec<PacketByteBuf, RecruitmentAdminActionPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, RecruitmentAdminActionPayload::botAlias,
                    STRING_CODEC, RecruitmentAdminActionPayload::action,
                    INT_CODEC, RecruitmentAdminActionPayload::intArg,
                    BOOL_CODEC, RecruitmentAdminActionPayload::boolArg,
                    RecruitmentAdminActionPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
