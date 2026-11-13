package net.vg.codex.client.advancements;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CodexSettingsScreen extends CodexScreen {
    private final Screen parent;
    public CodexSettingsScreen(Screen parent) { super(Component.literal("Codex Settings")); this.parent = parent; }

    @Override protected void init() {
        int panelWidth = Math.min(360, width - 24), left = (width - panelWidth) / 2;
        addRenderableWidget(CycleButton.builder((Integer value) -> Component.literal(CodexClientSettings.scaleName(value)), CodexClientSettings.scale)
                .withValues(0, -1, 1, 2, 3, 4).create(left, 56, panelWidth, 20, Component.literal("Codex UI"), (button, value) -> {
                    CodexClientSettings.scale = value; CodexClientSettings.save(); minecraft.resizeGui();
                }));
        addRenderableWidget(Button.builder(Component.literal("Quest tracker: " + (CodexClientSettings.hud ? "On" : "Off")), b -> {
            CodexClientSettings.hud = !CodexClientSettings.hud; CodexClientSettings.save(); rebuildWidgets();
        }).bounds(left, 94, panelWidth / 2 - 3, 20).build());
        addRenderableWidget(CycleButton.builder((Integer value) -> Component.literal(new String[]{"Top right", "Top left", "Bottom right", "Bottom left"}[value]), CodexClientSettings.hudPosition)
                .withValues(0, 1, 2, 3).create(left + panelWidth / 2 + 3, 94, panelWidth / 2 - 3, 20, Component.literal("Position"), (button, value) -> {
                    CodexClientSettings.hudPosition = value; CodexClientSettings.save();
                }));
        addRenderableWidget(CycleButton.onOffBuilder(CodexClientSettings.animations).create(left, 120, panelWidth / 2 - 3, 20, Component.literal("Animations"), (b, v) -> { CodexClientSettings.animations = v; CodexClientSettings.save(); }));
        addRenderableWidget(CycleButton.onOffBuilder(CodexClientSettings.holdAccept).create(left + panelWidth / 2 + 3, 120, panelWidth / 2 - 3, 20, Component.literal("Hold to accept"), (b, v) -> { CodexClientSettings.holdAccept = v; CodexClientSettings.save(); }));
        addRenderableWidget(CycleButton.onOffBuilder(CodexClientSettings.notices).create(left, 146, panelWidth / 2 - 3, 20, Component.literal("Quest receipts"), (b, v) -> { CodexClientSettings.notices = v; CodexClientSettings.save(); }));
        addRenderableWidget(CycleButton.onOffBuilder(CodexClientSettings.sounds).create(left + panelWidth / 2 + 3, 146, panelWidth / 2 - 3, 20, Component.literal("Quest sounds"), (b, v) -> { CodexClientSettings.sounds = v; CodexClientSettings.save(); }));
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(left, height - 32, panelWidth, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) {
        graphics.fill(0, 0, width, height, 0xED202020);
        graphics.centeredText(font, title, width / 2, 20, 0xFFFFFFFF);
        graphics.centeredText(font, "Comfortable uses a smaller scale inside Codex.", width / 2, 84, 0xFFBBBBBB);
        super.extractRenderState(graphics, x, y, tick);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
