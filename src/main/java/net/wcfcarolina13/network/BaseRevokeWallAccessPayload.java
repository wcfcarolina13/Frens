package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: revoke wall mutation access from another owner (player UUID/name). */
public record BaseRevokeWallAccessPayload(String label, String grantee) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "base_revoke_wall_access");
    public static final CustomPayload.Id<BaseRevokeWallAccessPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
    public static final PacketCodec<PacketByteBuf, BaseRevokeWallAccessPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, BaseRevokeWallAccessPayload::label,
                    STRING_CODEC, BaseRevokeWallAccessPayload::grantee,
                    BaseRevokeWallAccessPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
