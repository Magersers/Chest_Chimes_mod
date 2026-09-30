package dev.chestchimes;

import dev.chestchimes.network.Wire;
import dev.chestchimes.server.ChestService;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

@Mod(ChestChimes.ID)
public final class ChestChimes {
    public static final String ID = "chestchimes";
    public ChestChimes() {
        Wire.init();
        MinecraftForge.EVENT_BUS.register(ChestService.class);
    }
}
