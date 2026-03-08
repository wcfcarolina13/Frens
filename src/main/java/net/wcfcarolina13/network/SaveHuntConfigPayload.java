package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: save hunt config for a bot. JSON string with botName + config fields. */
public record SaveHuntConfigPayload(String configJson) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "save_hunt_config");
    public static final CustomPayload.Id<SaveHuntConfigPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, SaveHuntConfigPayload> CODEC =
            PacketCodec.tuple(new StringCodec(32767), SaveHuntConfigPayload::configJson, SaveHuntConfigPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
