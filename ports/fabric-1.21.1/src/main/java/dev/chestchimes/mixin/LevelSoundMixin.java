package dev.chestchimes.mixin;
import dev.chestchimes.server.ChestService;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerLevel.class)
public abstract class LevelSoundMixin {
    @Inject(method="playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at=@At("HEAD"), cancellable=true)
    private void chestchimes$sound(net.minecraft.world.entity.player.Player player, double x,double y,double z,
            net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound, net.minecraft.sounds.SoundSource source,
            float volume,float pitch,long seed,CallbackInfo ci) {
        if (ChestService.vanillaSound((ServerLevel)(Object)this,new net.minecraft.world.phys.Vec3(x,y,z),sound.value())) ci.cancel();
    }
}
