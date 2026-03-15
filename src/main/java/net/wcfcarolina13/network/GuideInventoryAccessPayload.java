package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: indicates whether the guide-initiated inventory open has full access, and why. */
public record GuideInventoryAccessPayload(String botAlias, boolean fullAccess, String reason) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "guide_inventory_access");
    public static final CustomPayload.Id<GuideInventoryAccessPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    private static final PacketCodec<PacketByteBuf, Boolean> BOOL_CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, Boolean value) {
            buf.writeBoolean(value != null && value);
        }

        @Override
        public Boolean decode(PacketByteBuf buf) {
            return buf.readBoolean();
        }
    };

    public static final PacketCodec<PacketByteBuf, GuideInventoryAccessPayload> CODEC =
            PacketCodec.tuple(
                    new StringCodec(32767), GuideInventoryAccessPayload::botAlias,
                    BOOL_CODEC, GuideInventoryAccessPayload::fullAccess,
                    new StringCodec(32767), GuideInventoryAccessPayload::reason,
                    GuideInventoryAccessPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
