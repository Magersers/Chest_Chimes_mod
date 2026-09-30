package dev.chestchimes.client;

import dev.chestchimes.audio.AudioRules;
import dev.chestchimes.network.Wire;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class ChimeScreen extends Screen {
    private final ContainerScreen parent;
    private final int menu;
    private AudioImporter.Imported selected;
    private String filename;
    private int existingBytes;
    private boolean shared, choosing, success;
    private String status = "";
    private int millis = 5000;
    private Button choose, audience, preview, save, reset;
    private DurationSlider duration;

    public ChimeScreen(ContainerScreen parent, Wire.Message state) {
        super(Component.translatable("chestchimes.title"));
        this.parent = parent;
        this.menu = state.menu();
        this.filename = state.name();
        this.existingBytes = state.total();
        this.shared = state.shared();
        if (existingBytes > 0) millis = Math.max(100, existingBytes * 1000 / (AudioRules.RATE * 2));
    }
    public int menuId() { return menu; }

    @Override protected void init() {
        int x = width / 2 - 130, y = Math.max(20, height / 2 - 110);
        choose = addRenderableWidget(Button.builder(tr("choose"), button -> select())
                .bounds(x, y + 43, 260, 20).build());
        audience = addRenderableWidget(Button.builder(audienceLabel(), button -> {
            shared = !shared; button.setMessage(audienceLabel());
        }).bounds(x, y + 69, 260, 20).build());
        duration = addRenderableWidget(new DurationSlider(x, y + 95));
        preview = addRenderableWidget(Button.builder(tr("preview"), button -> {
            if (selected != null) ClientState.preview(selected.pcm(), millis);
        }).bounds(x, y + 121, 126, 20).build());
        save = addRenderableWidget(Button.builder(tr("save"), button -> {
            ClientState.stopPreview();
            status = "chestchimes.saving"; success = false;
            if (selected != null) ClientState.upload(menu, selected, millis, shared);
            else ClientState.action(Wire.ADJUST, menu, millis, shared);
        }).bounds(x + 134, y + 121, 126, 20).build());
        reset = addRenderableWidget(Button.builder(tr("reset"), button -> {
            ClientState.stopPreview();
            status = "chestchimes.saving"; success = false;
            ClientState.action(Wire.RESET, menu, 0, false);
        }).bounds(x, y + 147, 260, 20).build());
        addRenderableWidget(Button.builder(tr("back"), button -> onClose())
                .bounds(x, y + 183, 260, 20).build());
        updateButtons();
    }

    private static Component tr(String key) { return Component.translatable("chestchimes." + key); }
    private Component audienceLabel() { return tr(shared ? "audience.nearby" : "audience.private"); }

    private void select() {
        choosing = true;
        status = "chestchimes.choosing";
        String title = tr("choose").getString(), filters = tr("formats").getString();
        CompletableFuture.supplyAsync(() -> {
            try { return AudioImporter.choose(title, filters); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }).whenComplete((result, error) -> minecraft.execute(() -> {
            choosing = false;
            if (minecraft.screen != this || minecraft.player == null
                    || minecraft.player.containerMenu.containerId != menu) return;
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                String key = cause.getMessage();
                message(key != null && key.startsWith("chestchimes.error.") ? key : "chestchimes.error.audio", false);
            } else if (result == null) { status = ""; }
            else {
                selected = result;
                filename = result.name();
                millis = Math.min(5000, Math.max(100, result.pcm().length * 1000 / (AudioRules.RATE * 2)));
                duration.refresh();
                message("chestchimes.selected", true);
            }
            updateButtons();
        }));
    }

    public void serverState(Wire.Message state) {
        filename = state.name();
        existingBytes = state.total();
        shared = state.shared();
        selected = null;
        millis = existingBytes == 0 ? 5000 : Math.max(100, existingBytes * 1000 / (AudioRules.RATE * 2));
        if (duration != null) duration.refresh();
        if (audience != null) audience.setMessage(audienceLabel());
    }
    public void message(String key, boolean success) { this.status = key; this.success = success; }

    @Override public void tick() {
        if (minecraft.player == null || minecraft.player.containerMenu.containerId != menu
                || !minecraft.player.isAlive()) { minecraft.setScreen(null); return; }
        updateButtons();
    }
    private void updateButtons() {
        boolean available = !choosing && !ClientState.saving;
        choose.active = available;
        audience.active = available;
        duration.active = available;
        preview.active = available && selected != null;
        save.active = available && (selected != null || existingBytes > 0);
        reset.active = available && existingBytes > 0;
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int x = width / 2 - 138, y = Math.max(20, height / 2 - 110);
        g.fill(x, y - 12, x + 276, y + 211, 0xF021252E);
        g.drawCenteredString(font, title, width / 2, y - 2, 0xFFE8C978);
        Component file = filename.isEmpty() ? tr("standard") : Component.literal(filename);
        g.drawCenteredString(font, font.plainSubstrByWidth(file.getString(), 250), width / 2, y + 16, 0xFFFFFFFF);
        g.drawCenteredString(font, tr("limits"), width / 2, y + 30, 0xFFA7AAB5);
        if (!status.isEmpty())
            g.drawCenteredString(font, font.plainSubstrByWidth(Component.translatable(status).getString(), 264),
                    width / 2, y + 172, success ? 0xFF8FDE9D : 0xFFEACB8A);
        super.render(g, mouseX, mouseY, partialTick);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void removed() { ClientState.stopPreview(); }
    @Override public boolean isPauseScreen() { return false; }

    private final class DurationSlider extends AbstractSliderButton {
        DurationSlider(int x, int y) { super(x, y, 260, 20, Component.empty(), (millis - 100) / 4900.0); updateMessage(); }
        void refresh() { value = (millis - 100) / 4900.0; updateMessage(); }
        @Override protected void updateMessage() {
            setMessage(Component.translatable("chestchimes.duration", String.format(Locale.ROOT, "%.1f", millis / 1000.0)));
        }
        @Override protected void applyValue() {
            millis = 100 + (int) Math.round(value * 49) * 100;
            updateMessage();
        }
    }
}
