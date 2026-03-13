package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: player accepted or dismissed the auto-return HUD prompt. */
public record NavigationResponsePayload(String botAlias, boolean accepted) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "nav_response");
    public static final CustomPayload.Id<NavigationResponsePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

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

    public static final PacketCodec<PacketByteBuf, NavigationResponsePayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, NavigationResponsePayload::botAlias,
                    BOOL_CODEC, NavigationResponsePayload::accepted,
                    NavigationResponsePayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
