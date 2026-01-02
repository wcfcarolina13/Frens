package net.shasankp000.Network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: request crafting history list for the current world/commander. */
public record RequestCraftingHistoryPayload(String query) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "request_crafting_history");
    public static final CustomPayload.Id<RequestCraftingHistoryPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
    public static final PacketCodec<PacketByteBuf, RequestCraftingHistoryPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, RequestCraftingHistoryPayload::query, RequestCraftingHistoryPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
