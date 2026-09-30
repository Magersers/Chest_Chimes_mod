package dev.chestchimes.audio;

public final class SoundPriority {
    public enum Source { LOCAL, SHARED, VANILLA }
    private SoundPriority() {}
    public static Source choose(Chime local, int sharedBytes) {
        // A local sound at 0% is intentional silence, not permission to play the shared sound.
        if (local != null) return Source.LOCAL;
        return sharedBytes > 0 ? Source.SHARED : Source.VANILLA;
    }
}
