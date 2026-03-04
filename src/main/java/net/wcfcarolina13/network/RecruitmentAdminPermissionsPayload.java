package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: serialized admin-permissions snapshot for player settings UI. */
public record RecruitmentAdminPermissionsPayload(String botAlias, String jsonData) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "recruitment_admin_permissions");
    public static final CustomPayload.Id<RecruitmentAdminPermissionsPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, RecruitmentAdminPermissionsPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, RecruitmentAdminPermissionsPayload::botAlias,
                    STRING_CODEC, RecruitmentAdminPermissionsPayload::jsonData,
                    RecruitmentAdminPermissionsPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
