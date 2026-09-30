package dev.chestchimes.client;

import dev.chestchimes.audio.*;
import dev.chestchimes.network.Wire;
import dev.chestchimes.network.Wire.Message;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

public final class ClientState {
    private record Incoming(Message header, Assembly data, long expires) {}
    private record Playing(PcmSound sound, boolean local) {}
    public static Message state;
    public static Chime sharedSound;
    private static net.minecraft.world.inventory.AbstractContainerMenu stateMenu;
    private static final Map<UUID, Incoming> INCOMING = new HashMap<>();
    private static final Map<String, Playing> PLAYING = new HashMap<>();
    private static ResourceKey<Level> dimension;
    private static PcmSound preview;
    private static byte[] upload;
    private static UUID uploadId;
    private static int uploadMenu, uploadIndex;
    public static boolean saving;
    private static long saveDeadline;
    private ClientState() {}

    public static void accept(Message message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        checkDimension();
        switch (message.type()) {
            case Wire.STATE -> {
                if (mc.player == null || mc.player.containerMenu.containerId != message.menu()) return;
                INCOMING.values().removeIf(in -> in.header.type() == Wire.STATE);
                state = message;
                stateMenu = mc.player.containerMenu;
                sharedSound = null;
                if (message.total() > 0) begin(message);
                refreshScreen();
            }
            case Wire.REPLY -> {
                boolean ownReply = saving && uploadMenu == message.menu();
                if (ownReply) { saving = false; upload = null; }
                if (mc.screen instanceof ChimeScreen screen && screen.menuId() == message.menu())
                    screen.reply(message.name(), message.shared(), ownReply);
            }
            case Wire.PLAY_BEGIN -> {
                if (active(message.key())) return;
                Chime local = LocalSounds.get(message.key());
                switch (SoundPriority.choose(local, message.total())) {
                    case LOCAL -> play(message, local, true);
                    case SHARED -> begin(message);
                    case VANILLA -> mc.level.playLocalSound(
                            message.pos().getX() + .5, message.pos().getY() + .5, message.pos().getZ() + .5,
                            message.index() == 1 ? SoundEvents.ENDER_CHEST_OPEN : SoundEvents.CHEST_OPEN,
                            SoundSource.BLOCKS, .5f, .9f + mc.level.random.nextFloat() * .1f, false);
                }
            }
            case Wire.MIGRATE -> {
                if (mc.player != null && mc.player.containerMenu.containerId == message.menu()) begin(message);
            }
            case Wire.STATE_CHUNK, Wire.PLAY_CHUNK, Wire.MIGRATE_CHUNK -> chunk(message);
            case Wire.STOP -> {
                INCOMING.values().removeIf(in -> in.header.type() == Wire.PLAY_BEGIN && in.header.key().equals(message.key()));
                Playing active = PLAYING.get(message.key());
                // Public changes must never interrupt a private override.
                if (active != null && !active.local) stop(message.key());
            }
            default -> { }
        }
    }
    private static void begin(Message header) {
        try {
            AudioRules.volume(header.volume());
            if (header.key().isBlank() || header.key().length() > 160) return;
            if (header.type() == Wire.PLAY_BEGIN)
                INCOMING.values().removeIf(in -> in.header.type() == Wire.PLAY_BEGIN && in.header.key().equals(header.key()));
            if (INCOMING.size() >= 32) {
                if (header.type() == Wire.PLAY_BEGIN) return;
                INCOMING.entrySet().removeIf(entry -> entry.getValue().header.type() == Wire.PLAY_BEGIN);
            }
            INCOMING.put(header.transfer(), new Incoming(header, new Assembly(header.total()),
                    System.nanoTime() + 10_000_000_000L));
        } catch (IllegalArgumentException ignored) { }
    }
    private static void chunk(Message message) {
        Incoming in = INCOMING.get(message.transfer());
        if (in == null || !in.header.key().equals(message.key())) return;
        int expected = switch (in.header.type()) {
            case Wire.STATE -> Wire.STATE_CHUNK;
            case Wire.MIGRATE -> Wire.MIGRATE_CHUNK;
            default -> Wire.PLAY_CHUNK;
        };
        if (expected != message.type()) return;
        try {
            if (!in.data.append(message.index(), message.bytes())) return;
            INCOMING.remove(message.transfer());
            Chime sound = new Chime(in.header.name(), in.data.finish(), in.header.volume());
            if (in.header.type() == Wire.STATE) {
                if (state != null && state.transfer().equals(in.header.transfer())) { sharedSound = sound; refreshScreen(); }
            } else if (in.header.type() == Wire.MIGRATE) {
                if (LocalSounds.get(in.header.key()) == null) LocalSounds.save(in.header.key(), sound);
                // This is only an acknowledgement; personal audio is never uploaded.
                Wire.toServer(new Message(Wire.MIGRATED, in.header.menu(), in.header.transfer(), BlockPos.ZERO,
                        false, "", 0, 0, new byte[0]));
                if (Minecraft.getInstance().screen instanceof ChimeScreen screen) screen.localMigrated();
            } else if (!active(in.header.key())) {
                Chime local = LocalSounds.get(in.header.key());
                play(in.header, local == null ? sound : local, local != null);
            }
        } catch (java.io.IOException | IllegalArgumentException e) {
            INCOMING.remove(message.transfer());
            if (in.header.type() == Wire.MIGRATE && Minecraft.getInstance().screen instanceof ChimeScreen screen)
                screen.message("chestchimes.error.local", false);
        }
    }
    private static boolean active(String key) {
        Playing current = PLAYING.get(key);
        if (current == null) return false;
        if (!current.sound.finished()) return true;
        stop(key);
        return false;
    }
    private static void play(Message header, Chime sound, boolean local) {
        if (active(header.key())) return;
        PcmSound instance = new PcmSound(sound.pcm(), header.pos(), false, sound.volume());
        PLAYING.put(header.key(), new Playing(instance, local));
        if (sound.volume() > 0) Minecraft.getInstance().getSoundManager().play(instance);
    }
    private static void stop(String key) {
        Playing current = PLAYING.remove(key);
        if (current != null) Minecraft.getInstance().getSoundManager().stop(current.sound);
    }
    public static void localChanged(String key) {
        stop(key);
        INCOMING.values().removeIf(in -> in.header.type() == Wire.PLAY_BEGIN && in.header.key().equals(key));
    }
    private static void refreshScreen() {
        if (Minecraft.getInstance().screen instanceof ChimeScreen screen && screen.menuId() == state.menu())
            screen.serverState();
    }
    public static boolean hasState(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        return state != null && stateMenu == menu && state.menu() == menu.containerId;
    }
    public static void upload(int menu, AudioImporter.Imported selected, int millis, int volume) {
        byte[] pcm = AudioRules.trim(selected.pcm(), millis);
        AudioRules.volume(volume);
        uploadId = UUID.randomUUID(); uploadMenu = menu; uploadIndex = 0; upload = pcm; saving = true;
        saveDeadline = System.nanoTime() + 30_000_000_000L;
        Wire.toServer(new Message(Wire.BEGIN, menu, uploadId, BlockPos.ZERO, true,
                selected.name(), pcm.length, selected.sourceBytes(), new byte[0], "", volume));
    }
    public static void resetShared(int menu) {
        upload = null; uploadMenu = menu; saving = true;
        saveDeadline = System.nanoTime() + 30_000_000_000L;
        Wire.toServer(Message.simple(Wire.RESET, menu));
    }
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        checkDimension();
        INCOMING.values().removeIf(in -> System.nanoTime() > in.expires);
        PLAYING.entrySet().removeIf(entry -> {
            if (!entry.getValue().sound.finished()) return false;
            mc.getSoundManager().stop(entry.getValue().sound); return true;
        });
        if (saving && System.nanoTime() > saveDeadline) {
            upload = null; saving = false;
            if (mc.screen instanceof ChimeScreen screen) screen.reply("chestchimes.error.timeout", false, true);
        }
        if (upload == null) return;
        if (mc.player == null || mc.player.containerMenu.containerId != uploadMenu) {
            upload = null; saving = false; return;
        }
        int offset = uploadIndex * AudioRules.CHUNK;
        byte[] chunk = Arrays.copyOfRange(upload, offset, Math.min(upload.length, offset + AudioRules.CHUNK));
        Wire.toServer(new Message(Wire.CHUNK, uploadMenu, uploadId, BlockPos.ZERO, true, "", 0, uploadIndex++, chunk));
        if (offset + chunk.length == upload.length) upload = null;
    }
    private static void checkDimension() {
        ResourceKey<Level> current = Minecraft.getInstance().level.dimension();
        if (!current.equals(dimension)) { clear(); dimension = current; }
    }
    public static void preview(byte[] pcm, int millis, int volume) {
        stopPreview();
        preview = new PcmSound(AudioRules.trim(pcm, millis), BlockPos.ZERO, true, volume);
        if (volume > 0) Minecraft.getInstance().getSoundManager().play(preview);
    }
    public static void stopPreview() {
        if (preview != null) Minecraft.getInstance().getSoundManager().stop(preview);
        preview = null;
    }
    public static void clear() {
        var sounds = Minecraft.getInstance().getSoundManager();
        PLAYING.values().forEach(active -> sounds.stop(active.sound));
        PLAYING.clear(); INCOMING.clear(); stopPreview(); LocalSounds.clearSession();
        state = null; sharedSound = null; stateMenu = null; upload = null; saving = false; dimension = null;
    }
}
