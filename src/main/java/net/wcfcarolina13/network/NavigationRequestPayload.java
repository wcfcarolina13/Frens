package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: bot wants to auto-return home; shows HUD overlay with destination and ETA. */
public record NavigationRequestPayload(String botAlias, String destination, int estimatedSeconds) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "nav_request");
    public static final CustomPayload.Id<NavigationRequestPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

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

    public static final PacketCodec<PacketByteBuf, NavigationRequestPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, NavigationRequestPayload::botAlias,
                    STRING_CODEC, NavigationRequestPayload::destination,
                    INT_CODEC, NavigationRequestPayload::estimatedSeconds,
                    NavigationRequestPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
