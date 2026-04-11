package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> Server: player pressed the "rescue teleport" key.
 *
 * <p>The server finds the closest bot currently following this player that is within strict
 * proximity + line-of-sight constraints and teleports it to the player's exact block. The
 * constraints (5 blocks horizontal, ≤3 above, ≤1 below, must be visible) exist so that the
 * feature is purely an anti-stuck convenience and cannot be used as a free long-range
 * teleport or to push a bot through walls. Payload carries no data — the server resolves
 * everything from the player's current position + follow state.</p>
 */
public record RescueTeleportRequestPayload() implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "rescue_teleport_request");
    public static final CustomPayload.Id<RescueTeleportRequestPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, RescueTeleportRequestPayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, RescueTeleportRequestPayload value) {
            // no fields
        }

        @Override
        public RescueTeleportRequestPayload decode(PacketByteBuf buf) {
            return new RescueTeleportRequestPayload();
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
