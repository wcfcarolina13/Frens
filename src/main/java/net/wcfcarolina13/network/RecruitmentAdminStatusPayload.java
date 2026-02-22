package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: status/info lines from survival recruitment admin actions. */
public record RecruitmentAdminStatusPayload(String botAlias, String linesJoined) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "recruitment_admin_status");
    public static final CustomPayload.Id<RecruitmentAdminStatusPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, RecruitmentAdminStatusPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, RecruitmentAdminStatusPayload::botAlias,
                    STRING_CODEC, RecruitmentAdminStatusPayload::linesJoined,
                    RecruitmentAdminStatusPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
