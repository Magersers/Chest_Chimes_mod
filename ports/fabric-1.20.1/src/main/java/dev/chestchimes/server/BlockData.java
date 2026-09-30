package dev.chestchimes.server;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
public interface BlockData {
    CompoundTag chestchimes$data();
    static CompoundTag get(BlockEntity block) { return ((BlockData)block).chestchimes$data(); }
}
