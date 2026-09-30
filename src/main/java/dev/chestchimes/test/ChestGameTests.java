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

    @GameTest(template = "forge:empty")
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

    @GameTest(template = "forge:empty")
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

    @GameTest(template = "forge:empty")
    public static void hooksApplyOnDedicatedServer(GameTestHelper helper) {
        for (Class<?> type : new Class<?>[] { ChestBlockEntity.class, EnderChestBlockEntity.class }) {
            helper.assertTrue(java.util.Arrays.stream(type.getDeclaredMethods())
                    .anyMatch(method -> method.getName().contains("chestchimes$capture")),
                    "Chest opening mixin did not apply to " + type.getSimpleName());
        }
        helper.succeed();
    }
}
