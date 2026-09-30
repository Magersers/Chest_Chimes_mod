package dev.chestchimes.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioValidationTest {
    private byte[] wav(int rate, int channels, int bits, int frames) {
        int size = frames * channels * bits / 8;
        ByteBuffer b = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + size);
        b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1);
        b.putShort((short) channels).putInt(rate).putInt(rate * channels * bits / 8);
        b.putShort((short) (channels * bits / 8)).putShort((short) bits);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(size);
        while (b.hasRemaining()) {
            if (bits == 16) b.putShort((short) 1234);
            else b.put((byte) 128);
        }
        return b.array();
    }

    @Test void enforcesDecimalTenMegabyteLimit() {
        AudioRules.sourceSize(10_000_000);
        assertThrows(IllegalArgumentException.class, () -> AudioRules.sourceSize(10_000_001));
        assertThrows(IllegalArgumentException.class, () -> AudioRules.sourceSize(0));
    }
    @Test void truncatesLongWavToFiveSeconds() {
        byte[] pcm = WavReader.decode(wav(44100, 2, 16, 44100 * 8));
        assertEquals(AudioRules.MAX_PCM, pcm.length);
        assertEquals(1234, ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).getShort());
    }
    @Test void convertsUnsignedEightBitSilence() {
        byte[] pcm = WavReader.decode(wav(22050, 1, 8, 22050));
        assertArrayEquals(new byte[44100], pcm);
    }
    @Test void trimsWithoutPaddingShortClips() {
        byte[] pcm = new byte[44100];
        assertEquals(4410, AudioRules.trim(pcm, 100).length);
        assertEquals(44100, AudioRules.trim(pcm, 5000).length);
        assertThrows(IllegalArgumentException.class, () -> AudioRules.trim(pcm, 5001));
        assertThrows(IllegalArgumentException.class, () -> AudioRules.trim(pcm, 0));
    }
    @Test void rejectsMisleadingAndTruncatedWav() {
        byte[] valid = wav(22050, 1, 16, 100);
        assertThrows(IllegalArgumentException.class, () -> WavReader.decode(new byte[44]));
        assertThrows(IllegalArgumentException.class, () -> WavReader.decode(java.util.Arrays.copyOf(valid, 48)));
        ByteBuffer.wrap(valid).order(ByteOrder.LITTLE_ENDIAN).putInt(40, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> WavReader.decode(valid));
    }
    @Test void rejectsCompressedWavAndUnsupportedChannels() {
        byte[] wav = wav(22050, 1, 16, 100);
        wav[20] = 3;
        assertThrows(IllegalArgumentException.class, () -> WavReader.decode(wav));
        assertThrows(IllegalArgumentException.class, () -> WavReader.decode(wav(22050, 3, 16, 100)));
    }
    @Test void rejectsOddAndOversizedPcm() {
        assertThrows(IllegalArgumentException.class, () -> AudioRules.pcm(new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> AudioRules.pcm(new byte[AudioRules.MAX_PCM + 2]));
    }
    @Test void orderedTransferRoundTrip() {
        byte[] pcm = WavReader.decode(wav(22050, 1, 16, 22050));
        Assembly assembly = new Assembly(pcm.length);
        assertFalse(assembly.append(0, java.util.Arrays.copyOfRange(pcm, 0, AudioRules.CHUNK)));
        assertTrue(assembly.append(1, java.util.Arrays.copyOfRange(pcm, AudioRules.CHUNK, pcm.length)));
        assertArrayEquals(pcm, assembly.finish());
    }
    @Test void rejectsOutOfOrderDuplicateIncompleteAndOverrunTransfers() {
        assertThrows(IllegalArgumentException.class, () -> new Assembly(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new Assembly(3));
        Assembly assembly = new Assembly(24002);
        assertThrows(IllegalStateException.class, assembly::finish);
        assertThrows(IllegalArgumentException.class, () -> assembly.append(1, new byte[24000]));
        assertFalse(assembly.append(0, new byte[24000]));
        assertThrows(IllegalArgumentException.class, () -> assembly.append(0, new byte[24000]));
        assertThrows(IllegalArgumentException.class, () -> assembly.append(1, new byte[4]));
        assertTrue(assembly.append(1, new byte[2]));
        assertThrows(IllegalArgumentException.class, () -> assembly.append(2, new byte[0]));
    }
}
