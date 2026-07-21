package net.vg.codex.client.advancements;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recreation of vanilla's {@code AdvancementsScreen}. This is stage 2 of the plan: a faithful
 * read-only clone driven by the same {@link ClientAdvancements} vanilla's own screen listens to,
 * so anything vanilla shows, this shows identically. Edit mode (stage 3+) swaps the data source
 * to a locally-built, unfiltered tree instead so hidden/locked advancements are visible too.
 */
public final class CodexAdvancementsScreen extends Screen implements ClientAdvancements.Listener {
    private static final Identifier WINDOW_LOCATION = Identifier.fromNamespaceAndPath("minecraft", "textures/gui/advancements/window.png");
    private static final int WINDOW_WIDTH = 252;
    private static final int WINDOW_HEIGHT = 140;
    private static final int WINDOW_INSIDE_X = 9;
    private static final int WINDOW_INSIDE_Y = 18;

    private final Screen lastScreen;
    private final ClientAdvancements advancements;
    private final Map<AdvancementHolder, CodexAdvancementTab> tabs = new LinkedHashMap<>();
    private CodexAdvancementTab selectedTab;
    private boolean isScrolling;

    public CodexAdvancementsScreen(ClientAdvancements advancements, Screen lastScreen) {
        super(Component.literal("Advancements"));
        this.advancements = advancements;
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        tabs.clear();
        selectedTab = null;
        advancements.setListener(this);
        if (selectedTab == null && !tabs.isEmpty()) {
            CodexAdvancementTab first = tabs.values().iterator().next();
            advancements.setSelectedTab(first.getRootNode().holder(), true);
        }

        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(left + WINDOW_WIDTH - 90, top + WINDOW_HEIGHT + 6, 90, 20)
                .build());
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(lastScreen);
    }

    @Override
    public void removed() {
        advancements.setListener(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;

        extractor.blit(RenderPipelines.GUI_TEXTURED, WINDOW_LOCATION, left, top, 0.0F, 0.0F, WINDOW_WIDTH, WINDOW_HEIGHT, 256, 256);

        if (tabs.size() > 1) {
            for (CodexAdvancementTab tab : tabs.values()) {
                tab.extractTab(extractor, left, top, tab == selectedTab);
            }
            for (CodexAdvancementTab tab : tabs.values()) {
                tab.extractIcon(extractor, left, top);
            }
        }

        Component title = selectedTab != null ? selectedTab.getTitle() : this.title;
        extractor.text(this.font, title, left + 8, top + 6, 0xFF404040, false);

        if (selectedTab != null) {
            selectedTab.extractContents(extractor, left + WINDOW_INSIDE_X, top + WINDOW_INSIDE_Y, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() == 0) {
            int left = (this.width - WINDOW_WIDTH) / 2;
            int top = (this.height - WINDOW_HEIGHT) / 2;
            for (CodexAdvancementTab tab : tabs.values()) {
                if (tab.isMouseOverTabButton(left, top, event.x(), event.y())) {
                    advancements.setSelectedTab(tab.getRootNode().holder(), true);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.button() == 0 && selectedTab != null) {
            isScrolling = true;
            selectedTab.scroll(-dragX, -dragY);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        isScrolling = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (selectedTab != null) {
            selectedTab.scroll(scrollX * 16.0, scrollY * 16.0);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onAddAdvancementRoot(AdvancementNode node) {
        CodexAdvancementTab tab = CodexAdvancementTab.create(tabs.size(), node);
        if (tab == null) {
            return;
        }
        tabs.put(node.holder(), tab);
    }

    @Override
    public void onRemoveAdvancementRoot(AdvancementNode node) {
        tabs.remove(node.holder());
    }

    @Override
    public void onAddAdvancementTask(AdvancementNode node) {
        CodexAdvancementTab tab = getTab(node);
        if (tab != null) {
            tab.addAdvancement(node);
        }
    }

    @Override
    public void onRemoveAdvancementTask(AdvancementNode node) {
        // Not handled for the read-only clone yet; vanilla rebuilds the whole tab in this case.
    }

    @Override
    public void onUpdateAdvancementProgress(AdvancementNode node, AdvancementProgress progress) {
        CodexAdvancementTab tab = getTab(node);
        if (tab == null) {
            return;
        }
        CodexAdvancementWidget widget = tab.getWidget(node.holder());
        if (widget != null) {
            widget.setProgress(progress);
        }
    }

    @Override
    public void onSelectedTabChanged(AdvancementHolder holder) {
        selectedTab = tabs.get(holder);
    }

    @Override
    public void onAdvancementsCleared() {
        tabs.clear();
        selectedTab = null;
    }

    private CodexAdvancementTab getTab(AdvancementNode node) {
        AdvancementNode root = AdvancementNode.getRoot(node);
        return tabs.get(root.holder());
    }
}
