package net.vg.codex.client.advancements;

import dev.architectury.networking.NetworkManager;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vg.codex.network.RequestAdvancementSnapshotPayload;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recreation of vanilla's {@code AdvancementsScreen}. Normal view mode is a faithful clone driven
 * by the same {@link ClientAdvancements} vanilla's own screen listens to, so anything vanilla
 * shows, this shows identically. Edit mode (stage 3) swaps to a separate, locally-built tree from
 * the full unfiltered server snapshot, so hidden/locked advancements are visible and editable too
 * - kept in a completely separate tab map so live ClientAdvancements updates (which keep arriving
 * in the background) never cross-contaminate the edit-mode view.
 */
public final class CodexAdvancementsScreen extends Screen implements ClientAdvancements.Listener {
    private static final Identifier WINDOW_LOCATION = Identifier.fromNamespaceAndPath("minecraft", "textures/gui/advancements/window.png");
    private static final int WINDOW_WIDTH = 252;
    private static final int WINDOW_HEIGHT = 140;
    private static final int WINDOW_INSIDE_X = 9;
    private static final int WINDOW_INSIDE_Y = 18;

    private final Screen lastScreen;
    private final ClientAdvancements advancements;

    private final Map<AdvancementHolder, CodexAdvancementTab> viewTabs = new LinkedHashMap<>();
    private CodexAdvancementTab selectedViewTab;

    private final Map<AdvancementHolder, CodexAdvancementTab> editTabs = new LinkedHashMap<>();
    private CodexAdvancementTab selectedEditTab;
    private boolean editMode;

    private boolean isScrolling;
    private Button editModeButton;

    public CodexAdvancementsScreen(ClientAdvancements advancements, Screen lastScreen) {
        super(Component.literal("Advancements"));
        this.advancements = advancements;
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        viewTabs.clear();
        selectedViewTab = null;
        advancements.setListener(this);
        if (selectedViewTab == null && !viewTabs.isEmpty()) {
            CodexAdvancementTab first = viewTabs.values().iterator().next();
            advancements.setSelectedTab(first.getRootNode().holder(), true);
        }

        int left = (this.width - WINDOW_WIDTH) / 2;
        int top = (this.height - WINDOW_HEIGHT) / 2;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(left + WINDOW_WIDTH - 90, top + WINDOW_HEIGHT + 6, 90, 20)
                .build());

        if (canEdit()) {
            editModeButton = addRenderableWidget(Button.builder(editModeLabel(), b -> toggleEditMode())
                    .bounds(left, top + WINDOW_HEIGHT + 6, 120, 20)
                    .build());
        }
    }

    private boolean canEdit() {
        return this.minecraft.player != null
                && Commands.LEVEL_GAMEMASTERS.check(this.minecraft.player.permissions());
    }

    private Component editModeLabel() {
        return Component.literal(editMode ? "Edit Mode: On" : "Edit Mode: Off");
    }

    private void toggleEditMode() {
        if (editMode) {
            editMode = false;
            editModeButton.setMessage(editModeLabel());
        } else {
            NetworkManager.sendToServer(new RequestAdvancementSnapshotPayload());
            // editMode flips true once the response arrives - see enterEditMode.
        }
    }

    /** Called by AdvancementEditorClient when the full snapshot arrives while this screen is open. */
    public void enterEditMode(List<AdvancementHolder> snapshot) {
        AdvancementTree tree = new AdvancementTree();
        tree.addAll(snapshot);

        editTabs.clear();
        selectedEditTab = null;
        for (AdvancementNode root : tree.roots()) {
            CodexAdvancementTab tab = CodexAdvancementTab.create(editTabs.size(), root);
            if (tab == null) {
                continue;
            }
            editTabs.put(root.holder(), tab);
            addDescendants(tab, root);
        }
        if (!editTabs.isEmpty()) {
            selectedEditTab = editTabs.values().iterator().next();
        }

        editMode = true;
        if (editModeButton != null) {
            editModeButton.setMessage(editModeLabel());
        }
    }

    private void addDescendants(CodexAdvancementTab tab, AdvancementNode node) {
        for (AdvancementNode child : node.children()) {
            tab.addAdvancement(child);
            addDescendants(tab, child);
        }
    }

    private Map<AdvancementHolder, CodexAdvancementTab> activeTabs() {
        return editMode ? editTabs : viewTabs;
    }

    private CodexAdvancementTab activeSelectedTab() {
        return editMode ? selectedEditTab : selectedViewTab;
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

        Map<AdvancementHolder, CodexAdvancementTab> tabs = activeTabs();
        CodexAdvancementTab selectedTab = activeSelectedTab();

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
            int contentX = left + WINDOW_INSIDE_X;
            int contentY = top + WINDOW_INSIDE_Y;
            selectedTab.extractContents(extractor, contentX, contentY, editMode);
            var hovered = selectedTab.findHovered(contentX, contentY, mouseX, mouseY);
            if (hovered != null) {
                extractor.setComponentTooltipForNextFrame(this.font,
                        List.of(hovered.display().getTitle(), hovered.display().getDescription()), mouseX, mouseY);
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() == 0) {
            int left = (this.width - WINDOW_WIDTH) / 2;
            int top = (this.height - WINDOW_HEIGHT) / 2;
            for (CodexAdvancementTab tab : activeTabs().values()) {
                if (tab.isMouseOverTabButton(left, top, event.x(), event.y())) {
                    if (editMode) {
                        selectedEditTab = tab;
                    } else {
                        advancements.setSelectedTab(tab.getRootNode().holder(), true);
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        CodexAdvancementTab selectedTab = activeSelectedTab();
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
        CodexAdvancementTab selectedTab = activeSelectedTab();
        if (selectedTab != null) {
            selectedTab.scroll(scrollX * 16.0, scrollY * 16.0);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onAddAdvancementRoot(AdvancementNode node) {
        CodexAdvancementTab tab = CodexAdvancementTab.create(viewTabs.size(), node);
        if (tab == null) {
            return;
        }
        viewTabs.put(node.holder(), tab);
    }

    @Override
    public void onRemoveAdvancementRoot(AdvancementNode node) {
        viewTabs.remove(node.holder());
    }

    @Override
    public void onAddAdvancementTask(AdvancementNode node) {
        CodexAdvancementTab tab = getViewTab(node);
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
        CodexAdvancementTab tab = getViewTab(node);
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
        selectedViewTab = viewTabs.get(holder);
    }

    @Override
    public void onAdvancementsCleared() {
        viewTabs.clear();
        selectedViewTab = null;
    }

    private CodexAdvancementTab getViewTab(AdvancementNode node) {
        AdvancementNode root = AdvancementNode.getRoot(node);
        return viewTabs.get(root.holder());
    }
}
