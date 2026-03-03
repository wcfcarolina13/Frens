package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: grant wall mutation access to another owner (player UUID/name). */
public record BaseGrantWallAccessPayload(String label, String grantee) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "base_grant_wall_access");
    public static final CustomPayload.Id<BaseGrantWallAccessPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
    public static final PacketCodec<PacketByteBuf, BaseGrantWallAccessPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, BaseGrantWallAccessPayload::label,
                    STRING_CODEC, BaseGrantWallAccessPayload::grantee,
                    BaseGrantWallAccessPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
