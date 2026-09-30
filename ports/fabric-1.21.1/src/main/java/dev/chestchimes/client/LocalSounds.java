package dev.chestchimes.client;

import dev.chestchimes.audio.*;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;

public final class LocalSounds {
    private static LocalSoundStore store;
    private static Path currentRoot;
    private static final java.util.Set<String> failed = new java.util.HashSet<>();
    private LocalSounds() {}
    private static LocalSoundStore store() {
        Minecraft mc = Minecraft.getInstance();
        String account = mc.getUser().getProfileId().toString();
        String server = mc.getCurrentServer() == null ? "singleplayer" : mc.getCurrentServer().ip;
        Path root = mc.gameDirectory.toPath().resolve("config/chestchimes/local")
                .resolve(LocalSoundStore.hash(account + "/" + server));
        if (!root.equals(currentRoot)) { currentRoot = root; store = new LocalSoundStore(root); failed.clear(); }
        return store;
    }
    public static Chime get(String key) {
        try { return store().get(key); }
        catch (IOException | IllegalArgumentException e) {
            if (failed.add(key)) org.slf4j.LoggerFactory.getLogger("ChestChimes").warn("Cannot read local chest sound: {}", e.getMessage());
            return null;
        }
    }
    public static void save(String key, Chime sound) throws IOException { store().save(key, sound); failed.remove(key); }
    public static void remove(String key) throws IOException { store().remove(key); failed.remove(key); }
    public static void clearSession() { store = null; currentRoot = null; failed.clear(); }
}
