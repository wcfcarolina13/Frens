package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: sync lock mode active state for UI rendering. */
public record LockModeStatePayload(boolean active) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "lock_mode_state");
    public static final CustomPayload.Id<LockModeStatePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, LockModeStatePayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, LockModeStatePayload payload) {
            buf.writeBoolean(payload.active);
        }
        @Override
        public LockModeStatePayload decode(PacketByteBuf buf) {
            return new LockModeStatePayload(buf.readBoolean());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
