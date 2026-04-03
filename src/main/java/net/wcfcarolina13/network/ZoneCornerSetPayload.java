package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: confirms a zone corner position was recorded. */
public record ZoneCornerSetPayload(int cornerIndex, int posX, int posY, int posZ) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "zone_corner_set");
    public static final CustomPayload.Id<ZoneCornerSetPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, ZoneCornerSetPayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, ZoneCornerSetPayload payload) {
            buf.writeInt(payload.cornerIndex);
            buf.writeInt(payload.posX);
            buf.writeInt(payload.posY);
            buf.writeInt(payload.posZ);
        }

        @Override
        public ZoneCornerSetPayload decode(PacketByteBuf buf) {
            return new ZoneCornerSetPayload(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
