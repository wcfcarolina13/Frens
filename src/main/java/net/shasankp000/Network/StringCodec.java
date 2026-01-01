package net.shasankp000.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;

/** Small utility codec wrapper for strings used by simple payloads. */
public final class StringCodec implements PacketCodec<PacketByteBuf, String> {
    private final int maxLen;

    public StringCodec(int maxLen) {
        this.maxLen = maxLen;
    }

    // Different mappings use different method names; provide both forwards.
    public String decode(PacketByteBuf buf) {
        return buf.readString(this.maxLen);
    }

    public void encode(PacketByteBuf buf, String value) {
        try {
            buf.writeString(value, this.maxLen);
        } catch (NoSuchMethodError e) {
            // Fallback to single-arg variant if mappings differ
            buf.writeString(value);
        }
    }

    // Older mappings may require read/write names
    public String read(PacketByteBuf buf) {
        return decode(buf);
    }

    public void write(PacketByteBuf buf, String value) {
        encode(buf, value);
    }
}
