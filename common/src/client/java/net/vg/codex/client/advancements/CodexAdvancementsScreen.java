package net.vg.codex.client.advancements;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import net.minecraft.client.gui.components.CycleButton;
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

import java.util.ArrayList;
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
    private static final int MAX_CRITERIA_ROWS = 4;
    private static final int CRITERIA_ROW_HEIGHT = 14;

    private static final float ZOOM_STEP = 0.25F;
    private static final float MIN_ZOOM = 0.5F;
    private static final float MAX_ZOOM = 2.5F;

    // Advancement.CRITERIA_CODEC (the vanilla equivalent) is private - this is the same shape
    // (an unbounded map of criterion name -> Criterion), built from Criterion.CODEC which is public.
    private static final Codec<Map<String, Criterion<?>>> CRITERIA_CODEC = Codec.unboundedMap(Codec.STRING, Criterion.CODEC);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Curated common trigger types with simplified single-value fields, so editing criteria means
     * picking a type and filling in one plain-text value instead of hand-writing JSON. JSON shapes
     * below were confirmed against real vanilla advancement files (sniper_duel.json for
     * player_killed_entity, find_fortress.json for location, purple_stained_glass_pane.json for
     * recipe_unlocked/inventory_changed), not guessed. CUSTOM remains as a raw-JSON escape hatch
     * for anything not in this curated list.
     */
    private enum CriterionType {
        IMPOSSIBLE("minecraft:impossible", "Impossible", false),
        TICK("minecraft:tick", "Always (tick)", false),
        ITEM_OBTAINED("minecraft:inventory_changed", "Item Obtained", true),
        PLAYER_KILLED_ENTITY("minecraft:player_killed_entity", "Player Killed Entity", true),
        ENTITY_KILLED_PLAYER("minecraft:entity_killed_player", "Entity Killed Player", true),
        LOCATION("minecraft:location", "Reached Location", true),
        RECIPE_UNLOCKED("minecraft:recipe_unlocked", "Recipe Unlocked", true),
        CUSTOM(null, "Custom (raw JSON)", true);

        final String triggerId;
        final String label;
        final boolean hasValue;

        CriterionType(String triggerId, String label, boolean hasValue) {
            this.triggerId = triggerId;
            this.label = label;
            this.hasValue = hasValue;
        }

        static CriterionType fromTriggerId(String id) {
            for (CriterionType type : values()) {
                if (type.triggerId != null && type.triggerId.equals(id)) {
                    return type;
                }
            }
            return CUSTOM;
        }
    }

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
    private StringWidget statusWidget;
    private Button applyEditButton;
    private Button saveEditsButton;

    // Criteria editor: `criteriaEntries` is the working copy for whichever advancement is
    // currently selected (raw per-criterion JSON, name -> object), edited via the list + form
    // below rather than one big hand-written JSON blob.
    private final Map<String, JsonObject> criteriaEntries = new LinkedHashMap<>();
    private final List<String> currentCriteriaNames = new ArrayList<>();
    private String selectedCriterionName;
    private final List<Button> criteriaListButtons = new ArrayList<>();
    private Button addCriterionButton;
    private EditBox criterionNameBox;
    private CycleButton<CriterionType> criterionTypeButton;
    private EditBox criterionValueBox;
    private Button applyCriterionButton;
    private Button removeCriterionButton;

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

        criteriaListButtons.clear();
        if (showEditPanel()) {
            int panelWidth = panelWidth();
            int panelLeft = left + windowWidth + PANEL_GAP;
            int y = top;

            titleBox = addRenderableWidget(new EditBox(this.font, panelLeft, y, panelWidth, 16, Component.literal("Title")));
            y += 20;

            descriptionBox = addRenderableWidget(MultiLineEditBox.builder()
                    .setX(panelLeft)
                    .setY(y)
                    .setPlaceholder(Component.literal("Description"))
                    .build(this.font, panelWidth, DESCRIPTION_HEIGHT, Component.literal("Description")));
            y += DESCRIPTION_HEIGHT + 6;

            for (int i = 0; i < MAX_CRITERIA_ROWS; i++) {
                int rowIndex = i;
                int rowY = y + i * CRITERIA_ROW_HEIGHT;
                Button row = addRenderableWidget(Button.builder(Component.empty(), b -> selectCriterionAt(rowIndex))
                        .bounds(panelLeft, rowY, panelWidth, CRITERIA_ROW_HEIGHT - 2)
                        .build());
                row.visible = false;
                criteriaListButtons.add(row);
            }
            y += MAX_CRITERIA_ROWS * CRITERIA_ROW_HEIGHT + 4;

            addCriterionButton = addRenderableWidget(Button.builder(Component.literal("+ Add Criterion"), b -> addCriterion())
                    .bounds(panelLeft, y, panelWidth, 16)
                    .build());
            y += 20;

            criterionNameBox = addRenderableWidget(new EditBox(this.font, panelLeft, y, panelWidth, 16, Component.literal("Criterion name")));
            y += 20;

            criterionTypeButton = addRenderableWidget(CycleButton.builder((CriterionType t) -> Component.literal(t.label), CriterionType.IMPOSSIBLE)
                    .withValues(CriterionType.values())
                    .create(panelLeft, y, panelWidth, 20, Component.literal("Type"), (button, value) ->
                            criterionValueBox.setEditable(value.hasValue)));
            y += 24;

            criterionValueBox = addRenderableWidget(new EditBox(this.font, panelLeft, y, panelWidth, 16, Component.literal("Value")));
            y += 20;

            int halfWidth = (panelWidth - 10) / 2;
            applyCriterionButton = addRenderableWidget(Button.builder(Component.literal("Apply Criterion"), b -> applyCriterion())
                    .bounds(panelLeft, y, halfWidth, 20)
                    .build());
            removeCriterionButton = addRenderableWidget(Button.builder(Component.literal("Remove"), b -> removeCriterion())
                    .bounds(panelLeft + halfWidth + 10, y, halfWidth, 20)
                    .build());
            y += 26;

            applyEditButton = addRenderableWidget(Button.builder(Component.literal("Apply Edit"), b -> applyEdit())
                    .bounds(panelLeft, y, halfWidth, 20)
                    .build());
            saveEditsButton = addRenderableWidget(Button.builder(Component.literal("Save"), b -> saveEdits())
                    .bounds(panelLeft + halfWidth + 10, y, halfWidth, 20)
                    .build());
            y += 24;

            statusWidget = addRenderableWidget(new StringWidget(panelLeft, y, panelWidth, 10, Component.empty(), this.font));

            if (selectedEditWidget != null) {
                titleBox.setValue(selectedEditWidget.display().getTitle().getString());
                descriptionBox.setValue(selectedEditWidget.display().getDescription().getString());
                loadCriteriaEntries(criteriaFor(selectedEditWidget));
            } else {
                clearCriterionEditor();
                refreshCriteriaList();
            }
        }
    }

    private HolderLookup.Provider registries() {
        return this.minecraft.level.registryAccess();
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
        loadCriteriaEntries(criteriaFor(widget));
        setStatus("");
    }

    /** Prior applied-but-unsaved edits (tracked in `dirty`) take precedence over the original. */
    private Map<String, Criterion<?>> criteriaFor(CodexAdvancementWidget widget) {
        AdvancementHolder dirtyHolder = dirty.get(widget.node().holder().id());
        return dirtyHolder != null ? dirtyHolder.value().criteria() : widget.node().advancement().criteria();
    }

    // ---- Criteria list/editor ----

    private void loadCriteriaEntries(Map<String, Criterion<?>> criteria) {
        criteriaEntries.clear();
        if (!criteria.isEmpty()) {
            JsonElement encoded = CRITERIA_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries()), criteria).getOrThrow();
            for (Map.Entry<String, JsonElement> e : encoded.getAsJsonObject().entrySet()) {
                criteriaEntries.put(e.getKey(), e.getValue().getAsJsonObject());
            }
        }
        clearCriterionEditor();
        refreshCriteriaList();
    }

    private void refreshCriteriaList() {
        currentCriteriaNames.clear();
        currentCriteriaNames.addAll(criteriaEntries.keySet());
        for (int i = 0; i < criteriaListButtons.size(); i++) {
            Button button = criteriaListButtons.get(i);
            if (i < currentCriteriaNames.size()) {
                String name = currentCriteriaNames.get(i);
                JsonObject json = criteriaEntries.get(name);
                String triggerId = json.has("trigger") ? json.get("trigger").getAsString() : "?";
                button.setMessage(Component.literal(name + " (" + triggerId + ")"));
                button.visible = true;
                button.active = true;
            } else {
                button.visible = false;
                button.active = false;
            }
        }
    }

    private void selectCriterionAt(int index) {
        if (index >= 0 && index < currentCriteriaNames.size()) {
            selectCriterion(currentCriteriaNames.get(index));
        }
    }

    private void selectCriterion(String name) {
        JsonObject json = criteriaEntries.get(name);
        if (json == null) {
            return;
        }
        selectedCriterionName = name;
        criterionNameBox.setValue(name);
        String triggerId = json.has("trigger") ? json.get("trigger").getAsString() : null;
        CriterionType type = CriterionType.fromTriggerId(triggerId);
        criterionTypeButton.setValue(type);
        criterionValueBox.setEditable(type.hasValue);
        criterionValueBox.setValue(extractValue(type, json));
        setStatus("");
    }

    private void clearCriterionEditor() {
        selectedCriterionName = null;
        if (criterionNameBox != null) {
            criterionNameBox.setValue("");
            criterionTypeButton.setValue(CriterionType.IMPOSSIBLE);
            criterionValueBox.setValue("");
            criterionValueBox.setEditable(false);
        }
    }

    private void addCriterion() {
        String base = "criterion";
        String name = base;
        int n = 1;
        while (criteriaEntries.containsKey(name)) {
            name = base + "_" + (n++);
        }
        criteriaEntries.put(name, buildCriterionJson(CriterionType.IMPOSSIBLE, ""));
        refreshCriteriaList();
        selectCriterion(name);
    }

    private void applyCriterion() {
        String name = criterionNameBox.getValue().trim();
        if (name.isEmpty()) {
            setStatus("Criterion name can't be blank.");
            return;
        }
        CriterionType type = criterionTypeButton.getValue();
        JsonObject json;
        try {
            json = buildCriterionJson(type, criterionValueBox.getValue());
        } catch (RuntimeException e) {
            setStatus("Invalid criterion: " + e.getMessage());
            return;
        }
        if (selectedCriterionName != null && !selectedCriterionName.equals(name)) {
            criteriaEntries.remove(selectedCriterionName);
        }
        criteriaEntries.put(name, json);
        selectedCriterionName = name;
        refreshCriteriaList();
        setStatus("Criterion applied to this advancement - hit Apply Edit, then Save.");
    }

    private void removeCriterion() {
        if (selectedCriterionName == null) {
            setStatus("Select a criterion first.");
            return;
        }
        criteriaEntries.remove(selectedCriterionName);
        clearCriterionEditor();
        refreshCriteriaList();
        setStatus("Criterion removed - hit Apply Edit, then Save.");
    }

    /**
     * Builds the raw JSON for one criterion from a curated type + simplified value, matching real
     * vanilla shapes confirmed from actual advancement files rather than guessed.
     */
    private JsonObject buildCriterionJson(CriterionType type, String value) {
        if (type == CriterionType.CUSTOM) {
            String text = value.isBlank() ? "{}" : value;
            return JsonParser.parseString(text).getAsJsonObject();
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("trigger", type.triggerId);
        JsonObject conditions = new JsonObject();
        String trimmed = value.trim();
        switch (type) {
            case ITEM_OBTAINED -> {
                JsonArray items = new JsonArray();
                JsonObject item = new JsonObject();
                item.addProperty("items", trimmed.isEmpty() ? "minecraft:stone" : trimmed);
                items.add(item);
                conditions.add("items", items);
            }
            case PLAYER_KILLED_ENTITY, ENTITY_KILLED_PLAYER -> {
                if (!trimmed.isEmpty()) {
                    JsonObject predicateInner = new JsonObject();
                    predicateInner.addProperty("type", trimmed);
                    conditions.add("entity", wrapEntityProperties(predicateInner));
                }
            }
            case LOCATION -> {
                if (!trimmed.isEmpty()) {
                    JsonObject location = new JsonObject();
                    location.addProperty("dimension", trimmed);
                    JsonObject predicateInner = new JsonObject();
                    predicateInner.add("location", location);
                    conditions.add("player", wrapEntityProperties(predicateInner));
                }
            }
            case RECIPE_UNLOCKED -> conditions.addProperty("recipe", trimmed);
            default -> {
                // IMPOSSIBLE / TICK take no fields.
            }
        }
        if (!conditions.isEmpty()) {
            obj.add("conditions", conditions);
        }
        return obj;
    }

    private JsonArray wrapEntityProperties(JsonObject predicateInner) {
        JsonObject condition = new JsonObject();
        condition.addProperty("condition", "minecraft:entity_properties");
        condition.addProperty("entity", "this");
        condition.add("predicate", predicateInner);
        JsonArray array = new JsonArray();
        array.add(condition);
        return array;
    }

    /** Best-effort reverse of {@link #buildCriterionJson} for populating the value box on select. */
    private String extractValue(CriterionType type, JsonObject criterionJson) {
        if (type == CriterionType.CUSTOM) {
            return GSON.toJson(criterionJson);
        }
        if (!criterionJson.has("conditions") || !criterionJson.get("conditions").isJsonObject()) {
            return "";
        }
        JsonObject conditions = criterionJson.getAsJsonObject("conditions");
        try {
            return switch (type) {
                case ITEM_OBTAINED -> conditions.getAsJsonArray("items").get(0).getAsJsonObject().get("items").getAsString();
                case PLAYER_KILLED_ENTITY, ENTITY_KILLED_PLAYER -> {
                    if (!conditions.has("entity")) {
                        yield "";
                    }
                    JsonObject predicate = conditions.getAsJsonArray("entity").get(0).getAsJsonObject().getAsJsonObject("predicate");
                    yield predicate.has("type") ? predicate.get("type").getAsString() : "";
                }
                case LOCATION -> {
                    if (!conditions.has("player")) {
                        yield "";
                    }
                    JsonObject predicate = conditions.getAsJsonArray("player").get(0).getAsJsonObject().getAsJsonObject("predicate");
                    JsonObject location = predicate.getAsJsonObject("location");
                    yield location != null && location.has("dimension") ? location.get("dimension").getAsString() : "";
                }
                case RECIPE_UNLOCKED -> conditions.has("recipe") ? conditions.get("recipe").getAsString() : "";
                default -> "";
            };
        } catch (RuntimeException e) {
            // Shape didn't match what this type expects (e.g. hand-edited or from a mod) - fall
            // back to blank rather than crash; the raw JSON is still intact under the hood since
            // we only ever replace an entry wholesale via applyCriterion.
            return "";
        }
    }

    // ---- Apply / Save ----

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
        Map<String, Criterion<?>> newCriteria = new LinkedHashMap<>();
        try {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries());
            for (Map.Entry<String, JsonObject> e : criteriaEntries.entrySet()) {
                newCriteria.put(e.getKey(), Criterion.CODEC.parse(ops, e.getValue()).getOrThrow());
            }
        } catch (RuntimeException e) {
            setStatus("Invalid criteria: " + e.getMessage());
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
