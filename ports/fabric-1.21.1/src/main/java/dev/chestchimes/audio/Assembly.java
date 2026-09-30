package dev.chestchimes.audio;

import java.io.ByteArrayOutputStream;

/** Strict ordered, bounded transfer. No allocation based on an unchecked packet length. */
public final class Assembly {
    private final int total;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private int next;
    public Assembly(int total) {
        if (total < 2 || total > AudioRules.MAX_PCM || (total & 1) != 0)
            throw new IllegalArgumentException("Invalid audio length");
        this.total = total;
    }
    public boolean append(int index, byte[] bytes) {
        int expected = Math.min(AudioRules.CHUNK, total - output.size());
        if (index != next || bytes.length != expected || expected == 0)
            throw new IllegalArgumentException("Invalid audio chunk");
        output.writeBytes(bytes);
        next++;
        return output.size() == total;
    }
    public byte[] finish() {
        if (output.size() != total) throw new IllegalStateException("Incomplete audio");
        return output.toByteArray();
    }
}
