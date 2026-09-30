package dev.chestchimes.client;

import dev.chestchimes.audio.*;
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
    private static final class Draft {
        String name = "";
        byte[] pcm;
        int sourceBytes, millis = 5000, volume = 100;
        boolean dirty, exists;
    }
    private final ContainerScreen parent;
    private final int menu;
    private final String key;
    private Draft shared = new Draft(), local = new Draft();
    private boolean localTab, choosing, success, committing;
    private String status = "";
    private Button sharedTab, privateTab, choose, preview, save, reset;
    private DurationSlider duration;
    private VolumeSlider volume;

    public ChimeScreen(ContainerScreen parent, Wire.Message state) {
        super(Component.translatable("chestchimes.title"));
        this.parent = parent; this.menu = state.menu(); this.key = state.key();
        reloadShared(); reloadLocal();
    }
    public int menuId() { return menu; }
    private Draft draft() { return localTab ? local : shared; }
    private static Draft from(Chime sound) {
        Draft d = new Draft();
        if (sound != null) {
            d.name = sound.name(); d.pcm = sound.pcm(); d.sourceBytes = d.pcm.length;
            d.volume = sound.volume(); d.millis = sound.millis(); d.exists = true;
        }
        return d;
    }
    private void reloadLocal() { local = from(LocalSounds.get(key)); }
    private void reloadShared() {
        if (ClientState.state == null || !ClientState.state.key().equals(key)) return;
        shared = from(ClientState.sharedSound);
        if (ClientState.sharedSound == null) {
            shared.name = ClientState.state.name(); shared.exists = ClientState.state.total() > 0;
            shared.volume = ClientState.state.volume();
            if (shared.exists) shared.millis = Math.max(100, ClientState.state.total() * 1000 / (AudioRules.RATE * 2));
        }
    }
    @Override protected void init() {
        int x = width / 2 - 130, y = Math.max(7, height / 2 - 113);
        sharedTab = addRenderableWidget(Button.builder(tr("tab.shared"), b -> switchTab(false))
                .bounds(x, y + 16, 126, 18).build());
        privateTab = addRenderableWidget(Button.builder(tr("tab.local"), b -> switchTab(true))
                .bounds(x + 134, y + 16, 126, 18).build());
        choose = addRenderableWidget(Button.builder(tr("choose"), b -> select())
                .bounds(x, y + 76, 260, 20).build());
        duration = addRenderableWidget(new DurationSlider(x, y + 100));
        volume = addRenderableWidget(new VolumeSlider(x, y + 124));
        preview = addRenderableWidget(Button.builder(tr("preview"), b -> {
            Draft d = draft();
            if (d.pcm != null) ClientState.preview(d.pcm, d.millis, d.volume);
        }).bounds(x, y + 148, 126, 20).build());
        save = addRenderableWidget(Button.builder(tr("save"), b -> save())
                .bounds(x + 134, y + 148, 126, 20).build());
        reset = addRenderableWidget(Button.builder(tr("reset"), b -> reset())
                .bounds(x, y + 172, 260, 20).build());
        addRenderableWidget(Button.builder(tr("back"), b -> onClose())
                .bounds(x, y + 207, 260, 20).build());
        refresh();
    }
    private static Component tr(String key) { return Component.translatable("chestchimes." + key); }
    private void switchTab(boolean personal) {
        ClientState.stopPreview();
        localTab = personal;
        if (!localTab && !shared.dirty) reloadShared();
        status = "";
        refresh();
    }
    private void refresh() {
        if (duration == null) return;
        duration.refresh(); volume.refresh();
        reset.setMessage(tr(localTab ? "reset.local" : "reset.shared"));
        updateButtons();
    }
    private void select() {
        choosing = true;
        status = "chestchimes.choosing";
        Draft target = draft();
        String title = tr("choose").getString(), filters = tr("formats").getString();
        CompletableFuture.supplyAsync(() -> {
            try { return AudioImporter.choose(title, filters); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }).whenComplete((result, error) -> minecraft.execute(() -> {
            choosing = false;
            if (minecraft.screen != this || !validMenu()) return;
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                String errorKey = cause.getMessage();
                message(errorKey != null && errorKey.startsWith("chestchimes.error.") ? errorKey : "chestchimes.error.audio", false);
            } else if (result == null) { status = ""; if (!localTab && !shared.dirty) reloadShared(); }
            else {
                target.pcm = result.pcm(); target.name = result.name(); target.sourceBytes = result.sourceBytes();
                target.millis = Math.min(5000, Math.max(100, target.pcm.length * 1000 / (AudioRules.RATE * 2)));
                target.dirty = true;
                // Preserve the draft being edited if public metadata arrived while the dialog was open.
                if (localTab) local = target; else shared = target;
                message("chestchimes.selected", true);
            }
            refresh();
        }));
    }
    private void save() {
        ClientState.stopPreview();
        Draft d = draft();
        if (d.pcm == null) return;
        if (localTab) {
            try {
                LocalSounds.save(key, new Chime(d.name, AudioRules.trim(d.pcm, d.millis), d.volume));
                ClientState.localChanged(key);
                reloadLocal(); message("chestchimes.saved.local", true);
            } catch (java.io.IOException e) { message("chestchimes.error.local", false); }
            refresh();
        } else {
            committing = true;
            status = "chestchimes.saving"; success = false;
            ClientState.upload(menu, new AudioImporter.Imported(d.name, d.sourceBytes, d.pcm), d.millis, d.volume);
        }
    }
    private void reset() {
        ClientState.stopPreview();
        if (localTab) {
            try {
                LocalSounds.remove(key); ClientState.localChanged(key);
                reloadLocal(); message("chestchimes.reset.local_done", true);
            } catch (java.io.IOException e) { message("chestchimes.error.local", false); }
            refresh();
        } else {
            committing = true;
            status = "chestchimes.saving"; success = false;
            ClientState.resetShared(menu);
        }
    }
    public void serverState() {
        if (!shared.dirty && !committing && !choosing) { reloadShared(); refresh(); }
        else if (!committing) message("chestchimes.shared_changed", false);
    }
    public void localMigrated() {
        if (!local.dirty && !choosing) { reloadLocal(); refresh(); }
    }
    public void reply(String status, boolean ok, boolean ownReply) {
        if (ownReply) {
            committing = false;
            if (ok) { reloadShared(); refresh(); }
        }
        message(status, ok);
    }
    public void message(String key, boolean success) { this.status = key; this.success = success; }
    private boolean validMenu() {
        return minecraft.player != null && minecraft.player.containerMenu == parent.getMenu() && minecraft.player.isAlive();
    }
    @Override public void tick() {
        if (!validMenu()) { minecraft.setScreen(null); return; }
        updateButtons();
    }
    private void updateButtons() {
        boolean available = !choosing && !ClientState.saving;
        sharedTab.active = available && localTab;
        privateTab.active = available && !localTab;
        choose.active = available;
        duration.active = available && draft().pcm != null;
        volume.active = available && draft().pcm != null;
        preview.active = available && draft().pcm != null;
        save.active = available && draft().pcm != null;
        reset.active = available && draft().exists;
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        int x = width / 2 - 138, y = Math.max(7, height / 2 - 113);
        g.fill(x, y - 6, x + 276, y + 233, 0xF021252E);
        g.drawCenteredString(font, title, width / 2, y, 0xFFE8C978);
        Draft d = draft();
        String filename = d.name.isEmpty() ? tr(localTab ? "inherit" : "standard").getString() : d.name;
        g.drawCenteredString(font, font.plainSubstrByWidth(filename, 256), width / 2, y + 39, 0xFFFFFFFF);
        g.drawCenteredString(font, tr("limits"), width / 2, y + 51, 0xFFA7AAB5);
        String hint = localTab ? "hint.local" : LocalSounds.get(key) != null ? "hint.override" : "hint.shared";
        g.drawCenteredString(font, font.plainSubstrByWidth(tr(hint).getString(), 264), width / 2, y + 63, 0xFF8FC9DE);
        String shown = !status.isEmpty() ? status : d.exists && d.pcm == null ? "chestchimes.loading" : "";
        if (!shown.isEmpty())
            g.drawCenteredString(font, font.plainSubstrByWidth(Component.translatable(shown).getString(), 264),
                    width / 2, y + 196, success ? 0xFF8FDE9D : 0xFFEACB8A);
        super.render(g, mouseX, mouseY, partialTick);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void removed() { ClientState.stopPreview(); }
    @Override public boolean isPauseScreen() { return false; }

    private final class DurationSlider extends AbstractSliderButton {
        DurationSlider(int x, int y) { super(x, y, 260, 20, Component.empty(), 0); refresh(); }
        void refresh() { value = (draft().millis - 100) / 4900.0; updateMessage(); }
        @Override protected void updateMessage() {
            setMessage(Component.translatable("chestchimes.duration", String.format(Locale.ROOT, "%.1f", draft().millis / 1000.0)));
        }
        @Override protected void applyValue() {
            draft().millis = 100 + (int) Math.round(value * 49) * 100; draft().dirty = true; updateMessage();
        }
    }
    private final class VolumeSlider extends AbstractSliderButton {
        VolumeSlider(int x, int y) { super(x, y, 260, 20, Component.empty(), 1); refresh(); }
        void refresh() { value = draft().volume / 100.0; updateMessage(); }
        @Override protected void updateMessage() { setMessage(Component.translatable("chestchimes.volume", draft().volume)); }
        @Override protected void applyValue() { draft().volume = (int) Math.round(value * 100); draft().dirty = true; updateMessage(); }
    }
}
