package dev.chestchimes.client;

import dev.chestchimes.ChestChimes;
import dev.chestchimes.network.Wire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@net.neoforged.fml.common.EventBusSubscriber(modid = ChestChimes.ID, value = Dist.CLIENT)
public final class ClientEvents {
    private static Button chestButton;
    private static ContainerScreen chestScreen;
    @net.neoforged.fml.common.EventBusSubscriber(modid = ChestChimes.ID, value = Dist.CLIENT, bus = net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
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
            if (ClientState.hasState(chest.getMenu()))
                Minecraft.getInstance().setScreen(new ChimeScreen(chest, state));
        }).bounds(left + 158, top + 3, 12, 12)
                .tooltip(Tooltip.create(Component.translatable("chestchimes.title"))).build();
        chestButton = button;
        chestScreen = chest;
        button.visible = ClientState.hasState(chest.getMenu());
        event.addListener(button);
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        ClientState.tick();
        if (chestButton != null && Minecraft.getInstance().screen == chestScreen)
            chestButton.visible = ClientState.hasState(chestScreen.getMenu());
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientState.clear(); chestButton = null; chestScreen = null;
    }
}
