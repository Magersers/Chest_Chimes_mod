package dev.chestchimes;

import dev.chestchimes.network.Wire;
import dev.chestchimes.server.ChestService;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.common.Mod;

@Mod(ChestChimes.ID)
public final class ChestChimes {
    public static final String ID = "chestchimes";
    public ChestChimes(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(Wire::init);
        NeoForge.EVENT_BUS.register(ChestService.class);
    }
}
