package dev.chestchimes.mixin;

import dev.chestchimes.server.ChestService;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChestBlockEntity.class)
public abstract class ChestOpenMixin {
    @Inject(method = "startOpen", at = @At("HEAD"))
    private void chestchimes$capture(Player player, CallbackInfo ci) {
        ChestService.capture(player, (BlockEntity) (Object) this);
    }
}
