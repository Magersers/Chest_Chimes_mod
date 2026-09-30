package dev.chestchimes.audio;

public final class AudioRules {
    public static final int MAX_FILE = 10_000_000;
    public static final int RATE = 22050;
    public static final int MAX_MILLIS = 5000;
    public static final int MAX_PCM = RATE * 2 * 5;
    public static final int CHUNK = 24_000;
    private AudioRules() {}

    public static void sourceSize(long bytes) {
        if (bytes <= 0 || bytes > MAX_FILE) throw new IllegalArgumentException("chestchimes.error.size");
    }

    public static int volume(int percent) {
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("chestchimes.error.volume");
        return percent;
    }

    public static void pcm(byte[] bytes) {
        if (bytes.length < 2 || bytes.length > MAX_PCM || (bytes.length & 1) != 0)
            throw new IllegalArgumentException("chestchimes.error.audio");
    }

    public static byte[] trim(byte[] bytes, int millis) {
        pcm(bytes);
        if (millis < 100 || millis > MAX_MILLIS) throw new IllegalArgumentException("chestchimes.error.duration");
        return java.util.Arrays.copyOf(bytes, Math.min(bytes.length, RATE * millis / 1000 * 2));
    }
}
