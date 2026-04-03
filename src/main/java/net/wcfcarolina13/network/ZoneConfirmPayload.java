package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: confirm zone creation with two corners and a name. */
public record ZoneConfirmPayload(int x1, int y1, int z1, int x2, int y2, int z2, String name) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "zone_confirm");
    public static final CustomPayload.Id<ZoneConfirmPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, ZoneConfirmPayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, ZoneConfirmPayload payload) {
            buf.writeInt(payload.x1);
            buf.writeInt(payload.y1);
            buf.writeInt(payload.z1);
            buf.writeInt(payload.x2);
            buf.writeInt(payload.y2);
            buf.writeInt(payload.z2);
            buf.writeString(payload.name, 256);
        }

        @Override
        public ZoneConfirmPayload decode(PacketByteBuf buf) {
            return new ZoneConfirmPayload(
                    buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readString(256));
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
