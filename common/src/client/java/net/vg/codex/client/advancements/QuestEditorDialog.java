package net.vg.codex.client.advancements;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared, bounded dialog surface. Only the active screen can receive input. */
abstract class QuestEditorDialog extends CodexScreen {
    protected final Screen parent;
    protected int left, top, panelWidth, panelHeight;
    protected String error = "";

    QuestEditorDialog(Screen parent, String title) {
        super(Component.literal(title));
        this.parent = parent;
    }

    @Override protected void init() {
        panelWidth = Math.min(380, width - 24);
        panelHeight = Math.min(242, height - 24);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
    }

    protected void footer(String saveLabel, Runnable save, Runnable remove) {
        int count = remove == null ? 2 : 3;
        int buttonWidth = (panelWidth - 24 - (count - 1) * 6) / count;
        int y = top + panelHeight - 32;
        addRenderableWidget(Button.builder(Component.literal(saveLabel), b -> {
            try { save.run(); minecraft.setScreen(parent); }
            catch (RuntimeException invalid) { error = invalid.getMessage() == null ? "Invalid value" : invalid.getMessage(); }
        }).bounds(left + 12, y, buttonWidth, 20).build());
        if (remove != null) addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
            try { remove.run(); minecraft.setScreen(parent); }
            catch (RuntimeException invalid) { error = invalid.getMessage(); }
        }).bounds(left + 18 + buttonWidth, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(left + 12 + (buttonWidth + 6) * (count - 1), y, buttonWidth, 20).build());
    }

    protected void text(GuiGraphicsExtractor graphics, String text, int y, int color) {
        graphics.text(font, font.plainSubstrByWidth(text, panelWidth - 24), left + 12, top + y, color, false);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) {
        graphics.fill(0, 0, width, height, 0xC0101010);
        graphics.fill(left - 1, top - 1, left + panelWidth + 1, top + panelHeight + 1, 0xFF080808);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFF303030);
        text(graphics, title.getString(), 12, 0xFFFFFFFF);
        super.extractRenderState(graphics, x, y, tick);
        if (error != null && !error.isEmpty()) text(graphics, error, panelHeight - 48, 0xFFFF8888);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
