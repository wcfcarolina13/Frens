package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> Server: query or set the per-save "Cut trees in the Spawn base" admin toggle.
 *
 * <p>A negative {@code value} is a query. {@code 0} sets it off, {@code 1} sets it on; only
 * operators or the host may set it (same gate as {@link AdminMaxBaseRadiusPayload}). The server
 * always answers with an {@link AdminSpawnWoodcutStatePayload}.
 */
public record AdminSpawnWoodcutPayload(int value) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "admin_spawn_woodcut");
    public static final CustomPayload.Id<AdminSpawnWoodcutPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, Integer> INT_CODEC = new PacketCodec<>() {
        @Override public void encode(PacketByteBuf buf, Integer value) { buf.writeInt(value != null ? value : -1); }
        @Override public Integer decode(PacketByteBuf buf) { return buf.readInt(); }
    };

    public static final PacketCodec<PacketByteBuf, AdminSpawnWoodcutPayload> CODEC =
            PacketCodec.tuple(INT_CODEC, AdminSpawnWoodcutPayload::valueBoxed, AdminSpawnWoodcutPayload::new);

    private Integer valueBoxed() { return value; }

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
