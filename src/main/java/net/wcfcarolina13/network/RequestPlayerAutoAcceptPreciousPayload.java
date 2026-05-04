package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S payload: player asks the server to send back their current
 * "Auto-accept precious foods" preference. Empty body — the sender is
 * always the subject.
 */
public record RequestPlayerAutoAcceptPreciousPayload() implements CustomPayload {

    public static final RequestPlayerAutoAcceptPreciousPayload INSTANCE =
            new RequestPlayerAutoAcceptPreciousPayload();

    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "request_player_auto_accept_precious");
    public static final CustomPayload.Id<RequestPlayerAutoAcceptPreciousPayload> ID =
            new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, RequestPlayerAutoAcceptPreciousPayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, RequestPlayerAutoAcceptPreciousPayload payload) {
            // no fields
        }

        @Override
        public RequestPlayerAutoAcceptPreciousPayload decode(PacketByteBuf buf) {
            return INSTANCE;
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
