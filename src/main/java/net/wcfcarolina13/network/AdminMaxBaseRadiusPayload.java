package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> Server: query or set the world's max user-settable base radius.
 *
 * <p>A negative {@code value} is a query ("send me the current state"). A non-negative
 * {@code value} is a set request; only operators are permitted to apply the change.
 * The server always responds with an {@link AdminMaxBaseRadiusStatePayload} so the
 * client's UI reflects the authoritative current state after either path.
 */
public record AdminMaxBaseRadiusPayload(int value) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "admin_max_base_radius");
    public static final CustomPayload.Id<AdminMaxBaseRadiusPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, Integer> INT_CODEC = new PacketCodec<>() {
        @Override public void encode(PacketByteBuf buf, Integer value) { buf.writeInt(value != null ? value : -1); }
        @Override public Integer decode(PacketByteBuf buf) { return buf.readInt(); }
    };

    public static final PacketCodec<PacketByteBuf, AdminMaxBaseRadiusPayload> CODEC =
            PacketCodec.tuple(INT_CODEC, AdminMaxBaseRadiusPayload::valueBoxed, AdminMaxBaseRadiusPayload::new);

    private Integer valueBoxed() { return value; }

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
