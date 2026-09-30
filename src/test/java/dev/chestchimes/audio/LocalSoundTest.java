package dev.chestchimes.audio;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalSoundTest {
    @TempDir Path root;
    private Chime sound(int volume) { return new Chime("personal.wav", new byte[4410], volume); }

    @Test void personalOverridesSharedIncludingMute() {
        assertEquals(SoundPriority.Source.LOCAL, SoundPriority.choose(sound(35), 4410));
        assertEquals(SoundPriority.Source.LOCAL, SoundPriority.choose(sound(0), 4410));
        assertEquals(SoundPriority.Source.LOCAL, SoundPriority.choose(sound(0), 0));
        assertEquals(SoundPriority.Source.SHARED, SoundPriority.choose(null, 4410));
        assertEquals(SoundPriority.Source.VANILLA, SoundPriority.choose(null, 0));
    }
    @Test void validatesVolumeBoundaries() {
        assertEquals(0, AudioRules.volume(0));
        assertEquals(100, AudioRules.volume(100));
        assertThrows(IllegalArgumentException.class, () -> sound(-1));
        assertThrows(IllegalArgumentException.class, () -> sound(101));
    }
    @Test void personalSoundSurvivesRestartWithItsVolume() throws Exception {
        new LocalSoundStore(root).save("world/dimension/chest", sound(27));
        Chime loaded = new LocalSoundStore(root).get("world/dimension/chest");
        assertEquals("personal.wav", loaded.name());
        assertEquals(27, loaded.volume());
        assertArrayEquals(new byte[4410], loaded.pcm());
    }
    @Test void accountsWorldsDimensionsAndChestsStaySeparate() throws Exception {
        LocalSoundStore alice = new LocalSoundStore(root.resolve("alice/server1"));
        LocalSoundStore bob = new LocalSoundStore(root.resolve("bob/server1"));
        LocalSoundStore otherServer = new LocalSoundStore(root.resolve("alice/server2"));
        alice.save("world1/overworld/chest1", sound(50));
        assertNull(bob.get("world1/overworld/chest1"));
        assertNull(otherServer.get("world1/overworld/chest1"));
        assertNull(alice.get("world2/overworld/chest1"));
        assertNull(alice.get("world1/nether/chest1"));
        assertNull(alice.get("world1/overworld/chest2"));
    }
    @Test void deletingLocalRevealsSharedThenVanilla() throws Exception {
        LocalSoundStore store = new LocalSoundStore(root);
        store.save("chest", sound(0));
        store.remove("chest");
        assertNull(new LocalSoundStore(root).get("chest"));
        assertEquals(SoundPriority.Source.SHARED, SoundPriority.choose(store.get("chest"), 4410));
        assertEquals(SoundPriority.Source.VANILLA, SoundPriority.choose(store.get("chest"), 0));
    }
    @Test void replaceIsDurableAndLeavesNoTemporaryFiles() throws Exception {
        LocalSoundStore store = new LocalSoundStore(root);
        store.save("chest", sound(80));
        store.save("chest", sound(0));
        assertEquals(0, new LocalSoundStore(root).get("chest").volume());
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }
    @Test void corruptLocalRecordingIsRejectedWithoutDeletingIt() throws Exception {
        LocalSoundStore store = new LocalSoundStore(root);
        store.save("chest", sound(80));
        Path file;
        try (var files = Files.list(root)) { file = files.findFirst().orElseThrow(); }
        Files.write(file, new byte[] {1, 2, 3});
        assertThrows(java.io.IOException.class, () -> new LocalSoundStore(root).get("chest"));
        assertTrue(Files.exists(file));
    }
    @Test void keysCannotEscapeLocalDirectory() throws Exception {
        new LocalSoundStore(root).save("../../escape", sound(20));
        try (var files = Files.list(root)) {
            Path file = files.findFirst().orElseThrow();
            assertEquals(root, file.getParent());
            assertTrue(file.getFileName().toString().matches("[a-f0-9]{64}\\.chime"));
        }
    }
    @Test void chimeOwnsItsAudioBytes() {
        byte[] bytes = new byte[10];
        Chime sound = new Chime("test", bytes, 100);
        bytes[0] = 1;
        byte[] exposed = sound.pcm(); exposed[1] = 2;
        assertArrayEquals(new byte[10], sound.pcm());
    }
}
