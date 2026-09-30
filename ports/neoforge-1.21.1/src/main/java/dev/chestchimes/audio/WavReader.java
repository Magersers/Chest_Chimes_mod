package dev.chestchimes.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** RIFF PCM WAV, mono/stereo, 8/16 bit; all lengths are checked before reading. */
public final class WavReader {
    private WavReader() {}
    public static byte[] decode(byte[] file) {
        AudioRules.sourceSize(file.length);
        ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        if (file.length < 44 || !tag(file, 0, "RIFF") || !tag(file, 8, "WAVE")) throw invalid();
        long end = Integer.toUnsignedLong(b.getInt(4)) + 8;
        if (end > file.length || end < 44) throw invalid();
        int channels = 0, rate = 0, bits = 0, offset = -1, size = 0;
        for (long cursor = 12; cursor + 8 <= end;) {
            int p = (int) cursor;
            long length = Integer.toUnsignedLong(b.getInt(p + 4));
            if (cursor + 8 + length > end) throw invalid();
            if (tag(file, p, "fmt ")) {
                if (length < 16 || b.getShort(p + 8) != 1) throw invalid();
                channels = Short.toUnsignedInt(b.getShort(p + 10));
                rate = b.getInt(p + 12);
                bits = Short.toUnsignedInt(b.getShort(p + 22));
                if ((channels != 1 && channels != 2) || rate < 8000 || rate > 96000
                        || (bits != 8 && bits != 16)
                        || Short.toUnsignedInt(b.getShort(p + 20)) != channels * bits / 8) throw invalid();
            } else if (tag(file, p, "data") && offset < 0) {
                offset = p + 8;
                size = (int) length;
            }
            cursor += 8 + length + (length & 1);
        }
        if (offset < 0 || channels == 0 || size == 0 || size % (channels * bits / 8) != 0) throw invalid();
        int frames = Math.min(size / (channels * bits / 8), rate * 5);
        short[] samples = new short[frames * channels];
        b.position(offset);
        for (int i = 0; i < samples.length; i++)
            samples[i] = bits == 16 ? b.getShort() : (short) ((Byte.toUnsignedInt(b.get()) - 128) << 8);
        return normalize(samples, channels, rate, frames);
    }
    public static byte[] normalize(short[] samples, int channels, int rate, int frames) {
        if (channels < 1 || channels > 2 || rate < 8000 || rate > 96000 || frames < 1
                || (long) frames * channels > samples.length) throw invalid();
        int count = (int) Math.min(AudioRules.RATE * 5L, (long) frames * AudioRules.RATE / rate);
        if (count < 1) throw invalid();
        ByteBuffer out = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            double pos = (double) i * rate / AudioRules.RATE;
            int a = Math.min((int) pos, frames - 1), z = Math.min(a + 1, frames - 1);
            double mixed = 0;
            for (int c = 0; c < channels; c++)
                mixed += samples[a * channels + c] * (1 - (pos - a)) + samples[z * channels + c] * (pos - a);
            out.putShort((short) Math.round(mixed / channels));
        }
        return out.array();
    }
    private static boolean tag(byte[] bytes, int at, String value) {
        return new String(bytes, at, 4, StandardCharsets.US_ASCII).equals(value);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("chestchimes.error.wav"); }
}
