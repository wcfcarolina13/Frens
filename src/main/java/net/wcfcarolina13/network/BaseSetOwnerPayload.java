package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> Server: admin-only reassign of a base's owner. Used to claim legacy bases or to
 * transfer ownership after player migrations.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code label} — the base to reassign.</li>
 *   <li>{@code newOwnerUuid} — target player's UUID string, or {@code "SERVER"} to mark as
 *       server-owned, or empty to clear (becomes admin-only legacy).</li>
 *   <li>{@code newOwnerName} — display name for UI (cached alongside UUID).</li>
 * </ul>
 *
 * <p>Server enforces operator permission before applying.
 */
public record BaseSetOwnerPayload(String label, String newOwnerUuid, String newOwnerName) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "base_set_owner");
    public static final CustomPayload.Id<BaseSetOwnerPayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, String> STRING_CODEC = new StringCodec(32767);

    public static final PacketCodec<PacketByteBuf, BaseSetOwnerPayload> CODEC =
            PacketCodec.tuple(
                    STRING_CODEC, BaseSetOwnerPayload::label,
                    STRING_CODEC, BaseSetOwnerPayload::newOwnerUuid,
                    STRING_CODEC, BaseSetOwnerPayload::newOwnerName,
                    BaseSetOwnerPayload::new
            );

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
