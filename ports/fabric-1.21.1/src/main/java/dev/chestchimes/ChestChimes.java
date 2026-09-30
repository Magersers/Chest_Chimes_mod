package dev.chestchimes;
import dev.chestchimes.network.Wire;
import dev.chestchimes.server.ChestService;
public final class ChestChimes implements net.fabricmc.api.ModInitializer {
    public static final String ID="chestchimes";
    @Override public void onInitialize() {
        Wire.init();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(ChestService::tick);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(ChestService::stopped);
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->ChestService.logout(handler.player));
    }
}
