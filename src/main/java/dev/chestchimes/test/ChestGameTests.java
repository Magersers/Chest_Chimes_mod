package dev.chestchimes.test;

import dev.chestchimes.server.ChestService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("chestchimes")
@PrefixGameTestTemplate(false)
public final class ChestGameTests {
    private static CompoundTag sound() {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("pcm", new byte[4410]);
        tag.putString("name", "test.wav");
        tag.putBoolean("shared", true);
        tag.putInt("volume", 45);
        return tag;
    }

    @GameTest(template = "empty")
    public static void persistenceAndReset(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(pos);
        ChestService.write(chest, sound());
        CompoundTag saved = chest.saveWithFullMetadata();
        BlockEntity restored = BlockEntity.loadStatic(chest.getBlockPos(), chest.getBlockState(), saved);
        helper.assertTrue(restored != null && ChestService.settings(restored).getByteArray("pcm").length == 4410,
                "Audio must survive saving and loading");
        ChestService.write(chest, new CompoundTag());
        helper.assertTrue(ChestService.settings(chest).isEmpty(), "Reset must remove custom sound data");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void doubleChestSharesSettings(GameTestHelper helper) {
        BlockPos a = helper.absolutePos(new BlockPos(1, 1, 1));
        var rightState = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT);
        BlockPos b = a.relative(ChestBlock.getConnectedDirection(rightState));
        helper.getLevel().setBlock(a, rightState, 2);
        helper.getLevel().setBlock(b, rightState.setValue(ChestBlock.TYPE, ChestType.LEFT), 2);
        BlockEntity right = helper.getLevel().getBlockEntity(a), left = helper.getLevel().getBlockEntity(b);
        right.getPersistentData().putUUID("chestchimes_id", java.util.UUID.randomUUID());
        String identity = ChestService.identity(right);
        helper.assertTrue(identity.equals(ChestService.identity(left)) && left.getPersistentData().hasUUID("chestchimes_id"),
                "Joining a second half must synchronize an existing chest identity");
        ChestService.write(right, sound());
        helper.assertTrue(ChestService.canonical(left) == right, "Both halves must resolve to one chest");
        helper.assertTrue(ChestService.settings(left).getByteArray("pcm").length == 4410, "Left half lost sound");
        ChestService.write(left, new CompoundTag());
        helper.assertTrue(ChestService.settings(right).isEmpty(), "Reset must clear both halves");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void uploadRequiresOpenChestAndCommitsAtomically(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(pos);
        var player = new net.minecraftforge.common.util.FakePlayer(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "ChimeTest"));
        var absolute = helper.absolutePos(pos);
        player.setPos(absolute.getX() + .5, absolute.getY(), absolute.getZ() + 1.5);
        player.openMenu(chest);
        int menu = player.containerMenu.containerId;
        java.util.UUID transfer = java.util.UUID.randomUUID();
        dev.chestchimes.server.ChestService.receive(player, new dev.chestchimes.network.Wire.Message(
                dev.chestchimes.network.Wire.BEGIN, menu, transfer, BlockPos.ZERO, true, "test.wav", 4410, 4454, new byte[0]));
        helper.assertTrue(ChestService.settings(chest).isEmpty(), "Incomplete upload changed the chest");
        dev.chestchimes.server.ChestService.receive(player, new dev.chestchimes.network.Wire.Message(
                dev.chestchimes.network.Wire.CHUNK, menu, transfer, BlockPos.ZERO, false, "", 0, 0, new byte[4410]));
        helper.assertTrue(ChestService.settings(chest).getByteArray("pcm").length == 4410,
                "Valid upload into open chest failed");
        helper.assertTrue(!ChestService.settings(chest).hasUUID("owner") && ChestService.settings(chest).getBoolean("shared"),
                "Server must store only shared recordings, without a private owner");
        dev.chestchimes.server.ChestService.receive(player,
                dev.chestchimes.network.Wire.Message.simple(dev.chestchimes.network.Wire.RESET, menu + 1));
        helper.assertTrue(!ChestService.settings(chest).isEmpty(), "Forged menu ID reset a chest");
        player.closeContainer();
        dev.chestchimes.server.ChestService.receive(player,
                dev.chestchimes.network.Wire.Message.simple(dev.chestchimes.network.Wire.RESET, menu));
        helper.assertTrue(!ChestService.settings(chest).isEmpty(), "Closed chest accepted a reset");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void routesOpeningsForClientPriorityAndKeepsClosingSound(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.CHEST);
        BlockEntity chest = helper.getBlockEntity(pos);
        ChestService.write(chest, sound());
        var open = new net.minecraftforge.event.PlayLevelSoundEvent.AtPosition(helper.getLevel(),
                chest.getBlockPos().getCenter(),
                net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(net.minecraft.sounds.SoundEvents.CHEST_OPEN),
                net.minecraft.sounds.SoundSource.BLOCKS, .5f, 1);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(open);
        helper.assertTrue(open.isCanceled(), "Vanilla opening sound was not suppressed");
        var close = new net.minecraftforge.event.PlayLevelSoundEvent.AtPosition(helper.getLevel(),
                chest.getBlockPos().getCenter(),
                net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(net.minecraft.sounds.SoundEvents.CHEST_CLOSE),
                net.minecraft.sounds.SoundSource.BLOCKS, .5f, 1);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(close);
        helper.assertTrue(!close.isCanceled(), "Closing sound must remain unchanged");
        ChestService.write(chest, new CompoundTag());
        var restored = new net.minecraftforge.event.PlayLevelSoundEvent.AtPosition(helper.getLevel(),
                chest.getBlockPos().getCenter(),
                net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(net.minecraft.sounds.SoundEvents.CHEST_OPEN),
                net.minecraft.sounds.SoundSource.BLOCKS, .5f, 1);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(restored);
        helper.assertTrue(restored.isCanceled() && ChestService.settings(chest).isEmpty(),
                "Unconfigured openings must reach client priority selection before falling back to vanilla");
        helper.succeed();
    }


    private static final class ObservedPlayer extends net.minecraftforge.common.util.FakePlayer {
        final java.util.List<dev.chestchimes.network.Wire.Message> received = new java.util.ArrayList<>();
        ObservedPlayer(net.minecraft.server.level.ServerLevel level, String name) {
            super(level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), name));
            var transport = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND) {
                @Override public void send(net.minecraft.network.protocol.Packet<?> packet) { collect(packet); }
                @Override public void send(net.minecraft.network.protocol.Packet<?> packet,
                                           net.minecraft.network.PacketSendListener listener) { collect(packet); }
            };
            connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(), transport, this);
        }
        private void collect(net.minecraft.network.protocol.Packet<?> packet) {
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket payload
                    && payload.getIdentifier().equals(new net.minecraft.resources.ResourceLocation("chestchimes", "audio"))) {
                var bytes = payload.getData();
                try {
                    bytes.readVarInt();
                    received.add(dev.chestchimes.network.Wire.Message.decode(bytes));
                } finally { bytes.release(); }
            }
        }
        void open(ChestBlockEntity chest) {
            BlockPos pos = chest.getBlockPos();
            setPos(pos.getX() + .5, pos.getY(), pos.getZ() + 1.5);
            openMenu(chest);
        }
    }

    @GameTest(template = "empty")
    public static void anotherPlayerReceivesAndEditsSharedSound(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        ChestService.write(chest, sound());
        ObservedPlayer other = new ObservedPlayer(helper.getLevel(), "OtherEditor");
        other.open(chest);
        helper.assertTrue(other.received.stream().anyMatch(m -> m.type() == dev.chestchimes.network.Wire.STATE
                        && m.name().equals("test.wav") && m.volume() == 45 && m.total() == 4410),
                "A different player must receive the shared filename, volume and duration");
        helper.assertTrue(other.received.stream().anyMatch(m -> m.type() == dev.chestchimes.network.Wire.STATE_CHUNK
                        && m.bytes().length == 4410), "Shared audio must be available for preview and editing");
        ChestService.receive(other, new dev.chestchimes.network.Wire.Message(dev.chestchimes.network.Wire.ADJUST,
                other.containerMenu.containerId, java.util.UUID.randomUUID(), BlockPos.ZERO,
                true, "", 100, 0, new byte[0], "", 0));
        helper.assertTrue(ChestService.settings(chest).getInt("volume") == 0,
                "A different player must be allowed to set shared volume to zero");
        helper.assertTrue(ChestService.settings(chest).getByteArray("pcm").length == 4410,
                "Zero volume must preserve the configured sound instead of falling back");
        other.closeContainer();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void privateUploadsAndInvalidVolumeAreRejected(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        ObservedPlayer player = new ObservedPlayer(helper.getLevel(), "InvalidUpload");
        player.open(chest);
        for (int volume : new int[] {-1, 101}) {
            ChestService.receive(player, new dev.chestchimes.network.Wire.Message(dev.chestchimes.network.Wire.BEGIN,
                    player.containerMenu.containerId, java.util.UUID.randomUUID(), BlockPos.ZERO,
                    true, "test.wav", 4410, 4454, new byte[0], "", volume));
        }
        ChestService.receive(player, new dev.chestchimes.network.Wire.Message(dev.chestchimes.network.Wire.BEGIN,
                player.containerMenu.containerId, java.util.UUID.randomUUID(), BlockPos.ZERO,
                false, "private.wav", 4410, 4454, new byte[0], "", 50));
        helper.assertTrue(ChestService.settings(chest).isEmpty(), "Invalid/private uploads must not enter world data");
        helper.assertTrue(player.received.stream().filter(m -> m.type() == dev.chestchimes.network.Wire.REPLY
                && !m.shared()).count() == 3, "Server must reject all three invalid uploads");
        player.closeContainer();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void legacyPrivateAudioOnlyMigratesToItsOwner(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        ObservedPlayer owner = new ObservedPlayer(helper.getLevel(), "PrivateOwner");
        ObservedPlayer other = new ObservedPlayer(helper.getLevel(), "OtherPlayer");
        CompoundTag legacy = sound();
        legacy.putBoolean("shared", false); legacy.putUUID("owner", owner.getUUID());
        ChestService.write(chest, legacy);
        other.open(chest);
        helper.assertTrue(other.received.stream().noneMatch(m -> m.type() == dev.chestchimes.network.Wire.MIGRATE
                || m.type() == dev.chestchimes.network.Wire.STATE_CHUNK),
                "Legacy private audio must never be sent to another player");
        other.closeContainer();
        owner.open(chest);
        var migration = owner.received.stream().filter(m -> m.type() == dev.chestchimes.network.Wire.MIGRATE).findFirst();
        helper.assertTrue(migration.isPresent(), "Owner must receive their legacy recording for local storage");
        helper.assertTrue(chest.getPersistentData().contains("chestchimes_legacy_private"),
                "Legacy sound must remain recoverable until local persistence is acknowledged");
        ChestService.receive(owner, new dev.chestchimes.network.Wire.Message(dev.chestchimes.network.Wire.MIGRATED,
                owner.containerMenu.containerId, migration.orElseThrow().transfer(), BlockPos.ZERO, false, "", 0, 0, new byte[0]));
        helper.assertTrue(!chest.getPersistentData().contains("chestchimes_legacy_private"),
                "Acknowledged private recording must be removed from server storage");
        owner.closeContainer();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void chestIdentitySurvivesSettingsChangesButNotReplacement(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.CHEST);
        BlockEntity chest = helper.getBlockEntity(pos);
        String id = ChestService.identity(chest);
        ChestService.write(chest, sound());
        ChestService.write(chest, new CompoundTag());
        helper.assertTrue(id.equals(ChestService.identity(chest)), "Editing/resetting shared sound must not orphan local sound");
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos, Blocks.CHEST);
        helper.assertTrue(!id.equals(ChestService.identity(helper.getBlockEntity(pos))),
                "A newly placed chest at the same coordinates must not inherit a private sound");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void hooksApplyOnDedicatedServer(GameTestHelper helper) {
        for (Class<?> type : new Class<?>[] { ChestBlockEntity.class, EnderChestBlockEntity.class }) {
            helper.assertTrue(java.util.Arrays.stream(type.getDeclaredMethods())
                    .anyMatch(method -> method.getName().contains("chestchimes$capture")),
                    "Chest opening mixin did not apply to " + type.getSimpleName());
        }
        helper.succeed();
    }
}
