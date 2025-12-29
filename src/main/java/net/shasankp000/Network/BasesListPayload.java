package net.shasankp000.Network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: current bases list as JSON (see BaseNetworkManager for schema). */
public record BasesListPayload(String basesJson) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "bases_list");
    public static final CustomPayload.Id<BasesListPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, BasesListPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, BasesListPayload::basesJson, BasesListPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
