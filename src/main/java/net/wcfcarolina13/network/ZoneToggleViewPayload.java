package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: toggle particle visualization for a saved zone (any player). */
public record ZoneToggleViewPayload(String label, boolean enabled) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "zone_toggle_view");
    public static final CustomPayload.Id<ZoneToggleViewPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    private static final PacketCodec<PacketByteBuf, Boolean> BOOL_CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, Boolean value) { buf.writeBoolean(value != null && value); }
        @Override
        public Boolean decode(PacketByteBuf buf) { return buf.readBoolean(); }
    };

    public static final PacketCodec<PacketByteBuf, ZoneToggleViewPayload> CODEC =
            PacketCodec.tuple(
                    new StringCodec(256), ZoneToggleViewPayload::label,
                    BOOL_CODEC, ZoneToggleViewPayload::enabledBoxed,
                    ZoneToggleViewPayload::new
            );

    private Boolean enabledBoxed() { return enabled; }

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
