package net.vg.codex.client.advancements;

import net.vg.codex.advancement.AdvancementDocument;
import net.vg.codex.advancement.AdvancementDocument.Requirements;
import net.vg.codex.advancement.QuestCriteria.CriterionType;
import net.vg.codex.advancement.QuestRewards.RewardType;
import net.vg.codex.advancement.QuestRewards;
import net.vg.codex.advancement.QuestCriteria.StatisticCategory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.architectury.networking.NetworkManager;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.TreeNodePosition;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementWidgetType;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.vg.codex.ModMain;
import net.vg.codex.advancement.AdvancementPackIO;
import net.vg.codex.network.DeleteAdvancementsPayload;
import net.vg.codex.network.ClaimAllAdvancementRewardsPayload;
import net.vg.codex.network.ClaimAdvancementRewardPayload;
import net.vg.codex.network.SubmitAdvancementItemsPayload;
import net.vg.codex.network.OpenAdvancementEditorPayload;
import net.vg.codex.network.RawAdvancement;
import net.vg.codex.network.RequestAdvancementSnapshotPayload;
import net.vg.codex.network.SaveAdvancementTreePayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
public final class CodexAdvancementsScreen extends CodexScreen implements ClientAdvancements.Listener {
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
    // Was capped at 4 - now that the whole panel scrolls, there's no reason a real advancement
    // with dozens of criteria (e.g. "A Balanced Diet") can't have all of them listed and selectable.
    private static final int MAX_CRITERIA_ROWS = 48;
    private static final int CRITERIA_ROW_HEIGHT = 14;
    // A reward list this long is unrealistic, but capped for the same reason MAX_CRITERIA_ROWS is.
    private static final int MAX_REWARD_ROWS = 32;
    private static final int REWARD_ROW_HEIGHT = 14;
    // Reserved so the scrollbar has a small gap from the field content instead of sitting flush
    // against it.
    private static final int PANEL_SCROLLBAR_GUTTER = 8;
    // Reserved at the bottom of the panel's own box (below the scrollable area) for whichever
    // action buttons + status line apply - Apply Edit/Save/Delete, or Create/Cancel - so they're
    // always visible instead of requiring a scroll to the very bottom to reach them.
    private static final int PANEL_FOOTER_HEIGHT = 54;
    private static final int FRAME_ICON_SIZE = 24;

    private static final int ICON_PICKER_WIDTH = 216;
    private static final int ICON_PICKER_HEIGHT = 240;
    private static final int ICON_TITLE_Y = 10;

    private static final float ZOOM_STEP = 0.25F;
    private static final float MIN_ZOOM = 0.5F;
    private static final float MAX_ZOOM = 2.5F;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Curated common trigger types with simplified value fields, so editing criteria means picking
     * a type and filling in plain text instead of hand-writing JSON. JSON shapes below were
     * confirmed against real vanilla advancement files extracted straight from the game jar (not
     * guessed) - sniper_duel.json for player_killed_entity, follow_ender_eye.json/find_bastion.json
     * for location (structures), nether/explore_nether.json for location (biomes),
     * story/enter_the_end.json for changed_dimension, husbandry/wax_on.json and
     * adventure/use_lodestone.json for item_used_on_block, purple_stained_glass_pane.json for
     * recipe_unlocked/inventory_changed. CUSTOM remains as a raw-JSON escape hatch for anything not
     * in this curated list.
     */
    /** What the details panel currently shows instead of the normal edit form. */
    private enum CreationMode {
        NONE, NEW_TAB, NEW_NODE
    }

    /** The 5 tiled background textures vanilla itself ships, plus a raw-id escape hatch. */
    private enum BackgroundOption {
        STONE("minecraft:gui/advancements/backgrounds/stone", "Stone (Story)"),
        ADVENTURE("minecraft:gui/advancements/backgrounds/adventure", "Adventure"),
        NETHER("minecraft:gui/advancements/backgrounds/nether", "Nether"),
        END("minecraft:gui/advancements/backgrounds/end", "End"),
        HUSBANDRY("minecraft:gui/advancements/backgrounds/husbandry", "Husbandry"),
        CUSTOM(null, "Custom...");

        final String textureId;
        final String label;

        BackgroundOption(String textureId, String label) {
            this.textureId = textureId;
            this.label = label;
        }
    }

    /**
     * Vanilla's {@code AdvancementRequirements} supports arbitrary AND-of-OR groupings, but this
     * editor only exposes the two common shapes: every criterion mandatory (one singleton group
     * each - {@link AdvancementRequirements#allOf}), or any single one sufficient (all names in one
     * group - {@link AdvancementRequirements#anyOf}). Mixed groups remain intact and can be edited in the group editor.
     */
    /**
     * A single reward-list entry's kind. EXPERIENCE and FUNCTION are singletons in vanilla (a bare
     * int / a single id, not an array) - {@code addReward}/{@code applyReward} refuse to create a
     * second entry of either. ITEM isn't a real vanilla reward field at all; it's carried through
     * an auto-generated loot table (see {@code AdvancementPackIO}) that "loot" ends up pointing at.
     */
    private final Screen lastScreen;
    private final ClientAdvancements advancements;

    // Keyed by Identifier, not AdvancementHolder - see CodexAdvancementTab's own widgets map for
    // why (AdvancementHolder wraps the whole Advancement, so its equals/hashCode is only as
    // reliable as every criterion/reward field's own; a lookup miss here silently drops a whole
    // tab's progress tracking, not just one widget's).
    private final Map<Identifier, CodexAdvancementTab> viewTabs = new LinkedHashMap<>();
    private CodexAdvancementTab selectedViewTab;

    private final Map<Identifier, CodexAdvancementTab> editTabs = new LinkedHashMap<>();
    private CodexAdvancementTab selectedEditTab;
    private boolean editMode;
    /** When enabled, dragging a selected node writes a Codex-only shared layout position. */
    private boolean customLayoutMode;
    private Identifier pendingLayoutResetTab;

    private boolean isScrolling;
    private Button editModeButton;
    private Button layoutModeButton;
    private float zoom = 1.0F;

    // Index of the first tab shown in the top tab row, once there are more tabs than fit the
    // window's current width - see buildTabRow(). Shared between view/edit mode rather than two
    // separate fields; switching modes just re-clamps it against whichever tab list is now active.
    private int tabPageStart;

    // Tracks the hover box across frames so it can persist while the mouse moves off the icon and
    // into the box itself (e.g. to read a reward tooltip) - see extractRenderState's hover block.
    private CodexAdvancementWidget pressedWidget;
    private double pressX, pressY;
    private long pressedAt;
    private boolean holdSent;
    public Identifier questGesture() { return pressedWidget == null ? null : pressedWidget.node().holder().id(); }
    @Override public void tick() {
        if (pressedWidget != null && !holdSent && CodexClientSettings.holdAccept && System.nanoTime() - pressedAt >= 700_000_000L) {
            var info = ClientQuests.info(pressedWidget.node().holder().id());
            if (ClientQuests.canAccept(info)) { ClientQuests.action(pressedWidget.node().holder().id(), "accept", ""); holdSent = true; }
        }
    }

    private CodexAdvancementWidget lastHoveredWidget;
    private CodexAdvancementTab lastHoveredTab;

    /** Optional recipe viewers must use the retained foreground card, never background nodes. */
    public CodexAdvancementWidget.HoverItem hoverIngredientAt(double mouseX, double mouseY) {
        CodexAdvancementTab tab = activeSelectedTab();
        if (editMode || partyPanelOpen || tab == null || tab != lastHoveredTab || lastHoveredWidget == null) return null;
        int contentX = windowLeft() + BORDER_LEFT, contentY = windowTop() + BORDER_TOP;
        return lastHoveredWidget.hoverIngredientAt(font, tab.widgetScreenX(lastHoveredWidget, contentX, zoom),
                tab.widgetScreenY(lastHoveredWidget, contentY, zoom), width, contentY + contentHeight(), (int)mouseX, (int)mouseY);
    }

    private CodexAdvancementWidget selectedEditWidget;
    // Keep a cursor-space position while dragging. Re-snapping the widget after every tiny event
    // threw away sub-grid movement and made one-cell adjustments feel randomly ignored.
    private CodexAdvancementWidget layoutDragWidget;
    private float layoutDragRawX;
    private float layoutDragRawY;
    // Full JSON of every advancement in the last-received edit-mode snapshot (the "base" an edit
    // patches against) and the edited-but-unsaved JSON per advancement id - kept as raw JsonObject
    // end to end rather than a typed Advancement, since some registry-dependent fields (e.g.
    // HolderSet-based structure predicates) can't be decoded client-side (see RawAdvancement).
    private final Map<Identifier, JsonObject> rawFullJson = new LinkedHashMap<>();
    private final Map<Identifier, java.util.Deque<JsonObject>> undoHistory = new LinkedHashMap<>();
    private record DraftBatch(Map<Identifier, JsonObject> before, Map<Identifier, JsonObject> after) { }
    private final java.util.Deque<DraftBatch> batchUndo = new java.util.ArrayDeque<>();
    private final Map<Identifier, JsonObject> dirty = new LinkedHashMap<>();
    // Real, server-authoritative progress for every advancement (see enterEditMode) - unlike
    // cross-referencing view mode's own live data, this covers advancements the player has never
    // actually discovered in survival, which view mode simply has no data for at all.
    private final Map<Identifier, AdvancementProgress> editProgressSnapshot = new LinkedHashMap<>();
    private Identifier formAdvancementId;
    private EditBox titleBox;
    private MultiLineEditBox descriptionBox;
    private Checkbox showToastCheckbox;
    private Checkbox announceChatCheckbox;
    private Checkbox hiddenCheckbox;
    private Checkbox claimableRewardsCheckbox;
    private CycleButton<Requirements> requirementModeButton;
    private MultiLineTextWidget statusWidget;
    private Button applyEditButton;
    private Button saveEditsButton;
    // Saving triggers an asynchronous server datapack reload.  Keep the local edits intact until
    // that reload has actually succeeded; clearing them at packet-send time caused the close
    // warning and the visible editor state to disagree with what was really saved.
    private boolean savePending;
    private boolean saveQueued;
    private final Map<Identifier, JsonObject> pendingSaveEntries = new LinkedHashMap<>();
    private Button deleteButton;
    // Set on the first click of Delete; a second click on the same selected node confirms it.
    private Identifier pendingDeleteId;

    // ---- Panel scrolling ----
    // Panel widgets are registered via addWidget() (click/keyboard/narration only, no automatic
    // rendering) rather than addRenderableWidget(), so they can be rendered manually through a
    // scissor box below instead of drawing on top of the advancement tree/tabs unclipped.
    private final List<AbstractWidget> panelWidgets = new ArrayList<>();
    private final Map<AbstractWidget, Integer> panelWidgetBaseY = new HashMap<>();
    private double panelScrollOffset;
    private int panelContentBottom;

    private Button claimsButton;
    private Button claimAllButton;

    // ---- Party actions ----
    private boolean partyPanelOpen;
    private Button partyAcceptButton;
    private Button partyLeaveButton;
    private Button partyCloseButton;
    private final List<Button> partyInviteButtons = new ArrayList<>();

    // The sidebar lists quest content. Dedicated dialogs own all entry editing and drafts.
    private final Map<String, JsonObject> criteriaEntries = new LinkedHashMap<>();
    private final List<String> currentCriteriaNames = new ArrayList<>();
    private final List<Button> criteriaListButtons = new ArrayList<>();
    private CodexAdvancementWidget criteriaEntriesSelection;
    private final List<JsonObject> rewardEntries = new ArrayList<>();
    private final List<Button> rewardListButtons = new ArrayList<>();

    // ---- Tab/node creation and reparenting (stage 7) ----
    private CreationMode formCreationMode = CreationMode.NONE;
    private record CreationDraft(String id, String icon, BackgroundOption background, String customBackground, net.vg.codex.advancement.QuestTemplates.Type template) { }
    private CreationMode creationMode = CreationMode.NONE;
    // Node the "New Advancement" form will parent the created node under.
    private CodexAdvancementWidget pendingNodeParent;
    // Set by shift+right-click; a following shift+left-click on another node reparents it here.
    private CodexAdvancementWidget linkSource;
    private CycleButton<net.vg.codex.advancement.QuestTemplates.Type> questTemplateButton;
    private EditBox newIdBox;
    private EditBox newIconBox;
    private CycleButton<BackgroundOption> backgroundOptionButton;
    private EditBox newBackgroundBox;

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
        // The frame uses fixed corners/tiled edges. Editing can use the full height even when
        // the sidebar leaves a narrow canvas; do not shrink the form to the canvas aspect ratio.
        return showEditPanel() ? Math.max(MIN_WINDOW_HEIGHT, height - HEADER_HEIGHT - FOOTER_HEIGHT - SCREEN_MARGIN)
                : Math.round(ART_HEIGHT * windowScale());
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

    private int panelLeft() {
        return windowLeft() + windowWidth() + PANEL_GAP;
    }

    /**
     * The panel's scrollable viewport - the advancement window's height minus the pinned footer
     * (action buttons + status) reserved below it.
     */
    private int panelVisibleHeight() {
        return windowHeight() - PANEL_FOOTER_HEIGHT;
    }

    private int panelFooterTop() {
        return windowTop() + panelVisibleHeight() + 6;
    }

    /**
     * Registers a panel widget for click/keyboard/narration handling only (not automatic
     * rendering - see {@link #panelWidgets}) and records its unscrolled Y so
     * {@link #applyPanelScroll()} can reposition it later without a full re-init.
     */
    private <T extends AbstractWidget> T panel(T widget) {
        addWidget(widget);
        panelWidgets.add(widget);
        panelWidgetBaseY.put(widget, widget.getY());
        return widget;
    }

    private boolean isMouseOverPanel(double mouseX, double mouseY) {
        if (panelWidgets.isEmpty()) {
            return false;
        }
        int left = panelLeft();
        int top = windowTop();
        return mouseX >= left && mouseX < left + panelWidth() && mouseY >= top && mouseY < top + panelVisibleHeight();
    }

    private int maxPanelScroll() {
        return Math.max(0, panelContentBottom - windowTop() - panelVisibleHeight());
    }

    private void scrollPanel(double delta) {
        panelScrollOffset = Mth.clamp(panelScrollOffset + delta, 0, maxPanelScroll());
        applyPanelScroll();
    }

    /** Repositions every panel widget from its recorded base Y, hiding (and disabling clicks on)
     * whatever scrolls fully outside the visible viewport. */
    private void applyPanelScroll() {
        panelScrollOffset = Mth.clamp(panelScrollOffset, 0, maxPanelScroll());
        int top = windowTop();
        int visibleBottom = top + panelVisibleHeight();
        int offset = (int) Math.round(panelScrollOffset);
        for (AbstractWidget widget : panelWidgets) {
            int baseY = panelWidgetBaseY.get(widget);
            int newY = baseY - offset;
            widget.setY(newY);
            widget.visible = newY + widget.getHeight() > top && newY < visibleBottom;
        }
    }

    @Override
    protected void init() {
        pressedWidget = null; holdSent = false;
        EditorFormState previousForm = editMode && selectedEditWidget != null && selectedEditWidget.node().holder().id().equals(formAdvancementId)
                && titleBox != null && descriptionBox != null && requirementModeButton != null
                ? new EditorFormState(titleBox.getValue(), descriptionBox.getValue(), showToastCheckbox.selected(), announceChatCheckbox.selected(), hiddenCheckbox.selected(), claimableRewardsCheckbox.selected(), requirementModeButton.getValue()) : null;
        CreationDraft creationDraft = creationMode != CreationMode.NONE && creationMode == formCreationMode && newIdBox != null
                ? new CreationDraft(newIdBox.getValue(), newIconBox.getValue(), backgroundOptionButton == null ? BackgroundOption.STONE : backgroundOptionButton.getValue(), newBackgroundBox == null ? "" : newBackgroundBox.getValue(), questTemplateButton == null ? net.vg.codex.advancement.QuestTemplates.Type.OBTAIN : questTemplateButton.getValue()) : null;
        formCreationMode = creationMode;
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
        // Rewards are player actions, so keep both the searchable per-quest list and Claim All
        // in the title bar rather than competing with edit/zoom controls in the narrow footer.
        int availableClaims = ClientClaimableRewards.available().size();
        boolean compactHeaderActions = windowWidth < 300;
        int claimAllWidth = compactHeaderActions ? 42 : 64;
        int claimsWidth = compactHeaderActions ? 47 : 76;
        int partyWidth = compactHeaderActions ? 20 : 42;
        int claimsX = left + windowWidth - claimAllWidth - claimsWidth - partyWidth - 10;
        addRenderableWidget(Button.builder(Component.literal("UI"), b -> minecraft.setScreen(new CodexSettingsScreen(this)))
                .bounds(claimsX - 26, top + 2, 24, 14).build()).setTooltip(Tooltip.create(Component.literal("Codex scale and quest tracker settings")));
        addRenderableWidget(Button.builder(Component.literal(editMode ? (compactHeaderActions ? "T" : "Tools") : compactHeaderActions ? "P" : "Party"), b -> { if (editMode) openPackTools(); else openPartyPanel(); })
                .bounds(claimsX, top + 2, partyWidth, 14)
                .build()).setTooltip(Tooltip.create(Component.literal(editMode ? "Pack Creator Tools: check links, preview, duplicate and bulk edit" : "Party: invite players, accept an invitation, or leave")));
        claimsButton = addRenderableWidget(Button.builder(Component.literal(compactHeaderActions ? "R(" + availableClaims + ")" : "Rewards (" + availableClaims + ")"), b -> openClaimRewards())
                .bounds(claimsX + partyWidth + 2, top + 2, claimsWidth, 14)
                .build());
        claimAllButton = addRenderableWidget(Button.builder(Component.literal(compactHeaderActions ? "All" : "Claim All"), b ->
                        NetworkManager.sendToServer(new ClaimAllAdvancementRewardsPayload()))
                .bounds(left + windowWidth - claimAllWidth - 4, top + 2, claimAllWidth, 14)
                .build());
        claimsButton.setTooltip(Tooltip.create(Component.literal("Choose an earned quest reward to claim")));
        claimAllButton.setTooltip(Tooltip.create(Component.literal("Claim all earned quest rewards")));
        // The vanilla-width window is only 252px: a 120px Edit button plus the old fixed
        // zoom/Done group totalled more than that and overlapped by a few pixels. Keep the roomy
        // labels on ordinary windows, but use a compact, explicitly calculated footer below 300px.
        boolean compactFooter = windowWidth < 300;
        int doneWidth = compactFooter ? 64 : 90;
        int editWidth = compactFooter ? 82 : 120;
        int zoomGroupWidth = 20 + 4 + 20 + 6 + doneWidth;
        int footerY = top + windowHeight + 6;
        int footerRight = left + windowWidth;
        addRenderableWidget(Button.builder(Component.literal(compactFooter ? "Done" : "Done"), b -> onClose())
                .bounds(footerRight - doneWidth, footerY, doneWidth, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustZoom(-ZOOM_STEP))
                .bounds(footerRight - zoomGroupWidth, footerY, 20, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustZoom(ZOOM_STEP))
                .bounds(footerRight - zoomGroupWidth + 24, footerY, 20, 20)
                .build());

        if (canEdit()) {
            Component editLabel = compactFooter
                    ? Component.literal(editMode ? "Edit: On" : "Edit: Off")
                    : editModeLabel();
            editModeButton = addRenderableWidget(Button.builder(editLabel, b -> toggleEditMode())
                    .bounds(left, footerY, editWidth, 20)
                    .build());
        }

        criteriaListButtons.clear();
        rewardListButtons.clear();
        panelWidgets.clear();
        panelWidgetBaseY.clear();
        if (showEditPanel() && creationMode != CreationMode.NONE) {
            initCreationPanel(left, windowWidth, top, creationDraft);
        } else if (showEditPanel()) {
            int panelWidth = panelWidth() - PANEL_SCROLLBAR_GUTTER;
            int panelLeft = left + windowWidth + PANEL_GAP;
            int y = top;

            // Loaded before the criteria/reward rows below are built, so their row counts reflect
            // the actual entry counts instead of a fixed row count that either wastes space (mostly
            // empty rows for a 2-criterion advancement) or hard-caps how many can ever be shown.
            // Only reloaded from disk on an actual selection change - re-init triggered by editing
            // criteriaEntries/rewardEntries themselves (add/remove/apply) must leave the in-memory
            // working copy alone.
            if (selectedEditWidget != criteriaEntriesSelection) {
                criteriaEntriesSelection = selectedEditWidget;
                // A stale selection (by name/index) must not carry over to a different
                // advancement's criteria/reward lists just because it happens to still resolve.
                criteriaEntries.clear();
                if (selectedEditWidget != null) {
                    for (Map.Entry<String, JsonElement> e : criteriaFor(selectedEditWidget).entrySet()) {
                        criteriaEntries.put(e.getKey(), e.getValue().getAsJsonObject());
                    }
                }
                rewardEntries.clear();
                rewardEntries.addAll(loadRewardEntries(currentRewardsJson()));
            }

            titleBox = panel(new EditBox(this.font, panelLeft, y, panelWidth, 16, Component.literal("Title")));
            titleBox.setMaxLength(32_767);
            y += 20;

            descriptionBox = panel(MultiLineEditBox.builder()
                    .setX(panelLeft)
                    .setY(y)
                    .setPlaceholder(Component.literal("Description"))
                    .build(this.font, panelWidth, DESCRIPTION_HEIGHT, Component.literal("Description")));
            y += DESCRIPTION_HEIGHT + 6;

            // Checkbox has no setter to reflect a newly-selected node's state onto an
            // already-built widget, so its initial "selected" value is read here at construction
            // time - selectEditWidget() does a full re-init specifically so this stays correct.
            DisplayInfo currentDisplay = selectedEditWidget != null ? selectedEditWidget.display() : null;
            boolean compactChecks = panelWidth < 220;
            int checkWidth = compactChecks ? (panelWidth - 6) / 2 : panelWidth / 3;
            showToastCheckbox = panel(Checkbox.builder(Component.literal("Toast"), this.font)
                    .pos(panelLeft, y)
                    .maxWidth(checkWidth)
                    .selected(previousForm != null ? previousForm.toast() : currentDisplay == null || currentDisplay.shouldShowToast())
                    .build());
            announceChatCheckbox = panel(Checkbox.builder(Component.literal("Chat"), this.font)
                    .pos(panelLeft + checkWidth + (compactChecks ? 6 : 0), y)
                    .maxWidth(checkWidth)
                    .selected(previousForm != null ? previousForm.chat() : currentDisplay == null || currentDisplay.shouldAnnounceChat())
                    .build());
            if (compactChecks) {
                y += 18;
            }
            hiddenCheckbox = panel(Checkbox.builder(Component.literal("Hidden"), this.font)
                    .pos(compactChecks ? panelLeft : panelLeft + checkWidth * 2, y)
                    .maxWidth(checkWidth)
                    .selected(previousForm != null ? previousForm.hidden() : currentDisplay != null && currentDisplay.isHidden())
                    .build());
            y += 20;

            // Both preview live (not gated behind Apply Edit) since there's otherwise no way to see
            // what you're actually choosing before committing - icon on the left opens the item
            // picker, frame on the right (the real frame+icon art, matching the tree itself,
            // instead of naming the enum constant) cycles through Task/Goal/Challenge on click.
            panel(new IconPreviewButton(panelLeft, y, FRAME_ICON_SIZE,
                    () -> selectedEditWidget != null ? selectedEditWidget.display().getIcon().create() : ItemStack.EMPTY,
                    this::previewIconChange));
            panel(new FrameStyleButton(panelLeft + panelWidth - FRAME_ICON_SIZE, y, FRAME_ICON_SIZE));
            y += FRAME_ICON_SIZE + 4;

            layoutModeButton = panel(Button.builder(layoutModeLabel(),
                            b -> toggleLayoutMode())
                    .bounds(panelLeft, y, panelWidth, 16)
                    .build());
            y += 20;

            // Rewards - a dynamic list (same pattern as the criteria list below) rather than 4
            // always-visible fields, since most advancements use at most one or two reward types.
            // Entries are hand-built JSON, not decoded through AdvancementRewards.CODEC client-side,
            // matching this editor's "never decode registry-dependent codecs client-side" approach.
            panel(new StringWidget(panelLeft, y, panelWidth, 10, Component.literal("Rewards"), this.font));
            y += 12;

            claimableRewardsCheckbox = panel(Checkbox.builder(Component.literal("Claim rewards manually"), this.font)
                    .pos(panelLeft, y)
                    .maxWidth(panelWidth)
                    .selected(previousForm != null ? previousForm.claimable() : isCurrentClaimable())
                    .build());
            y += 18;

            int rewardRowCount = Math.min(rewardEntries.size(), MAX_REWARD_ROWS);
            for (int i = 0; i < rewardRowCount; i++) {
                int rowIndex = i;
                int rowY = y + i * REWARD_ROW_HEIGHT;
                panel(new EntryIconButton(panelLeft, rowY, () -> rewardEntryIcon(rewardEntries.get(rowIndex)),
                        () -> openRewardDialog(rowIndex)));
                Button row = panel(Button.builder(Component.empty(), b -> openRewardDialog(rowIndex))
                        .bounds(panelLeft + 18, rowY, panelWidth - 18, REWARD_ROW_HEIGHT - 2)
                        .build());
                rewardListButtons.add(row);
            }
            y += rewardRowCount * REWARD_ROW_HEIGHT + 4;

            panel(Button.builder(Component.literal("+ Add Reward"), b -> openRewardDialog(null))
                    .bounds(panelLeft, y, panelWidth, 16)
                    .build());
            y += 20;

            panel(new StringWidget(panelLeft, y, panelWidth, 10, Component.literal("Criteria"), this.font));
            y += 14;

            int criteriaRowCount = Math.min(criteriaEntries.size(), MAX_CRITERIA_ROWS);
            for (int i = 0; i < criteriaRowCount; i++) {
                int rowIndex = i;
                int rowY = y + i * CRITERIA_ROW_HEIGHT;
                panel(new EntryIconButton(panelLeft, rowY, () -> criterionEntryIcon(criteriaEntries.get(currentCriteriaNames.get(rowIndex))),
                        () -> openCriterionDialog(currentCriteriaNames.get(rowIndex))));
                Button row = panel(Button.builder(Component.empty(), b -> openCriterionDialog(currentCriteriaNames.get(rowIndex)))
                        .bounds(panelLeft + 18, rowY, panelWidth - 18, CRITERIA_ROW_HEIGHT - 2)
                        .build());
                criteriaListButtons.add(row);
            }
            y += criteriaRowCount * CRITERIA_ROW_HEIGHT + 4;

            panel(Button.builder(Component.literal("+ Add Criterion"), b -> openCriterionDialog(null))
                    .bounds(panelLeft, y, panelWidth, 16)
                    .build());
            y += 20;

            requirementModeButton = panel(CycleButton.builder((Requirements m) -> Component.literal(m.label), previousForm != null ? previousForm.requirements() : currentRequirements())
                    .withValues(Requirements.values())
                    .create(panelLeft, y, panelWidth, 20, Component.literal("Requirements")));
            y += 24;
            panel(Button.builder(Component.literal("Edit Requirement Groups"), b -> openRequirementsEditor()).bounds(panelLeft, y, panelWidth, 20).build());
            y += 24;
            int halfWidth = (panelWidth - 10) / 2;
            panel(Button.builder(Component.literal("Advanced JSON"), b -> openDocumentEditor()).bounds(panelLeft, y, halfWidth, 20).build());
            panel(Button.builder(Component.literal("Undo Apply"), b -> undoApply()).bounds(panelLeft + halfWidth + 10, y, halfWidth, 20).build());
            y += 24;
            panel(Button.builder(Component.literal("Treasure Lists"), b -> {
                if (applyEdit()) minecraft.setScreen(new TreasureListScreen(this));
            }).bounds(panelLeft, y, panelWidth, 20).build());
            y += 24;
            panel(Button.builder(Component.literal("Quest Story & Rules"), b -> openQuestProperties()).bounds(panelLeft, y, panelWidth, 20).build());
            y += 24;
            panel(Button.builder(Component.literal("Recipe Editor"), b -> minecraft.setScreen(new RecipeEditorScreen(this))).bounds(panelLeft, y, panelWidth, 20).build());
            y += 24;
            panel(Button.builder(Component.literal("Pack Creator Tools"), b -> openPackTools()).bounds(panelLeft, y, panelWidth, 20).build());
            y += 24;
            panelContentBottom = y;

            if (selectedEditWidget != null) {
                titleBox.setValue(previousForm != null ? previousForm.title() : selectedEditWidget.display().getTitle().getString());
                descriptionBox.setValue(previousForm != null ? previousForm.description() : selectedEditWidget.display().getDescription().getString());
                formAdvancementId = selectedEditWidget.node().holder().id();
            }
            refreshCriteriaList();
            refreshRewardList();
            applyPanelScroll();

            // Pinned footer - outside the scrollable area (not wrapped via panel()) so these stay
            // reachable without scrolling all the way down.
            int footerTop = panelFooterTop();
            int fullPanelWidth = panelWidth();
            int thirdWidth = (fullPanelWidth - 20) / 3;
            applyEditButton = addRenderableWidget(Button.builder(Component.literal("Apply"), b -> applyEdit())
                    .bounds(panelLeft, footerTop, thirdWidth, 20)
                    .build());
            saveEditsButton = addRenderableWidget(Button.builder(Component.literal("Save"), b -> saveEdits())
                    .bounds(panelLeft + thirdWidth + 10, footerTop, thirdWidth, 20)
                    .build());
            deleteButton = addRenderableWidget(Button.builder(Component.literal("Delete"), b -> deleteSelected())
                    .bounds(panelLeft + (thirdWidth + 10) * 2, footerTop, thirdWidth, 20)
                    .build());
            statusWidget = addRenderableWidget(new MultiLineTextWidget(panelLeft, footerTop + 24, Component.empty(), this.font).setMaxWidth(fullPanelWidth));
        }

        if (partyPanelOpen) {
            initPartyPanel();
        }
    }

    /** Replaces the normal edit form with a small "new tab"/"new advancement" form. */
    private void initCreationPanel(int left, int windowWidth, int top, CreationDraft draft) {
        int panelWidth = panelWidth() - PANEL_SCROLLBAR_GUTTER;
        int panelLeft = left + windowWidth + PANEL_GAP;
        int y = top;

        boolean isTab = creationMode == CreationMode.NEW_TAB;
        panel(new StringWidget(panelLeft, y, panelWidth, 12,
                Component.literal(isTab ? "New Tab" : "New Advancement"), this.font));
        y += 16;

        if (!isTab && pendingNodeParent != null) {
            panel(new StringWidget(panelLeft, y, panelWidth, 10,
                    Component.literal("Parent: " + pendingNodeParent.node().holder().id()), this.font));
            y += 14;
        }

        newIdBox = panel(new EditBox(this.font, panelLeft, y, panelWidth, 16, Component.literal("Id")));
        newIdBox.setMaxLength(256);
        if (draft != null) newIdBox.setValue(draft.id());
        newIdBox.setHint(Component.literal("e.g. my_advancement"));
        y += 20;

        int iconButtonSize = 20;
        newIconBox = panel(new EditBox(this.font, panelLeft + iconButtonSize + 4, y, panelWidth - iconButtonSize - 4, 16, Component.literal("Icon item id")));
        newIconBox.setMaxLength(256);
        newIconBox.setValue(draft != null ? draft.icon() : "minecraft:stone");
        panel(new IconPreviewButton(panelLeft, y - 2, iconButtonSize,
                () -> resolveIconStack(newIconBox.getValue()),
                id -> newIconBox.setValue(id.toString())));
        y += 20;

        if (isTab) {
            backgroundOptionButton = panel(CycleButton.builder((BackgroundOption o) -> Component.literal(o.label), draft != null ? draft.background() : BackgroundOption.STONE)
                    .withValues(BackgroundOption.values())
                    .create(panelLeft, y, panelWidth, 20, Component.literal("Background"), (button, value) ->
                            newBackgroundBox.active = value == BackgroundOption.CUSTOM));
            y += 24;

            newBackgroundBox = panel(new EditBox(this.font, panelLeft, y, panelWidth, 16, Component.literal("Custom background id")));
            newBackgroundBox.setMaxLength(256);
            newBackgroundBox.active = backgroundOptionButton.getValue() == BackgroundOption.CUSTOM;
            if (draft != null) newBackgroundBox.setValue(draft.customBackground());
            y += 20;
        }
        questTemplateButton = panel(CycleButton.builder((net.vg.codex.advancement.QuestTemplates.Type type) -> Component.literal(type.label), net.vg.codex.advancement.QuestTemplates.Type.OBTAIN)
                .withValues(net.vg.codex.advancement.QuestTemplates.Type.values()).create(panelLeft, y, panelWidth, 20, Component.literal("Template")));
        y += 28;
        panelContentBottom = y;
        applyPanelScroll();

        // Pinned footer - outside the scrollable area, same as the normal edit panel's Apply/Save/Delete.
        int footerTop = panelFooterTop();
        int fullPanelWidth = panelWidth();
        int halfWidth = (fullPanelWidth - 10) / 2;
        addRenderableWidget(Button.builder(Component.literal("Create"), b -> {
                    if (creationMode == CreationMode.NEW_TAB) {
                        createTab();
                    } else {
                        createNode();
                    }
                })
                .bounds(panelLeft, footerTop, halfWidth, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> cancelCreation())
                .bounds(panelLeft + halfWidth + 10, footerTop, halfWidth, 20)
                .build());
        statusWidget = addRenderableWidget(new MultiLineTextWidget(panelLeft, footerTop + 24, Component.empty(), this.font).setMaxWidth(fullPanelWidth));
    }

    // ---- Item picker (icon selection) ----

    private void openIconPicker(Consumer<Identifier> callback) {
        this.minecraft.setScreen(new ItemPickerScreen(this, callback));
    }

    private int iconPickerLeft() { return (this.width - ICON_PICKER_WIDTH) / 2; }
    private int iconPickerTop() { return (this.height - ICON_PICKER_HEIGHT) / 2; }

    private void tryBlur(GuiGraphicsExtractor extractor) {
        try {
            extractor.blurBeforeThisStratum();
        } catch (IllegalStateException ignored) {
            // Already blurred this frame - fine, just skip the effect this time.
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

    private boolean editorSessionClosed;
    public boolean isEditorSessionClosed() {
        return editorSessionClosed;
    }

    private void closeEditorSession() {
        editorSessionClosed = true;
        saveQueued = false;
        ClientEditorRequests.cancelSnapshots(this);
    }

    private void toggleEditMode() {
        if (editMode) {
            ClientEditorRequests.cancelSnapshots(this);
            editMode = false;
            selectedEditWidget = null;
            this.init(this.width, this.height);
        } else {
            ClientEditorRequests.requestSnapshot(this);
            // editMode flips true once the response arrives - see enterEditMode.
        }
    }

    /**
     * Called by AdvancementEditorClient when the full snapshot arrives while this screen is open.
     * Never decodes a full {@link Advancement}/{@code Criterion} client-side - only the
     * registry-safe {@link DisplayInfo} sub-object (item registry, always fully networked) is
     * decoded, purely to build the tab/tree structure; criteria stay as raw JSON in
     * {@link #rawFullJson} and only ever get decoded server-side on save.
     */
    public void enterEditMode(List<RawAdvancement> snapshot, List<OpenAdvancementEditorPayload.ProgressEntry> progressSnapshot) {
        Identifier editingBeforeRefresh = editMode && selectedEditWidget != null ? selectedEditWidget.node().holder().id() : null;
        rawFullJson.clear();
        for (RawAdvancement raw : snapshot) {
            rawFullJson.put(raw.id(), JsonParser.parseString(raw.json()).getAsJsonObject());
        }
        editProgressSnapshot.clear();
        for (OpenAdvancementEditorPayload.ProgressEntry entry : progressSnapshot) {
            // AdvancementProgress's own network format never carries `requirements` (see
            // OpenAdvancementEditorPayload's doc comment) - without this, getPercent()/
            // getProgressText() both silently treat every advancement as having 0 requirements,
            // which for getProgressText() specifically means it always returns null.
            entry.progress().update(entry.requirements());
            editProgressSnapshot.put(entry.advancementId(), entry.progress());
        }
        Identifier viewedTab = selectedViewTab == null ? null : selectedViewTab.getRootNode().holder().id();
        editMode = true;
        rebuildEditTree();
        if (editingBeforeRefresh != null && effectiveJson().containsKey(editingBeforeRefresh)) selectEditNodeById(editingBeforeRefresh);
        else if (viewedTab != null) selectEditNodeById(viewedTab);
        if (selectedEditWidget == null && selectedEditTab != null) selectedEditWidget = selectedEditTab.getRoot();
        revealSelectedTab();
        this.init(this.width, this.height);
    }

    /** {@link #rawFullJson} merged with unsaved {@link #dirty} edits/creations, dirty taking precedence. */
    private Map<Identifier, JsonObject> effectiveJson() {
        Map<Identifier, JsonObject> merged = new LinkedHashMap<>(rawFullJson);
        merged.putAll(dirty);
        return merged;
    }

    /**
     * (Re)builds {@link #editTabs} from {@link #effectiveJson()} - the same construction
     * {@link #enterEditMode} originally did against the raw server snapshot alone, now reusable
     * after a client-side structural change (tab/node creation, reparenting) that doesn't warrant a
     * full server round trip. Never decodes a full {@link Advancement}/{@code Criterion}
     * client-side - only the registry-safe {@link DisplayInfo} sub-object (item registry, always
     * fully networked) is decoded, purely to build the tab/tree structure.
     */
    private void rebuildEditTree() {
        Identifier previousTabRootId = selectedEditTab != null ? selectedEditTab.getRootNode().holder().id() : null;
        Identifier previousWidgetId = selectedEditWidget != null ? selectedEditWidget.node().holder().id() : null;

        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries());
        List<AdvancementHolder> holders = new ArrayList<>();
        for (Map.Entry<Identifier, JsonObject> e : effectiveJson().entrySet()) {
            JsonObject json = e.getValue().deepCopy();
            json.entrySet().removeIf(entry -> entry.getValue().isJsonNull());
            Optional<Identifier> parent = json.has("parent")
                    ? Optional.of(Identifier.parse(json.get("parent").getAsString()))
                    : Optional.empty();
            Optional<DisplayInfo> display = json.has("display")
                    ? Optional.of(DisplayInfo.CODEC.parse(ops, json.get("display")).getOrThrow())
                    : Optional.empty();

            Advancement dummy = new Advancement(parent, display, AdvancementRewards.EMPTY,
                    Map.of(), AdvancementRequirements.EMPTY, false);
            holders.add(new AdvancementHolder(e.getKey(), dummy));
        }

        AdvancementTree tree = new AdvancementTree();
        tree.addAll(holders);

        editTabs.clear();
        selectedEditTab = null;
        selectedEditWidget = null;
        for (AdvancementNode root : tree.roots()) {
            if (root.advancement().display().isEmpty()) {
                // Matches ServerAdvancementManager's own guard - TreeNodePosition.run refuses to
                // position an invisible root, and CodexAdvancementTab.create skips it anyway.
                continue;
            }
            TreeNodePosition.run(root);
            CodexAdvancementTab tab = CodexAdvancementTab.create(root);
            if (tab == null) {
                continue;
            }
            editTabs.put(root.holder().id(), tab);
            addDescendants(tab, root);
            tab.applyProgress(this::lookupEditProgress);
            tab.applyRewardsOverride(this::lookupEditRewards);
            tab.applyRequiredItemsOverride(this::lookupEditRequiredItems);
            tab.applyRequiredHintsOverride(this::lookupEditRequiredHints);
            tab.applyLayout(layoutPositions());
            if (root.holder().id().equals(previousTabRootId)) {
                selectedEditTab = tab;
            }
        }
        if (selectedEditTab == null && !editTabs.isEmpty()) {
            selectedEditTab = editTabs.values().iterator().next();
        }
        customLayoutMode = selectedEditTab != null && tabHasCustomLayout(selectedEditTab);
        if (previousWidgetId != null && selectedEditTab != null) {
            selectedEditWidget = selectedEditTab.findById(previousWidgetId);
        }
    }

    private void addDescendants(CodexAdvancementTab tab, AdvancementNode node) {
        for (AdvancementNode child : node.children()) {
            tab.addAdvancement(child);
            addDescendants(tab, child);
        }
    }

    private void openClaimRewards() {
        this.minecraft.setScreen(new IdentifierSelectionScreen(this, "Claim a Reward", ClientClaimableRewards.available(), id -> {
            for (CodexAdvancementTab tab : viewTabs.values()) {
                CodexAdvancementWidget widget = tab.findById(id);
                if (widget != null) return widget.display().getTitle();
            }
            return Component.literal(id.toString());
        }, id -> NetworkManager.sendToServer(new ClaimAdvancementRewardPayload(id, -1))));
    }

    private void openPartyPanel() {
        partyPanelOpen = true;
        this.init(this.width, this.height);
    }

    private void closePartyPanel() {
        partyPanelOpen = false;
        this.init(this.width, this.height);
    }

    /** A lightweight player-facing wrapper around the party commands. Online names come from the
     * active connection, so this remains correct for dedicated servers and LAN worlds alike. */
    private void initPartyPanel() {
        // The overlay owns input. Buttons beneath it must not intercept a party action.
        for (var child : this.children()) {
            if (child instanceof AbstractWidget widget) widget.active = false;
        }
        int left = iconPickerLeft();
        int top = iconPickerTop();
        partyAcceptButton = addWidget(Button.builder(Component.literal("Accept pending invite"), b -> sendPartyCommand("codex party accept"))
                .bounds(left + 10, top + 34, ICON_PICKER_WIDTH - 20, 18).build());
        partyLeaveButton = addWidget(Button.builder(Component.literal("Leave party"), b -> sendPartyCommand("codex party leave"))
                .bounds(left + 10, top + 56, ICON_PICKER_WIDTH - 20, 18).build());
        partyInviteButtons.clear();
        if (this.minecraft.getConnection() != null && this.minecraft.player != null) {
            int index = 0;
            for (var info : this.minecraft.getConnection().getListedOnlinePlayers()) {
                String name = info.getProfile().name();
                if (name.equals(this.minecraft.player.getGameProfile().name()) || index >= 7) continue;
                Button button = addWidget(Button.builder(Component.literal("Invite " + name), b -> sendPartyCommand("codex party invite " + name))
                        .bounds(left + 10, top + 82 + index * 18, ICON_PICKER_WIDTH - 20, 16).build());
                partyInviteButtons.add(button);
                index++;
            }
        }
        partyCloseButton = addWidget(Button.builder(Component.literal("Close"), b -> closePartyPanel())
                .bounds(left + 10, top + ICON_PICKER_HEIGHT - 26, ICON_PICKER_WIDTH - 20, 20).build());
    }

    private void sendPartyCommand(String command) {
        if (this.minecraft.getConnection() != null) this.minecraft.getConnection().sendCommand(command);
        closePartyPanel();
    }

    private void extractPartyPanel(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.nextStratum();
        tryBlur(extractor);
        extractor.fill(0, 0, this.width, this.height, 0xC0000000);
        int left = iconPickerLeft(); int top = iconPickerTop();
        extractor.fill(left, top, left + ICON_PICKER_WIDTH, top + ICON_PICKER_HEIGHT, 0xFF2B2B2B);
        extractor.horizontalLine(left, left + ICON_PICKER_WIDTH - 1, top, 0xFF000000);
        extractor.horizontalLine(left, left + ICON_PICKER_WIDTH - 1, top + ICON_PICKER_HEIGHT - 1, 0xFF000000);
        extractor.verticalLine(left, top, top + ICON_PICKER_HEIGHT - 1, 0xFF000000);
        extractor.verticalLine(left + ICON_PICKER_WIDTH - 1, top, top + ICON_PICKER_HEIGHT - 1, 0xFF000000);
        extractor.text(this.font, "Party", left + 10, top + ICON_TITLE_Y, 0xFFFFFFFF, false);
        extractor.text(this.font, "Invite an online player, or accept an invite.", left + 10, top + 20, 0xFFAAAAAA, false);
        partyAcceptButton.extractRenderState(extractor, mouseX, mouseY, partialTick);
        partyLeaveButton.extractRenderState(extractor, mouseX, mouseY, partialTick);
        for (Button button : partyInviteButtons) button.extractRenderState(extractor, mouseX, mouseY, partialTick);
        partyCloseButton.extractRenderState(extractor, mouseX, mouseY, partialTick);
    }

    private void openRewardDialog(Integer index) {
        JsonObject original = index == null ? null : rewardEntries.get(index);
        this.minecraft.setScreen(new RewardEditorScreen(this, original, entry -> {
            RewardType type = RewardType.valueOf(entry.get("type").getAsString());
            if (rewardTypeConflict(type, index)) throw new IllegalArgumentException("Only one " + type.label + " reward is allowed");
            if (index == null) rewardEntries.add(entry); else rewardEntries.set(index, entry);
        }, index == null ? null : () -> rewardEntries.remove((int) index)));
    }

    private void openCriterionDialog(String originalName) {
        String name = originalName;
        if (name == null) {
            name = "criterion";
            for (int suffix = 2; criteriaEntries.containsKey(name); suffix++) name = "criterion_" + suffix;
        }
        this.minecraft.setScreen(new CriterionEditorScreen(this, name, criteriaEntries.get(name), effectiveJson(), (key, entry) -> {
            if (criteriaEntries.containsKey(key) && !key.equals(originalName)) throw new IllegalArgumentException("A criterion already has that key");
            if (originalName != null && !originalName.equals(key)) {
                renameRequirementReference(originalName, key);
                criteriaEntries.remove(originalName);
            }
            criteriaEntries.put(key, entry);
        }, originalName == null ? null : () -> {
            if (criteriaEntries.size() == 1) throw new IllegalArgumentException("An advancement needs at least one criterion");
            criteriaEntries.remove(originalName);
        }));
    }

    private void renameRequirementReference(String oldName, String newName) {
        if (selectedEditWidget == null) return;
        Identifier id = selectedEditWidget.node().holder().id(); JsonObject base = baseJsonFor(id);
        if (base == null || !base.has("requirements")) return;
        JsonObject updated = base.deepCopy(); updated.add("requirements", AdvancementDocument.renameReference(base.getAsJsonArray("requirements"), oldName, newName));
        rememberUndo(id, base, updated); dirty.put(id, updated);
    }

    private Map<Identifier, int[]> layoutPositions() {
        Map<Identifier, int[]> result = new HashMap<>();
        for (Map.Entry<Identifier, JsonObject> entry : effectiveJson().entrySet()) {
            JsonObject json = entry.getValue();
            if (!json.has("codex_layout") || !json.get("codex_layout").isJsonObject()) continue;
            try {
                JsonObject point = json.getAsJsonObject("codex_layout");
                result.put(entry.getKey(), new int[]{point.get("x").getAsInt(), point.get("y").getAsInt()});
            } catch (RuntimeException ignored) {
                // Invalid hand-edited metadata falls back to vanilla's safe automatic layout.
            }
        }
        return result;
    }

    private boolean tabHasCustomLayout(CodexAdvancementTab tab) {
        for (Map.Entry<Identifier, JsonObject> entry : effectiveJson().entrySet()) {
            if (tab.contains(entry.getKey()) && entry.getValue().has("codex_layout")
                    && entry.getValue().get("codex_layout").isJsonObject()) {
                return true;
            }
        }
        return false;
    }

    private Component layoutModeLabel() {
        return Component.literal(customLayoutMode ? "Layout: Custom" : "Layout: Auto");
    }

    private void toggleLayoutMode() {
        if (selectedEditTab == null) return;
        if (!customLayoutMode) {
            customLayoutMode = true;
            pendingLayoutResetTab = null;
            this.init(this.width, this.height);
            setStatus("Custom layout: select and drag a node. Save to publish positions to every player.");
            return;
        }
        Identifier root = selectedEditTab.getRootNode().holder().id();
        if (!root.equals(pendingLayoutResetTab)) {
            pendingLayoutResetTab = root;
            setStatus("Switch this tab to automatic layout? Click Layout: Custom again to apply.");
            return;
        }
        for (Identifier id : effectiveJson().keySet()) {
            if (!selectedEditTab.contains(id)) continue;
            JsonObject base = baseJsonFor(id);
            if (base == null) continue;
            JsonObject updated = base.deepCopy();
            // Explicit null is the wire instruction to remove the sidecar entry server-side.
            updated.add("codex_layout", com.google.gson.JsonNull.INSTANCE);
            dirty.put(id, updated);
        }
        pendingLayoutResetTab = null;
        customLayoutMode = false;
        rebuildEditTree();
        this.init(this.width, this.height);
        setStatus("Automatic layout applied locally. Save to publish the reset.");
    }

    /** Edit mode's primary progress source - see {@link #editProgressSnapshot}'s own comment for
     * why this, not {@link #lookupViewProgress} (kept only as a defensive fallback for an
     * advancement created this session, too new to have a snapshot entry yet), is what actually
     * fixes progress being blank for a tab the player has never discovered in survival. */
    private AdvancementProgress lookupEditProgress(Identifier id) {
        AdvancementProgress snapshot = editProgressSnapshot.get(id);
        return snapshot != null ? snapshot : lookupViewProgress(id);
    }

    /** view mode's tabs are kept live-updated by {@link ClientAdvancements} regardless of which
     * mode is currently showing - cross-referenced here purely for display, see
     * {@link CodexAdvancementTab#applyProgress}. */
    private AdvancementProgress lookupViewProgress(Identifier id) {
        for (CodexAdvancementTab tab : viewTabs.values()) {
            CodexAdvancementWidget widget = tab.findById(id);
            if (widget != null) {
                return widget.progress();
            }
        }
        return null;
    }

    /** The current "rewards" object for {@code id} - {@link #dirty} if it's been Apply Edit'd this
     * session, else the last raw snapshot - or an empty object for a rewardless/unknown node. Feeds
     * {@link CodexAdvancementWidget#applyRewardsOverride}, the only way edit-mode's hover can show
     * rewards at all (its own Advancement object never carries real reward data - see that
     * method's doc comment). */
    private JsonObject lookupEditRewards(Identifier id) {
        JsonObject json = effectiveJson().get(id);
        if (json == null || !json.has("rewards") || !json.get("rewards").isJsonObject()) {
            return new JsonObject();
        }
        return json.getAsJsonObject("rewards");
    }

    /** Edit-mode analog of the server's own required-item broadcast (see
     * {@code AdvancementPackSync#buildRequiredItemsPayload}) - reads the currently edited criteria
     * JSON directly rather than waiting for a save+reload round trip, via the same shared
     * extraction {@code AdvancementPackIO#extractCriterionItemId} the server uses. */
    private List<Identifier> lookupEditRequiredItems(Identifier id) {
        JsonObject json = effectiveJson().get(id);
        if (json == null || !json.has("criteria") || !json.get("criteria").isJsonObject()) {
            return List.of();
        }
        List<Identifier> items = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("criteria").entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            AdvancementPackIO.extractCriterionItemId(entry.getValue().getAsJsonObject()).ifPresent(items::add);
        }
        return items;
    }

    private List<ClientAdvancementRequiredItems.Hint> lookupEditRequiredHints(Identifier id) {
        JsonObject json = effectiveJson().get(id);
        if (json == null || !json.has("criteria") || !json.get("criteria").isJsonObject()) {
            return List.of();
        }
        List<ClientAdvancementRequiredItems.Hint> hints = new ArrayList<>();
        for (JsonElement element : json.getAsJsonObject("criteria").asMap().values()) {
            if (!element.isJsonObject()) continue;
            JsonObject criterion = net.vg.codex.advancement.CriterionFormats.authoringView(element.getAsJsonObject());
            if (!criterion.has("trigger") || !"minecraft:recipe_unlocked".equals(criterion.get("trigger").getAsString())
                    || !criterion.has("conditions") || !criterion.get("conditions").isJsonObject()) continue;
            JsonObject conditions = criterion.getAsJsonObject("conditions");
            if (conditions.has("recipe")) {
                hints.add(new ClientAdvancementRequiredItems.Hint(Identifier.withDefaultNamespace("knowledge_book"),
                        Component.literal("Unlock recipe: " + conditions.get("recipe").getAsString())));
            }
        }
        return hints;
    }

    private void selectEditNodeById(Identifier id) {
        panelScrollOffset = 0;
        for (CodexAdvancementTab tab : editTabs.values()) {
            CodexAdvancementWidget widget = tab.findById(id);
            if (widget != null) {
                selectedEditTab = tab;
                selectedEditWidget = widget;
                return;
            }
        }
    }

    private Map<Identifier, CodexAdvancementTab> activeTabs() {
        return editMode ? editTabs : viewTabs;
    }

    private void revealSelectedTab() {
        CodexAdvancementTab selected = activeSelectedTab();
        if (selected == null) return;
        List<CodexAdvancementTab> tabs = new ArrayList<>(activeTabs().values());
        int selectedIndex = tabs.indexOf(selected);
        int capacity = Math.max(1, windowWidth() / CodexAdvancementTab.TAB_SLOT);
        if (tabs.size() + (editMode ? 1 : 0) > capacity) {
            int visible = Math.max(1, capacity - 2);
            if (selectedIndex < tabPageStart || selectedIndex >= tabPageStart + visible) tabPageStart = Math.max(0, selectedIndex - visible / 2);
        }
    }

    private CodexAdvancementTab activeSelectedTab() {
        return editMode ? selectedEditTab : selectedViewTab;
    }

    /** One rendered position in the top tab row for the current frame - either a real tab, the
     * edit-mode "+" new-tab slot, or (once paging kicks in) a prev/next arrow. */
    private record TabRowSlot(CodexAdvancementTab tab, boolean isAdd, boolean isPrev, boolean isNext, boolean enabled) {
        static TabRowSlot forTab(CodexAdvancementTab tab) {
            return new TabRowSlot(tab, false, false, false, true);
        }

        static TabRowSlot addSlot() {
            return new TabRowSlot(null, true, false, false, true);
        }

        static TabRowSlot arrow(boolean forward, boolean enabled) {
            return new TabRowSlot(null, false, !forward, forward, enabled);
        }
    }

    /**
     * Builds exactly what the top tab row should show this frame: every tab if they all fit the
     * window's current width, or a paged window of them (plus {@code <}/{@code >} arrows at the
     * ends) once they don't - unbounded tab count, since the window can only ever hold so many
     * tabs side-by-side no matter how they're arranged. Replaces an earlier vanilla-style 4-sided
     * ABOVE/BELOW/LEFT/RIGHT layout that looked fine with a handful of tabs but broke down badly at
     * this screen's much larger, resizable window: tabs rendered inside the tree content instead of
     * outside it, ran off the left edge, and overlapped the edit panel/footer buttons. Also clamps
     * {@link #tabPageStart} against the current tab count as a side effect, so switching modes or
     * losing tabs (e.g. after a Save/rebuild) can never leave it pointing past the end.
     */
    private List<TabRowSlot> buildTabRow(int windowWidth) {
        List<CodexAdvancementTab> tabs = new ArrayList<>(activeTabs().values());
        boolean showAddSlot = editMode;
        int totalSlots = tabs.size() + (showAddSlot ? 1 : 0);
        int capacity = Math.max(1, windowWidth / CodexAdvancementTab.TAB_SLOT);

        List<TabRowSlot> row = new ArrayList<>();
        if (totalSlots <= capacity) {
            tabPageStart = 0;
            for (CodexAdvancementTab tab : tabs) {
                row.add(TabRowSlot.forTab(tab));
            }
            if (showAddSlot) {
                row.add(TabRowSlot.addSlot());
            }
            return row;
        }

        int visibleSlots = Math.max(1, capacity - 2);
        int maxStart = Math.max(0, totalSlots - visibleSlots);
        tabPageStart = Mth.clamp(tabPageStart, 0, maxStart);

        row.add(TabRowSlot.arrow(false, tabPageStart > 0));
        for (int i = 0; i < visibleSlots; i++) {
            int globalIndex = tabPageStart + i;
            if (globalIndex >= totalSlots) {
                break;
            }
            row.add(globalIndex < tabs.size() ? TabRowSlot.forTab(tabs.get(globalIndex)) : TabRowSlot.addSlot());
        }
        row.add(TabRowSlot.arrow(true, tabPageStart + visibleSlots < totalSlots));
        return row;
    }

    @Override
    public void onClose() {
        if (savePending) {
            setStatus("Save in progress - wait for the server reload before closing.");
            return;
        }
        if (editMode && selectedEditWidget != null && titleBox != null && !applyEdit()) return;
        // dirty only lives in this screen instance's memory - closing without saving discards it
        // for good (a new CodexAdvancementsScreen starts with an empty dirty map), so confirm
        // first rather than silently losing whatever's pending.
        if (!dirty.isEmpty()) {
            this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
                        if (confirmed) {
                            closeEditorSession();
                            this.minecraft.setScreen(lastScreen);
                        } else {
                            this.minecraft.setScreen(this);
                        }
                    },
                    Component.literal("Unsaved changes"),
                    Component.literal("You have " + dirty.size() + " unsaved change(s). Leave without saving?")));
            return;
        }
        closeEditorSession();
        this.minecraft.setScreen(lastScreen);
    }

    @Override
    public void removed() {
        super.removed();
        advancements.setListener(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        int left = windowLeft();
        int top = windowTop();
        int windowWidth = windowWidth();
        int windowHeight = windowHeight();

        CodexAdvancementTab selectedTab = activeSelectedTab();
        frameLocation = !editMode && selectedTab != null ? ClientTabSummaries.windowTexture(WINDOW_LOCATION, selectedTab.getRootNode().holder().id()) : WINDOW_LOCATION;
        drawWindowFrame(extractor, left, top, windowWidth, windowHeight);
        if (!editMode && selectedTab != null) ClientTabSummaries.completionSheen(extractor, selectedTab.getRootNode().holder().id(), left, top, windowWidth);

        if (activeTabs().size() > 1 || editMode) {
            List<TabRowSlot> tabRow = buildTabRow(windowWidth);
            int tabRowY = top - CodexAdvancementTab.TAB_HEIGHT + 4;
            // Background first, then icons/glyphs in a separate pass (matches vanilla) so an
            // adjacent tab's background never gets drawn on top of this one's icon/glyph.
            int x = left;
            for (int i = 0; i < tabRow.size(); i++) {
                TabRowSlot slot = tabRow.get(i);
                boolean selected = slot.tab() != null && slot.tab() == selectedTab;
                Identifier sprite = CodexAdvancementTab.tabSprite(i == 0, i == tabRow.size() - 1, selected);
                if (!editMode && slot.tab() != null) sprite = ClientTabSummaries.tabSprite(sprite, slot.tab().getRootNode().holder().id());
                extractor.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, tabRowY,
                        CodexAdvancementTab.TAB_WIDTH, CodexAdvancementTab.TAB_HEIGHT);
                x += CodexAdvancementTab.TAB_SLOT;
            }
            x = left;
            for (TabRowSlot slot : tabRow) {
                if (slot.tab() != null) {
                    slot.tab().extractIcon(extractor, x, tabRowY, editMode ? 1 : ClientTabSummaries.iconScale(slot.tab().getRootNode().holder().id(), !partyPanelOpen && mouseX >= x && mouseX < x + CodexAdvancementTab.TAB_WIDTH && mouseY >= tabRowY && mouseY < tabRowY + CodexAdvancementTab.TAB_HEIGHT));
                    if (!partyPanelOpen && mouseX >= x && mouseX < x + CodexAdvancementTab.TAB_WIDTH && mouseY >= tabRowY && mouseY < tabRowY + CodexAdvancementTab.TAB_HEIGHT)
                        extractor.setComponentTooltipForNextFrame(font, ClientTabSummaries.tooltip(slot.tab().getRootNode().holder().id(), slot.tab().getTitle()), mouseX, mouseY);
                } else if (slot.isAdd()) {
                    extractor.centeredText(this.font, "+", x + CodexAdvancementTab.TAB_WIDTH / 2,
                            tabRowY + (CodexAdvancementTab.TAB_HEIGHT - this.font.lineHeight) / 2, 0xFFFFFFFF);
                } else if (slot.isPrev() || slot.isNext()) {
                    int color = slot.enabled() ? 0xFFFFFFFF : 0xFF808080;
                    extractor.centeredText(this.font, slot.isPrev() ? "<" : ">", x + CodexAdvancementTab.TAB_WIDTH / 2,
                            tabRowY + (CodexAdvancementTab.TAB_HEIGHT - this.font.lineHeight) / 2, color);
                }
                x += CodexAdvancementTab.TAB_SLOT;
            }
        }

        Component title = selectedTab != null ? selectedTab.getTitle() : this.title;
        int headerActionsWidth = windowWidth < 300 ? 145 : 218;
        extractor.text(this.font, this.font.plainSubstrByWidth(title.getString(), Math.max(20, windowWidth - headerActionsWidth - 16)), left + 8, top + 6, 0xFF404040, false);

        CodexAdvancementWidget hovered = null;
        int hoverX = 0;
        int hoverY = 0;
        int hoverBottom = 0;
        if (selectedTab != null) {
            int contentX = left + BORDER_LEFT;
            int contentY = top + BORDER_TOP;
            int contentWidth = contentWidth();
            int contentHeight = contentHeight();
            ClientQuests.view(editMode ? null : selectedTab.getRootNode().holder().id());
            if (pressedWidget != null && CodexClientSettings.holdAccept && ClientQuests.canAccept(ClientQuests.info(pressedWidget.node().holder().id())))
                QuestFeedback.hold(pressedWidget.node().holder().id(), (System.nanoTime() - pressedAt) / 700_000_000F);
            int cBottom = contentY + contentHeight;
            // A visible card owns its actual rectangle, including item chips over other nodes.
            if (!editMode && lastHoveredWidget != null && selectedTab == lastHoveredTab) {
                int cx = selectedTab.widgetScreenX(lastHoveredWidget, contentX, zoom);
                int cy = selectedTab.widgetScreenY(lastHoveredWidget, contentY, zoom);
                if (lastHoveredWidget.computeHoverBounds(font, cx, cy, width, cBottom).contains(mouseX, mouseY)) {
                    hovered = lastHoveredWidget; hoverX = cx; hoverY = cy; hoverBottom = cBottom;
                }
            }
            if (hovered == null) {
                hovered = selectedTab.findHovered(contentX, contentY, contentWidth, contentHeight, zoom, mouseX, mouseY);
                if (hovered != null) {
                    hoverX = selectedTab.widgetScreenX(hovered, contentX, zoom);
                    hoverY = selectedTab.widgetScreenY(hovered, contentY, zoom); hoverBottom = cBottom;
                }
            }
            selectedTab.animateHover(hovered);
            selectedTab.extractContents(extractor, contentX, contentY, contentWidth, contentHeight, zoom, editMode);
        } else {
            // No advancements to show at all (e.g. a fresh world before anything's synced yet) -
            // without this, the content area was fully transparent instead of matching vanilla's
            // own solid-background-plus-message look, which read as broken/unfinished rather than
            // "there's genuinely nothing here right now".
            int contentX = left + BORDER_LEFT;
            int contentY = top + BORDER_TOP;
            int contentWidth = contentWidth();
            int contentHeight = contentHeight();
            extractor.fill(contentX, contentY, contentX + contentWidth, contentY + contentHeight, 0xFF1E1E1E);
            extractor.centeredText(this.font, Component.literal("No advancements to show"),
                    contentX + contentWidth / 2, contentY + contentHeight / 2 - this.font.lineHeight / 2, 0xFFAAAAAA);
        }
        int pendingClaims = ClientClaimableRewards.available().size();
        claimsButton.setMessage(Component.literal(windowWidth < 300 ? "R(" + pendingClaims + ")" : "Rewards (" + pendingClaims + ")"));
        claimsButton.active = !partyPanelOpen && pendingClaims > 0;
        claimAllButton.active = !partyPanelOpen && pendingClaims > 0;
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        lastHoveredWidget = hovered;
        lastHoveredTab = selectedTab;

        // Panel widgets are added via addWidget() (not addRenderableWidget()), so they're never
        // drawn by the automatic pass above (super.extractRenderState()) - render them manually
        // here, clipped to the panel's viewport, so scrolled-out content actually disappears
        // instead of drawing over the tree/tab strip.
        if (!panelWidgets.isEmpty()) {
            int panelLeft = panelLeft();
            int panelWidth = panelWidth();
            int panelTop = top;
            int panelBottom = top + panelVisibleHeight();
            extractor.enableScissor(panelLeft, panelTop, panelLeft + panelWidth, panelBottom);
            for (AbstractWidget widget : panelWidgets) {
                widget.extractRenderState(extractor, mouseX, mouseY, partialTick);
            }
            extractor.disableScissor();

            int maxScroll = maxPanelScroll();
            if (maxScroll > 0) {
                int trackX = panelLeft + panelWidth - 1;
                int trackHeight = panelBottom - panelTop;
                float visibleRatio = Math.min(1.0F, (float) trackHeight / (panelContentBottom - panelTop));
                int thumbHeight = Math.max(10, Math.round(trackHeight * visibleRatio));
                int thumbY = panelTop + Math.round((trackHeight - thumbHeight) * (float) (panelScrollOffset / maxScroll));
                extractor.verticalLine(trackX, panelTop, panelBottom, 0x33FFFFFF);
                extractor.verticalLine(trackX, thumbY, thumbY + thumbHeight, 0xFFAAAAAA);
            }
        }

        // Drawn last (after the panel widgets above) so the hover box is always on top - edit
        // mode's title/description boxes previously covered it since they used to draw afterward.
        if (hovered != null) {
            // Vanilla's real hover box is a custom widget (title_box sprite, progress-colored
            // split background), not a generic floating tooltip - rendered in its own stratum
            // with a blur behind it, matching vanilla's own AdvancementTab.extractTooltips.
            extractor.nextStratum();
            tryBlur(extractor);
            hovered.extractHover(extractor, this.font, hoverX, hoverY, this.width, hoverBottom, mouseX, mouseY);
        }
        QuestFeedback.render(extractor);

        if (partyPanelOpen) {
            extractPartyPanel(extractor, mouseX, mouseY, partialTick);
        }
    }

    /**
     * Draws the window chrome as 4 fixed-size corners plus tiled (not stretched) edges, using the
     * real vanilla border art, so it can scale to any size without blurring a stretched blit.
     */
    private Identifier frameLocation = WINDOW_LOCATION;

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
        extractor.blit(RenderPipelines.GUI_TEXTURED, frameLocation, x, y, (float) u, (float) v, w, h, 256, 256);
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
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (partyPanelOpen && event.isEscape()) {
            closePartyPanel();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (partyPanelOpen) {
            if (event.button() == 0) super.mouseClicked(event, doubled);
            return true;
        }

        int left = windowLeft();
        int top = windowTop();

        if (event.button() == 0 && (activeTabs().size() > 1 || editMode)) {
            List<TabRowSlot> tabRow = buildTabRow(windowWidth());
            int tabRowY = top - CodexAdvancementTab.TAB_HEIGHT + 4;
            int x = left;
            for (TabRowSlot slot : tabRow) {
                if (event.x() >= x && event.x() < x + CodexAdvancementTab.TAB_WIDTH
                        && event.y() >= tabRowY && event.y() < tabRowY + CodexAdvancementTab.TAB_HEIGHT) {
                    if (slot.isPrev()) {
                        if (slot.enabled()) {
                            tabPageStart--;
                        }
                        return true;
                    }
                    if (slot.isNext()) {
                        if (slot.enabled()) {
                            tabPageStart++;
                        }
                        return true;
                    }
                    if (slot.isAdd()) {
                        if (creationMode == CreationMode.NONE) {
                            creationMode = CreationMode.NEW_TAB;
                            panelScrollOffset = 0;
                            this.init(this.width, this.height);
                            return true;
                        }
                        break;
                    }
                    if (slot.tab() != null) {
                        CodexAdvancementTab tab = slot.tab();
                        if (editMode) {
                            if (selectedEditWidget != null && titleBox != null && !applyEdit()) return true;
                            selectedEditTab = tab;
                            selectedEditWidget = tab.getRoot();
                            panelScrollOffset = 0;
                            customLayoutMode = tabHasCustomLayout(tab);
                            pendingLayoutResetTab = null;
                            if (creationMode != CreationMode.NONE) {
                                creationMode = CreationMode.NONE;
                                this.init(this.width, this.height);
                            }
                            this.init(this.width, this.height);
                        } else {
                            advancements.setSelectedTab(tab.getRootNode().holder(), true);
                        }
                        return true;
                    }
                }
                x += CodexAdvancementTab.TAB_SLOT;
            }
        }

        if (editMode && selectedEditTab != null && creationMode == CreationMode.NONE) {
            int contentX = left + BORDER_LEFT;
            int contentY = top + BORDER_TOP;
            boolean inContent = event.x() >= contentX && event.x() < contentX + contentWidth()
                    && event.y() >= contentY && event.y() < contentY + contentHeight();

            if (inContent) {
                CodexAdvancementWidget hit = selectedEditTab.findHovered(contentX, contentY, contentWidth(), contentHeight(), zoom, event.x(), event.y());

                if (event.button() == 0 && event.hasShiftDown() && linkSource != null) {
                    if (hit != null) {
                        tryReparent(hit);
                    }
                    return true;
                }
                if (event.button() == 1 && event.hasShiftDown()) {
                    if (hit != null) {
                        linkSource = hit;
                        setStatus("Selected " + hit.node().holder().id() + " as the new parent - shift+left-click another node to attach it.");
                    }
                    return true;
                }
                if (event.button() == 1) {
                    if (hit == null) {
                        pendingNodeParent = selectedEditWidget != null ? selectedEditWidget : selectedEditTab.getRoot();
                        creationMode = CreationMode.NEW_NODE;
                        panelScrollOffset = 0;
                        this.init(this.width, this.height);
                    }
                    return true;
                }
                if (event.button() == 0 && hit != null) {
                    selectEditWidget(hit);
                    if (customLayoutMode) {
                        layoutDragWidget = hit;
                        layoutDragRawX = hit.x();
                        layoutDragRawY = hit.y();
                    }
                    return true;
                }
            }
        }
        // Hover-table actions are intentionally left-clickable: the exact submission item sends
        // that task, and a displayed claimable reward claims that quest. This is checked before
        // the broader node right-click shortcut below.
        if (!editMode && (event.button() == 0 || event.button() == 1) && selectedViewTab != null && lastHoveredWidget != null
                && lastHoveredTab == selectedViewTab) {
            int contentX = left + BORDER_LEFT;
            int contentY = top + BORDER_TOP;
            int widgetX = selectedViewTab.widgetScreenX(lastHoveredWidget, contentX, zoom);
            int widgetY = selectedViewTab.widgetScreenY(lastHoveredWidget, contentY, zoom);
            int contentBottom = contentY + contentHeight();
            int mouseX = (int) event.x();
            int mouseY = (int) event.y();
            boolean insideCard = lastHoveredWidget.computeHoverBounds(font, widgetX, widgetY, width, contentBottom).contains(mouseX, mouseY);
            if (insideCard && event.button() == 1) {
                if (mouseX >= widgetX + 3 && mouseX < widgetX + 29 && mouseY >= widgetY && mouseY < widgetY + 26 && !ClientQuests.locked(lastHoveredWidget.node().holder().id())) {
                    var tasks = ClientSubmissionTasks.forAdvancement(lastHoveredWidget.node().holder().id());
                    if (!tasks.isEmpty()) NetworkManager.sendToServer(new SubmitAdvancementItemsPayload(tasks.get(0).advancementId(), tasks.get(0).criterion(), java.util.Optional.empty()));
                    else if (ClientClaimableRewards.contains(lastHoveredWidget.node().holder().id())) NetworkManager.sendToServer(new ClaimAdvancementRewardPayload(lastHoveredWidget.node().holder().id(), -1));
                }
                return true;
            }
            if (insideCard && mouseX >= widgetX + 3 && mouseX < widgetX + 29 && mouseY >= widgetY && mouseY < widgetY + 26) {
                pressedWidget = lastHoveredWidget; pressX = event.x(); pressY = event.y(); pressedAt = System.nanoTime(); holdSent = false;
                ClientQuests.watch(lastHoveredWidget.node().holder().id()); return true;
            }
            var submission = lastHoveredWidget.submissionAt(this.font, widgetX, widgetY, this.width, contentBottom, mouseX, mouseY);
            if (submission != null) {
                NetworkManager.sendToServer(new SubmitAdvancementItemsPayload(submission.task().advancementId(), submission.task().criterion(), java.util.Optional.of(submission.itemId())));
                return true;
            }
            Identifier id = lastHoveredWidget.node().holder().id();
            int rewardIndex = lastHoveredWidget.rewardChipAt(this.font, widgetX, widgetY, this.width, contentBottom, mouseX, mouseY);
            if (rewardIndex >= 0 && ClientClaimableRewards.contains(id, rewardIndex)) {
                NetworkManager.sendToServer(new ClaimAdvancementRewardPayload(id, rewardIndex));
                return true;
            }
            if (insideCard) return true;
        }
        if (!editMode && event.button() == 0 && selectedViewTab != null) {
            CodexAdvancementWidget hit = selectedViewTab.findHovered(left + BORDER_LEFT, top + BORDER_TOP, contentWidth(), contentHeight(), zoom, event.x(), event.y());
            if (hit != null) {
                pressedWidget = hit; pressX = event.x(); pressY = event.y(); pressedAt = System.nanoTime(); holdSent = false;
                ClientQuests.watch(hit.node().holder().id());
                return true;
            }
        }
        if (!editMode && event.button() == 1 && selectedViewTab != null) {
            int contentX = left + BORDER_LEFT;
            int contentY = top + BORDER_TOP;
            CodexAdvancementWidget hit = selectedViewTab.findHovered(contentX, contentY, contentWidth(), contentHeight(), zoom, event.x(), event.y());
            if (hit != null && !ClientQuests.locked(hit.node().holder().id())) {
                var tasks = ClientSubmissionTasks.forAdvancement(hit.node().holder().id());
                if (!tasks.isEmpty()) {
                    var task = tasks.get(0);
                    NetworkManager.sendToServer(new SubmitAdvancementItemsPayload(task.advancementId(), task.criterion(), java.util.Optional.empty()));
                    return true;
                }
                if (ClientClaimableRewards.contains(hit.node().holder().id())) {
                    NetworkManager.sendToServer(new ClaimAdvancementRewardPayload(hit.node().holder().id(), -1));
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubled);
    }

    /**
     * Reparents {@code child} onto whatever node was last shift+right-clicked ({@link #linkSource}).
     * Guards against self-links, reparenting a tab's own root (that would orphan the tab - create a
     * new advancement under it instead), and cycles (making an ancestor a descendant of itself).
     */
    private void tryReparent(CodexAdvancementWidget child) {
        AdvancementNode newParentNode = linkSource.node();
        Identifier parentId = newParentNode.holder().id();
        Identifier childId = child.node().holder().id();
        linkSource = null;

        if (parentId.equals(childId)) {
            setStatus("Can't link a node to itself.");
            return;
        }
        if (child.node().parent() == null) {
            setStatus("Can't reparent a tab's root - create a new advancement under it instead.");
            return;
        }
        for (AdvancementNode ancestor = newParentNode; ancestor != null; ancestor = ancestor.parent()) {
            if (ancestor.holder().id().equals(childId)) {
                setStatus("Can't link - that would create a cycle.");
                return;
            }
        }

        JsonObject base = dirty.containsKey(childId) ? dirty.get(childId) : rawFullJson.get(childId);
        if (base == null) {
            setStatus("No data for this advancement.");
            return;
        }
        JsonObject updated = base.deepCopy();
        updated.addProperty("parent", parentId.toString());
        dirty.put(childId, updated);

        rebuildEditTree();
        this.init(this.width, this.height);
        setStatus(parentId + " is now the parent of " + childId + " - hit Save to persist.");
    }

    private void cancelCreation() {
        creationMode = CreationMode.NONE;
        pendingNodeParent = null;
        this.init(this.width, this.height);
    }

    private void createTab() {
        Identifier id;
        try {
            id = sanitizeNewId(newIdBox.getValue());
        } catch (RuntimeException e) {
            setStatus("Invalid id: " + e.getMessage());
            return;
        }
        if (effectiveJson().containsKey(id)) {
            setStatus("An advancement with that id already exists.");
            return;
        }
        String iconId = newIconBox.getValue().trim();
        String backgroundId = backgroundOptionButton.getValue() == BackgroundOption.CUSTOM
                ? newBackgroundBox.getValue().trim()
                : backgroundOptionButton.getValue().textureId;
        if (backgroundOptionButton.getValue() == BackgroundOption.CUSTOM && backgroundId.isEmpty()) {
            setStatus("Enter a custom background id, or pick a preset.");
            return;
        }
        JsonObject display;
        try {
            display = buildDisplayJson(id.getPath(), "", iconId.isEmpty() ? "minecraft:stone" : iconId, backgroundId);
        } catch (RuntimeException e) {
            setStatus("Invalid icon/background: " + e.getMessage());
            return;
        }
        JsonObject advancement = new JsonObject();
        advancement.add("display", display);
        JsonObject tabRules = new JsonObject(); tabRules.addProperty("quest_tab", true); advancement.add("codex_quest", tabRules);
        advancement.add("criteria", net.vg.codex.advancement.QuestTemplates.criteria(questTemplateButton.getValue()));
        dirty.put(id, advancement);

        creationMode = CreationMode.NONE;
        rebuildEditTree();
        selectEditNodeById(id);
        this.init(this.width, this.height);
        setStatus("Created editable quest from template — configure its criteria, then Save.");
    }

    private void addTestTabs() {
        int created = 0;
        int idx = 1;
        while (created < 12 && idx <= 1000) {
            Identifier id = Identifier.fromNamespaceAndPath("codex", "debug/test_tab_" + idx);
            if (!effectiveJson().containsKey(id)) {
                JsonObject display = buildDisplayJson("Test Tab " + idx, "Debug tab for testing tab overflow.", "minecraft:stone", null);
                JsonObject advancement = new JsonObject();
                advancement.add("display", display);
                advancement.add("criteria", defaultCriteria());
                advancement.add("requirements", defaultRequirements());
                dirty.put(id, advancement);
                created++;
            }
            idx++;
        }
        rebuildEditTree();
        this.init(this.width, this.height);
        setStatus("Added " + created + " test tab(s) - hit Save to persist them, or just reopen to discard.");
    }

    private void createNode() {
        if (pendingNodeParent == null) {
            setStatus("No parent selected.");
            return;
        }
        Identifier id;
        try {
            id = sanitizeNewId(newIdBox.getValue());
        } catch (RuntimeException e) {
            setStatus("Invalid id: " + e.getMessage());
            return;
        }
        if (effectiveJson().containsKey(id)) {
            setStatus("An advancement with that id already exists.");
            return;
        }
        String iconId = newIconBox.getValue().trim();
        JsonObject display;
        try {
            display = buildDisplayJson(id.getPath(), "", iconId.isEmpty() ? "minecraft:stone" : iconId, null);
        } catch (RuntimeException e) {
            setStatus("Invalid icon: " + e.getMessage());
            return;
        }
        Identifier parentId = pendingNodeParent.node().holder().id();
        JsonObject advancement = new JsonObject();
        advancement.addProperty("parent", parentId.toString());
        advancement.add("display", display);
        advancement.add("criteria", net.vg.codex.advancement.QuestTemplates.criteria(questTemplateButton.getValue()));
        dirty.put(id, advancement);

        creationMode = CreationMode.NONE;
        pendingNodeParent = null;
        rebuildEditTree();
        selectEditNodeById(id);
        this.init(this.width, this.height);
        setStatus("Created advancement " + id + " under " + parentId + " - it starts with a placeholder "
                + "criterion (Impossible) so it's always saveable; replace it via the criteria list below, then Save.");
    }

    /**
     * A brand-new advancement can't be saved with zero criteria - vanilla's own
     * {@code Advancement.CODEC} rejects that outright ("Advancement criteria cannot be empty"),
     * which previously surfaced only as a server-log error well after the fact, with nothing on
     * screen explaining why the save silently dropped that entry. A single placeholder "Impossible"
     * criterion (same trigger vanilla itself uses for advancements that should never auto-complete)
     * keeps every freshly created node saveable immediately; it shows up in the criteria list ready
     * to be renamed/replaced with a real one.
     */
    private JsonObject defaultCriteria() {
        JsonObject criteria = new JsonObject();
        JsonObject placeholder = new JsonObject();
        placeholder.addProperty("trigger", CriterionType.IMPOSSIBLE.triggerId);
        criteria.add("placeholder", placeholder);
        return criteria;
    }

    private JsonArray defaultRequirements() {
        JsonArray requirements = new JsonArray();
        JsonArray group = new JsonArray();
        group.add("placeholder");
        requirements.add(group);
        return requirements;
    }

    /**
     * Builds a vanilla-shaped "display" JSON object directly (not via {@link DisplayInfo}
     * construction) and validates it eagerly through {@link DisplayInfo#CODEC} - safe client-side
     * since it only touches the Item registry and an optional texture path, both always fully
     * networked - so a typo'd icon/background id fails fast with a clear message here instead of
     * only being caught by the server on Save.
     */
    private JsonObject buildDisplayJson(String title, String description, String iconId, String backgroundId) {
        JsonObject display = new JsonObject();
        JsonObject titleJson = new JsonObject();
        titleJson.addProperty("text", title.isBlank() ? "Untitled" : title);
        display.add("title", titleJson);
        JsonObject descriptionJson = new JsonObject();
        descriptionJson.addProperty("text", description);
        display.add("description", descriptionJson);
        JsonObject icon = new JsonObject();
        icon.addProperty("id", iconId);
        display.add("icon", icon);
        if (backgroundId != null && !backgroundId.isBlank()) {
            display.addProperty("background", backgroundId);
        }
        display.addProperty("show_toast", true);
        display.addProperty("announce_to_chat", true);
        DisplayInfo.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries()), display).getOrThrow();
        return display;
    }

    /**
     * Turns casual input ("Dev", "My Tab!") into a valid {@link Identifier} instead of making the
     * user learn resource-location rules (lowercase only, no spaces) up front - defaults to this
     * mod's own namespace (not "minecraft") when none is typed, so custom content never accidentally
     * claims a vanilla-looking id. Only throws if nothing valid is left after sanitizing.
     */
    private Identifier sanitizeNewId(String raw) {
        String trimmed = raw.trim();
        int colon = trimmed.indexOf(':');
        String namespace = colon >= 0 ? sanitizeIdPart(trimmed.substring(0, colon)) : ModMain.MOD_ID;
        String path = sanitizeIdPart(colon >= 0 ? trimmed.substring(colon + 1) : trimmed);
        if (namespace.isEmpty() || path.isEmpty()) {
            throw new IllegalArgumentException("Enter a name with at least one letter, number, or '_'.");
        }
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    private static String sanitizeIdPart(String part) {
        StringBuilder sb = new StringBuilder();
        for (char c : part.toLowerCase(java.util.Locale.ROOT).trim().replace(' ', '_').toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void selectEditWidget(CodexAdvancementWidget widget) {
        if (selectedEditWidget != null && widget != selectedEditWidget && titleBox != null && !savePending && !applyEdit()) return;
        selectedEditWidget = widget;
        panelScrollOffset = 0;
        // Full re-init rather than poking existing widgets: Checkbox (unlike EditBox) has no
        // setter to reflect a newly-selected node's shouldShowToast()/shouldAnnounceChat() onto an
        // already-built widget, so it needs to be constructed fresh with the right initial state -
        // init() already does exactly that (and re-populates title/description/criteria too, via
        // the same "if (selectedEditWidget != null)" branch used on the very first selection).
        this.init(this.width, this.height);
    }

    /**
     * Frame changes preview live on the tree (unlike title/description/criteria, which stay
     * pending until Apply Edit) since there's otherwise no way to see the actual frame shape
     * before committing to it - purely visual, doesn't touch {@code dirty} until Apply Edit runs.
     */
    private void previewFrameChange(AdvancementType type) {
        if (selectedEditWidget == null) {
            return;
        }
        DisplayInfo old = selectedEditWidget.display();
        DisplayInfo preview = new DisplayInfo(old.getIcon(), old.getTitle(), old.getDescription(),
                old.getBackground(), type, old.shouldShowToast(), old.shouldAnnounceChat(), old.isHidden());
        selectedEditWidget.setDisplay(preview);
        if (selectedEditTab != null && selectedEditWidget == selectedEditTab.getRoot()) {
            selectedEditTab.refreshDisplay(preview);
        }
    }

    /** Same live-preview approach as {@link #previewFrameChange}, for the icon instead of the frame. */
    private void previewIconChange(Identifier iconId) {
        if (selectedEditWidget == null) {
            return;
        }
        DisplayInfo old = selectedEditWidget.display();
        DisplayInfo preview = new DisplayInfo(new ItemStackTemplate(BuiltInRegistries.ITEM.getValue(iconId)),
                old.getTitle(), old.getDescription(), old.getBackground(), old.getType(),
                old.shouldShowToast(), old.shouldAnnounceChat(), old.isHidden());
        selectedEditWidget.setDisplay(preview);
        if (selectedEditTab != null && selectedEditWidget == selectedEditTab.getRoot()) {
            selectedEditTab.refreshDisplay(preview);
        }
    }

    /** Best-effort icon preview for arbitrary (possibly invalid/in-progress) typed text - an unknown
     * or unparseable id resolves to {@link DefaultedRegistry}'s default (air), not a crash. */
    private static ItemStack resolveIconStack(String idText) {
        try {
            return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(idText.trim())));
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack rewardEntryIcon(JsonObject entry) {
        try {
            return switch (RewardType.valueOf(entry.get("type").getAsString())) {
                case EXPERIENCE -> new ItemStack(Items.EXPERIENCE_BOTTLE);
                case TREASURE_BAG -> new ItemStack(net.vg.codex.advancement.TreasureBagItem.ITEM);
                case ITEM -> new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(entry.get("id").getAsString())));
                default -> new ItemStack(Items.PAPER);
            };
        } catch (RuntimeException ignored) { return ItemStack.EMPTY; }
    }

    private static ItemStack criterionEntryIcon(JsonObject entry) {
        try {
            CriterionType type = CriterionType.fromJson(entry);
            if (type == CriterionType.ITEM_OBTAINED) {
                JsonObject conditions = entry.getAsJsonObject("conditions");
                String id = conditions.getAsJsonArray("items").get(0).getAsJsonObject().get("items").getAsString();
                return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
            }
            if (type == CriterionType.SUBMIT_ITEMS) return new ItemStack(Items.HOPPER);
            if (type == CriterionType.STATISTIC) return new ItemStack(Items.BOOK);
            return new ItemStack(Items.PAPER);
        } catch (RuntimeException ignored) { return ItemStack.EMPTY; }
    }

    /** Compact non-editing visual affordance used beside reward and criterion rows. */
    private final class EntryIconButton extends AbstractButton {
        private final Supplier<ItemStack> icon;
        private final Runnable action;
        EntryIconButton(int x, int y, Supplier<ItemStack> icon, Runnable action) {
            super(x, y, 16, 12, Component.empty());
            this.icon = icon;
            this.action = action;
        }
        @Override public void onPress(InputWithModifiers input) { action.run(); }
        @Override protected void extractContents(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
            extractor.fakeItem(icon.get(), getX(), getY() - 2);
        }
        @Override protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
            output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, Component.literal("Edit entry"));
        }
    }

    /** A small clickable icon preview - clicking opens the item picker; {@code onPick} receives the
     * chosen item's id. Used both for a text-backed icon field (creation forms) and a live-preview
     * field with no backing text box at all (the normal edit panel, via {@link #previewIconChange}). */
    private final class IconPreviewButton extends AbstractButton {
        private final Supplier<ItemStack> currentIcon;
        private final Consumer<Identifier> onPick;

        IconPreviewButton(int x, int y, int size, Supplier<ItemStack> currentIcon, Consumer<Identifier> onPick) {
            super(x, y, size, size, Component.literal("Icon"));
            this.currentIcon = currentIcon;
            this.onPick = onPick;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            openIconPicker(onPick);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
            extractor.fakeItem(currentIcon.get(), getX() + (getWidth() - 16) / 2, getY() + (getHeight() - 16) / 2);
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
            output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, Component.literal("Icon - click to change"));
        }
    }

    /**
     * Shows the frame style as the actual vanilla frame+icon art (the same sprites the tree itself
     * uses), rather than a text label naming the enum constant - clicking cycles to the next frame
     * type. Reads/writes the same live-preview display as {@link #previewFrameChange}.
     */
    private final class FrameStyleButton extends AbstractButton {
        FrameStyleButton(int x, int y, int size) {
            super(x, y, size, size, Component.literal("Frame style"));
        }

        @Override
        public void onPress(InputWithModifiers input) {
            if (selectedEditWidget == null) {
                return;
            }
            AdvancementType[] values = AdvancementType.values();
            AdvancementType current = selectedEditWidget.display().getType();
            previewFrameChange(values[(current.ordinal() + 1) % values.length]);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
            if (selectedEditWidget == null) {
                return;
            }
            DisplayInfo display = selectedEditWidget.display();
            extractor.blitSprite(RenderPipelines.GUI_TEXTURED, AdvancementWidgetType.OBTAINED.frameSprite(display.getType()),
                    getX() - 1, getY() - 3, 26, 26);
            extractor.fakeItem(display.getIcon().create(), getX() + 4, getY() + 2);
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
            if (selectedEditWidget != null) {
                output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                        Component.literal("Frame: " + selectedEditWidget.display().getType().getSerializedName()));
            }
        }
    }

    /** Prior applied-but-unsaved edits (tracked in `dirty`) take precedence over the original. */
    private JsonObject baseJsonFor(Identifier id) {
        return dirty.containsKey(id) ? dirty.get(id) : rawFullJson.get(id);
    }

    private JsonObject criteriaFor(CodexAdvancementWidget widget) {
        JsonObject base = baseJsonFor(widget.node().holder().id());
        if (base != null && base.has("criteria") && base.get("criteria").isJsonObject()) {
            return base.getAsJsonObject("criteria");
        }
        return new JsonObject();
    }

    /** The selected node's current "rewards" object, or an empty one for a fresh/rewardless node. */
    private JsonObject currentRewardsJson() {
        if (selectedEditWidget == null) {
            return new JsonObject();
        }
        JsonObject base = baseJsonFor(selectedEditWidget.node().holder().id());
        return base != null && base.has("rewards") && base.get("rewards").isJsonObject()
                ? base.getAsJsonObject("rewards") : new JsonObject();
    }

    /**
     * Reverses {@link #buildRewardsJson} - turns a "rewards" object back into {@link #rewardEntries}'
     * working format. The generated loot table id (item rewards' actual grant mechanism) is filtered
     * out of the plain loot-table entries it'd otherwise show up as, since "codex_items" already
     * represents the same data as proper Item entries.
     */
    private List<JsonObject> loadRewardEntries(JsonObject rewards) {
        return QuestRewards.entries(rewards, selectedEditWidget == null ? null : selectedEditWidget.node().holder().id());
    }

    private Requirements currentRequirements() {
        if (selectedEditWidget == null) {
            return Requirements.ALL;
        }
        JsonObject base = baseJsonFor(selectedEditWidget.node().holder().id());
        if (base == null) {
            return Requirements.ALL;
        }
        int criteriaCount = base.has("criteria") && base.get("criteria").isJsonObject()
                ? base.getAsJsonObject("criteria").size() : 0;
        JsonArray requirements = base.has("requirements") && base.get("requirements").isJsonArray()
                ? base.getAsJsonArray("requirements") : null;
        return Requirements.fromJson(requirements, criteriaCount);
    }

    private boolean isCurrentClaimable() {
        if (selectedEditWidget == null) return false;
        JsonObject base = baseJsonFor(selectedEditWidget.node().holder().id());
        return base != null && base.has("codex_claimable") && base.get("codex_claimable").getAsBoolean();
    }

    // ---- Criteria list/editor ----

    private void refreshCriteriaList() {
        currentCriteriaNames.clear();
        currentCriteriaNames.addAll(criteriaEntries.keySet());
        for (int i = 0; i < criteriaListButtons.size(); i++) {
            Button button = criteriaListButtons.get(i);
            if (i < currentCriteriaNames.size()) {
                String name = currentCriteriaNames.get(i);
                JsonObject json = criteriaEntries.get(name);
                String triggerId = json.has("trigger") ? json.get("trigger").getAsString() : "?";
                CriterionType type = CriterionType.fromJson(json);
                String label = type == CriterionType.CUSTOM ? "Advanced: " + name : type.label;
                if (type == CriterionType.ITEM_OBTAINED) {
                    String value = net.vg.codex.advancement.QuestCriteria.extractValue(type, json);
                    label = value.startsWith("#") ? "Obtain any " + Identifier.parse(value.substring(1)).getPath().replace('_', ' ')
                            : "Obtain " + resolveIconStack(value).getHoverName().getString();
                }
                button.setMessage(Component.literal(label));
                button.setTooltip(Tooltip.create(Component.literal("Key: " + name + "\nTrigger: " + triggerId)));
                button.visible = true;
                button.active = true;
            } else {
                button.visible = false;
                button.active = false;
            }
        }
    }

    // ---- Reward list/editor ----

    private void refreshRewardList() {
        for (int i = 0; i < rewardListButtons.size(); i++) {
            Button button = rewardListButtons.get(i);
            if (i < rewardEntries.size()) {
                button.setMessage(Component.literal(describeReward(rewardEntries.get(i))));
                button.visible = true;
                button.active = true;
            } else {
                button.visible = false;
                button.active = false;
            }
        }
    }

    private String describeReward(JsonObject entry) { return ClientRewardPresentation.label(entry); }

    private boolean rewardTypeConflict(RewardType type, Integer excludeIndex) {
        if (type != RewardType.EXPERIENCE && type != RewardType.FUNCTION) {
            return false;
        }
        for (int i = 0; i < rewardEntries.size(); i++) {
            if (excludeIndex != null && i == excludeIndex) {
                continue;
            }
            if (RewardType.valueOf(rewardEntries.get(i).get("type").getAsString()) == type) {
                return true;
            }
        }
        return false;
    }

    // ---- Apply / Save ----

    /**
     * Builds the "rewards" JSON from {@link #rewardEntries} using real vanilla field names
     * ("experience", "recipes", "loot", "function", confirmed from AdvancementRewards' codec) for
     * everything except item rewards, which aren't a vanilla field at all - those go out under the
     * wire-only "codex_items" key that {@code AdvancementEditorNetworking} strips server-side after
     * turning it into a real generated loot table (see that class and {@code AdvancementPackIO}).
     */
    private JsonObject buildRewardsJson() {
        return QuestRewards.serialize(rewardEntries, currentRewardsJson(), selectedEditWidget == null ? null : selectedEditWidget.node().holder().id());
    }

    private void rememberUndo(Identifier id, JsonObject before, JsonObject after) {
        if (before.equals(after)) return;
        var history = undoHistory.computeIfAbsent(id, ignored -> new java.util.ArrayDeque<>());
        history.push(before.deepCopy());
        while (history.size() > 20) history.removeLast();
    }

    private void undoApply() {
        if (selectedEditWidget == null || savePending) return;
        Identifier id = selectedEditWidget.node().holder().id();
        for (DraftBatch batch : java.util.List.copyOf(batchUndo)) if (batch.after().containsKey(id)) {
            Map<Identifier, JsonObject> current = effectiveJson();
            boolean exact = batch.after().entrySet().stream().allMatch(e -> java.util.Objects.equals(current.get(e.getKey()), e.getValue()));
            if (exact) {
                for (var entry : batch.before().entrySet()) {
                    if (entry.getValue() == null) dirty.remove(entry.getKey());
                    else {
                        dirty.put(entry.getKey(), entry.getValue().deepCopy());
                        var undo = undoHistory.get(entry.getKey());
                        if (undo != null && !undo.isEmpty() && undo.peek().equals(entry.getValue())) undo.pop();
                    }
                }
                batchUndo.remove(batch); formAdvancementId = null; criteriaEntriesSelection = null;
                rebuildEditTree(); selectEditNodeById(id); this.init(this.width, this.height);
                setStatus("Linked draft batch restored. Save writes the result."); return;
            }
            if (java.util.Objects.equals(current.get(id), batch.after().get(id))) {
                setStatus("Undo later edits on the linked quests first, then undo this batch."); return;
            }
            break;
        }
        var history = undoHistory.get(id);
        if (history == null || history.isEmpty()) { setStatus("No applied changes to undo."); return; }
        dirty.put(id, history.pop());
        formAdvancementId = null;
        criteriaEntriesSelection = null;
        rebuildEditTree(); selectEditNodeById(id); this.init(this.width, this.height);
        setStatus("Previous draft restored; Save Changes writes it to the server.");
    }

    private void openRequirementsEditor() {
        if (selectedEditWidget == null || !applyEdit()) return;
        Identifier id = selectedEditWidget.node().holder().id();
        JsonObject base = baseJsonFor(id);
        minecraft.setScreen(new RequirementsScreen(this, criteriaEntries.keySet(), base.has("requirements") ? base.getAsJsonArray("requirements") : null, groups -> {
            JsonObject updated = base.deepCopy(); updated.add("requirements", groups);
            requirementModeButton.setValue(Requirements.fromJson(groups, criteriaEntries.size()));
            rememberUndo(id, base, updated); dirty.put(id, updated);
        }));
    }

    private void openQuestProperties() {
        if (selectedEditWidget == null || !applyEdit()) return;
        Identifier id = selectedEditWidget.node().holder().id(); JsonObject base = baseJsonFor(id);
        Map<Identifier, JsonObject> definitions = new LinkedHashMap<>(rawFullJson); definitions.putAll(dirty);
        minecraft.setScreen(new QuestPropertiesScreen(this, id, base.has("codex_quest") ? base.getAsJsonObject("codex_quest") : new JsonObject(), definitions, metadata -> {
            JsonObject updated = base.deepCopy(); updated.add("codex_quest", metadata);
            net.vg.codex.advancement.QuestDefinitions.validateAdvancement(metadata, updated);
            Map<Identifier, JsonObject> changes = net.vg.codex.advancement.PackAuthoring.linkBranches(definitions, id, metadata);
            changes.put(id, updated);
            for (var entry : changes.entrySet()) {
                JsonObject rules = entry.getValue().has("codex_quest") ? entry.getValue().getAsJsonObject("codex_quest") : new JsonObject();
                net.vg.codex.advancement.QuestDefinitions.validateAdvancement(rules, entry.getValue());
            }
            applyDraftBatch(changes);
        }));
    }

    private void applyDraftBatch(Map<Identifier, JsonObject> changes) {
        Map<Identifier, JsonObject> beforeBatch = new LinkedHashMap<>(), afterBatch = new LinkedHashMap<>();
        Map<Identifier, JsonObject> current = effectiveJson();
        changes.forEach((id, value) -> { beforeBatch.put(id, current.containsKey(id) ? current.get(id).deepCopy() : null); afterBatch.put(id, value.deepCopy()); });
        if (changes.size() > 1 || beforeBatch.values().stream().anyMatch(java.util.Objects::isNull)) {
            batchUndo.push(new DraftBatch(beforeBatch, afterBatch)); while (batchUndo.size() > 20) batchUndo.removeLast();
        }
        for (var entry : changes.entrySet()) {
            JsonObject before = effectiveJson().get(entry.getKey());
            if (before != null) rememberUndo(entry.getKey(), before, entry.getValue());
            dirty.put(entry.getKey(), entry.getValue().deepCopy());
        }
        formAdvancementId = null; criteriaEntriesSelection = null;
        rebuildEditTree();
        setStatus("Updated " + changes.size() + " drafts. Save writes them to the server.");
    }

    private void openPackTools() {
        if (savePending || selectedEditWidget == null || !applyEdit()) return;
        Identifier selected = selectedEditWidget.node().holder().id();
        minecraft.setScreen(new PackToolsScreen(this, effectiveJson(), selected, this::applyDraftBatch,
                id -> { formAdvancementId = null; criteriaEntriesSelection = null; selectEditNodeById(id); }));
    }

    private void openDocumentEditor() {
        if (selectedEditWidget == null || !applyEdit()) return;
        Identifier id = selectedEditWidget.node().holder().id(); JsonObject base = baseJsonFor(id);
        minecraft.setScreen(new JsonDocumentScreen(this, base, updated -> {
            if (!updated.has("criteria") || (updated.getAsJsonObject("criteria").size() == 0)) throw new IllegalArgumentException("At least one criterion is required");
            for (var criterion : updated.getAsJsonObject("criteria").entrySet()) {
                JsonObject value = criterion.getValue().getAsJsonObject();
                if (!value.has("trigger") || !value.get("trigger").isJsonPrimitive() || !value.get("trigger").getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Every criterion needs a trigger identifier");
                Identifier.parse(value.get("trigger").getAsString());
            }
            if (updated.has("requirements") && !updated.get("requirements").isJsonNull()) AdvancementDocument.validateGroups(updated.getAsJsonArray("requirements"), updated.getAsJsonObject("criteria").keySet());
            if (updated.has("parent") && !updated.get("parent").isJsonNull()) Identifier.parse(updated.get("parent").getAsString());
            if (updated.has("display") && !updated.get("display").isJsonNull()) DisplayInfo.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries()), updated.get("display")).getOrThrow();
            net.vg.codex.advancement.AdvancementValidation.validateParents(id, updated, parent -> parent.equals(id) ? updated : effectiveJson().get(parent));
            rememberUndo(id, base, updated); dirty.put(id, updated);
            formAdvancementId = null; criteriaEntriesSelection = null; rebuildEditTree(); selectEditNodeById(id);
        }));
    }

    private boolean applyEdit() {
        if (selectedEditWidget == null) {
            setStatus("Select an advancement first.");
            return false;
        }
        Identifier id = selectedEditWidget.node().holder().id();
        JsonObject base = dirty.containsKey(id) ? dirty.get(id) : rawFullJson.get(id);
        if (base == null) {
            setStatus("No base data for this advancement.");
            return false;
        }
        JsonObject updated = base.deepCopy();

        DisplayInfo old = selectedEditWidget.display();
        DisplayInfo updatedDisplay = new DisplayInfo(
                old.getIcon(),
                Component.literal(titleBox.getValue()),
                Component.literal(descriptionBox.getValue()),
                old.getBackground(),
                old.getType(),
                showToastCheckbox.selected(),
                announceChatCheckbox.selected(),
                hiddenCheckbox.selected()
        );
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries());
        JsonObject display = base.getAsJsonObject("display").deepCopy();
        JsonObject encoded = DisplayInfo.CODEC.encodeStart(ops, updatedDisplay).getOrThrow().getAsJsonObject();
        // Preserve rich components, icon properties, and unknown display fields on a normal save.
        DisplayInfo sourceDisplay = DisplayInfo.CODEC.parse(ops, display).getOrThrow();
        if (!titleBox.getValue().equals(sourceDisplay.getTitle().getString())) display.add("title", encoded.get("title"));
        if (!descriptionBox.getValue().equals(sourceDisplay.getDescription().getString())) display.add("description", encoded.get("description"));
        if (!DisplayInfo.CODEC.encodeStart(ops, sourceDisplay).getOrThrow().getAsJsonObject().get("icon").equals(encoded.get("icon"))) display.add("icon", encoded.get("icon"));
        if (old.getType() != sourceDisplay.getType()) display.add("frame", encoded.get("frame"));
        if (showToastCheckbox.selected() != sourceDisplay.shouldShowToast()) display.addProperty("show_toast", showToastCheckbox.selected());
        if (announceChatCheckbox.selected() != sourceDisplay.shouldAnnounceChat()) display.addProperty("announce_to_chat", announceChatCheckbox.selected());
        if (hiddenCheckbox.selected() != sourceDisplay.isHidden()) display.addProperty("hidden", hiddenCheckbox.selected());
        updated.add("display", display);

        JsonObject criteriaJson = new JsonObject();
        for (Map.Entry<String, JsonObject> e : criteriaEntries.entrySet()) {
            criteriaJson.add(e.getKey(), e.getValue());
        }
        updated.add("criteria", criteriaJson);

        try {
            JsonArray requirements = AdvancementDocument.requirements(base, criteriaEntries.keySet(), requirementModeButton.getValue());
            if (requirements != null) updated.add("requirements", requirements);
        } catch (RuntimeException error) {
            setStatus(error.getMessage());
            return false;
        }

        JsonObject rewards;
        try {
            rewards = buildRewardsJson();
        } catch (RuntimeException e) {
            setStatus("Invalid reward: " + e.getMessage());
            return false;
        }
        if (base.has("rewards") || (rewards.size() != 0)) updated.add("rewards", rewards);
        if (base.has("codex_claimable") || claimableRewardsCheckbox.selected()) updated.addProperty("codex_claimable", claimableRewardsCheckbox.selected());

        rememberUndo(id, base, updated);
        if (!updated.equals(base) || dirty.containsKey(id)) dirty.put(id, updated);
        selectedEditWidget.setDisplay(updatedDisplay);
        // Without this, the hover's reward/required chips would only refresh on the next full tree
        // rebuild (rebuildEditTree, e.g. after Save) - both overrides are otherwise only set there.
        selectedEditWidget.applyRewardsOverride(rewards);
        List<Identifier> requiredItems = new ArrayList<>();
        for (JsonObject criterion : criteriaEntries.values()) {
            AdvancementPackIO.extractCriterionItemId(criterion).ifPresent(requiredItems::add);
        }
        selectedEditWidget.applyRequiredItemsOverride(requiredItems);
        selectedEditWidget.applyRequiredHintsOverride(lookupEditRequiredHints(id));
        // The tab strip's own icon/title are captured once at tab construction, not re-derived
        // from the root widget's display on every render - refresh them explicitly here or an
        // edited tab root's title/icon never shows up in the tab strip.
        if (selectedEditTab != null && selectedEditWidget == selectedEditTab.getRoot()) {
            selectedEditTab.refreshDisplay(updatedDisplay);
        }
        setStatus("Edit applied - not saved yet.");
        return true;
    }

    private void saveEdits() {
        if (savePending) {
            // Do not discard an edit made while the expensive datapack reload is in flight. One
            // follow-up save is enough: it serializes the newest dirty state after the current
            // acknowledgement arrives, no matter how often the button was clicked.
            saveQueued = true;
            setStatus("Save queued - it will run after the current reload.");
            return;
        }
        if (editMode && selectedEditWidget != null && titleBox != null && !applyEdit()) return;
        if (dirty.isEmpty()) {
            setStatus("Nothing to save - apply an edit first.");
            return;
        }
        // Vanilla's own Advancement.CODEC hard-rejects zero criteria ("Advancement criteria cannot
        // be empty") - previously that only surfaced as a server-log error well after the fact,
        // with the client just showing "saved N, failed M" and no indication which id or why. Catch
        // it here, before anything reaches the network, so a broken entry can never even be sent.
        List<Identifier> emptyCriteria = new ArrayList<>();
        for (Map.Entry<Identifier, JsonObject> e : dirty.entrySet()) {
            JsonObject json = e.getValue();
            boolean hasCriteria = json.has("criteria") && json.get("criteria").isJsonObject()
                    && (json.getAsJsonObject("criteria").size() != 0);
            if (!hasCriteria) {
                emptyCriteria.add(e.getKey());
            }
        }
        if (!emptyCriteria.isEmpty()) {
            setStatus("Can't save - these need at least one criterion (vanilla rejects advancements "
                    + "with none): " + emptyCriteria);
            return;
        }
        List<RawAdvancement> toSave = new ArrayList<>();
        for (Map.Entry<Identifier, JsonObject> e : dirty.entrySet()) {
            toSave.add(new RawAdvancement(e.getKey(), GSON.toJson(e.getValue())));
        }
        ClientEditorRequests.expectSave(this);
        NetworkManager.sendToServer(new SaveAdvancementTreePayload(toSave));
        savePending = true;
        pendingSaveEntries.clear();
        for (Map.Entry<Identifier, JsonObject> entry : dirty.entrySet()) {
            pendingSaveEntries.put(entry.getKey(), entry.getValue().deepCopy());
        }
        setStatus("Sent " + dirty.size() + " edit(s) to the server.");
    }

    /** Server confirmation arrives after its datapack reload, not merely after packet receipt. */
    public void onSaveConfirmed(int saved, int failed) {
        savePending = false;
        if (failed == 0) {
            // Only remove the exact JSON that was in the packet. If the user applied a newer
            // change while that packet was reloading, keep it dirty for the queued save.
            for (Map.Entry<Identifier, JsonObject> entry : pendingSaveEntries.entrySet()) {
                if (entry.getValue().equals(dirty.get(entry.getKey()))) dirty.remove(entry.getKey());
            }
            pendingSaveEntries.clear();
            setStatus("Saved " + saved + " advancement(s).");
            if (editMode) ClientEditorRequests.requestSnapshot(this);
        } else {
            setStatus("Saved " + saved + ", failed " + failed + ".");
        }
        if (saveQueued && !dirty.isEmpty()) {
            saveQueued = false;
            saveEdits();
        } else {
            saveQueued = false;
        }
    }

    /**
     * Two clicks to confirm: the first arms {@link #pendingDeleteId} and reports how many
     * descendants would go with it (deleting a node deletes its whole subtree, since a child
     * pointing at a now-missing parent id would otherwise just silently vanish on next load - see
     * the "Couldn't load advancements" issue this same class of bug caused earlier). The second
     * click on the same node actually removes it - locally immediately (so the tree updates without
     * waiting on the server) and via {@link DeleteAdvancementsPayload} so the change persists.
     */
    private void deleteSelected() {
        if (selectedEditWidget == null) {
            setStatus("Select an advancement first.");
            return;
        }
        Identifier id = selectedEditWidget.node().holder().id();
        List<Identifier> toDelete = collectWithDescendants(selectedEditWidget.node());

        if (!id.equals(pendingDeleteId)) {
            pendingDeleteId = id;
            int descendants = toDelete.size() - 1;
            setStatus(descendants > 0
                    ? "Delete " + id + " and " + descendants + " descendant(s)? Click Delete again to confirm."
                    : "Delete " + id + "? Click Delete again to confirm.");
            return;
        }

        pendingDeleteId = null;
        for (Identifier deletedId : toDelete) {
            dirty.remove(deletedId);
            rawFullJson.remove(deletedId);
        }
        NetworkManager.sendToServer(new DeleteAdvancementsPayload(toDelete));
        rebuildEditTree();
        this.init(this.width, this.height);
        setStatus("Deleted " + toDelete.size() + " advancement(s) - sent to the server.");
    }

    private List<Identifier> collectWithDescendants(AdvancementNode node) {
        List<Identifier> ids = new ArrayList<>();
        ids.add(node.holder().id());
        for (AdvancementNode child : node.children()) {
            ids.addAll(collectWithDescendants(child));
        }
        return ids;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (pressedWidget != null && event.button() == 0) {
            if (Math.hypot(event.x() - pressX, event.y() - pressY) < 5) return true;
            pressedWidget = null;
        }
        CodexAdvancementTab selectedTab = activeSelectedTab();
        if (event.button() == 0 && customLayoutMode && layoutDragWidget != null) {
            layoutDragRawX += (float) (dragX / zoom);
            layoutDragRawY += (float) (dragY / zoom);
            int snappedX = Math.round(layoutDragRawX / 7.0F) * 7;
            int snappedY = Math.round(layoutDragRawY / 7.0F) * 7;
            layoutDragWidget.setPosition(snappedX, snappedY);
            Identifier id = layoutDragWidget.node().holder().id();
            JsonObject updated = baseJsonFor(id).deepCopy();
            JsonObject point = new JsonObject();
            point.addProperty("x", snappedX);
            point.addProperty("y", snappedY);
            updated.add("codex_layout", point);
            dirty.put(id, updated);
            return true;
        }
        if (event.button() == 0 && selectedTab != null) {
            isScrolling = true;
            selectedTab.scroll(-dragX / zoom, -dragY / zoom, contentWidth(), contentHeight(), zoom);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0 && pressedWidget != null) {
            var hit = pressedWidget; pressedWidget = null;
            if (!holdSent && (!CodexClientSettings.holdAccept || System.nanoTime() - pressedAt < 250_000_000L || !ClientQuests.canAccept(ClientQuests.info(hit.node().holder().id()))) && Math.hypot(event.x() - pressX, event.y() - pressY) < 5) minecraft.setScreen(new QuestDetailsScreen(this, hit.node().holder().id(), hit.display().getTitle(), hit.display().getDescription()));
            return true;
        }
        isScrolling = false;
        if (event.button() == 0) layoutDragWidget = null;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isMouseOverPanel(mouseX, mouseY)) {
            scrollPanel(-scrollY * 16.0);
            return true;
        }
        CodexAdvancementTab selectedTab = activeSelectedTab();
        if (selectedTab != null) {
            selectedTab.scroll(scrollX * 16.0, scrollY * 16.0, contentWidth(), contentHeight(), zoom);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    //? if <26.3
    @Override
    public void onAddAdvancementRoot(AdvancementNode node) {
        CodexAdvancementTab tab = CodexAdvancementTab.create(node);
        if (tab == null) {
            return;
        }
        viewTabs.put(node.holder().id(), tab);
        tab.applyLayout(ClientAdvancementLayout.positions());
    }

    /** Called when the server's layout packet arrives after this screen was already opened. */
    public void applySharedLayout() {
        for (CodexAdvancementTab tab : viewTabs.values()) {
            tab.applyLayout(ClientAdvancementLayout.positions());
        }
    }

    //? if <26.3
    @Override
    public void onRemoveAdvancementRoot(AdvancementNode node) {
        viewTabs.remove(node.holder().id());
    }

    //? if <26.3
    @Override
    public void onAddAdvancementTask(AdvancementNode node) {
        CodexAdvancementTab tab = getViewTab(node);
        if (tab != null) {
            tab.addAdvancement(node);
            tab.applyLayout(ClientAdvancementLayout.positions());
        }
    }

    //? if <26.3
    @Override
    public void onRemoveAdvancementTask(AdvancementNode node) {
        // A server datapack reload removes the old task graph and then adds the replacement graph.
        // Keeping the old tab here made normal view show pre-save titles/rewards until reopening
        // the world, even though edit mode held the newly-saved raw snapshot. Drop the clone and
        // let the ensuing add callbacks rebuild it from the authoritative new nodes.
        viewTabs.clear();
        selectedViewTab = null;
    }

    //? if <26.3
    @Override
    public void onUpdateAdvancementProgress(AdvancementNode node, AdvancementProgress progress) {
        CodexAdvancementTab tab = getViewTab(node);
        if (tab != null) {
            CodexAdvancementWidget widget = tab.findById(node.holder().id());
            if (widget != null) {
                widget.setProgress(progress);
            }
        }
        // Edit mode's progress display was previously a one-time snapshot taken from viewTabs at
        // rebuildEditTree() time (see applyProgress) - correct only if every advancement's progress
        // had already arrived by then, which isn't guaranteed (progress can land on its own
        // schedule, independent of the tree-structure sync). Updating the matching edit-tab widget
        // here too, on every live update, keeps it correct regardless of timing.
        Identifier id = node.holder().id();
        for (CodexAdvancementTab editTab : editTabs.values()) {
            CodexAdvancementWidget editWidget = editTab.findById(id);
            if (editWidget != null) {
                editWidget.setProgress(progress);
            }
        }
    }

    //? if >=26.3 {
    /*@Override
    public void onAdvancementsUpdated() {
        Identifier selected = selectedViewTab == null ? null : selectedViewTab.getRootNode().holder().id();
        Identifier hovered = lastHoveredWidget == null ? null : lastHoveredWidget.node().holder().id();
        var previous = new LinkedHashMap<>(viewTabs); viewTabs.clear();
        var tree = advancements.tree();
        for (var root : tree.roots()) onAddAdvancementRoot(root);
        for (var node : tree.tasks()) { var tab=getViewTab(node); if(tab!=null)tab.addAdvancement(node); }
        for (var entry : advancements.progress().entrySet()) {
            var node=tree.get(entry.getKey()); if(node!=null)onUpdateAdvancementProgress(node,entry.getValue());
        }
        for (var entry : viewTabs.entrySet()) {
            entry.getValue().applyLayout(ClientAdvancementLayout.positions());
            var prior=previous.get(entry.getKey()); if(prior!=null)entry.getValue().copyViewportFrom(prior);
        }
        selectedViewTab=selected==null?null:viewTabs.get(selected);
        var hoveredNode=hovered==null?null:tree.get(hovered);
        lastHoveredTab=hoveredNode==null?null:getViewTab(hoveredNode);
        lastHoveredWidget=lastHoveredTab==null?null:lastHoveredTab.findById(hovered);
    }
    *///?}

    @Override
    public void onSelectedTabChanged(AdvancementHolder holder) {
        // Vanilla passes null here when nothing is selected (e.g. no advancements loaded yet on a
        // fresh world) - viewTabs.get(holder) previously tolerated that silently (Map.get(null) is
        // legal), but calling .id() on it directly, needed once this map switched to Identifier
        // keys, does not.
        selectedViewTab = holder != null ? viewTabs.get(holder.id()) : null;
    }

    @Override
    public void onAdvancementsCleared() {
        viewTabs.clear();
        selectedViewTab = null;
    }

    private CodexAdvancementTab getViewTab(AdvancementNode node) {
        AdvancementNode root = AdvancementNode.getRoot(node);
        return viewTabs.get(root.holder().id());
    }
}
