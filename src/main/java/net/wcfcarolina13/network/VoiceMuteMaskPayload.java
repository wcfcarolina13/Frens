package net.wcfcarolina13.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Client -> Server: the sender's personal voiced-dialogue mute mask (category ids).
 *
 * <p>The list is capped well above the category count; ids are validated server-side against
 * {@code VoiceLineCategory} before being stored.
 */
public record VoiceMuteMaskPayload(List<String> mutedCategoryIds) implements CustomPayload {

    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "voice_mute_mask");
    public static final CustomPayload.Id<VoiceMuteMaskPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    /** Hard cap on the wire; the enum has 9 ids today, 64 leaves room without unbounded input. */
    public static final int MAX_ENTRIES = 64;

    public static final PacketCodec<RegistryByteBuf, VoiceMuteMaskPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.collection(ArrayList::new, PacketCodecs.STRING, MAX_ENTRIES),
                    VoiceMuteMaskPayload::mutedCategoryIds,
                    VoiceMuteMaskPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
