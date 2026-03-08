package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: chest registry list for a bot (JSON array). */
public record ChestRegistryPayload(String json) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "chest_registry");
    public static final CustomPayload.Id<ChestRegistryPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, ChestRegistryPayload> CODEC =
            PacketCodec.tuple(new StringCodec(32767), ChestRegistryPayload::json, ChestRegistryPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
