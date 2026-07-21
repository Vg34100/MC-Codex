package net.vg.codex.client.advancements;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.chat.Component;

/**
 * Stage 1 placeholder: proves {@code ClientGuiEvent.SET_SCREEN} interception replaces vanilla's
 * AdvancementsScreen. Stage 2 replaces this body with a faithful clone (tabs, tree, widgets)
 * built on {@link ClientAdvancements} and vanilla's own layout/sprite helpers.
 */
public final class CodexAdvancementsScreen extends Screen {
    private final Screen lastScreen;
    private final ClientAdvancements advancements;

    public CodexAdvancementsScreen(ClientAdvancements advancements, Screen lastScreen) {
        super(Component.literal("Codex Advancements"));
        this.advancements = advancements;
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        addRenderableWidget(new StringWidget(centerX - 100, centerY - 10, 200, 12,
                Component.literal("Codex advancement screen swap OK"), this.font));
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(centerX - 50, centerY + 10, 100, 20)
                .build());
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(lastScreen);
    }
}
