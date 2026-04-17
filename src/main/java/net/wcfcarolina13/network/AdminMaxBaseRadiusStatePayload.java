package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server -> Client: current max-radius value plus the hard ceiling a client UI must not
 * let the user exceed. Sent in response to {@link AdminMaxBaseRadiusPayload} (query or set)
 * so the admin screen always reflects authoritative server state.
 */
public record AdminMaxBaseRadiusStatePayload(int current, int hardLimit) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "admin_max_base_radius_state");
    public static final CustomPayload.Id<AdminMaxBaseRadiusStatePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, Integer> INT_CODEC = new PacketCodec<>() {
        @Override public void encode(PacketByteBuf buf, Integer value) { buf.writeInt(value != null ? value : 0); }
        @Override public Integer decode(PacketByteBuf buf) { return buf.readInt(); }
    };

    public static final PacketCodec<PacketByteBuf, AdminMaxBaseRadiusStatePayload> CODEC =
            PacketCodec.tuple(
                    INT_CODEC, AdminMaxBaseRadiusStatePayload::currentBoxed,
                    INT_CODEC, AdminMaxBaseRadiusStatePayload::hardLimitBoxed,
                    AdminMaxBaseRadiusStatePayload::new
            );

    private Integer currentBoxed() { return current; }
    private Integer hardLimitBoxed() { return hardLimit; }

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
