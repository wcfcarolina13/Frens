package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server -> Client: open the Bot Roster once the screen is free. Sent when a new world was set to
 * Admin automatically because Questing mode is off (1.1.235), standing in for the roster that
 * choosing Admin in the World Mode chooser opens. Senders check
 * {@code ServerPlayNetworking.canSend} so an older client is simply skipped.
 */
public record OpenRosterPayload() implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "open_roster");
    public static final CustomPayload.Id<OpenRosterPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final OpenRosterPayload INSTANCE = new OpenRosterPayload();

    public static final PacketCodec<PacketByteBuf, OpenRosterPayload> CODEC = PacketCodec.unit(INSTANCE);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
