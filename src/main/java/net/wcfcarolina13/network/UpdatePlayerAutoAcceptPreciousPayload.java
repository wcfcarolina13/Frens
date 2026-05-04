package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S payload: player toggles their "Auto-accept precious foods" preference.
 *
 * <p>The sender is always the subject — no target UUID field. The server
 * handler uses the player who sent the packet as the subject of the update.
 */
public record UpdatePlayerAutoAcceptPreciousPayload(boolean enabled) implements CustomPayload {

    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "update_player_auto_accept_precious");
    public static final CustomPayload.Id<UpdatePlayerAutoAcceptPreciousPayload> ID =
            new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, UpdatePlayerAutoAcceptPreciousPayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, UpdatePlayerAutoAcceptPreciousPayload payload) {
            buf.writeBoolean(payload.enabled());
        }

        @Override
        public UpdatePlayerAutoAcceptPreciousPayload decode(PacketByteBuf buf) {
            return new UpdatePlayerAutoAcceptPreciousPayload(buf.readBoolean());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
