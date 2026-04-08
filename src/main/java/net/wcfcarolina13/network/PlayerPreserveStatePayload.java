package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C payload: server sends the current "Preserve Expensive Gear" value
 * to the client. Used by BotPlayerPreferencesScreen to populate the
 * initial toggle state on open.
 */
public record PlayerPreserveStatePayload(boolean enabled) implements CustomPayload {

    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "player_preserve_state");
    public static final CustomPayload.Id<PlayerPreserveStatePayload> ID =
            new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, PlayerPreserveStatePayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, PlayerPreserveStatePayload payload) {
            buf.writeBoolean(payload.enabled());
        }

        @Override
        public PlayerPreserveStatePayload decode(PacketByteBuf buf) {
            return new PlayerPreserveStatePayload(buf.readBoolean());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
