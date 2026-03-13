package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: player casts Remote Guidance or Chorus Recall. */
public record SpellGuidancePayload(String botAlias, String spellType, String destination) implements CustomPayload {
    // spellType: "guidance" or "recall"
    // destination: "player" | base label (for guidance) | "bot_to_player" | "player_to_bot" (for recall)

    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "spell_guidance");
    public static final CustomPayload.Id<SpellGuidancePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, SpellGuidancePayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, SpellGuidancePayload::botAlias,
                    STRING_CODEC, SpellGuidancePayload::spellType,
                    STRING_CODEC, SpellGuidancePayload::destination,
                    SpellGuidancePayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
