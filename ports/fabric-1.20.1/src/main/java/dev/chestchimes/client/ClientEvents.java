package dev.chestchimes.client;
import dev.chestchimes.network.Wire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
public final class ClientEvents implements net.fabricmc.api.ClientModInitializer {
    private static Button chestButton;
    private static ContainerScreen chestScreen;
    @Override public void onInitializeClient() {
        Wire.initClient();
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((mc,screen,width,height)-> {
            if (!(screen instanceof ContainerScreen chest)) return;
            int left=(width-176)/2,top=(height-(114+chest.getMenu().getRowCount()*18))/2;
            Button button=Button.builder(Component.literal("♫"),unused->{
                if (ClientState.hasState(chest.getMenu())) mc.setScreen(new ChimeScreen(chest,ClientState.state));
            }).bounds(left+158,top+3,12,12).tooltip(Tooltip.create(Component.translatable("chestchimes.title"))).build();
            chestButton=button; chestScreen=chest;
            button.visible=ClientState.hasState(chest.getMenu());
            net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(chest).add(button);
        });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc->{
            ClientState.tick();
            if (chestButton!=null && mc.screen==chestScreen) chestButton.visible=ClientState.hasState(chestScreen.getMenu());
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler,mc)->{
            ClientState.clear(); chestButton=null; chestScreen=null;
        });
    }
}
