package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S payload: player asks the server to send back their current
 * "Preserve Expensive Gear" preference. Empty body — the sender is
 * always the subject.
 */
public record RequestPlayerPreservePayload() implements CustomPayload {

    public static final RequestPlayerPreservePayload INSTANCE =
            new RequestPlayerPreservePayload();

    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "request_player_preserve");
    public static final CustomPayload.Id<RequestPlayerPreservePayload> ID =
            new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, RequestPlayerPreservePayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, RequestPlayerPreservePayload payload) {
            // no fields
        }

        @Override
        public RequestPlayerPreservePayload decode(PacketByteBuf buf) {
            return INSTANCE;
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
