package dev.chestchimes.server;

import dev.chestchimes.audio.*;
import dev.chestchimes.network.Wire;
import dev.chestchimes.network.Wire.Message;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.event.PlayLevelSoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class ChestService {
    private static final String KEY = "chestchimes", ID = "chestchimes_id", LEGACY = "chestchimes_legacy_private";
    private static final UUID ZERO = new UUID(0, 0);
    private record Candidate(BlockEntity chest, long tick) {}
    private record Context(BlockEntity chest, int menu) {}
    private record Upload(UUID id, Assembly assembly, String name, int volume, long expires) {}
    private record Migration(UUID id, BlockEntity chest) {}
    private static final Map<UUID, Candidate> PENDING = new HashMap<>();
    private static final Map<UUID, Context> OPEN = new HashMap<>();
    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
    private static final Map<UUID, Migration> MIGRATIONS = new HashMap<>();
    private static final Map<UUID, Long> RATE_LIMIT = new HashMap<>();
    private static final Map<String, Long> LAST_OPEN = new HashMap<>();
    private ChestService() {}

    public static void capture(Player player, BlockEntity chest) {
        if (player instanceof ServerPlayer && !player.isSpectator() && !chest.isRemoved()) {
            migrateLegacy(chest);
            PENDING.put(player.getUUID(), new Candidate(chest, player.level().getGameTime()));
        }
    }
    public static BlockEntity canonical(BlockEntity chest) {
        if (chest instanceof ChestBlockEntity && chest.getLevel() != null) {
            var state = chest.getBlockState();
            if (state.getValue(ChestBlock.TYPE) == ChestType.LEFT) {
                BlockEntity right = chest.getLevel().getBlockEntity(
                        chest.getBlockPos().relative(ChestBlock.getConnectedDirection(state)));
                if (right instanceof ChestBlockEntity) return right;
            }
        }
        return chest;
    }
    private static List<BlockEntity> halves(BlockEntity chest) {
        List<BlockEntity> result = new ArrayList<>();
        result.add(chest);
        if (chest instanceof ChestBlockEntity && chest.getLevel() != null) {
            var state = chest.getBlockState();
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockEntity other = chest.getLevel().getBlockEntity(
                        chest.getBlockPos().relative(ChestBlock.getConnectedDirection(state)));
                if (other instanceof ChestBlockEntity) result.add(other);
            }
        }
        return result;
    }
    public static String identity(BlockEntity chest) {
        chest = canonical(chest);
        CompoundTag data = chest.getPersistentData();
        UUID id = data.hasUUID(ID) ? data.getUUID(ID) : UUID.randomUUID();
        for (BlockEntity half : halves(chest)) {
            if (!half.getPersistentData().hasUUID(ID) || !half.getPersistentData().getUUID(ID).equals(id)) {
                half.getPersistentData().putUUID(ID, id); half.setChanged();
            }
        }
        ServerLevel level = (ServerLevel) chest.getLevel();
        return WorldIdentity.get(level) + "/" + UUID.nameUUIDFromBytes(level.dimension().location().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "/" + data.getUUID(ID);
    }
    public static CompoundTag settings(BlockEntity chest) {
        CompoundTag tag = canonical(chest).getPersistentData().getCompound(KEY);
        // A v1 private recording must never become public while waiting for migration.
        return tag.hasUUID("owner") && !tag.getBoolean("shared") ? new CompoundTag() : tag;
    }
    private static boolean isChest(BlockEntity chest) {
        return chest instanceof ChestBlockEntity || chest instanceof EnderChestBlockEntity;
    }
    private static byte[] audio(CompoundTag tag) {
        byte[] pcm = tag.getByteArray("pcm");
        return pcm.length >= 2 && pcm.length <= AudioRules.MAX_PCM && (pcm.length & 1) == 0 ? pcm : new byte[0];
    }
    private static int volume(CompoundTag tag) {
        return tag.contains("volume") ? Math.max(0, Math.min(100, tag.getInt("volume"))) : 100;
    }
    private static boolean matches(ServerPlayer player, BlockEntity chest) {
        if (!(player.containerMenu instanceof ChestMenu menu)) return false;
        var inventory = menu.getContainer();
        return inventory == chest
                || inventory instanceof CompoundContainer compound && chest instanceof net.minecraft.world.Container container
                   && compound.contains(container)
                || chest instanceof EnderChestBlockEntity ender && inventory == player.getEnderChestInventory()
                   && player.getEnderChestInventory().isActiveChest(ender);
    }
    @SubscribeEvent public static void opened(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Candidate candidate = PENDING.remove(player.getUUID());
        if (candidate == null || candidate.tick != player.level().getGameTime() || !matches(player, candidate.chest)) return;
        BlockEntity chest = canonical(candidate.chest);
        OPEN.put(player.getUUID(), new Context(chest, player.containerMenu.containerId));
        state(player, chest);
        sendMigration(player, chest);
        play(player.serverLevel(), chest);
    }
    @SubscribeEvent public static void closed(PlayerContainerEvent.Close event) {
        UUID id = event.getEntity().getUUID();
        OPEN.remove(id); UPLOADS.remove(id); MIGRATIONS.remove(id);
    }
    @SubscribeEvent public static void vanillaSound(PlayLevelSoundEvent.AtPosition event) {
        if (event.getLevel().isClientSide || event.getSound() == null) return;
        var sound = event.getSound().value();
        if (sound == SoundEvents.CHEST_CLOSE || sound == SoundEvents.ENDER_CHEST_CLOSE) {
            BlockEntity chest = event.getLevel().getBlockEntity(BlockPos.containing(event.getPosition()));
            if (isChest(chest)) {
                chest = canonical(chest);
                String key = identity(chest);
                // The vanilla opener counter emits this only when the last viewer
                // closes the lid (and only once for a double chest).
                // Stop both personal and shared melodies before the vanilla packet.
                for (ServerPlayer listener : ((ServerLevel) event.getLevel()).players())
                    Wire.toPlayer(listener, new Message(Wire.CLOSE, 0, ZERO, chest.getBlockPos(), false,
                            "", 0, 0, new byte[0], key, 100));
                LAST_OPEN.remove(key);
            }
            return; // Keep Minecraft's sound, position, volume and pitch unchanged.
        }
        if (sound != SoundEvents.CHEST_OPEN && sound != SoundEvents.ENDER_CHEST_OPEN) return;
        // Every opening is routed to clients so private overrides also work with an unconfigured chest.
        if (isChest(event.getLevel().getBlockEntity(BlockPos.containing(event.getPosition())))) event.setCanceled(true);
    }
    private static BlockEntity authorized(ServerPlayer player, int menu) {
        Context c = OPEN.get(player.getUUID());
        if (c == null || c.menu != menu || player.containerMenu.containerId != menu || player.isSpectator()
                || c.chest.isRemoved() || c.chest.getLevel() != player.level()
                || player.distanceToSqr(c.chest.getBlockPos().getCenter()) > 64
                || !player.containerMenu.stillValid(player) || !matches(player, c.chest)
                || player.level().getBlockEntity(c.chest.getBlockPos()) != c.chest) return null;
        return c.chest;
    }
    public static void receive(ServerPlayer player, Message message) {
        BlockEntity chest = authorized(player, message.menu());
        if (chest == null) {
            UPLOADS.remove(player.getUUID());
            reply(player, message.menu(), "chestchimes.error.closed", false);
            return;
        }
        try {
            long now = player.server.overworld().getGameTime();
            switch (message.type()) {
                case Wire.BEGIN -> {
                    if (!message.shared()) throw new IllegalArgumentException("Private audio is local only");
                    AudioRules.volume(message.volume());
                    AudioRules.sourceSize(message.index());
                    if (now < RATE_LIMIT.getOrDefault(player.getUUID(), 0L)) {
                        reply(player, message.menu(), "chestchimes.error.wait", false);
                        return;
                    }
                    RATE_LIMIT.put(player.getUUID(), now + 40);
                    UPLOADS.put(player.getUUID(), new Upload(message.transfer(), new Assembly(message.total()),
                            cleanName(message.name()), message.volume(), now + 600));
                }
                case Wire.CHUNK -> {
                    Upload upload = UPLOADS.get(player.getUUID());
                    if (upload == null || !upload.id.equals(message.transfer()) || now > upload.expires)
                        throw new IllegalArgumentException("Expired upload");
                    if (upload.assembly.append(message.index(), message.bytes())) {
                        byte[] pcm = upload.assembly.finish();
                        AudioRules.pcm(pcm);
                        CompoundTag tag = new CompoundTag();
                        tag.putByteArray("pcm", pcm); tag.putString("name", upload.name);
                        tag.putBoolean("shared", true); tag.putInt("volume", upload.volume);
                        write(chest, tag);
                        UPLOADS.remove(player.getUUID());
                        changed(player, chest, "chestchimes.saved");
                    }
                }
                case Wire.RESET -> {
                    UPLOADS.remove(player.getUUID());
                    write(chest, new CompoundTag());
                    changed(player, chest, "chestchimes.reset_done");
                }
                case Wire.ADJUST -> {
                    if (!message.shared()) throw new IllegalArgumentException("Private audio is local only");
                    AudioRules.volume(message.volume());
                    UPLOADS.remove(player.getUUID());
                    CompoundTag tag = settings(chest).copy();
                    tag.putByteArray("pcm", AudioRules.trim(audio(tag), message.total()));
                    tag.putInt("volume", message.volume());
                    tag.remove("owner"); tag.putBoolean("shared", true);
                    write(chest, tag);
                    changed(player, chest, "chestchimes.saved");
                }
                case Wire.MIGRATED -> {
                    Migration migration = MIGRATIONS.get(player.getUUID());
                    CompoundTag legacy = chest.getPersistentData().getCompound(LEGACY);
                    if (migration != null && migration.chest == chest && migration.id.equals(message.transfer())
                            && legacy.hasUUID("owner") && legacy.getUUID("owner").equals(player.getUUID())) {
                        for (BlockEntity half : halves(chest)) { half.getPersistentData().remove(LEGACY); half.setChanged(); }
                        MIGRATIONS.remove(player.getUUID());
                    }
                }
                default -> { }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            UPLOADS.remove(player.getUUID());
            reply(player, message.menu(), "chestchimes.error.audio", false);
        }
    }
    private static String cleanName(String name) {
        String clean = name.replaceAll("[\\p{Cntrl}§/\\\\]", "_");
        return clean.substring(0, Math.min(clean.length(), 80));
    }
    public static void write(BlockEntity chest, CompoundTag tag) {
        for (BlockEntity half : halves(chest)) {
            if (tag.isEmpty()) half.getPersistentData().remove(KEY);
            else half.getPersistentData().put(KEY, tag.copy());
            half.setChanged();
        }
    }
    private static void state(ServerPlayer player, BlockEntity chest) {
        CompoundTag tag = settings(chest);
        byte[] pcm = audio(tag);
        UUID transfer = UUID.randomUUID();
        Message header = new Message(Wire.STATE, player.containerMenu.containerId, transfer, chest.getBlockPos(),
                true, tag.getString("name"), pcm.length, 0, new byte[0], identity(chest), volume(tag));
        Wire.toPlayer(player, header);
        chunks(player, header, Wire.STATE_CHUNK, pcm);
    }
    private static void chunks(ServerPlayer player, Message header, int type, byte[] pcm) {
        for (int offset = 0, index = 0; offset < pcm.length; offset += AudioRules.CHUNK, index++)
            Wire.toPlayer(player, new Message(type, header.menu(), header.transfer(), header.pos(), true, "", 0, index,
                    Arrays.copyOfRange(pcm, offset, Math.min(pcm.length, offset + AudioRules.CHUNK)),
                    header.key(), header.volume()));
    }
    private static void reply(ServerPlayer player, int menu, String key, boolean ok) {
        Wire.toPlayer(player, new Message(Wire.REPLY, menu, ZERO, BlockPos.ZERO, ok, key, 0, 0, new byte[0]));
    }
    private static void changed(ServerPlayer sender, BlockEntity chest, String reply) {
        String key = identity(chest);
        for (ServerPlayer player : sender.serverLevel().players())
            Wire.toPlayer(player, new Message(Wire.STOP, 0, ZERO, chest.getBlockPos(), true, "", 0, 0,
                    new byte[0], key, 100));
        // Include fake players in integration tests; normal players are all in this list.
        Set<ServerPlayer> viewers = new HashSet<>(sender.serverLevel().players());
        viewers.add(sender);
        for (ServerPlayer viewer : viewers) {
            BlockEntity open = authorized(viewer, viewer.containerMenu.containerId);
            if (open == chest) state(viewer, chest);
        }
        reply(sender, sender.containerMenu.containerId, reply, true);
    }
    private static void play(ServerLevel level, BlockEntity chest) {
        String key = identity(chest);
        long now = level.getGameTime();
        if (LAST_OPEN.getOrDefault(key, -1L) == now) return;
        LAST_OPEN.put(key, now);
        CompoundTag tag = settings(chest);
        byte[] pcm = audio(tag);
        for (ServerPlayer listener : level.players()) {
            if (listener.distanceToSqr(chest.getBlockPos().getCenter()) > 256) continue;
            Message header = new Message(Wire.PLAY_BEGIN, 0, UUID.randomUUID(), chest.getBlockPos(), true, "",
                    pcm.length, chest instanceof EnderChestBlockEntity ? 1 : 0, new byte[0], key, volume(tag));
            Wire.toPlayer(listener, header);
            chunks(listener, header, Wire.PLAY_CHUNK, pcm);
        }
    }
    private static void migrateLegacy(BlockEntity chest) {
        chest = canonical(chest);
        CompoundTag old = chest.getPersistentData().getCompound(KEY);
        if (!old.hasUUID("owner") || old.getBoolean("shared")) return;
        for (BlockEntity half : halves(chest)) {
            half.getPersistentData().put(LEGACY, old.copy());
            half.getPersistentData().remove(KEY);
            half.setChanged();
        }
    }
    private static void sendMigration(ServerPlayer player, BlockEntity chest) {
        CompoundTag legacy = chest.getPersistentData().getCompound(LEGACY);
        if (!legacy.hasUUID("owner") || !legacy.getUUID("owner").equals(player.getUUID())) return;
        byte[] pcm = audio(legacy);
        if (pcm.length == 0) return;
        UUID transfer = UUID.randomUUID();
        MIGRATIONS.put(player.getUUID(), new Migration(transfer, chest));
        Message header = new Message(Wire.MIGRATE, player.containerMenu.containerId, transfer, chest.getBlockPos(),
                false, legacy.getString("name"), pcm.length, 0, new byte[0], identity(chest), volume(legacy));
        Wire.toPlayer(player, header);
        chunks(player, header, Wire.MIGRATE_CHUNK, pcm);
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().overworld().getGameTime();
        UPLOADS.entrySet().removeIf(entry -> !OPEN.containsKey(entry.getKey()) || now > entry.getValue().expires);
        PENDING.clear(); LAST_OPEN.clear();
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        PENDING.remove(id); OPEN.remove(id); UPLOADS.remove(id); RATE_LIMIT.remove(id); MIGRATIONS.remove(id);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        PENDING.clear(); OPEN.clear(); UPLOADS.clear(); RATE_LIMIT.clear(); MIGRATIONS.clear(); LAST_OPEN.clear();
    }
}
