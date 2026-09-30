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
        tag.putUUID("owner", new java.util.UUID(1, 2));
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
        helper.assertTrue(ChestService.settings(chest).getUUID("owner").equals(player.getUUID()),
                "Private audience owner must be the authenticated sender");
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
    public static void replacesOpeningSoundOnlyUntilReset(GameTestHelper helper) {
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
        open.setCanceled(false);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(open);
        helper.assertTrue(!open.isCanceled(), "Default opening sound was not restored");
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
