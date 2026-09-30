package dev.chestchimes.audio;

public record Chime(String name, byte[] pcm, int volume) {
    public Chime {
        AudioRules.pcm(pcm);
        AudioRules.volume(volume);
        if (name == null || name.length() > 160) throw new IllegalArgumentException("Invalid sound name");
        pcm = pcm.clone();
    }
    @Override public byte[] pcm() { return pcm.clone(); }
    public int millis() { return Math.max(100, pcm.length * 1000 / (AudioRules.RATE * 2)); }
}
