package dev.chestchimes.mixin;
import dev.chestchimes.server.ChestService;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(ServerPlayer.class)
public abstract class PlayerMenuMixin {
    @Inject(method="openMenu",at=@At("RETURN"))
    private void chestchimes$opened(net.minecraft.world.MenuProvider provider, CallbackInfoReturnable<java.util.OptionalInt> ci) {
        if (ci.getReturnValue().isPresent()) ChestService.opened((ServerPlayer)(Object)this);
    }
    @Inject(method="doCloseContainer",at=@At("HEAD"))
    private void chestchimes$closed(CallbackInfo ci) { ChestService.closed((ServerPlayer)(Object)this); }
}
