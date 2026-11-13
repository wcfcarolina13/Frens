package net.wcfcarolina13.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> Client: the Companions roster as this player may see it ({@link RosterVisibilityPolicy}).
 * {@code thisWorld} holds the companions saved in this world, present ones included (the client
 * hides those); {@code otherWorlds} is empty unless {@code canManage}. {@code canManage} is true for
 * an operator or the host, who may restore and create.
 */
public record RosterListPayload(List<String> thisWorld, List<String> otherWorlds, boolean canManage)
        implements CustomPayload {

    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "roster_list");
    public static final CustomPayload.Id<RosterListPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<RegistryByteBuf, RosterListPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.collection(ArrayList::new, PacketCodecs.string(RosterVisibilityPolicy.MAX_ALIAS_LEN),
                            RosterVisibilityPolicy.MAX_LISTED),
                    RosterListPayload::thisWorld,
                    PacketCodecs.collection(ArrayList::new, PacketCodecs.string(RosterVisibilityPolicy.MAX_ALIAS_LEN),
                            RosterVisibilityPolicy.MAX_LISTED),
                    RosterListPayload::otherWorlds,
                    PacketCodecs.BOOLEAN,
                    RosterListPayload::canManage,
                    RosterListPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
