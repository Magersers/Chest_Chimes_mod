package dev.chestchimes.client;

import dev.chestchimes.audio.Assembly;
import dev.chestchimes.audio.AudioRules;
import dev.chestchimes.network.Wire;
import dev.chestchimes.network.Wire.Message;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class ClientState {
    private record Incoming(BlockPos pos, Assembly data, long expires) {}
    public static Message state;
    public static String status = "";
    private static final Map<UUID, Incoming> INCOMING = new HashMap<>();
    private static final Map<BlockPos, PcmSound> PLAYING = new HashMap<>();
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
                state = message;
                if (mc.screen instanceof ChimeScreen screen && screen.menuId() == message.menu())
                    screen.serverState(message);
            }
            case Wire.REPLY -> {
                if (saving && uploadMenu == message.menu()) {
                    saving = false;
                    upload = null;
                }
                status = message.name();
                if (mc.screen instanceof ChimeScreen screen && screen.menuId() == message.menu())
                    screen.message(message.name(), message.shared());
            }
            case Wire.PLAY_BEGIN -> {
                PcmSound active = PLAYING.get(message.pos());
                if (active != null && !active.finished()) return;
                if (INCOMING.size() >= 32) return;
                INCOMING.values().removeIf(in -> in.pos.equals(message.pos()));
                try {
                    INCOMING.put(message.transfer(), new Incoming(message.pos(), new Assembly(message.total()),
                            System.nanoTime() + 10_000_000_000L));
                } catch (IllegalArgumentException ignored) { }
            }
            case Wire.PLAY_CHUNK -> {
                Incoming incoming = INCOMING.get(message.transfer());
                if (incoming == null || !incoming.pos.equals(message.pos())) return;
                try {
                    if (incoming.data.append(message.index(), message.bytes())) {
                        INCOMING.remove(message.transfer());
                        PcmSound current = PLAYING.get(incoming.pos);
                        if (current == null || current.finished()) {
                            if (current != null) mc.getSoundManager().stop(current);
                            PcmSound sound = new PcmSound(incoming.data.finish(), incoming.pos, false);
                            PLAYING.put(incoming.pos, sound);
                            mc.getSoundManager().play(sound);
                        }
                    }
                } catch (IllegalArgumentException e) { INCOMING.remove(message.transfer()); }
            }
            case Wire.STOP -> {
                INCOMING.values().removeIf(in -> in.pos.equals(message.pos()));
                PcmSound active = PLAYING.remove(message.pos());
                if (active != null) mc.getSoundManager().stop(active);
            }
            default -> { }
        }
    }

    public static void upload(int menu, AudioImporter.Imported selected, int millis, boolean shared) {
        byte[] pcm = AudioRules.trim(selected.pcm(), millis);
        uploadId = UUID.randomUUID();
        uploadMenu = menu;
        uploadIndex = 0;
        upload = pcm;
        saving = true;
        saveDeadline = System.nanoTime() + 30_000_000_000L;
        Wire.toServer(new Message(Wire.BEGIN, menu, uploadId, BlockPos.ZERO, shared,
                selected.name(), pcm.length, selected.sourceBytes(), new byte[0]));
    }

    public static void action(int type, int menu, int millis, boolean shared) {
        upload = null;
        uploadMenu = menu;
        saving = true;
        saveDeadline = System.nanoTime() + 30_000_000_000L;
        Wire.toServer(new Message(type, menu, UUID.randomUUID(), BlockPos.ZERO, shared, "", millis, 0, new byte[0]));
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        checkDimension();
        INCOMING.values().removeIf(in -> System.nanoTime() > in.expires);
        PLAYING.entrySet().removeIf(entry -> {
            if (!entry.getValue().finished()) return false;
            mc.getSoundManager().stop(entry.getValue());
            return true;
        });
        if (saving && System.nanoTime() > saveDeadline) {
            upload = null;
            saving = false;
            if (mc.screen instanceof ChimeScreen screen) screen.message("chestchimes.error.timeout", false);
        }
        if (upload == null) return;
        if (mc.player == null || mc.player.containerMenu.containerId != uploadMenu) {
            upload = null; saving = false; return;
        }
        int offset = uploadIndex * AudioRules.CHUNK;
        byte[] chunk = Arrays.copyOfRange(upload, offset, Math.min(upload.length, offset + AudioRules.CHUNK));
        Wire.toServer(new Message(Wire.CHUNK, uploadMenu, uploadId, BlockPos.ZERO, false, "", 0, uploadIndex++, chunk));
        if (offset + chunk.length == upload.length) upload = null;
    }

    private static void checkDimension() {
        ResourceKey<Level> current = Minecraft.getInstance().level.dimension();
        if (!current.equals(dimension)) {
            clear();
            dimension = current;
        }
    }

    public static void preview(byte[] pcm, int millis) {
        stopPreview();
        preview = new PcmSound(AudioRules.trim(pcm, millis), BlockPos.ZERO, true);
        Minecraft.getInstance().getSoundManager().play(preview);
    }
    public static void stopPreview() {
        if (preview != null) Minecraft.getInstance().getSoundManager().stop(preview);
        preview = null;
    }
    public static void clear() {
        var sounds = Minecraft.getInstance().getSoundManager();
        PLAYING.values().forEach(sounds::stop);
        PLAYING.clear(); INCOMING.clear(); stopPreview();
        state = null; status = ""; upload = null; saving = false; dimension = null;
    }
}
