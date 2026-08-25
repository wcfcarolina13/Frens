package net.wcfcarolina13.GameAI.souls.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Pure PCM plumbing: canonical-WAV parsing, chunking, and client-side chunk reassembly. */
public final class SoulVoicePcm {

    private static final long STALE_SET_MS = 10_000L;

    public record PcmAudio(int sampleRate, byte[] data) {
    }

    private SoulVoicePcm() {
    }

    public static Optional<PcmAudio> parseWav(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < 44) {
            return Optional.empty();
        }
        ByteBuffer buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] tag = new byte[4];
        buf.get(tag);
        if (!"RIFF".equals(new String(tag))) return Optional.empty();
        buf.getInt(); // riff size
        buf.get(tag);
        if (!"WAVE".equals(new String(tag))) return Optional.empty();

        int sampleRate = -1;
        boolean formatOk = false;
        while (buf.remaining() >= 8) {
            buf.get(tag);
            int size = buf.getInt();
            String chunkId = new String(tag);
            if (size < 0 || size > buf.remaining()) return Optional.empty();
            if ("fmt ".equals(chunkId)) {
                if (size < 16) return Optional.empty();
                int start = buf.position();
                short format = buf.getShort();
                short channels = buf.getShort();
                sampleRate = buf.getInt();
                buf.getInt();   // byte rate
                buf.getShort(); // block align
                short bits = buf.getShort();
                formatOk = format == 1 && channels == 1 && bits == 16;
                buf.position(start + size);
            } else if ("data".equals(chunkId)) {
                if (!formatOk || sampleRate <= 0) return Optional.empty();
                byte[] pcm = new byte[size];
                buf.get(pcm);
                return Optional.of(new PcmAudio(sampleRate, pcm));
            } else {
                buf.position(buf.position() + size);
            }
        }
        return Optional.empty();
    }

    public static List<byte[]> chunk(byte[] pcm, int maxChunkBytes) {
        List<byte[]> chunks = new ArrayList<>();
        if (pcm == null || pcm.length == 0 || maxChunkBytes <= 0) {
            return chunks;
        }
        for (int offset = 0; offset < pcm.length; offset += maxChunkBytes) {
            int len = Math.min(maxChunkBytes, pcm.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(pcm, offset, chunk, 0, len);
            chunks.add(chunk);
        }
        return chunks;
    }

    /** Client-side chunk collector. Not thread-safe; confine to one thread. */
    public static final class Reassembly {
        /**
         * Upper bound on a network-supplied chunk count. {@code chunkCount} sizes a
         * {@code byte[chunkCount][]} reference array below; without a cap a malicious or
         * corrupt payload could request an unbounded allocation.
         */
        private static final int MAX_CHUNK_COUNT = 256;

        private record PendingSet(byte[][] chunks, long firstSeenMs) {
        }

        private final Map<UUID, PendingSet> pending = new HashMap<>();

        public Optional<byte[]> accept(UUID correlationId, int chunkIndex, int chunkCount,
                                        byte[] data, long nowMs) {
            if (correlationId == null || data == null || chunkCount <= 0 || chunkCount > MAX_CHUNK_COUNT
                    || chunkIndex < 0 || chunkIndex >= chunkCount) {
                return Optional.empty();
            }
            PendingSet set = pending.computeIfAbsent(correlationId,
                    id -> new PendingSet(new byte[chunkCount][], nowMs));
            if (set.chunks().length != chunkCount) {
                pending.remove(correlationId);
                return Optional.empty();
            }
            set.chunks()[chunkIndex] = data;
            int total = 0;
            for (byte[] c : set.chunks()) {
                if (c == null) return Optional.empty();
                total += c.length;
            }
            pending.remove(correlationId);
            byte[] joined = new byte[total];
            int offset = 0;
            for (byte[] c : set.chunks()) {
                System.arraycopy(c, 0, joined, offset, c.length);
                offset += c.length;
            }
            return Optional.of(joined);
        }

        public void expireStale(long nowMs) {
            pending.values().removeIf(set -> nowMs - set.firstSeenMs() > STALE_SET_MS);
        }
    }
}
