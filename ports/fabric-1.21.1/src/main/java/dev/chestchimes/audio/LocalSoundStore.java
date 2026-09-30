package dev.chestchimes.audio;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Local-only persistence. This class has no networking or server dependencies. */
public final class LocalSoundStore {
    private static final int MAGIC = 0x43434C32;
    private final Path root;
    private final Map<String, Optional<Chime>> cache = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Optional<Chime>> entry) { return size() > 128; }
    };
    public LocalSoundStore(Path root) { this.root = root; }

    public synchronized Chime get(String key) throws IOException {
        validateKey(key);
        Optional<Chime> cached = cache.get(key);
        if (cached != null) return cached.orElse(null);
        Path path = path(key);
        if (!Files.exists(path)) { cache.put(key, Optional.empty()); return null; }
        if (Files.size(path) > AudioRules.MAX_PCM + 2048) throw new IOException("Oversized local sound");
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            if (input.readInt() != MAGIC || !input.readUTF().equals(key)) throw new IOException("Invalid local sound");
            String name = input.readUTF();
            int volume = input.readInt(), length = input.readInt();
            if (length < 2 || length > AudioRules.MAX_PCM || (length & 1) != 0) throw new IOException("Invalid PCM length");
            byte[] pcm = input.readNBytes(length);
            if (pcm.length != length || input.read() != -1) throw new IOException("Truncated local sound");
            Chime value = new Chime(name, pcm, volume);
            cache.put(key, Optional.of(value));
            return value;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid local sound", e); }
    }

    public synchronized void save(String key, Chime value) throws IOException {
        validateKey(key);
        Objects.requireNonNull(value);
        Files.createDirectories(root);
        Path temporary = Files.createTempFile(root, "saving-", ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporary))) {
                output.writeInt(MAGIC); output.writeUTF(key); output.writeUTF(value.name());
                output.writeInt(value.volume());
                byte[] pcm = value.pcm();
                output.writeInt(pcm.length); output.write(pcm);
            }
            try { Files.move(temporary, path(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path(key), StandardCopyOption.REPLACE_EXISTING); }
            cache.put(key, Optional.of(value));
        } finally { Files.deleteIfExists(temporary); }
    }
    public synchronized void remove(String key) throws IOException {
        validateKey(key);
        Files.deleteIfExists(path(key));
        cache.put(key, Optional.empty());
    }
    private Path path(String key) { return root.resolve(hash(key) + ".chime"); }
    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 512) throw new IllegalArgumentException("Invalid chest key");
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
