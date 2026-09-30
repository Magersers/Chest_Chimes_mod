package dev.chestchimes.client;

import dev.chestchimes.ChestChimes;
import dev.chestchimes.network.Wire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = ChestChimes.ID, value = Dist.CLIENT)
public final class ClientEvents {
    @Mod.EventBusSubscriber(modid = ChestChimes.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> Wire.clientReceiver = ClientState::accept);
        }
    }
    @SubscribeEvent public static void init(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof ContainerScreen chest)) return;
        int left = (chest.width - 176) / 2;
        int top = (chest.height - (114 + chest.getMenu().getRowCount() * 18)) / 2;
        // The 12px button stays in the title strip, outside all inventory slots.
        Button button = Button.builder(Component.literal("\u266b"), unused -> {
            var state = ClientState.state;
            if (state != null && state.menu() == chest.getMenu().containerId)
                Minecraft.getInstance().setScreen(new ChimeScreen(chest, state));
        }).bounds(left + 158, top + 3, 12, 12)
                .tooltip(Tooltip.create(Component.translatable("chestchimes.title"))).build();
        event.addListener(button);
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) ClientState.tick();
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { ClientState.clear(); }
}
