package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: toggle lock mode on/off. */
public record LockModeTogglePayload(boolean active) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "lock_mode_toggle");
    public static final CustomPayload.Id<LockModeTogglePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, LockModeTogglePayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, LockModeTogglePayload payload) {
            buf.writeBoolean(payload.active);
        }
        @Override
        public LockModeTogglePayload decode(PacketByteBuf buf) {
            return new LockModeTogglePayload(buf.readBoolean());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
