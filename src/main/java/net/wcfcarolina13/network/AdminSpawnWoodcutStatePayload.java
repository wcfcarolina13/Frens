package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server -> Client: the current "Cut trees in the Spawn base" toggle, sent in answer to
 * {@link AdminSpawnWoodcutPayload} (query or set) so the World Settings screen shows the
 * authoritative value.
 */
public record AdminSpawnWoodcutStatePayload(boolean allowed) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "admin_spawn_woodcut_state");
    public static final CustomPayload.Id<AdminSpawnWoodcutStatePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, Boolean> BOOL_CODEC = new PacketCodec<>() {
        @Override public void encode(PacketByteBuf buf, Boolean value) { buf.writeBoolean(value != null && value); }
        @Override public Boolean decode(PacketByteBuf buf) { return buf.readBoolean(); }
    };

    public static final PacketCodec<PacketByteBuf, AdminSpawnWoodcutStatePayload> CODEC =
            PacketCodec.tuple(BOOL_CODEC, AdminSpawnWoodcutStatePayload::allowedBoxed, AdminSpawnWoodcutStatePayload::new);

    private Boolean allowedBoxed() { return allowed; }

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
