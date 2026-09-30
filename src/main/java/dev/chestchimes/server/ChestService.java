package dev.chestchimes.server;

import dev.chestchimes.audio.Assembly;
import dev.chestchimes.audio.AudioRules;
import dev.chestchimes.network.Wire;
import dev.chestchimes.network.Wire.Message;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.Level;
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
    private static final String KEY = "chestchimes";
    private static final UUID ZERO = new UUID(0, 0);
    private record Candidate(BlockEntity chest, long tick) {}
    private record Context(BlockEntity chest, int menu) {}
    private record Upload(UUID id, Assembly assembly, String name, boolean shared, long expires) {}
    private static final Map<UUID, Candidate> PENDING = new HashMap<>();
    private static final Map<UUID, Context> OPEN = new HashMap<>();
    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
    private static final Map<UUID, Long> RATE_LIMIT = new HashMap<>();
    private static final Map<UUID, Map<String, Long>> PLAYING = new HashMap<>();

    private ChestService() {}

    public static void capture(Player player, BlockEntity chest) {
        if (player instanceof ServerPlayer && !player.isSpectator() && !chest.isRemoved())
            PENDING.put(player.getUUID(), new Candidate(chest, player.level().getGameTime()));
    }

    public static BlockEntity canonical(BlockEntity chest) {
        if (chest instanceof ChestBlockEntity && chest.getLevel() != null) {
            var state = chest.getBlockState();
            if (state.getValue(ChestBlock.TYPE) == ChestType.LEFT) {
                BlockPos other = chest.getBlockPos().relative(ChestBlock.getConnectedDirection(state));
                BlockEntity right = chest.getLevel().getBlockEntity(other);
                if (right instanceof ChestBlockEntity) return right;
            }
        }
        return chest;
    }

    public static CompoundTag settings(BlockEntity chest) {
        return canonical(chest).getPersistentData().getCompound(KEY);
    }

    private static boolean configured(BlockEntity chest) {
        if (chest == null) return false;
        if (!(chest instanceof ChestBlockEntity) && !(chest instanceof EnderChestBlockEntity)) return false;
        int size = settings(chest).getByteArray("pcm").length;
        return size >= 2 && size <= AudioRules.MAX_PCM && (size & 1) == 0;
    }

    private static boolean matches(ServerPlayer player, BlockEntity chest) {
        if (!(player.containerMenu instanceof ChestMenu menu)) return false;
        var inventory = menu.getContainer();
        return inventory == chest
                || inventory instanceof CompoundContainer compound && chest instanceof net.minecraft.world.Container container && compound.contains(container)
                || chest instanceof EnderChestBlockEntity ender && inventory == player.getEnderChestInventory()
                   && player.getEnderChestInventory().isActiveChest(ender);
    }

    @SubscribeEvent
    public static void opened(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Candidate candidate = PENDING.remove(player.getUUID());
        if (candidate == null || candidate.tick != player.level().getGameTime()
                || !matches(player, candidate.chest)) return;
        BlockEntity chest = canonical(candidate.chest);
        OPEN.put(player.getUUID(), new Context(chest, player.containerMenu.containerId));
        state(player, chest);
        if (configured(chest)) play(player.serverLevel(), chest);
    }

    @SubscribeEvent
    public static void closed(PlayerContainerEvent.Close event) {
        UUID id = event.getEntity().getUUID();
        OPEN.remove(id);
        UPLOADS.remove(id);
    }

    @SubscribeEvent
    public static void vanillaSound(PlayLevelSoundEvent.AtPosition event) {
        if (event.getLevel().isClientSide || event.getSound() == null) return;
        var sound = event.getSound().value();
        if (sound != SoundEvents.CHEST_OPEN && sound != SoundEvents.ENDER_CHEST_OPEN) return;
        // The midpoint of a double chest always falls inside one of its two blocks.
        BlockEntity chest = event.getLevel().getBlockEntity(BlockPos.containing(event.getPosition()));
        if (configured(chest)) event.setCanceled(true);
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
                    if (now < RATE_LIMIT.getOrDefault(player.getUUID(), 0L)) {
                        reply(player, message.menu(), "chestchimes.error.wait", false);
                        return;
                    }
                    AudioRules.sourceSize(message.index());
                    RATE_LIMIT.put(player.getUUID(), now + 40);
                    UPLOADS.put(player.getUUID(), new Upload(message.transfer(), new Assembly(message.total()),
                            cleanName(message.name()), message.shared(), now + 600));
                }
                case Wire.CHUNK -> {
                    Upload upload = UPLOADS.get(player.getUUID());
                    if (upload == null || !upload.id.equals(message.transfer()) || now > upload.expires)
                        throw new IllegalArgumentException("Expired upload");
                    if (upload.assembly.append(message.index(), message.bytes())) {
                        byte[] pcm = upload.assembly.finish();
                        AudioRules.pcm(pcm);
                        CompoundTag tag = new CompoundTag();
                        tag.putByteArray("pcm", pcm);
                        tag.putString("name", upload.name);
                        tag.putBoolean("shared", upload.shared);
                        tag.putUUID("owner", player.getUUID());
                        write(chest, tag);
                        UPLOADS.remove(player.getUUID());
                        stop(player.serverLevel(), chest);
                        state(player, chest);
                        reply(player, message.menu(), "chestchimes.saved", true);
                    }
                }
                case Wire.RESET -> {
                    UPLOADS.remove(player.getUUID());
                    write(chest, new CompoundTag());
                    stop(player.serverLevel(), chest);
                    state(player, chest);
                    reply(player, message.menu(), "chestchimes.reset_done", true);
                }
                case Wire.ADJUST -> {
                    UPLOADS.remove(player.getUUID());
                    if (!configured(chest)) throw new IllegalArgumentException("No sound");
                    CompoundTag tag = settings(chest).copy();
                    tag.putByteArray("pcm", AudioRules.trim(tag.getByteArray("pcm"), message.total()));
                    tag.putBoolean("shared", message.shared());
                    tag.putUUID("owner", player.getUUID());
                    write(chest, tag);
                    stop(player.serverLevel(), chest);
                    state(player, chest);
                    reply(player, message.menu(), "chestchimes.saved", true);
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
        set(chest, tag);
        if (chest instanceof ChestBlockEntity) {
            var state = chest.getBlockState();
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockEntity other = chest.getLevel().getBlockEntity(
                        chest.getBlockPos().relative(ChestBlock.getConnectedDirection(state)));
                if (other instanceof ChestBlockEntity) set(other, tag.copy());
            }
        }
    }

    private static void set(BlockEntity chest, CompoundTag tag) {
        if (tag.isEmpty()) chest.getPersistentData().remove(KEY);
        else chest.getPersistentData().put(KEY, tag);
        chest.setChanged();
    }

    private static void state(ServerPlayer player, BlockEntity chest) {
        CompoundTag tag = settings(chest);
        Wire.toPlayer(player, new Message(Wire.STATE, player.containerMenu.containerId, ZERO,
                chest.getBlockPos(), tag.getBoolean("shared"), tag.getString("name"),
                tag.getByteArray("pcm").length, 0, new byte[0]));
    }

    private static void reply(ServerPlayer player, int menu, String key, boolean ok) {
        Wire.toPlayer(player, new Message(Wire.REPLY, menu, ZERO, BlockPos.ZERO, ok, key, 0, 0, new byte[0]));
    }

    private static String key(Level level, BlockPos pos) { return level.dimension().location() + "/" + pos.asLong(); }

    private static void play(ServerLevel level, BlockEntity chest) {
        CompoundTag tag = settings(chest);
        byte[] pcm = tag.getByteArray("pcm");
        BlockPos pos = chest.getBlockPos();
        UUID owner = tag.hasUUID("owner") ? tag.getUUID("owner") : ZERO;
        for (ServerPlayer listener : level.players()) {
            if (listener.distanceToSqr(pos.getCenter()) > 256) continue;
            if (!tag.getBoolean("shared") && !owner.equals(listener.getUUID())) {
                var vanilla = chest instanceof EnderChestBlockEntity ? SoundEvents.ENDER_CHEST_OPEN : SoundEvents.CHEST_OPEN;
                listener.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(vanilla),
                        SoundSource.BLOCKS, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5,
                        .5f, .9f + level.random.nextFloat() * .1f, level.random.nextLong()));
                continue;
            }
            Map<String, Long> playing = PLAYING.computeIfAbsent(listener.getUUID(), unused -> new HashMap<>());
            String key = key(level, pos);
            long now = level.getGameTime();
            if (playing.getOrDefault(key, 0L) > now) continue;
            playing.put(key, now + Math.max(1, (pcm.length * 20L + AudioRules.RATE * 2 - 1) / (AudioRules.RATE * 2)));
            UUID transfer = UUID.randomUUID();
            Wire.toPlayer(listener, new Message(Wire.PLAY_BEGIN, 0, transfer, pos, false, "",
                    pcm.length, 0, new byte[0]));
            for (int offset = 0, index = 0; offset < pcm.length; offset += AudioRules.CHUNK, index++)
                Wire.toPlayer(listener, new Message(Wire.PLAY_CHUNK, 0, transfer, pos, false, "", 0, index,
                        Arrays.copyOfRange(pcm, offset, Math.min(pcm.length, offset + AudioRules.CHUNK))));
        }
    }

    private static void stop(ServerLevel level, BlockEntity chest) {
        String key = key(level, chest.getBlockPos());
        for (ServerPlayer player : level.players()) {
            Map<String, Long> playing = PLAYING.get(player.getUUID());
            if (playing != null) playing.remove(key);
            Wire.toPlayer(player, new Message(Wire.STOP, 0, ZERO, chest.getBlockPos(), false, "", 0, 0, new byte[0]));
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().overworld().getGameTime();
        UPLOADS.entrySet().removeIf(entry -> !OPEN.containsKey(entry.getKey()) || now > entry.getValue().expires);
        PENDING.clear();
        PLAYING.values().forEach(map -> { if (map.size() > 256) map.clear(); });
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        PENDING.remove(id); OPEN.remove(id); UPLOADS.remove(id); RATE_LIMIT.remove(id); PLAYING.remove(id);
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        PENDING.clear(); OPEN.clear(); UPLOADS.clear(); RATE_LIMIT.clear(); PLAYING.clear();
    }
}
