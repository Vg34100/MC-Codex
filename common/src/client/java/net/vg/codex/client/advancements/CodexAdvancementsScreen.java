package net.vg.codex.client.advancements;

import dev.architectury.networking.NetworkManager;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vg.codex.network.RequestAdvancementSnapshotPayload;
import net.vg.codex.network.SaveAdvancementTreePayload;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Recreation of vanilla's {@code AdvancementsScreen}. Normal view mode is a faithful clone driven
 * by the same {@link ClientAdvancements} vanilla's own screen listens to, so anything vanilla
 * shows, this shows identically. Edit mode (stage 3) swaps to a separate, locally-built tree from
 * the full unfiltered server snapshot, so hidden/locked advancements are visible and editable too
 * - kept in a completely separate tab map so live ClientAdvancements updates (which keep arriving
 * in the background) never cross-contaminate the edit-mode view.
 *
 * <p>The window is only shifted left of screen-center, to make room for a details panel on the
 * right, while edit mode is actually on - toggling edit mode calls {@link #init(int, int)} again
 * (the same rebuild Screen already does on window resize) so the whole layout, including the
 * panel's width, is recomputed fresh against the current screen size every time.
 */
public final class CodexAdvancementsScreen extends Screen implements ClientAdvancements.Listener {
    private static final Identifier WINDOW_LOCATION = Identifier.fromNamespaceAndPath("minecraft", "textures/gui/advancements/window.png");
    private static final int WINDOW_WIDTH = 252;
    private static final int WINDOW_HEIGHT = 140;
    private static final int WINDOW_INSIDE_X = 9;
    private static final int WINDOW_INSIDE_Y = 18;
    private static final int PANEL_WIDTH = 220;
    private static final int PANEL_GAP = 10;

    private final Screen lastScreen;
    private final ClientAdvancements advancements;

    private final Map<AdvancementHolder, CodexAdvancementTab> viewTabs = new LinkedHashMap<>();
    private CodexAdvancementTab selectedViewTab;

    private final Map<AdvancementHolder, CodexAdvancementTab> editTabs = new LinkedHashMap<>();
    private CodexAdvancementTab selectedEditTab;
    private boolean editMode;

    private boolean isScrolling;
    private Button editModeButton;

    private CodexAdvancementWidget selectedEditWidget;
    private final Map<Identifier, AdvancementHolder> dirty = new LinkedHashMap<>();
    private EditBox titleBox;
    private EditBox descriptionBox;
    private StringWidget statusWidget;
    private Button applyEditButton;
    private Button saveEditsButton;

    public CodexAdvancementsScreen(ClientAdvancements advancements, Screen lastScreen) {
        super(Component.literal("Advancements"));
        this.advancements = advancements;
        this.lastScreen = lastScreen;
    }

    private boolean canEdit() {
        return this.minecraft.player != null
                && Commands.LEVEL_GAMEMASTERS.check(this.minecraft.player.permissions());
    }

    private boolean showEditPanel() {
        return canEdit() && editMode;
    }

    /** Clamped to whatever's actually left of the window within the current screen width. */
    private int panelWidth() {
        int available = this.width - WINDOW_WIDTH - PANEL_GAP - 20;
        return Math.max(100, Math.min(PANEL_WIDTH, available));
    }

    private int windowLeft() {
        if (showEditPanel()) {
            int totalWidth = WINDOW_WIDTH + PANEL_GAP + panelWidth();
            return Math.max(10, (this.width - totalWidth) / 2);
        }
        return Math.max(10, (this.width - WINDOW_WIDTH) / 2);
    }

    private int windowTop() {
        return Math.max(10, (this.height - WINDOW_HEIGHT) / 2);
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

        int left = windowLeft();
        int top = windowTop();
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(left + WINDOW_WIDTH - 90, top + WINDOW_HEIGHT + 6, 90, 20)
                .build());

        if (canEdit()) {
            editModeButton = addRenderableWidget(Button.builder(editModeLabel(), b -> toggleEditMode())
                    .bounds(left, top + WINDOW_HEIGHT + 6, 120, 20)
                    .build());
        }

        if (showEditPanel()) {
            int panelWidth = panelWidth();
            int panelLeft = left + WINDOW_WIDTH + PANEL_GAP;
            int panelTop = top;
            titleBox = addRenderableWidget(new EditBox(this.font, panelLeft, panelTop, panelWidth, 16, Component.literal("Title")));
            descriptionBox = addRenderableWidget(new EditBox(this.font, panelLeft, panelTop + 20, panelWidth, 16, Component.literal("Description")));
            int buttonWidth = (panelWidth - 10) / 2;
            applyEditButton = addRenderableWidget(Button.builder(Component.literal("Apply Edit"), b -> applyEdit())
                    .bounds(panelLeft, panelTop + 40, buttonWidth, 20)
                    .build());
            saveEditsButton = addRenderableWidget(Button.builder(Component.literal("Save"), b -> saveEdits())
                    .bounds(panelLeft + buttonWidth + 10, panelTop + 40, buttonWidth, 20)
                    .build());
            statusWidget = addRenderableWidget(new StringWidget(panelLeft, panelTop + 64, panelWidth, 10, Component.empty(), this.font));

            if (selectedEditWidget != null) {
                titleBox.setValue(selectedEditWidget.display().getTitle().getString());
                descriptionBox.setValue(selectedEditWidget.display().getDescription().getString());
            }
        }
    }

    private void setStatus(String message) {
        if (statusWidget != null) {
            statusWidget.setMessage(Component.literal(message));
        }
    }

    private Component editModeLabel() {
        return Component.literal(editMode ? "Edit Mode: On" : "Edit Mode: Off");
    }

    private void toggleEditMode() {
        if (editMode) {
            editMode = false;
            selectedEditWidget = null;
            this.init(this.width, this.height);
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
        this.init(this.width, this.height);
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
        int left = windowLeft();
        int top = windowTop();

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
            int left = windowLeft();
            int top = windowTop();
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
            if (editMode && selectedEditTab != null) {
                int contentX = left + WINDOW_INSIDE_X;
                int contentY = top + WINDOW_INSIDE_Y;
                CodexAdvancementWidget hit = selectedEditTab.findHovered(contentX, contentY, event.x(), event.y());
                if (hit != null) {
                    selectEditWidget(hit);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubled);
    }

    private void selectEditWidget(CodexAdvancementWidget widget) {
        selectedEditWidget = widget;
        titleBox.setValue(widget.display().getTitle().getString());
        descriptionBox.setValue(widget.display().getDescription().getString());
        setStatus("");
    }

    private void applyEdit() {
        if (selectedEditWidget == null) {
            setStatus("Select an advancement first.");
            return;
        }
        DisplayInfo old = selectedEditWidget.display();
        DisplayInfo updated = new DisplayInfo(
                old.getIcon(),
                Component.literal(titleBox.getValue()),
                Component.literal(descriptionBox.getValue()),
                old.getBackground(),
                old.getType(),
                old.shouldShowToast(),
                old.shouldAnnounceChat(),
                old.isHidden()
        );
        Advancement oldAdvancement = selectedEditWidget.node().advancement();
        Advancement updatedAdvancement = new Advancement(
                oldAdvancement.parent(),
                Optional.of(updated),
                oldAdvancement.rewards(),
                oldAdvancement.criteria(),
                oldAdvancement.requirements(),
                oldAdvancement.sendsTelemetryEvent(),
                oldAdvancement.name()
        );
        Identifier id = selectedEditWidget.node().holder().id();
        dirty.put(id, new AdvancementHolder(id, updatedAdvancement));
        selectedEditWidget.setDisplay(updated);
        setStatus("Edit applied - not saved yet.");
    }

    private void saveEdits() {
        if (dirty.isEmpty()) {
            setStatus("Nothing to save - apply an edit first.");
            return;
        }
        NetworkManager.sendToServer(new SaveAdvancementTreePayload(List.copyOf(dirty.values())));
        setStatus("Sent " + dirty.size() + " edit(s) to the server.");
        dirty.clear();
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
