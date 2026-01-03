package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.shasankp000.network.StringCodec;

/** Server -> Client: huntable mob list (JSON). */
public record HuntablesPayload(String huntablesJson) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("ai-player", "huntables_list");
    public static final CustomPayload.Id<HuntablesPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
    public static final PacketCodec<PacketByteBuf, HuntablesPayload> CODEC =
            PacketCodec.tuple(STRING_CODEC, HuntablesPayload::huntablesJson, HuntablesPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
