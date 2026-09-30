package dev.chestchimes.mixin;
import dev.chestchimes.server.BlockData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(BlockEntity.class)
public abstract class BlockDataMixin implements BlockData {
    @Unique private CompoundTag chestchimes$data = new CompoundTag();
    public CompoundTag chestchimes$data() { return chestchimes$data; }
    @Inject(method="load", at=@At("TAIL"))
    private void chestchimes$load(CompoundTag tag, CallbackInfo ci) {
        chestchimes$data = tag.getCompound("ForgeData").copy();
    }
    @Inject(method="saveAdditional", at=@At("TAIL"))
    private void chestchimes$save(CompoundTag tag, CallbackInfo ci) {
        if (!chestchimes$data.isEmpty()) tag.put("ForgeData", chestchimes$data.copy());
    }
}
