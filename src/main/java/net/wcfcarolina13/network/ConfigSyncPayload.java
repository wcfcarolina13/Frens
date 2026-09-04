package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: authoritative snapshot of the shared config subset. */
public record ConfigSyncPayload(String configJson) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "config_sync");
    public static final CustomPayload.Id<ConfigSyncPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(262144);

    public static final PacketCodec<PacketByteBuf, ConfigSyncPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, ConfigSyncPayload::configJson, ConfigSyncPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
