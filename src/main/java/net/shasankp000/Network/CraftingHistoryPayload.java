package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.shasankp000.network.StringCodec;

/** Server -> Client: crafting history list (JSON array of identifiers). */
public record CraftingHistoryPayload(String historyJson) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "crafting_history");
    public static final CustomPayload.Id<CraftingHistoryPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
    public static final PacketCodec<PacketByteBuf, CraftingHistoryPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, CraftingHistoryPayload::historyJson, CraftingHistoryPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
