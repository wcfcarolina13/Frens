package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulVoicePcmTest {

    private static byte[] wav(int sampleRate, short channels, short bits, byte[] pcm) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()); header.putInt(36 + pcm.length); header.put("WAVE".getBytes());
        header.put("fmt ".getBytes()); header.putInt(16); header.putShort((short) 1);
        header.putShort(channels); header.putInt(sampleRate);
        header.putInt(sampleRate * channels * bits / 8);
        header.putShort((short) (channels * bits / 8)); header.putShort(bits);
        header.put("data".getBytes()); header.putInt(pcm.length);
        out.write(header.array()); out.write(pcm);
        return out.toByteArray();
    }

    @Test
    void parsesCanonicalMono16WavToPcm() throws Exception {
        byte[] pcm = {1, 2, 3, 4, 5, 6};
        Optional<SoulVoicePcm.PcmAudio> parsed = SoulVoicePcm.parseWav(wav(22050, (short) 1, (short) 16, pcm));
        assertTrue(parsed.isPresent());
        assertEquals(22050, parsed.get().sampleRate());
        assertArrayEquals(pcm, parsed.get().data());
    }

    @Test
    void rejectsStereoNon16BitAndGarbage() throws Exception {
        assertTrue(SoulVoicePcm.parseWav(wav(22050, (short) 2, (short) 16, new byte[4])).isEmpty());
        assertTrue(SoulVoicePcm.parseWav(wav(22050, (short) 1, (short) 8, new byte[4])).isEmpty());
        assertTrue(SoulVoicePcm.parseWav(new byte[] {1, 2, 3}).isEmpty());
        assertTrue(SoulVoicePcm.parseWav(null).isEmpty());
    }

    @Test
    void chunksSplitAndRoundTripThroughReassembly() {
        byte[] pcm = new byte[100_000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
        List<byte[]> chunks = SoulVoicePcm.chunk(pcm, 32_768);
        assertEquals(4, chunks.size());

        SoulVoicePcm.Reassembly reassembly = new SoulVoicePcm.Reassembly();
        UUID id = UUID.randomUUID();
        Optional<byte[]> joined = Optional.empty();
        for (int i = 0; i < chunks.size(); i++) {
            joined = reassembly.accept(id, i, chunks.size(), chunks.get(i), 0L);
        }
        assertTrue(joined.isPresent());
        assertArrayEquals(pcm, joined.get());
    }

    @Test
    void staleIncompleteSetsAreExpired() {
        SoulVoicePcm.Reassembly reassembly = new SoulVoicePcm.Reassembly();
        UUID id = UUID.randomUUID();
        reassembly.accept(id, 0, 2, new byte[] {1}, 0L);
        reassembly.expireStale(10_001L);
        // Completing after expiry must NOT produce audio from the dropped first chunk.
        assertTrue(reassembly.accept(id, 1, 2, new byte[] {2}, 10_002L).isEmpty());
    }
}
