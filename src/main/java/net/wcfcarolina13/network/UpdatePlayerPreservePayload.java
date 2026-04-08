package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S payload: player toggles their "Preserve Expensive Gear" preference.
 *
 * <p>The sender is always the subject — no target UUID field. The server
 * handler uses the player who sent the packet as the subject of the update.
 * This makes cross-player editing impossible by construction.
 */
public record UpdatePlayerPreservePayload(boolean enabled) implements CustomPayload {

    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "update_player_preserve");
    public static final CustomPayload.Id<UpdatePlayerPreservePayload> ID =
            new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, UpdatePlayerPreservePayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, UpdatePlayerPreservePayload payload) {
            buf.writeBoolean(payload.enabled());
        }

        @Override
        public UpdatePlayerPreservePayload decode(PacketByteBuf buf) {
            return new UpdatePlayerPreservePayload(buf.readBoolean());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
