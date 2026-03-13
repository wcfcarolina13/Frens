package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: set the protection radius for a saved base. */
public record BaseSetRadiusPayload(String label, int radius) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "base_set_radius");
    public static final CustomPayload.Id<BaseSetRadiusPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

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

    public static final PacketCodec<PacketByteBuf, BaseSetRadiusPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, BaseSetRadiusPayload::label,
                    INT_CODEC, BaseSetRadiusPayload::radiusBoxed,
                    BaseSetRadiusPayload::new
            );

    private Integer radiusBoxed() {
        return radius;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
