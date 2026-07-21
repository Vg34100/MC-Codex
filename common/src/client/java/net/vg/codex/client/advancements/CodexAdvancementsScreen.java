package net.vg.codex.client.advancements;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.architectury.networking.NetworkManager;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.Mth;
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
 * <p>Unlike vanilla's fixed 252x140 window, this one scales with the game window (stage 5) - the
 * border texture was inspected directly (extracted window.png: a 9px bevel on left/right/bottom,
 * 18px on top for the title bar, fully transparent middle) so it's drawn as 4 fixed corners plus
 * tiled edges rather than a single stretched blit, letting it grow to any size without blurring.
 *
 * <p>The window is only shifted left of screen-center, to make room for a details panel on the
 * right, while edit mode is actually on - toggling edit mode calls {@link #init(int, int)} again
 * (the same rebuild Screen already does on window resize) so the whole layout, including the
 * panel's width, is recomputed fresh against the current screen size every time.
 */
public final class CodexAdvancementsScreen extends Screen implements ClientAdvancements.Listener {
    private static final Identifier WINDOW_LOCATION = Identifier.fromNamespaceAndPath("minecraft", "textures/gui/advancements/window.png");

    // Measured directly from the extracted window.png (256x256 atlas, art occupies 0,0-252,140).
    private static final int BORDER_LEFT = 9;
    private static final int BORDER_RIGHT = 9;
    private static final int BORDER_TOP = 18;
    private static final int BORDER_BOTTOM = 9;
    private static final int ART_WIDTH = 252;
    private static final int ART_HEIGHT = 140;
    private static final int EDGE_CHUNK = 16;

    private static final int MIN_WINDOW_WIDTH = ART_WIDTH;
    private static final int MIN_WINDOW_HEIGHT = ART_HEIGHT;
    // Window grows by scaling the whole 252x140 art up rather than stretching width/height
    // independently - independent scaling let the window end up a different aspect ratio than
    // vanilla's depending on the screen's shape, which looked stretched/off.
    private static final float MAX_WINDOW_SCALE = 3.0F;
    private static final int FOOTER_HEIGHT = 26;
    // Room above the window for the tab strip (drawn at windowTop - 28, 32px tall) - without this
    // reserved, a tall window could push windowTop small enough that tabs render off the top of
    // the screen entirely.
    private static final int HEADER_HEIGHT = 40;
    private static final int SCREEN_MARGIN = 20;

    private static final int PANEL_DESIRED_WIDTH = 260;
    private static final int PANEL_MIN_WIDTH = 150;
    private static final int PANEL_GAP = 10;
    private static final int DESCRIPTION_HEIGHT = 60;
    private static final int MIN_CRITERIA_HEIGHT = 40;
    private static final int BOTTOM_CONTROLS_HEIGHT = 50;

    private static final float ZOOM_STEP = 0.25F;
    private static final float MIN_ZOOM = 0.5F;
    private static final float MAX_ZOOM = 2.5F;

    // Advancement.CRITERIA_CODEC (the vanilla equivalent) is private - this is the same shape
    // (an unbounded map of criterion name -> Criterion), built from Criterion.CODEC which is public.
    private static final Codec<Map<String, Criterion<?>>> CRITERIA_CODEC = Codec.unboundedMap(Codec.STRING, Criterion.CODEC);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Screen lastScreen;
    private final ClientAdvancements advancements;

    private final Map<AdvancementHolder, CodexAdvancementTab> viewTabs = new LinkedHashMap<>();
    private CodexAdvancementTab selectedViewTab;

    private final Map<AdvancementHolder, CodexAdvancementTab> editTabs = new LinkedHashMap<>();
    private CodexAdvancementTab selectedEditTab;
    private boolean editMode;

    private boolean isScrolling;
    private Button editModeButton;
    private float zoom = 1.0F;

    private CodexAdvancementWidget selectedEditWidget;
    private final Map<Identifier, AdvancementHolder> dirty = new LinkedHashMap<>();
    private EditBox titleBox;
    private MultiLineEditBox descriptionBox;
    private MultiLineEditBox criteriaBox;
    private StringWidget statusWidget;
    private Button applyEditButton;
    private Button saveEditsButton;

    public CodexAdvancementsScreen(ClientAdvancements advancements, Screen lastScreen) {
        super(Component.literal("Advancements"));
        this.advancements = advancements;
        this.lastScreen = lastScreen;
    }

    private void adjustZoom(float delta) {
        zoom = Mth.clamp(zoom + delta, MIN_ZOOM, MAX_ZOOM);
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
        int available = this.width - MIN_WINDOW_WIDTH - PANEL_GAP - SCREEN_MARGIN;
        return Math.max(PANEL_MIN_WIDTH, Math.min(PANEL_DESIRED_WIDTH, available));
    }

    /** A single scale factor applied to the whole 252x140 art, so it never distorts proportions. */
    private float windowScale() {
        int reservedWidth = showEditPanel() ? panelWidth() + PANEL_GAP : 0;
        int availableWidth = this.width - reservedWidth - SCREEN_MARGIN;
        int availableHeight = this.height - HEADER_HEIGHT - FOOTER_HEIGHT - SCREEN_MARGIN;
        float scaleW = (float) availableWidth / ART_WIDTH;
        float scaleH = (float) availableHeight / ART_HEIGHT;
        return Mth.clamp(Math.min(scaleW, scaleH), 1.0F, MAX_WINDOW_SCALE);
    }

    private int windowWidth() {
        return Math.round(ART_WIDTH * windowScale());
    }

    private int windowHeight() {
        return Math.round(ART_HEIGHT * windowScale());
    }

    private int windowLeft() {
        int totalWidth = windowWidth() + (showEditPanel() ? PANEL_GAP + panelWidth() : 0);
        return Math.max(10, (this.width - totalWidth) / 2);
    }

    private int windowTop() {
        int totalBlockHeight = HEADER_HEIGHT + windowHeight() + FOOTER_HEIGHT;
        int blockTop = Math.max(10, (this.height - totalBlockHeight) / 2);
        return blockTop + HEADER_HEIGHT;
    }

    private int contentWidth() {
        return windowWidth() - BORDER_LEFT - BORDER_RIGHT;
    }

    private int contentHeight() {
        return windowHeight() - BORDER_TOP - BORDER_BOTTOM;
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
        int windowWidth = windowWidth();
        int windowHeight = windowHeight();
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(left + windowWidth - 90, top + windowHeight + 6, 90, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustZoom(-ZOOM_STEP))
                .bounds(left + windowWidth - 90 - 46, top + windowHeight + 6, 20, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustZoom(ZOOM_STEP))
                .bounds(left + windowWidth - 90 - 22, top + windowHeight + 6, 20, 20)
                .build());

        if (canEdit()) {
            editModeButton = addRenderableWidget(Button.builder(editModeLabel(), b -> toggleEditMode())
                    .bounds(left, top + windowHeight + 6, 120, 20)
                    .build());
        }

        if (showEditPanel()) {
            int panelWidth = panelWidth();
            int panelLeft = left + windowWidth + PANEL_GAP;
            int panelTop = top;
            titleBox = addRenderableWidget(new EditBox(this.font, panelLeft, panelTop, panelWidth, 16, Component.literal("Title")));
            descriptionBox = addRenderableWidget(MultiLineEditBox.builder()
                    .setX(panelLeft)
                    .setY(panelTop + 20)
                    .setPlaceholder(Component.literal("Description"))
                    .build(this.font, panelWidth, DESCRIPTION_HEIGHT, Component.literal("Description")));

            int criteriaTop = panelTop + 20 + DESCRIPTION_HEIGHT + 6;
            int buttonsTop = top + windowHeight - BOTTOM_CONTROLS_HEIGHT;
            int criteriaHeight = Math.max(MIN_CRITERIA_HEIGHT, buttonsTop - criteriaTop - 6);
            criteriaBox = addRenderableWidget(MultiLineEditBox.builder()
                    .setX(panelLeft)
                    .setY(criteriaTop)
                    .setPlaceholder(Component.literal("Criteria (raw JSON)"))
                    .build(this.font, panelWidth, criteriaHeight, Component.literal("Criteria (raw JSON)")));

            int buttonWidth = (panelWidth - 10) / 2;
            applyEditButton = addRenderableWidget(Button.builder(Component.literal("Apply Edit"), b -> applyEdit())
                    .bounds(panelLeft, buttonsTop, buttonWidth, 20)
                    .build());
            saveEditsButton = addRenderableWidget(Button.builder(Component.literal("Save"), b -> saveEdits())
                    .bounds(panelLeft + buttonWidth + 10, buttonsTop, buttonWidth, 20)
                    .build());
            statusWidget = addRenderableWidget(new StringWidget(panelLeft, buttonsTop + 24, panelWidth, 10, Component.empty(), this.font));

            if (selectedEditWidget != null) {
                titleBox.setValue(selectedEditWidget.display().getTitle().getString());
                descriptionBox.setValue(selectedEditWidget.display().getDescription().getString());
                criteriaBox.setValue(encodeCriteria(criteriaFor(selectedEditWidget)));
            }
        }
    }

    private HolderLookup.Provider registries() {
        return this.minecraft.level.registryAccess();
    }

    private String encodeCriteria(Map<String, Criterion<?>> criteria) {
        JsonElement json = CRITERIA_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries()), criteria).getOrThrow();
        return GSON.toJson(json);
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
        int windowWidth = windowWidth();
        int windowHeight = windowHeight();

        drawWindowFrame(extractor, left, top, windowWidth, windowHeight);

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
            int contentX = left + BORDER_LEFT;
            int contentY = top + BORDER_TOP;
            int contentWidth = contentWidth();
            int contentHeight = contentHeight();
            selectedTab.extractContents(extractor, contentX, contentY, contentWidth, contentHeight, zoom, editMode);
            var hovered = selectedTab.findHovered(contentX, contentY, contentWidth, contentHeight, zoom, mouseX, mouseY);
            if (hovered != null) {
                extractor.setComponentTooltipForNextFrame(this.font,
                        List.of(hovered.display().getTitle(), hovered.display().getDescription()), mouseX, mouseY);
            }
        }
    }

    /**
     * Draws the window chrome as 4 fixed-size corners plus tiled (not stretched) edges, using the
     * real vanilla border art, so it can scale to any size without blurring a stretched blit.
     */
    private void drawWindowFrame(GuiGraphicsExtractor extractor, int left, int top, int width, int height) {
        // Corners (native size, no scaling).
        blitRegion(extractor, left, top, 0, 0, BORDER_LEFT, BORDER_TOP);
        blitRegion(extractor, left + width - BORDER_RIGHT, top, ART_WIDTH - BORDER_RIGHT, 0, BORDER_RIGHT, BORDER_TOP);
        blitRegion(extractor, left, top + height - BORDER_BOTTOM, 0, ART_HEIGHT - BORDER_BOTTOM, BORDER_LEFT, BORDER_BOTTOM);
        blitRegion(extractor, left + width - BORDER_RIGHT, top + height - BORDER_BOTTOM, ART_WIDTH - BORDER_RIGHT, ART_HEIGHT - BORDER_BOTTOM, BORDER_RIGHT, BORDER_BOTTOM);

        // Top/bottom edges, tiled horizontally between the corners.
        int edgeWidth = width - BORDER_LEFT - BORDER_RIGHT;
        tileHorizontal(extractor, left + BORDER_LEFT, top, edgeWidth, BORDER_LEFT, 0, BORDER_TOP);
        tileHorizontal(extractor, left + BORDER_LEFT, top + height - BORDER_BOTTOM, edgeWidth, BORDER_LEFT, ART_HEIGHT - BORDER_BOTTOM, BORDER_BOTTOM);

        // Left/right edges, tiled vertically between the corners.
        int edgeHeight = height - BORDER_TOP - BORDER_BOTTOM;
        tileVertical(extractor, left, top + BORDER_TOP, edgeHeight, 0, BORDER_TOP, BORDER_LEFT);
        tileVertical(extractor, left + width - BORDER_RIGHT, top + BORDER_TOP, edgeHeight, ART_WIDTH - BORDER_RIGHT, BORDER_TOP, BORDER_RIGHT);
    }

    private void blitRegion(GuiGraphicsExtractor extractor, int x, int y, int u, int v, int w, int h) {
        extractor.blit(RenderPipelines.GUI_TEXTURED, WINDOW_LOCATION, x, y, (float) u, (float) v, w, h, 256, 256);
    }

    private void tileHorizontal(GuiGraphicsExtractor extractor, int x, int y, int totalWidth, int u, int v, int chunkHeight) {
        int drawn = 0;
        while (drawn < totalWidth) {
            int w = Math.min(EDGE_CHUNK, totalWidth - drawn);
            blitRegion(extractor, x + drawn, y, u, v, w, chunkHeight);
            drawn += w;
        }
    }

    private void tileVertical(GuiGraphicsExtractor extractor, int x, int y, int totalHeight, int u, int v, int chunkWidth) {
        int drawn = 0;
        while (drawn < totalHeight) {
            int h = Math.min(EDGE_CHUNK, totalHeight - drawn);
            blitRegion(extractor, x, y + drawn, u, v, chunkWidth, h);
            drawn += h;
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
                int contentX = left + BORDER_LEFT;
                int contentY = top + BORDER_TOP;
                CodexAdvancementWidget hit = selectedEditTab.findHovered(contentX, contentY, contentWidth(), contentHeight(), zoom, event.x(), event.y());
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
        criteriaBox.setValue(encodeCriteria(criteriaFor(widget)));
        setStatus("");
    }

    /** Prior applied-but-unsaved edits (tracked in `dirty`) take precedence over the original. */
    private Map<String, Criterion<?>> criteriaFor(CodexAdvancementWidget widget) {
        AdvancementHolder dirtyHolder = dirty.get(widget.node().holder().id());
        return dirtyHolder != null ? dirtyHolder.value().criteria() : widget.node().advancement().criteria();
    }

    private void applyEdit() {
        if (selectedEditWidget == null) {
            setStatus("Select an advancement first.");
            return;
        }
        DisplayInfo old = selectedEditWidget.display();
        DisplayInfo updatedDisplay = new DisplayInfo(
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
        Map<String, Criterion<?>> oldCriteria = oldAdvancement.criteria();
        Map<String, Criterion<?>> newCriteria;
        try {
            JsonElement json = JsonParser.parseString(criteriaBox.getValue());
            newCriteria = CRITERIA_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries()), json).getOrThrow();
        } catch (RuntimeException e) {
            setStatus("Invalid criteria JSON: " + e.getMessage());
            return;
        }
        // Requirements name the criteria groups that must be met to complete the advancement.
        // If the criteria keys didn't change, keep whatever grouping (AND/OR) was already there;
        // if they did, fall back to requiring all of them - editing custom OR-groupings is not
        // yet exposed, this is a reasonable default rather than silently keeping stale group names.
        AdvancementRequirements requirements = newCriteria.keySet().equals(oldCriteria.keySet())
                ? oldAdvancement.requirements()
                : AdvancementRequirements.allOf(newCriteria.keySet());

        Advancement updatedAdvancement = new Advancement(
                oldAdvancement.parent(),
                Optional.of(updatedDisplay),
                oldAdvancement.rewards(),
                newCriteria,
                requirements,
                oldAdvancement.sendsTelemetryEvent(),
                oldAdvancement.name()
        );
        Identifier id = selectedEditWidget.node().holder().id();
        dirty.put(id, new AdvancementHolder(id, updatedAdvancement));
        selectedEditWidget.setDisplay(updatedDisplay);
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
            selectedTab.scroll(-dragX / zoom, -dragY / zoom, contentWidth(), contentHeight(), zoom);
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
            selectedTab.scroll(scrollX * 16.0, scrollY * 16.0, contentWidth(), contentHeight(), zoom);
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
