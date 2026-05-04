package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C payload: server sends the current "Auto-accept precious foods" value
 * to the client. Used by BotPlayerPreferencesScreen to populate the
 * initial toggle state on open.
 */
public record PlayerAutoAcceptPreciousStatePayload(boolean enabled) implements CustomPayload {

    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "player_auto_accept_precious_state");
    public static final CustomPayload.Id<PlayerAutoAcceptPreciousStatePayload> ID =
            new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, PlayerAutoAcceptPreciousStatePayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, PlayerAutoAcceptPreciousStatePayload payload) {
            buf.writeBoolean(payload.enabled());
        }

        @Override
        public PlayerAutoAcceptPreciousStatePayload decode(PacketByteBuf buf) {
            return new PlayerAutoAcceptPreciousStatePayload(buf.readBoolean());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
