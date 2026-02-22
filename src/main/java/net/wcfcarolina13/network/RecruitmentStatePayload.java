package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: current survival recruitment mode state for this world. */
public record RecruitmentStatePayload(boolean enabled,
                                      boolean recruited,
                                      String botAlias,
                                      boolean modeSelectionRequired,
                                      boolean modeSelectionCanChoose,
                                      String worldKey) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "recruitment_state");
    public static final CustomPayload.Id<RecruitmentStatePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);
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

    public static final PacketCodec<PacketByteBuf, RecruitmentStatePayload> CODEC =
            PacketCodec.tuple(BOOL_CODEC, RecruitmentStatePayload::enabled,
                    BOOL_CODEC, RecruitmentStatePayload::recruited,
                    STRING_CODEC, RecruitmentStatePayload::botAlias,
                    BOOL_CODEC, RecruitmentStatePayload::modeSelectionRequired,
                    BOOL_CODEC, RecruitmentStatePayload::modeSelectionCanChoose,
                    STRING_CODEC, RecruitmentStatePayload::worldKey,
                    RecruitmentStatePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
