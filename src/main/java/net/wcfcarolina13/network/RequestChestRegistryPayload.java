package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: request the chest registry list for a bot. */
public record RequestChestRegistryPayload(String botName) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "request_chest_registry");
    public static final CustomPayload.Id<RequestChestRegistryPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, RequestChestRegistryPayload> CODEC =
            PacketCodec.tuple(new StringCodec(256), RequestChestRegistryPayload::botName, RequestChestRegistryPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
