package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: first-time world mode selection (questing/admin). */
public record ModeSelectionChoicePayload(boolean questingMode) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "mode_selection_choice");
    public static final CustomPayload.Id<ModeSelectionChoicePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, Boolean> BOOL_CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, Boolean value) {
            buf.writeBoolean(value != null && value);
        }

        @Override
        public Boolean decode(PacketByteBuf buf) {
            return buf.readBoolean();
        }
    };

    public static final PacketCodec<PacketByteBuf, ModeSelectionChoicePayload> CODEC =
            PacketCodec.tuple(BOOL_CODEC, ModeSelectionChoicePayload::questingMode, ModeSelectionChoicePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
