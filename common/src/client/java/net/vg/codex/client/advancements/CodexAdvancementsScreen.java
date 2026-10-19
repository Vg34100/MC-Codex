package net.vg.codex.client.advancements;

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
    private static final int VALUE_HEIGHT = 36;
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
    private static final int ICON_CELL = 18;
    private static final int ICON_TITLE_Y = 10;
    private static final int ICON_SEARCH_Y = 24;
    private static final int ICON_GRID_TOP_Y = 48;

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
    private enum CriterionType {
        IMPOSSIBLE("minecraft:impossible", "Impossible", false, null),
        TICK("minecraft:tick", "Always (tick)", false, null),
        ITEM_OBTAINED("minecraft:inventory_changed", "Item Obtained", true, "minecraft:item_id"),
        PLAYER_KILLED_ENTITY("minecraft:player_killed_entity", "Player Killed Entity", true, "minecraft:entity_type"),
        ENTITY_KILLED_PLAYER("minecraft:entity_killed_player", "Entity Killed Player", true, "minecraft:entity_type"),
        CHANGED_DIMENSION("minecraft:changed_dimension", "Changed Dimension", true, "minecraft:the_end"),
        // "minecraft:location" covers both - disambiguated by shape, not trigger id, in fromJson().
        LOCATION_STRUCTURE("minecraft:location", "Reached Location (Structure)", true, "minecraft:stronghold"),
        LOCATION_BIOME("minecraft:location", "Reached Location (Biome)", true, "minecraft:jungle"),
        ITEM_USED_ON_BLOCK("minecraft:item_used_on_block", "Item Used On Block (block;item)", true, "minecraft:lodestone;minecraft:compass"),
        RECIPE_UNLOCKED("minecraft:recipe_unlocked", "Recipe Unlocked", true, "minecraft:diamond_pickaxe"),
        SUBMIT_ITEMS("minecraft:impossible", "Submit Items", true, "minecraft:iron_ingot x16; minecraft:gold_ingot x8"),
        STATISTIC("minecraft:impossible", "Statistic Threshold", true, "custom|minecraft:animals_bred >= 100"),
        CUSTOM(null, "Custom (raw JSON)", true, "{}");

        final String triggerId;
        final String label;
        final boolean hasValue;
        final String exampleValue;

        CriterionType(String triggerId, String label, boolean hasValue, String exampleValue) {
            this.triggerId = triggerId;
            this.label = label;
            this.hasValue = hasValue;
            this.exampleValue = exampleValue;
        }

        /** Reads the shape of {@code json}, not just its trigger id, since location's two curated
         * sub-types ({@link #LOCATION_STRUCTURE}, {@link #LOCATION_BIOME}) share one trigger. */
        static CriterionType fromJson(JsonObject json) {
            if (json.has("codex_submission")) {
                return SUBMIT_ITEMS;
            }
            if (json.has("codex_statistic")) {
                return STATISTIC;
            }
            if (!json.has("trigger")) {
                return CUSTOM;
            }
            String triggerId = json.get("trigger").getAsString();
            if (LOCATION_STRUCTURE.triggerId.equals(triggerId)) {
                JsonObject location = locationPredicate(json, "player");
                return location != null && location.has("biomes") ? LOCATION_BIOME : LOCATION_STRUCTURE;
            }
            for (CriterionType type : values()) {
                if (triggerId.equals(type.triggerId)) {
                    return type;
                }
            }
            return CUSTOM;
        }
    }

    /**
     * Digs out the {@code conditions.<key>[0].predicate.location} object vanilla's
     * {@code minecraft:entity_properties} condition wrapper nests location checks in (see
     * {@link #wrapEntityProperties}), or {@code null} if the shape doesn't match.
     */
    private static JsonObject locationPredicate(JsonObject criterionJson, String key) {
        if (!criterionJson.has("conditions")) {
            return null;
        }
        JsonObject conditions = criterionJson.getAsJsonObject("conditions");
        if (!conditions.has(key) || !conditions.get(key).isJsonArray() || conditions.getAsJsonArray(key).isEmpty()) {
            return null;
        }
        JsonElement first = conditions.getAsJsonArray(key).get(0);
        if (!first.isJsonObject() || !first.getAsJsonObject().has("predicate")) {
            return null;
        }
        JsonObject predicate = first.getAsJsonObject().getAsJsonObject("predicate");
        return predicate.has("location") ? predicate.getAsJsonObject("location") : null;
    }

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
     * group - {@link AdvancementRequirements#anyOf}). Anything more exotic falls back to ALL.
     */
    private enum RequirementMode {
        ALL("Require ALL criteria (AND)"),
        ANY("Require ANY criterion (OR)");

        final String label;

        RequirementMode(String label) {
            this.label = label;
        }

        static RequirementMode fromJson(JsonArray requirements, int criteriaCount) {
            if (requirements == null || requirements.isEmpty() || criteriaCount <= 1) {
                return ALL;
            }
            return requirements.size() == 1 && requirements.get(0).isJsonArray()
                    && requirements.get(0).getAsJsonArray().size() == criteriaCount
                    ? ANY : ALL;
        }
    }

    /**
     * A single reward-list entry's kind. EXPERIENCE and FUNCTION are singletons in vanilla (a bare
     * int / a single id, not an array) - {@code addReward}/{@code applyReward} refuse to create a
     * second entry of either. ITEM isn't a real vanilla reward field at all; it's carried through
     * an auto-generated loot table (see {@code AdvancementPackIO}) that "loot" ends up pointing at.
     */
    private enum RewardType {
        EXPERIENCE("Experience"),
        ITEM("Item"),
        RECIPE("Recipe"),
        LOOT_TABLE("Loot Table"),
        FUNCTION("Function");

        final String label;

        RewardType(String label) {
            this.label = label;
        }
    }

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
    private CodexAdvancementWidget lastHoveredWidget;
    private CodexAdvancementTab lastHoveredTab;

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
    private final Map<Identifier, JsonObject> dirty = new LinkedHashMap<>();
    // Real, server-authoritative progress for every advancement (see enterEditMode) - unlike
    // cross-referencing view mode's own live data, this covers advancements the player has never
    // actually discovered in survival, which view mode simply has no data for at all.
    private final Map<Identifier, AdvancementProgress> editProgressSnapshot = new LinkedHashMap<>();
    private EditBox titleBox;
    private MultiLineEditBox descriptionBox;
    private Checkbox showToastCheckbox;
    private Checkbox announceChatCheckbox;
    private Checkbox hiddenCheckbox;
    private Checkbox claimableRewardsCheckbox;
    private CycleButton<RequirementMode> requirementModeButton;
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

    // ---- Item picker (icon selection) ----
    private static List<Item> allItemsCache;
    private boolean iconPickerOpen;
    private Consumer<Identifier> iconPickerCallback;
    private EditBox iconSearchBox;
    private Button iconPickerCancelButton;
    private final List<Item> iconSearchResults = new ArrayList<>();
    private int iconGridScroll;
    private Item iconGridHovered;
    private String iconSearchText = "";

    // Generic identifier picker for registries that do not have a useful ItemStack grid (entities,
    // dimensions, structures, recipes, ...). It deliberately reuses the same modal geometry.
    private boolean identifierPickerOpen;
    private String identifierPickerTitle;
    private Consumer<Identifier> identifierPickerCallback;
    private EditBox identifierSearchBox;
    private Button identifierPickerCancelButton;
    private final List<Button> identifierPickerButtons = new ArrayList<>();
    private final List<Identifier> identifierPickerEntries = new ArrayList<>();
    private final List<Identifier> identifierPickerResults = new ArrayList<>();

    // ---- Party actions ----
    private boolean partyPanelOpen;
    private Button partyAcceptButton;
    private Button partyLeaveButton;
    private Button partyCloseButton;
    private final List<Button> partyInviteButtons = new ArrayList<>();

    // Criteria editor: `criteriaEntries` is the working copy for whichever advancement is
    // currently selected (raw per-criterion JSON, name -> object), edited via the list + form
    // below rather than one big hand-written JSON blob.
    private final Map<String, JsonObject> criteriaEntries = new LinkedHashMap<>();
    private final List<String> currentCriteriaNames = new ArrayList<>();
    private String selectedCriterionName;
    private final List<Button> criteriaListButtons = new ArrayList<>();
    // Which widget `criteriaEntries` currently holds a working copy for - re-init (e.g. after
    // adding/removing a criterion, so the row list can grow/shrink) must NOT reload from disk as
    // long as it's still the same widget, or every in-progress, not-yet-applied criteria edit
    // would be silently discarded the moment the row count changes.
    private CodexAdvancementWidget criteriaEntriesSelection;
    private Button addCriterionButton;
    private EditBox criterionNameBox;
    private CycleButton<CriterionType> criterionTypeButton;
    private CycleButton<StatisticCategory> statisticCategoryButton;
    private Button pickCriterionItemButton;
    private MultiLineEditBox criterionValueBox;
    private Button applyCriterionButton;
    private Button removeCriterionButton;

    private boolean criterionDialogOpen;
    private String criterionDialogOriginalName;
    private EditBox criterionDialogName;
    private CycleButton<CriterionType> criterionDialogType;
    private CycleButton<StatisticCategory> criterionDialogStatisticCategory;
    private MultiLineEditBox criterionDialogValue;
    private Button criterionDialogSave;
    private Button criterionDialogCancel;
    private Button criterionDialogPick;
    private String criterionDialogDraftName;
    private String criterionDialogDraftValue;
    private CriterionType criterionDialogDraftType;

    // Reward editor: same list-plus-inline-editor pattern as criteria above, but entries aren't
    // named - selection is by index into `rewardEntries`, which is safe here because every mutation
    // (add/apply/remove) triggers a full re-init that rebuilds rows 1:1 with the list, and removal
    // always clears the selection rather than trying to track a shifted index.
    private final List<JsonObject> rewardEntries = new ArrayList<>();
    private final List<Button> rewardListButtons = new ArrayList<>();
    private Integer selectedRewardIndex;
    private Button addRewardButton;
    private CycleButton<RewardType> rewardTypeButton;
    private EditBox rewardValueBox;
    private EditBox rewardCountBox;
    private Button pickRewardItemButton;
    private Button applyRewardButton;
    private Button removeRewardButton;

    // Reward add/edit uses a single modal rather than leaving a permanently-expanded form in the
    // details panel. The old inline controls remain as a compatibility fallback while this dialog
    // owns the normal Add/row-click path.
    private boolean rewardDialogOpen;
    private Integer rewardDialogIndex;
    private CycleButton<RewardType> rewardDialogType;
    private EditBox rewardDialogValue;
    private EditBox rewardDialogCount;
    private Button rewardDialogSave;
    private Button rewardDialogCancel;
    private Button rewardDialogPick;
    private RewardType rewardDialogDraftType;
    private String rewardDialogDraftValue;
    private String rewardDialogDraftCount;

    // ---- Tab/node creation and reparenting (stage 7) ----
    private CreationMode creationMode = CreationMode.NONE;
    // Node the "New Advancement" form will parent the created node under.
    private CodexAdvancementWidget pendingNodeParent;
    // Set by shift+right-click; a following shift+left-click on another node reparents it here.
    private CodexAdvancementWidget linkSource;
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
        addRenderableWidget(Button.builder(Component.literal(compactHeaderActions ? "P" : "Party"), b -> openPartyPanel())
                .bounds(claimsX, top + 2, partyWidth, 14)
                .build());
        addRenderableWidget(Button.builder(Component.literal(compactHeaderActions ? "R(" + availableClaims + ")" : "Rewards (" + availableClaims + ")"), b -> openClaimRewards())
                .bounds(claimsX + partyWidth + 2, top + 2, claimsWidth, 14)
                .build());
        addRenderableWidget(Button.builder(Component.literal(compactHeaderActions ? "All" : "Claim All"), b ->
                        NetworkManager.sendToServer(new ClaimAllAdvancementRewardsPayload()))
                .bounds(left + windowWidth - claimAllWidth - 4, top + 2, claimAllWidth, 14)
                .build());
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
            initCreationPanel(left, windowWidth, top);
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
                selectedCriterionName = null;
                selectedRewardIndex = null;
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
                    .selected(currentDisplay == null || currentDisplay.shouldShowToast())
                    .build());
            announceChatCheckbox = panel(Checkbox.builder(Component.literal("Chat"), this.font)
                    .pos(panelLeft + checkWidth + (compactChecks ? 6 : 0), y)
                    .maxWidth(checkWidth)
                    .selected(currentDisplay == null || currentDisplay.shouldAnnounceChat())
                    .build());
            if (compactChecks) {
                y += 18;
            }
            hiddenCheckbox = panel(Checkbox.builder(Component.literal("Hidden"), this.font)
                    .pos(compactChecks ? panelLeft : panelLeft + checkWidth * 2, y)
                    .maxWidth(checkWidth)
                    .selected(currentDisplay != null && currentDisplay.isHidden())
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
                    .selected(isCurrentClaimable())
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

            addRewardButton = panel(Button.builder(Component.literal("+ Add Reward"), b -> openRewardDialog(null))
                    .bounds(panelLeft, y, panelWidth, 16)
                    .build());
            y += 20;

            int rewardTypeWidth = panelWidth - 70;
            rewardTypeButton = panel(CycleButton.builder((RewardType t) -> Component.literal(t.label), RewardType.EXPERIENCE)
                    .withValues(RewardType.values())
                    .create(panelLeft, y, rewardTypeWidth, 20, Component.literal("Reward type"), (button, value) ->
                            updateRewardFieldsVisibility(value)));
            pickRewardItemButton = panel(Button.builder(Component.literal("Pick"), b -> pickRewardItem())
                    .bounds(panelLeft + rewardTypeWidth + 6, y, panelWidth - rewardTypeWidth - 6, 20)
                    .build());
            y += 24;

            rewardValueBox = panel(new EditBox(this.font, panelLeft, y, panelWidth, 16, Component.literal("Reward value")));
            rewardValueBox.setMaxLength(256);
            y += 20;

            panel(new StringWidget(panelLeft, y, panelWidth, 8, Component.literal("Count (item rewards only)"), this.font));
            y += 10;
            rewardCountBox = panel(new EditBox(this.font, panelLeft, y, panelWidth, 16, Component.literal("Count")));
            rewardCountBox.setMaxLength(8);
            y += 20;

            int rewardHalfWidth = (panelWidth - 10) / 2;
            applyRewardButton = panel(Button.builder(Component.literal("Apply Reward"), b -> applyReward())
                    .bounds(panelLeft, y, rewardHalfWidth, 20)
                    .build());
            removeRewardButton = panel(Button.builder(Component.literal("Remove"), b -> removeReward())
                    .bounds(panelLeft + rewardHalfWidth + 10, y, rewardHalfWidth, 20)
                    .build());
            y += 26;

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

            addCriterionButton = panel(Button.builder(Component.literal("+ Add Criterion"), b -> openCriterionDialog(null))
                    .bounds(panelLeft, y, panelWidth, 16)
                    .build());
            y += 20;

            criterionNameBox = panel(new EditBox(this.font, panelLeft, y, panelWidth, 16, Component.literal("Criterion name")));
            criterionNameBox.setMaxLength(256);
            y += 20;

            int criterionTypeWidth = panelWidth - 50;
            criterionTypeButton = panel(CycleButton.builder((CriterionType t) -> Component.literal(t.label), CriterionType.IMPOSSIBLE)
                    .withValues(CriterionType.values())
                    .create(panelLeft, y, criterionTypeWidth, 20, Component.literal("Type"), (button, value) -> {
                        criterionValueBox.active = value.hasValue;
                        updateCriterionFieldsVisibility(value);
                        if (value.hasValue && criterionValueBox.getValue().isEmpty() && value.exampleValue != null) {
                            criterionValueBox.setValue(value.exampleValue);
                        }
                    }));
            pickCriterionItemButton = panel(Button.builder(Component.literal("Pick"), b -> pickCriterionItem())
                    .bounds(panelLeft + criterionTypeWidth + 6, y, panelWidth - criterionTypeWidth - 6, 20)
                    .build());
            y += 24;

            statisticCategoryButton = panel(CycleButton.builder((StatisticCategory category) ->
                            Component.literal("Statistic: " + category.label), StatisticCategory.CUSTOM)
                    .withValues(StatisticCategory.values())
                    .create(panelLeft, y, panelWidth, 16, Component.literal("Statistic category")));
            y += 20;

            // Multi-line (unlike EditBox, no default character cap) so long values - a raw custom
            // JSON blob especially - don't silently get truncated at EditBox's 32-char default.
            criterionValueBox = panel(MultiLineEditBox.builder()
                    .setX(panelLeft)
                    .setY(y)
                    .setPlaceholder(Component.literal("Value"))
                    .build(this.font, panelWidth, VALUE_HEIGHT, Component.literal("Value")));
            y += VALUE_HEIGHT + 4;

            int halfWidth = (panelWidth - 10) / 2;
            applyCriterionButton = panel(Button.builder(Component.literal("Apply Criterion"), b -> applyCriterion())
                    .bounds(panelLeft, y, halfWidth, 20)
                    .build());
            removeCriterionButton = panel(Button.builder(Component.literal("Remove"), b -> removeCriterion())
                    .bounds(panelLeft + halfWidth + 10, y, halfWidth, 20)
                    .build());
            y += 26;

            requirementModeButton = panel(CycleButton.builder((RequirementMode m) -> Component.literal(m.label), currentRequirementMode())
                    .withValues(RequirementMode.values())
                    .create(panelLeft, y, panelWidth, 20, Component.literal("Requirements")));
            y += 24;
            panelContentBottom = y;

            if (selectedEditWidget != null) {
                titleBox.setValue(selectedEditWidget.display().getTitle().getString());
                descriptionBox.setValue(selectedEditWidget.display().getDescription().getString());
            }
            refreshCriteriaList();
            // Every init() rebuilds these widgets from scratch, so a still-valid selection is
            // reapplied to the new instances rather than unconditionally wiped - otherwise any
            // re-init triggered by something else entirely (e.g. opening the item picker for a
            // reward's item id) would silently discard whatever criterion/reward was being edited.
            if (selectedCriterionName != null && criteriaEntries.containsKey(selectedCriterionName)) {
                selectCriterion(selectedCriterionName);
            } else {
                clearCriterionEditor();
            }
            refreshRewardList();
            if (selectedRewardIndex != null && selectedRewardIndex < rewardEntries.size()) {
                selectReward(selectedRewardIndex);
            } else {
                clearRewardEditor();
            }
            applyPanelScroll();

            // Pinned footer - outside the scrollable area (not wrapped via panel()) so these stay
            // reachable without scrolling all the way down.
            int footerTop = panelFooterTop();
            int fullPanelWidth = panelWidth();
            int thirdWidth = (fullPanelWidth - 20) / 3;
            applyEditButton = addRenderableWidget(Button.builder(Component.literal("Apply Edit"), b -> applyEdit())
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

        if (iconPickerOpen) {
            initIconPicker();
        } else if (identifierPickerOpen) {
            initIdentifierPicker();
        } else if (partyPanelOpen) {
            initPartyPanel();
        } else if (rewardDialogOpen) {
            initRewardDialog();
        } else if (criterionDialogOpen) {
            initCriterionDialog();
        }
    }

    /** Replaces the normal edit form with a small "new tab"/"new advancement" form. */
    private void initCreationPanel(int left, int windowWidth, int top) {
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
        newIdBox.setHint(Component.literal("e.g. my_advancement"));
        y += 20;

        int iconButtonSize = 20;
        newIconBox = panel(new EditBox(this.font, panelLeft + iconButtonSize + 4, y, panelWidth - iconButtonSize - 4, 16, Component.literal("Icon item id")));
        newIconBox.setMaxLength(256);
        newIconBox.setValue("minecraft:stone");
        panel(new IconPreviewButton(panelLeft, y - 2, iconButtonSize,
                () -> resolveIconStack(newIconBox.getValue()),
                id -> newIconBox.setValue(id.toString())));
        y += 20;

        if (isTab) {
            backgroundOptionButton = panel(CycleButton.builder((BackgroundOption o) -> Component.literal(o.label), BackgroundOption.STONE)
                    .withValues(BackgroundOption.values())
                    .create(panelLeft, y, panelWidth, 20, Component.literal("Background"), (button, value) ->
                            newBackgroundBox.active = value == BackgroundOption.CUSTOM));
            y += 24;

            newBackgroundBox = panel(new EditBox(this.font, panelLeft, y, panelWidth, 16, Component.literal("Custom background id")));
            newBackgroundBox.setMaxLength(256);
            newBackgroundBox.active = false;
            y += 20;
        }
        y += 4;
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

    private void initIconPicker() {
        int left = iconPickerLeft();
        int top = iconPickerTop();
        // Registered via addWidget() (click/keyboard only, no automatic rendering) rather than
        // addRenderableWidget() - the picker's own background/border is drawn later in
        // extractIconPicker() (after super.extractRenderState() already ran), so an
        // auto-rendered widget would get drawn *before* that background and then be painted over
        // by it, leaving it visible but seemingly frozen (typed text never appeared to update,
        // even though the box's value was in fact updating - it just wasn't visibly redrawn on
        // top of the modal). Rendered manually, after the background, in extractIconPicker instead.
        iconSearchBox = addWidget(new EditBox(this.font, left + 24, top + ICON_SEARCH_Y, ICON_PICKER_WIDTH - 34, 16, Component.literal("Search")));
        iconSearchBox.setHint(Component.literal("Search items..."));
        iconSearchBox.setValue(iconSearchText);
        iconSearchBox.setResponder(this::refreshIconSearch);
        iconPickerCancelButton = addWidget(Button.builder(Component.literal("Cancel"), b -> closeIconPicker())
                .bounds(left + 10, top + ICON_PICKER_HEIGHT - 26, ICON_PICKER_WIDTH - 20, 20)
                .build());
        if (iconSearchResults.isEmpty()) {
            refreshIconSearch(iconSearchText);
        }
    }

    /** Opens the picker; {@code callback} is invoked with the chosen item's id and the picker
     * closes itself. Used both by the creation forms' icon field and the normal edit panel's. */
    private void openIconPicker(Consumer<Identifier> callback) {
        iconPickerOpen = true;
        iconPickerCallback = callback;
        iconSearchText = "";
        iconGridScroll = 0;
        refreshIconSearch("");
        this.init(this.width, this.height);
    }

    private void closeIconPicker() {
        iconPickerOpen = false;
        iconPickerCallback = null;
        this.init(this.width, this.height);
    }

    private void refreshIconSearch(String query) {
        iconSearchText = query;
        String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
        iconSearchResults.clear();
        for (Item item : allItems()) {
            if (needle.isEmpty()) {
                iconSearchResults.add(item);
                continue;
            }
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            String displayName = item.getName(item.getDefaultInstance()).getString().toLowerCase(java.util.Locale.ROOT);
            if (id.getPath().contains(needle) || displayName.contains(needle)) {
                iconSearchResults.add(item);
            }
        }
        iconGridScroll = 0;
    }

    private static List<Item> allItems() {
        if (allItemsCache == null) {
            List<Item> items = new ArrayList<>();
            Identifier air = Identifier.withDefaultNamespace("air");
            for (Item item : BuiltInRegistries.ITEM) {
                if (!BuiltInRegistries.ITEM.getKey(item).equals(air)) {
                    items.add(item);
                }
            }
            allItemsCache = items;
        }
        return allItemsCache;
    }

    private int iconPickerLeft() {
        return (this.width - ICON_PICKER_WIDTH) / 2;
    }

    private int iconPickerTop() {
        return (this.height - ICON_PICKER_HEIGHT) / 2;
    }

    /** Centers the grid within the picker's inner width instead of hugging the left edge - integer
     * division of available width by ICON_CELL almost never divides evenly, and the leftover slack
     * previously just sat unused on the right, making the grid look off-center. */
    private int iconGridLeft() {
        int available = ICON_PICKER_WIDTH - 20;
        int used = iconGridColumns() * ICON_CELL;
        return iconPickerLeft() + 10 + (available - used) / 2;
    }

    private int iconGridTop() {
        return iconPickerTop() + ICON_GRID_TOP_Y;
    }

    private int iconGridBottom() {
        return iconPickerTop() + ICON_PICKER_HEIGHT - 30;
    }

    private int iconGridColumns() {
        return (ICON_PICKER_WIDTH - 20) / ICON_CELL;
    }

    private Item iconGridItemAt(double mouseX, double mouseY) {
        int gridLeft = iconGridLeft();
        int gridTop = iconGridTop();
        int gridBottom = iconGridBottom();
        int columns = iconGridColumns();
        if (mouseX < gridLeft || mouseX >= gridLeft + columns * ICON_CELL || mouseY < gridTop || mouseY >= gridBottom) {
            return null;
        }
        int col = (int) ((mouseX - gridLeft) / ICON_CELL);
        int row = (int) ((mouseY - gridTop) / ICON_CELL) + iconGridScroll;
        int index = row * columns + col;
        return index >= 0 && index < iconSearchResults.size() ? iconSearchResults.get(index) : null;
    }

    /**
     * {@code blurBeforeThisStratum()} throws if a blur was already requested this frame - real
     * vanilla screens only ever have one blur source active at a time, but with both Fabric API's
     * and Architectury's screen-rendering mixins wrapping the same render call, extractRenderState
     * can end up invoked more than once per actual rendered frame (confirmed by a live crash: "Can
     * only blur once per frame" on a totally ordinary hover, not two blur sources overlapping on
     * purpose). Purely a cosmetic effect, so skip it rather than crash the game over it.
     */
    private void tryBlur(GuiGraphicsExtractor extractor) {
        try {
            extractor.blurBeforeThisStratum();
        } catch (IllegalStateException ignored) {
            // Already blurred this frame - fine, just skip the effect this time.
        }
    }

    /** Hand-drawn magnifying glass (a ring of edge/corner pixels plus a diagonal handle) - there's
     * no public vanilla sprite for the creative-search icon, so this is built from primitives. */
    private void drawMagnifyingGlass(GuiGraphicsExtractor extractor, int x, int y, int color) {
        extractor.horizontalLine(x + 2, x + 4, y, color);
        extractor.horizontalLine(x + 2, x + 4, y + 6, color);
        extractor.verticalLine(x, y + 2, y + 4, color);
        extractor.verticalLine(x + 6, y + 2, y + 4, color);
        extractor.fill(x + 1, y + 1, x + 2, y + 2, color);
        extractor.fill(x + 5, y + 1, x + 6, y + 2, color);
        extractor.fill(x + 1, y + 5, x + 2, y + 6, color);
        extractor.fill(x + 5, y + 5, x + 6, y + 6, color);
        extractor.fill(x + 7, y + 7, x + 9, y + 9, color);
    }

    private void extractIconPicker(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.nextStratum();
        tryBlur(extractor);
        extractor.fill(0, 0, this.width, this.height, 0xC0000000);

        int left = iconPickerLeft();
        int top = iconPickerTop();
        extractor.fill(left, top, left + ICON_PICKER_WIDTH, top + ICON_PICKER_HEIGHT, 0xFF2B2B2B);
        extractor.horizontalLine(left, left + ICON_PICKER_WIDTH - 1, top, 0xFF000000);
        extractor.horizontalLine(left, left + ICON_PICKER_WIDTH - 1, top + ICON_PICKER_HEIGHT - 1, 0xFF000000);
        extractor.verticalLine(left, top, top + ICON_PICKER_HEIGHT - 1, 0xFF000000);
        extractor.verticalLine(left + ICON_PICKER_WIDTH - 1, top, top + ICON_PICKER_HEIGHT - 1, 0xFF000000);
        extractor.text(this.font, "Pick an icon", left + 10, top + ICON_TITLE_Y, 0xFFFFFFFF, false);
        // Vertically centered against the 16-tall search box (top + ICON_SEARCH_Y), left of the
        // field's reserved 24px-from-`left` gap, so the box actually reads as a search bar.
        drawMagnifyingGlass(extractor, left + 10, top + ICON_SEARCH_Y + 3, 0xFFAAAAAA);
        // Rendered manually (not via addRenderableWidget) specifically so it draws after - not
        // before - the modal background above; see initIconPicker's comment for why.
        iconSearchBox.extractRenderState(extractor, mouseX, mouseY, partialTick);
        iconPickerCancelButton.extractRenderState(extractor, mouseX, mouseY, partialTick);

        int gridLeft = iconGridLeft();
        int gridTop = iconGridTop();
        int columns = iconGridColumns();
        int visibleRows = (iconGridBottom() - gridTop) / ICON_CELL;
        iconGridHovered = null;
        for (int row = 0; row < visibleRows; row++) {
            for (int col = 0; col < columns; col++) {
                int index = (row + iconGridScroll) * columns + col;
                if (index >= iconSearchResults.size()) {
                    continue;
                }
                Item item = iconSearchResults.get(index);
                int cellX = gridLeft + col * ICON_CELL;
                int cellY = gridTop + row * ICON_CELL;
                if (mouseX >= cellX && mouseX < cellX + ICON_CELL && mouseY >= cellY && mouseY < cellY + ICON_CELL) {
                    extractor.fill(cellX, cellY, cellX + ICON_CELL, cellY + ICON_CELL, 0x80FFFFFF);
                    iconGridHovered = item;
                }
                extractor.fakeItem(new ItemStack(item), cellX + 1, cellY + 1);
            }
        }
        if (iconGridHovered != null) {
            extractor.setComponentTooltipForNextFrame(this.font,
                    List.of(iconGridHovered.getName(iconGridHovered.getDefaultInstance())), mouseX, mouseY);
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

    /**
     * Called by AdvancementEditorClient when the full snapshot arrives while this screen is open.
     * Never decodes a full {@link Advancement}/{@code Criterion} client-side - only the
     * registry-safe {@link DisplayInfo} sub-object (item registry, always fully networked) is
     * decoded, purely to build the tab/tree structure; criteria stay as raw JSON in
     * {@link #rawFullJson} and only ever get decoded server-side on save.
     */
    public void enterEditMode(List<RawAdvancement> snapshot, List<OpenAdvancementEditorPayload.ProgressEntry> progressSnapshot) {
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
        editMode = true;
        rebuildEditTree();
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
            JsonObject json = e.getValue();
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

    private enum StatisticCategory {
        CUSTOM("custom", "Custom"), MINED("mined", "Blocks mined"), CRAFTED("crafted", "Items crafted"),
        USED("used", "Items used"), BROKEN("broken", "Items broken"), PICKED_UP("picked_up", "Items picked up"),
        DROPPED("dropped", "Items dropped"), KILLED("killed", "Entities killed"), KILLED_BY("killed_by", "Killed by entities");
        final String key;
        final String label;
        StatisticCategory(String key, String label) { this.key = key; this.label = label; }
        static StatisticCategory fromValue(String value) {
            int split = value.indexOf('|');
            if (split > 0) for (StatisticCategory category : values()) if (category.key.equals(value.substring(0, split))) return category;
            return CUSTOM;
        }
    }

    private void initIdentifierPicker() {
        int left = iconPickerLeft();
        int top = iconPickerTop();
        identifierSearchBox = addWidget(new EditBox(this.font, left + 10, top + ICON_SEARCH_Y,
                ICON_PICKER_WIDTH - 20, 16, Component.literal("Search")));
        identifierSearchBox.setHint(Component.literal("Search registry entries..."));
        identifierSearchBox.setResponder(this::refreshIdentifierSearch);
        identifierPickerCancelButton = addWidget(Button.builder(Component.literal("Cancel"), b -> closeIdentifierPicker())
                .bounds(left + 10, top + ICON_PICKER_HEIGHT - 26, ICON_PICKER_WIDTH - 20, 20).build());
        identifierPickerButtons.clear();
        for (int i = 0; i < 8; i++) {
            int index = i;
            Button button = addWidget(Button.builder(Component.empty(), b -> {
                        if (index < identifierPickerResults.size()) {
                            identifierPickerCallback.accept(identifierPickerResults.get(index));
                            closeIdentifierPicker();
                        }
                    })
                    .bounds(left + 10, top + 50 + i * 18, ICON_PICKER_WIDTH - 20, 16).build());
            identifierPickerButtons.add(button);
        }
        refreshIdentifierSearch("");
    }

    private void openIdentifierPicker(String title, List<Identifier> entries, Consumer<Identifier> callback) {
        identifierPickerOpen = true;
        identifierPickerTitle = title;
        identifierPickerCallback = callback;
        identifierPickerEntries.clear();
        identifierPickerEntries.addAll(entries);
        identifierPickerEntries.sort(Comparator.comparing(Identifier::toString));
        this.init(this.width, this.height);
    }

    /** Opens the same searchable modal used by registry pickers, but each row is a pending quest
     * reward and immediately sends one precise claim request. */
    private void openClaimRewards() {
        openIdentifierPicker("Claim a reward", ClientClaimableRewards.available(), id ->
                NetworkManager.sendToServer(new ClaimAdvancementRewardPayload(id, -1)));
    }

    private void closeIdentifierPicker() {
        identifierPickerOpen = false;
        identifierPickerCallback = null;
        this.init(this.width, this.height);
    }

    private void refreshIdentifierSearch(String query) {
        String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
        identifierPickerResults.clear();
        for (Identifier id : identifierPickerEntries) {
            if (needle.isEmpty() || id.toString().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                identifierPickerResults.add(id);
            }
        }
        for (int i = 0; i < identifierPickerButtons.size(); i++) {
            Button button = identifierPickerButtons.get(i);
            if (i < identifierPickerResults.size()) {
                button.setMessage(Component.literal(identifierPickerResults.get(i).toString()));
                button.visible = true;
                button.active = true;
            } else {
                button.visible = false;
                button.active = false;
            }
        }
    }

    private void extractIdentifierPicker(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.nextStratum();
        tryBlur(extractor);
        extractor.fill(0, 0, this.width, this.height, 0xC0000000);
        int left = iconPickerLeft(); int top = iconPickerTop();
        extractor.fill(left, top, left + ICON_PICKER_WIDTH, top + ICON_PICKER_HEIGHT, 0xFF2B2B2B);
        extractor.horizontalLine(left, left + ICON_PICKER_WIDTH - 1, top, 0xFF000000);
        extractor.horizontalLine(left, left + ICON_PICKER_WIDTH - 1, top + ICON_PICKER_HEIGHT - 1, 0xFF000000);
        extractor.verticalLine(left, top, top + ICON_PICKER_HEIGHT - 1, 0xFF000000);
        extractor.verticalLine(left + ICON_PICKER_WIDTH - 1, top, top + ICON_PICKER_HEIGHT - 1, 0xFF000000);
        extractor.text(this.font, identifierPickerTitle, left + 10, top + ICON_TITLE_Y, 0xFFFFFFFF, false);
        identifierSearchBox.extractRenderState(extractor, mouseX, mouseY, partialTick);
        for (Button button : identifierPickerButtons) if (button.visible) button.extractRenderState(extractor, mouseX, mouseY, partialTick);
        identifierPickerCancelButton.extractRenderState(extractor, mouseX, mouseY, partialTick);
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
        rewardDialogIndex = index;
        rewardDialogDraftType = null;
        rewardDialogDraftValue = null;
        rewardDialogDraftCount = null;
        rewardDialogOpen = true;
        this.init(this.width, this.height);
    }

    private void closeRewardDialog() {
        rewardDialogOpen = false;
        rewardDialogIndex = null;
        rewardDialogDraftType = null;
        rewardDialogDraftValue = null;
        rewardDialogDraftCount = null;
        this.init(this.width, this.height);
    }

    private void initRewardDialog() {
        int left = iconPickerLeft();
        int top = iconPickerTop();
        RewardType initialType = rewardDialogDraftType == null ? RewardType.EXPERIENCE : rewardDialogDraftType;
        String initialValue = rewardDialogDraftValue == null ? "" : rewardDialogDraftValue;
        String initialCount = rewardDialogDraftCount == null ? "" : rewardDialogDraftCount;
        if (rewardDialogDraftType == null && rewardDialogIndex != null && rewardDialogIndex < rewardEntries.size()) {
            JsonObject entry = rewardEntries.get(rewardDialogIndex);
            initialType = RewardType.valueOf(entry.get("type").getAsString());
            initialValue = initialType == RewardType.EXPERIENCE ? String.valueOf(entry.get("amount").getAsInt()) : entry.get("id").getAsString();
            initialCount = initialType == RewardType.ITEM ? String.valueOf(entry.get("count").getAsInt()) : "";
        }
        rewardDialogType = addWidget(CycleButton.builder((RewardType type) -> Component.literal(type.label), initialType)
                .withValues(RewardType.values()).create(left + 10, top + 34, ICON_PICKER_WIDTH - 20, 20, Component.literal("Reward type")));
        rewardDialogValue = addWidget(new EditBox(this.font, left + 10, top + 60, ICON_PICKER_WIDTH - 20, 16, Component.literal("Value")));
        rewardDialogValue.setValue(initialValue);
        rewardDialogValue.setMaxLength(256);
        rewardDialogCount = addWidget(new EditBox(this.font, left + 10, top + 82, ICON_PICKER_WIDTH - 20, 16, Component.literal("Count")));
        rewardDialogCount.setValue(initialCount);
        rewardDialogCount.setMaxLength(8);
        rewardDialogPick = addWidget(Button.builder(Component.literal("Pick Item / Recipe"), b -> pickRewardDialogValue())
                .bounds(left + 10, top + 104, ICON_PICKER_WIDTH - 20, 16).build());
        rewardDialogSave = addWidget(Button.builder(Component.literal(rewardDialogIndex == null ? "Add Reward" : "Save Reward"), b -> saveRewardDialog())
                .bounds(left + 10, top + 124, ICON_PICKER_WIDTH - 20, 20).build());
        rewardDialogCancel = addWidget(Button.builder(Component.literal("Cancel"), b -> closeRewardDialog())
                .bounds(left + 10, top + ICON_PICKER_HEIGHT - 26, ICON_PICKER_WIDTH - 20, 20).build());
    }

    private void saveRewardDialog() {
        RewardType type = rewardDialogType.getValue();
        if (rewardTypeConflict(type, rewardDialogIndex)) return;
        JsonObject entry = new JsonObject();
        try {
            entry.addProperty("type", type.name());
            if (type == RewardType.EXPERIENCE) {
                entry.addProperty("amount", rewardDialogValue.getValue().isBlank() ? 0 : Integer.parseInt(rewardDialogValue.getValue().trim()));
            } else if (type == RewardType.ITEM) {
                entry.addProperty("id", Identifier.parse(rewardDialogValue.getValue().trim()).toString());
                int count = rewardDialogCount.getValue().isBlank() ? 1 : Integer.parseInt(rewardDialogCount.getValue().trim());
                if (count < 1) throw new IllegalArgumentException("count must be at least 1");
                entry.addProperty("count", count);
            } else {
                entry.addProperty("id", Identifier.parse(rewardDialogValue.getValue().trim()).toString());
            }
        } catch (RuntimeException ignored) {
            return;
        }
        if (rewardDialogIndex == null) rewardEntries.add(entry); else rewardEntries.set(rewardDialogIndex, entry);
        closeRewardDialog();
    }

    private void pickRewardDialogValue() {
        rewardDialogDraftType = rewardDialogType.getValue();
        rewardDialogDraftValue = rewardDialogValue.getValue();
        rewardDialogDraftCount = rewardDialogCount.getValue();
        if (rewardDialogDraftType == RewardType.ITEM) {
            openIconPicker(id -> rewardDialogDraftValue = id.toString());
        } else if (rewardDialogDraftType == RewardType.RECIPE) {
            openIdentifierPicker("Pick recipe", ClientRecipeIds.all(), id -> rewardDialogDraftValue = id.toString());
        }
    }

    private void extractRewardDialog(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.nextStratum();
        tryBlur(extractor);
        extractor.fill(0, 0, this.width, this.height, 0xC0000000);
        int left = iconPickerLeft(); int top = iconPickerTop();
        extractor.fill(left, top, left + ICON_PICKER_WIDTH, top + ICON_PICKER_HEIGHT, 0xFF2B2B2B);
        extractor.horizontalLine(left, left + ICON_PICKER_WIDTH - 1, top, 0xFF000000);
        extractor.horizontalLine(left, left + ICON_PICKER_WIDTH - 1, top + ICON_PICKER_HEIGHT - 1, 0xFF000000);
        extractor.verticalLine(left, top, top + ICON_PICKER_HEIGHT - 1, 0xFF000000);
        extractor.verticalLine(left + ICON_PICKER_WIDTH - 1, top, top + ICON_PICKER_HEIGHT - 1, 0xFF000000);
        extractor.text(this.font, rewardDialogIndex == null ? "Add Reward" : "Edit Reward", left + 10, top + ICON_TITLE_Y, 0xFFFFFFFF, false);
        extractor.text(this.font, "Value: XP amount or a registry id", left + 10, top + 20, 0xFFAAAAAA, false);
        rewardDialogType.extractRenderState(extractor, mouseX, mouseY, partialTick);
        rewardDialogValue.extractRenderState(extractor, mouseX, mouseY, partialTick);
        rewardDialogCount.extractRenderState(extractor, mouseX, mouseY, partialTick);
        rewardDialogPick.extractRenderState(extractor, mouseX, mouseY, partialTick);
        rewardDialogSave.extractRenderState(extractor, mouseX, mouseY, partialTick);
        rewardDialogCancel.extractRenderState(extractor, mouseX, mouseY, partialTick);
    }

    private void openCriterionDialog(String name) {
        criterionDialogOriginalName = name;
        criterionDialogDraftName = null;
        criterionDialogDraftValue = null;
        criterionDialogDraftType = null;
        criterionDialogOpen = true;
        this.init(this.width, this.height);
    }

    private void closeCriterionDialog() {
        criterionDialogOpen = false;
        criterionDialogOriginalName = null;
        criterionDialogDraftName = null;
        criterionDialogDraftValue = null;
        criterionDialogDraftType = null;
        this.init(this.width, this.height);
    }

    private void initCriterionDialog() {
        int left = iconPickerLeft(); int top = iconPickerTop();
        CriterionType type = criterionDialogDraftType == null ? CriterionType.IMPOSSIBLE : criterionDialogDraftType;
        String value = criterionDialogDraftValue == null ? "" : criterionDialogDraftValue;
        String name = criterionDialogDraftName == null ? criterionDialogOriginalName : criterionDialogDraftName;
        if (criterionDialogDraftType == null && criterionDialogOriginalName != null && criteriaEntries.containsKey(criterionDialogOriginalName)) {
            JsonObject entry = criteriaEntries.get(criterionDialogOriginalName);
            type = CriterionType.fromJson(entry);
            value = extractValue(type, entry);
        }
        criterionDialogName = addWidget(new EditBox(this.font, left + 10, top + 34, ICON_PICKER_WIDTH - 20, 16, Component.literal("Criterion name")));
        criterionDialogName.setValue(name == null ? "criterion" : name);
        criterionDialogName.setMaxLength(256);
        criterionDialogType = addWidget(CycleButton.builder((CriterionType kind) -> Component.literal(kind.label), type)
                .withValues(CriterionType.values()).create(left + 10, top + 56, ICON_PICKER_WIDTH - 20, 20, Component.literal("Criterion type")));
        criterionDialogValue = addWidget(MultiLineEditBox.builder().setX(left + 10).setY(top + 82)
                .setPlaceholder(Component.literal("Value")).build(this.font, ICON_PICKER_WIDTH - 20, 36, Component.literal("Criterion value")));
        criterionDialogValue.setValue(value);
        criterionDialogStatisticCategory = addWidget(CycleButton.builder((StatisticCategory category) ->
                        Component.literal("Stats: " + category.label), StatisticCategory.fromValue(value))
                .withValues(StatisticCategory.values()).create(left + 10, top + 120, ICON_PICKER_WIDTH - 20, 16, Component.literal("Statistic category")));
        criterionDialogPick = addWidget(Button.builder(Component.literal("Pick Registry Value"), b -> pickCriterionDialogValue())
                .bounds(left + 10, top + 140, ICON_PICKER_WIDTH - 20, 16).build());
        criterionDialogSave = addWidget(Button.builder(Component.literal(criterionDialogOriginalName == null ? "Add Criterion" : "Save Criterion"), b -> saveCriterionDialog())
                .bounds(left + 10, top + 160, ICON_PICKER_WIDTH - 20, 20).build());
        criterionDialogCancel = addWidget(Button.builder(Component.literal("Cancel"), b -> closeCriterionDialog())
                .bounds(left + 10, top + ICON_PICKER_HEIGHT - 26, ICON_PICKER_WIDTH - 20, 20).build());
    }

    private void saveCriterionDialog() {
        String name = criterionDialogName.getValue().trim();
        if (name.isEmpty()) return;
        try {
            JsonObject entry = buildCriterionJson(criterionDialogType.getValue(), criterionDialogValue.getValue());
            if (criterionDialogOriginalName != null && !criterionDialogOriginalName.equals(name)) criteriaEntries.remove(criterionDialogOriginalName);
            criteriaEntries.put(name, entry);
            selectedCriterionName = name;
            closeCriterionDialog();
        } catch (RuntimeException ignored) { }
    }

    private void pickCriterionDialogValue() {
        criterionDialogDraftName = criterionDialogName.getValue();
        criterionDialogDraftValue = criterionDialogValue.getValue();
        criterionDialogDraftType = criterionDialogType.getValue();
        CriterionType type = criterionDialogDraftType;
        if (type == CriterionType.ITEM_OBTAINED) {
            openIconPicker(id -> criterionDialogDraftValue = id.toString());
            return;
        }
        List<Identifier> entries = new ArrayList<>();
        if (type == CriterionType.PLAYER_KILLED_ENTITY || type == CriterionType.ENTITY_KILLED_PLAYER) {
            for (var entity : BuiltInRegistries.ENTITY_TYPE) entries.add(BuiltInRegistries.ENTITY_TYPE.getKey(entity));
        } else if (type == CriterionType.RECIPE_UNLOCKED) {
            entries.addAll(ClientRecipeIds.all());
        } else if (type == CriterionType.CHANGED_DIMENSION) {
            entries.addAll(List.of(Identifier.withDefaultNamespace("overworld"), Identifier.withDefaultNamespace("the_nether"), Identifier.withDefaultNamespace("the_end")));
        } else if (type == CriterionType.LOCATION_BIOME || type == CriterionType.LOCATION_STRUCTURE) {
            if (this.minecraft.level != null) {
                if (type == CriterionType.LOCATION_BIOME) {
                    this.minecraft.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).keySet().forEach(entries::add);
                } else {
                    this.minecraft.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).keySet().forEach(entries::add);
                }
            }
        } else if (type == CriterionType.STATISTIC) {
            StatisticCategory category = criterionDialogStatisticCategory.getValue();
            switch (category) {
                case CUSTOM -> { for (var stat : BuiltInRegistries.CUSTOM_STAT) entries.add(BuiltInRegistries.CUSTOM_STAT.getKey(stat)); }
                case MINED -> { for (var block : BuiltInRegistries.BLOCK) entries.add(BuiltInRegistries.BLOCK.getKey(block)); }
                case CRAFTED, USED, BROKEN, PICKED_UP, DROPPED -> { for (var item : BuiltInRegistries.ITEM) entries.add(BuiltInRegistries.ITEM.getKey(item)); }
                case KILLED, KILLED_BY -> { for (var entity : BuiltInRegistries.ENTITY_TYPE) entries.add(BuiltInRegistries.ENTITY_TYPE.getKey(entity)); }
            }
        } else if (type == CriterionType.ITEM_USED_ON_BLOCK) {
            for (var block : BuiltInRegistries.BLOCK) entries.add(BuiltInRegistries.BLOCK.getKey(block));
        }
        if (!entries.isEmpty()) openIdentifierPicker("Pick criterion value", entries, id -> {
            criterionDialogDraftValue = type == CriterionType.ITEM_USED_ON_BLOCK ? id + ";minecraft:stick"
                    : type == CriterionType.STATISTIC ? criterionDialogStatisticCategory.getValue().key + "|" + id + " >= 1"
                    : id.toString();
        });
    }

    private void extractCriterionDialog(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.nextStratum();
        tryBlur(extractor);
        extractor.fill(0, 0, this.width, this.height, 0xC0000000);
        int left = iconPickerLeft(); int top = iconPickerTop();
        extractor.fill(left, top, left + ICON_PICKER_WIDTH, top + ICON_PICKER_HEIGHT, 0xFF2B2B2B);
        extractor.horizontalLine(left, left + ICON_PICKER_WIDTH - 1, top, 0xFF000000);
        extractor.horizontalLine(left, left + ICON_PICKER_WIDTH - 1, top + ICON_PICKER_HEIGHT - 1, 0xFF000000);
        extractor.verticalLine(left, top, top + ICON_PICKER_HEIGHT - 1, 0xFF000000);
        extractor.verticalLine(left + ICON_PICKER_WIDTH - 1, top, top + ICON_PICKER_HEIGHT - 1, 0xFF000000);
        extractor.text(this.font, criterionDialogOriginalName == null ? "Add Criterion" : "Edit Criterion", left + 10, top + ICON_TITLE_Y, 0xFFFFFFFF, false);
        criterionDialogName.extractRenderState(extractor, mouseX, mouseY, partialTick);
        criterionDialogType.extractRenderState(extractor, mouseX, mouseY, partialTick);
        criterionDialogValue.extractRenderState(extractor, mouseX, mouseY, partialTick);
        criterionDialogStatisticCategory.extractRenderState(extractor, mouseX, mouseY, partialTick);
        criterionDialogPick.extractRenderState(extractor, mouseX, mouseY, partialTick);
        criterionDialogSave.extractRenderState(extractor, mouseX, mouseY, partialTick);
        criterionDialogCancel.extractRenderState(extractor, mouseX, mouseY, partialTick);
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
            JsonObject criterion = element.getAsJsonObject();
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
        // dirty only lives in this screen instance's memory - closing without saving discards it
        // for good (a new CodexAdvancementsScreen starts with an empty dirty map), so confirm
        // first rather than silently losing whatever's pending.
        if (!dirty.isEmpty()) {
            this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
                        if (confirmed) {
                            this.minecraft.setScreen(lastScreen);
                        } else {
                            this.minecraft.setScreen(this);
                        }
                    },
                    Component.literal("Unsaved changes"),
                    Component.literal("You have " + dirty.size() + " unsaved change(s). Leave without saving?")));
            return;
        }
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

        CodexAdvancementTab selectedTab = activeSelectedTab();

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
                extractor.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, tabRowY,
                        CodexAdvancementTab.TAB_WIDTH, CodexAdvancementTab.TAB_HEIGHT);
                x += CodexAdvancementTab.TAB_SLOT;
            }
            x = left;
            for (TabRowSlot slot : tabRow) {
                if (slot.tab() != null) {
                    slot.tab().extractIcon(extractor, x, tabRowY);
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
        extractor.text(this.font, title, left + 8, top + 6, 0xFF404040, false);

        CodexAdvancementWidget hovered = null;
        int hoverX = 0;
        int hoverY = 0;
        int hoverBottom = 0;
        if (selectedTab != null) {
            int contentX = left + BORDER_LEFT;
            int contentY = top + BORDER_TOP;
            int contentWidth = contentWidth();
            int contentHeight = contentHeight();
            selectedTab.extractContents(extractor, contentX, contentY, contentWidth, contentHeight, zoom, editMode);

            int cBottom = contentY + contentHeight;
            // The persisted widget (if any, still in this tab) keeps priority over whatever's under
            // the cursor - without this, moving the mouse from the icon into the hover box, over a
            // *different* advancement's icon that happens to sit underneath the box, instantly
            // stole the hover to that other widget, making it impossible to actually read the box's
            // own content whenever advancements are packed close together. Recomputed fresh every
            // frame (not reusing a stale rect) so it stays correct across scroll/zoom.
            if (lastHoveredWidget != null && selectedTab == lastHoveredTab) {
                int cx = selectedTab.widgetScreenX(lastHoveredWidget, contentX, zoom);
                int cy = selectedTab.widgetScreenY(lastHoveredWidget, contentY, zoom);
                CodexAdvancementWidget.HoverBounds bounds =
                        lastHoveredWidget.computeHoverBounds(this.font, cx, cy, this.width, cBottom);
                if (bounds.contains(mouseX, mouseY)) {
                    hovered = lastHoveredWidget;
                    hoverX = cx;
                    hoverY = cy;
                    hoverBottom = cBottom;
                }
            }
            if (hovered == null) {
                CodexAdvancementWidget iconHovered = selectedTab.findHovered(contentX, contentY, contentWidth, contentHeight, zoom, mouseX, mouseY);
                if (iconHovered != null) {
                    hovered = iconHovered;
                    hoverX = selectedTab.widgetScreenX(iconHovered, contentX, zoom);
                    hoverY = selectedTab.widgetScreenY(iconHovered, contentY, zoom);
                    hoverBottom = cBottom;
                }
            }
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

        if (iconPickerOpen) {
            extractIconPicker(extractor, mouseX, mouseY, partialTick);
        } else if (identifierPickerOpen) {
            extractIdentifierPicker(extractor, mouseX, mouseY, partialTick);
        } else if (partyPanelOpen) {
            extractPartyPanel(extractor, mouseX, mouseY, partialTick);
        } else if (rewardDialogOpen) {
            extractRewardDialog(extractor, mouseX, mouseY, partialTick);
        } else if (criterionDialogOpen) {
            extractCriterionDialog(extractor, mouseX, mouseY, partialTick);
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
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        // Otherwise Escape falls through to the default Screen behavior and closes the whole
        // advancements screen (triggering the unsaved-changes prompt) while the picker is open.
        if (iconPickerOpen && event.isEscape()) {
            closeIconPicker();
            return true;
        }
        if (identifierPickerOpen && event.isEscape()) {
            closeIdentifierPicker();
            return true;
        }
        if (partyPanelOpen && event.isEscape()) {
            closePartyPanel();
            return true;
        }
        if (rewardDialogOpen && event.isEscape()) {
            closeRewardDialog();
            return true;
        }
        if (criterionDialogOpen && event.isEscape()) {
            closeCriterionDialog();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (iconPickerOpen) {
            // Consumes every click while open (even ones that miss the grid/search/cancel), so
            // nothing underneath - panel widgets, the tree, tabs - is reachable through the modal.
            if (event.button() == 0 && !super.mouseClicked(event, doubled)) {
                Item clicked = iconGridItemAt(event.x(), event.y());
                if (clicked != null && iconPickerCallback != null) {
                    iconPickerCallback.accept(BuiltInRegistries.ITEM.getKey(clicked));
                    closeIconPicker();
                }
            }
            return true;
        }

        if (identifierPickerOpen) {
            if (event.button() == 0) super.mouseClicked(event, doubled);
            return true;
        }
        if (partyPanelOpen) {
            if (event.button() == 0) super.mouseClicked(event, doubled);
            return true;
        }
        if (rewardDialogOpen) {
            if (event.button() == 0) super.mouseClicked(event, doubled);
            return true;
        }
        if (criterionDialogOpen) {
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
                            selectedEditTab = tab;
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
        if (!editMode && event.button() == 0 && selectedViewTab != null && lastHoveredWidget != null
                && lastHoveredTab == selectedViewTab) {
            int contentX = left + BORDER_LEFT;
            int contentY = top + BORDER_TOP;
            int widgetX = selectedViewTab.widgetScreenX(lastHoveredWidget, contentX, zoom);
            int widgetY = selectedViewTab.widgetScreenY(lastHoveredWidget, contentY, zoom);
            int contentBottom = contentY + contentHeight();
            int mouseX = (int) event.x();
            int mouseY = (int) event.y();
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
        }
        if (!editMode && event.button() == 1 && selectedViewTab != null) {
            int contentX = left + BORDER_LEFT;
            int contentY = top + BORDER_TOP;
            CodexAdvancementWidget hit = selectedViewTab.findHovered(contentX, contentY, contentWidth(), contentHeight(), zoom, event.x(), event.y());
            if (hit != null) {
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
        advancement.add("criteria", defaultCriteria());
        advancement.add("requirements", defaultRequirements());
        dirty.put(id, advancement);

        creationMode = CreationMode.NONE;
        rebuildEditTree();
        selectEditNodeById(id);
        this.init(this.width, this.height);
        setStatus("Created tab " + id + " - it starts with a placeholder criterion (Impossible) so it's "
                + "always saveable; replace it via the criteria list below, then Save.");
    }

    /**
     * Debug-only: bulk-creates a batch of throwaway root tabs so the tab strip's overflow
     * wrapping (ABOVE/BELOW caps at 8 tabs, LEFT/RIGHT caps at 5) can actually be exercised
     * without hand-creating a dozen tabs one at a time through the normal New Tab flow.
     */
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
        advancement.add("criteria", defaultCriteria());
        advancement.add("requirements", defaultRequirements());
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
        List<JsonObject> entries = new ArrayList<>();
        if (rewards.has("experience")) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", RewardType.EXPERIENCE.name());
            entry.addProperty("amount", rewards.get("experience").getAsInt());
            entries.add(entry);
        }
        if (rewards.has("function")) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", RewardType.FUNCTION.name());
            entry.addProperty("id", rewards.get("function").getAsString());
            entries.add(entry);
        }
        if (rewards.has("recipes") && rewards.get("recipes").isJsonArray()) {
            for (JsonElement e : rewards.getAsJsonArray("recipes")) {
                JsonObject entry = new JsonObject();
                entry.addProperty("type", RewardType.RECIPE.name());
                entry.addProperty("id", e.getAsString());
                entries.add(entry);
            }
        }
        String generatedLootId = selectedEditWidget != null
                ? AdvancementPackIO.generatedLootTableId(selectedEditWidget.node().holder().id()).toString()
                : null;
        if (rewards.has("loot") && rewards.get("loot").isJsonArray()) {
            for (JsonElement e : rewards.getAsJsonArray("loot")) {
                String id = e.getAsString();
                if (id.equals(generatedLootId)) {
                    continue;
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("type", RewardType.LOOT_TABLE.name());
                entry.addProperty("id", id);
                entries.add(entry);
            }
        }
        if (rewards.has("codex_items") && rewards.get("codex_items").isJsonArray()) {
            for (JsonElement e : rewards.getAsJsonArray("codex_items")) {
                JsonObject item = e.getAsJsonObject();
                JsonObject entry = new JsonObject();
                entry.addProperty("type", RewardType.ITEM.name());
                entry.addProperty("id", item.get("id").getAsString());
                entry.addProperty("count", item.has("count") ? item.get("count").getAsInt() : 1);
                entries.add(entry);
            }
        }
        return entries;
    }

    private RequirementMode currentRequirementMode() {
        if (selectedEditWidget == null) {
            return RequirementMode.ALL;
        }
        JsonObject base = baseJsonFor(selectedEditWidget.node().holder().id());
        if (base == null) {
            return RequirementMode.ALL;
        }
        int criteriaCount = base.has("criteria") && base.get("criteria").isJsonObject()
                ? base.getAsJsonObject("criteria").size() : 0;
        JsonArray requirements = base.has("requirements") && base.get("requirements").isJsonArray()
                ? base.getAsJsonArray("requirements") : null;
        return RequirementMode.fromJson(requirements, criteriaCount);
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
        CriterionType type = CriterionType.fromJson(json);
        criterionTypeButton.setValue(type);
        String value = extractValue(type, json);
        if (type == CriterionType.STATISTIC && statisticCategoryButton != null) {
            statisticCategoryButton.setValue(StatisticCategory.fromValue(value));
        }
        criterionValueBox.active = type.hasValue;
        updateCriterionFieldsVisibility(type);
        criterionValueBox.setValue(value);
        setStatus("");
    }

    private void clearCriterionEditor() {
        selectedCriterionName = null;
        if (criterionNameBox != null) {
            criterionNameBox.setValue("");
            criterionTypeButton.setValue(CriterionType.IMPOSSIBLE);
            criterionValueBox.setValue("");
            criterionValueBox.active = false;
            updateCriterionFieldsVisibility(CriterionType.IMPOSSIBLE);
        }
    }

    private boolean isCurrentClaimable() {
        if (selectedEditWidget == null) return false;
        JsonObject base = baseJsonFor(selectedEditWidget.node().holder().id());
        return base != null && base.has("codex_claimable") && base.get("codex_claimable").getAsBoolean();
    }

    private void updateCriterionFieldsVisibility(CriterionType type) {
        boolean supportsItemPicker = type == CriterionType.ITEM_OBTAINED
                || type == CriterionType.PLAYER_KILLED_ENTITY || type == CriterionType.ENTITY_KILLED_PLAYER
                || type == CriterionType.CHANGED_DIMENSION || type == CriterionType.LOCATION_STRUCTURE
                || type == CriterionType.LOCATION_BIOME || type == CriterionType.STATISTIC
                || type == CriterionType.RECIPE_UNLOCKED || type == CriterionType.ITEM_USED_ON_BLOCK;
        if (pickCriterionItemButton != null) {
            pickCriterionItemButton.visible = supportsItemPicker;
            pickCriterionItemButton.active = supportsItemPicker;
        }
        if (statisticCategoryButton != null) {
            boolean statistic = type == CriterionType.STATISTIC;
            statisticCategoryButton.visible = statistic;
            statisticCategoryButton.active = statistic;
        }
    }

    /** Reuses the icon picker for the common inventory_changed criterion. */
    private void pickCriterionItem() {
        if (criterionTypeButton == null) return;
        CriterionType type = criterionTypeButton.getValue();
        if (type == CriterionType.PLAYER_KILLED_ENTITY || type == CriterionType.ENTITY_KILLED_PLAYER) {
            List<Identifier> entities = new ArrayList<>();
            for (EntityType<?> entity : BuiltInRegistries.ENTITY_TYPE) entities.add(BuiltInRegistries.ENTITY_TYPE.getKey(entity));
            openIdentifierPicker("Pick entity", entities, id -> applyPickedCriterion(type, id));
            return;
        }
        if (type == CriterionType.CHANGED_DIMENSION) {
            openIdentifierPicker("Pick dimension", List.of(
                    Identifier.withDefaultNamespace("overworld"), Identifier.withDefaultNamespace("the_nether"),
                    Identifier.withDefaultNamespace("the_end")), id -> applyPickedCriterion(type, id));
            return;
        }
        if (type == CriterionType.LOCATION_BIOME || type == CriterionType.LOCATION_STRUCTURE) {
            if (this.minecraft.level == null) return;
            List<Identifier> entries = new ArrayList<>();
            if (type == CriterionType.LOCATION_BIOME) {
                this.minecraft.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                        .keySet().forEach(entries::add);
            } else {
                this.minecraft.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                        .keySet().forEach(entries::add);
            }
            openIdentifierPicker(type == CriterionType.LOCATION_BIOME ? "Pick biome" : "Pick structure", entries,
                    id -> applyPickedCriterion(type, id));
            return;
        }
        if (type == CriterionType.STATISTIC) {
            StatisticCategory category = statisticCategoryButton == null ? StatisticCategory.CUSTOM : statisticCategoryButton.getValue();
            List<Identifier> entries = new ArrayList<>();
            switch (category) {
                case CUSTOM -> { for (var stat : BuiltInRegistries.CUSTOM_STAT) entries.add(BuiltInRegistries.CUSTOM_STAT.getKey(stat)); }
                case MINED -> { for (var block : BuiltInRegistries.BLOCK) entries.add(BuiltInRegistries.BLOCK.getKey(block)); }
                case CRAFTED, USED, BROKEN, PICKED_UP, DROPPED -> { for (var item : BuiltInRegistries.ITEM) entries.add(BuiltInRegistries.ITEM.getKey(item)); }
                case KILLED, KILLED_BY -> { for (var entity : BuiltInRegistries.ENTITY_TYPE) entries.add(BuiltInRegistries.ENTITY_TYPE.getKey(entity)); }
            }
            openIdentifierPicker("Pick " + category.label.toLowerCase(java.util.Locale.ROOT), entries,
                    id -> applyPickedCriterionValue(type, category.key + "|" + id + " >= 1"));
            return;
        }
        if (type == CriterionType.RECIPE_UNLOCKED) {
            openIdentifierPicker("Pick recipe", ClientRecipeIds.all(), id -> applyPickedCriterion(type, id));
            return;
        }
        if (type == CriterionType.ITEM_USED_ON_BLOCK) {
            List<Identifier> blocks = new ArrayList<>();
            for (var block : BuiltInRegistries.BLOCK) blocks.add(BuiltInRegistries.BLOCK.getKey(block));
            String current = criterionValueBox == null ? "" : criterionValueBox.getValue();
            String itemPart = current.contains(";") ? current.substring(current.indexOf(';') + 1).trim() : "minecraft:stick";
            openIdentifierPicker("Pick required block", blocks,
                    id -> applyPickedCriterionValue(type, id + ";" + itemPart));
            return;
        }
        if (type != CriterionType.ITEM_OBTAINED) return;
        openIconPicker(id -> applyPickedCriterion(type, id));
    }

    private void applyPickedCriterion(CriterionType type, Identifier id) {
        applyPickedCriterionValue(type, id.toString());
    }

    private void applyPickedCriterionValue(CriterionType type, String value) {
        String requestedName = criterionNameBox == null ? "" : criterionNameBox.getValue().trim();
        String name = requestedName.isEmpty() ? selectedCriterionName : requestedName;
        if (name == null || name.isBlank()) {
            name = type == CriterionType.ITEM_OBTAINED ? "item_obtained" : "kill_entity";
            int suffix = 2;
            while (criteriaEntries.containsKey(name)) name = name + "_" + suffix++;
        }
        if (selectedCriterionName != null && !selectedCriterionName.equals(name)) {
            criteriaEntries.remove(selectedCriterionName);
        }
        criteriaEntries.put(name, buildCriterionJson(type, value));
        selectedCriterionName = name;
    }

    private void addCriterion() {
        String base = "criterion";
        String name = base;
        int n = 1;
        while (criteriaEntries.containsKey(name)) {
            name = base + "_" + (n++);
        }
        criteriaEntries.put(name, buildCriterionJson(CriterionType.IMPOSSIBLE, ""));
        // Row count grows with criteriaEntries, so a full re-init (not just refreshCriteriaList)
        // is needed to actually build the new row - init() leaves criteriaEntries itself alone
        // since criteriaEntriesSelection still matches the currently-selected widget.
        this.init(this.width, this.height);
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
        // A rename (name != selectedCriterionName) or first-time add changes the row count, so
        // this needs the same full re-init as addCriterion - see its comment above.
        this.init(this.width, this.height);
        selectCriterion(name);
        setStatus("Criterion applied to this advancement - hit Apply Edit, then Save.");
    }

    private void removeCriterion() {
        if (selectedCriterionName == null) {
            setStatus("Select a criterion first.");
            return;
        }
        criteriaEntries.remove(selectedCriterionName);
        this.init(this.width, this.height);
        setStatus("Criterion removed - hit Apply Edit, then Save.");
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

    private String describeReward(JsonObject entry) {
        RewardType type = RewardType.valueOf(entry.get("type").getAsString());
        return switch (type) {
            case EXPERIENCE -> "Experience: " + entry.get("amount").getAsInt();
            case ITEM -> "Item: " + entry.get("id").getAsString() + " x" + entry.get("count").getAsInt();
            case RECIPE -> "Recipe: " + entry.get("id").getAsString();
            case LOOT_TABLE -> "Loot table: " + entry.get("id").getAsString();
            case FUNCTION -> "Function: " + entry.get("id").getAsString();
        };
    }

    private void selectRewardAt(int index) {
        if (index >= 0 && index < rewardEntries.size()) {
            selectReward(index);
        }
    }

    private void selectReward(int index) {
        JsonObject entry = rewardEntries.get(index);
        selectedRewardIndex = index;
        RewardType type = RewardType.valueOf(entry.get("type").getAsString());
        rewardTypeButton.setValue(type);
        updateRewardFieldsVisibility(type);
        rewardValueBox.setValue(type == RewardType.EXPERIENCE
                ? String.valueOf(entry.get("amount").getAsInt())
                : entry.get("id").getAsString());
        rewardCountBox.setValue(type == RewardType.ITEM ? String.valueOf(entry.get("count").getAsInt()) : "");
        setStatus("");
    }

    private void clearRewardEditor() {
        selectedRewardIndex = null;
        if (rewardTypeButton != null) {
            rewardTypeButton.setValue(RewardType.EXPERIENCE);
            rewardValueBox.setValue("");
            rewardCountBox.setValue("");
            updateRewardFieldsVisibility(RewardType.EXPERIENCE);
        }
    }

    /** Item rewards need a count; item and recipe rewards both have searchable pickers. */
    private void updateRewardFieldsVisibility(RewardType type) {
        boolean isItem = type == RewardType.ITEM;
        if (rewardCountBox != null) {
            rewardCountBox.visible = isItem;
            rewardCountBox.active = isItem;
        }
        if (pickRewardItemButton != null) {
            boolean hasPicker = isItem || type == RewardType.RECIPE;
            pickRewardItemButton.visible = hasPicker;
            pickRewardItemButton.active = hasPicker;
        }
    }

    /**
     * True if a second entry of {@code type} (excluding {@code excludeIndex}, when editing an
     * existing entry in place) would violate vanilla's one-value-not-a-list shape for "experience"/
     * "function". Recipes/loot tables/items are real vanilla arrays, so any number is fine.
     */
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

    /** Reads whatever's currently in the type/value/count fields into a new entry, matching
     * {@link #rewardEntries}' working format. A blank Experience amount defaults to 0 (so a bare
     * "Add Reward" click without typing anything still works); every other blank/invalid value is a
     * hard error, not silently defaulted, since there's no sane default id to fall back to. */
    private JsonObject buildRewardEntryFromFields(RewardType type) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", type.name());
        switch (type) {
            case EXPERIENCE -> {
                String text = rewardValueBox.getValue().trim();
                entry.addProperty("amount", text.isEmpty() ? 0 : Integer.parseInt(text));
            }
            case ITEM -> {
                Identifier itemId = Identifier.parse(rewardValueBox.getValue().trim());
                String countText = rewardCountBox.getValue().trim();
                int count = countText.isEmpty() ? 1 : Integer.parseInt(countText);
                if (count < 1) {
                    throw new NumberFormatException("count must be at least 1");
                }
                entry.addProperty("id", itemId.toString());
                entry.addProperty("count", count);
            }
            default -> entry.addProperty("id", Identifier.parse(rewardValueBox.getValue().trim()).toString());
        }
        return entry;
    }

    /**
     * Opens the item picker and, once something's picked, commits it straight into the selected
     * entry (creating one first if nothing's selected yet, e.g. the user set the type to Item and
     * clicked Pick without ever clicking Add Reward) - never just the value box, since opening the
     * picker triggers a full panel re-init (new EditBox instances) that would otherwise wipe
     * anything only staged in a text field before the entry itself gets committed.
     */
    private void pickRewardItem() {
        if (rewardTypeButton != null && rewardTypeButton.getValue() == RewardType.RECIPE) {
            openIdentifierPicker("Pick recipe", ClientRecipeIds.all(), this::applyPickedRecipeReward);
            return;
        }
        String pendingCountText = rewardCountBox != null ? rewardCountBox.getValue().trim() : "";
        openIconPicker(id -> {
            int index;
            JsonObject entry;
            if (selectedRewardIndex != null && selectedRewardIndex < rewardEntries.size()) {
                index = selectedRewardIndex;
                entry = rewardEntries.get(index);
            } else {
                entry = new JsonObject();
                rewardEntries.add(entry);
                index = rewardEntries.size() - 1;
            }
            entry.addProperty("type", RewardType.ITEM.name());
            entry.addProperty("id", id.toString());
            int count = 1;
            try {
                count = pendingCountText.isEmpty() ? 1 : Integer.parseInt(pendingCountText);
            } catch (NumberFormatException ignored) {
                // Fall back to 1 rather than reject the pick outright over an unrelated typo.
            }
            entry.addProperty("count", Math.max(1, count));
            selectedRewardIndex = index;
        });
    }

    /** Picker callbacks survive a full screen re-init, so write into the reward working list
     * rather than the soon-to-be-disposed EditBox instance. */
    private void applyPickedRecipeReward(Identifier id) {
        int index;
        JsonObject entry;
        if (selectedRewardIndex != null && selectedRewardIndex < rewardEntries.size()) {
            index = selectedRewardIndex;
            entry = rewardEntries.get(index);
        } else {
            entry = new JsonObject();
            rewardEntries.add(entry);
            index = rewardEntries.size() - 1;
        }
        entry.addProperty("type", RewardType.RECIPE.name());
        entry.addProperty("id", id.toString());
        selectedRewardIndex = index;
    }

    private void addReward() {
        RewardType type = rewardTypeButton.getValue();
        if (rewardTypeConflict(type, null)) {
            setStatus("Only one " + type.label + " reward is allowed - select and edit the existing one instead.");
            return;
        }
        JsonObject entry;
        try {
            entry = buildRewardEntryFromFields(type);
        } catch (RuntimeException e) {
            setStatus("Invalid reward: " + e.getMessage());
            return;
        }
        rewardEntries.add(entry);
        int index = rewardEntries.size() - 1;
        // Row count grows with rewardEntries, so a full re-init is needed to build the new row -
        // same reasoning as addCriterion/applyCriterion above.
        this.init(this.width, this.height);
        selectReward(index);
        setStatus("Reward added - hit Apply Edit, then Save.");
    }

    private void applyReward() {
        if (selectedRewardIndex == null) {
            setStatus("Select a reward first.");
            return;
        }
        RewardType type = rewardTypeButton.getValue();
        if (rewardTypeConflict(type, selectedRewardIndex)) {
            setStatus("Only one " + type.label + " reward is allowed - select and edit the existing one instead.");
            return;
        }
        JsonObject entry;
        try {
            entry = buildRewardEntryFromFields(type);
        } catch (RuntimeException e) {
            setStatus("Invalid reward: " + e.getMessage());
            return;
        }
        rewardEntries.set(selectedRewardIndex, entry);
        int index = selectedRewardIndex;
        this.init(this.width, this.height);
        selectReward(index);
        setStatus("Reward applied - hit Apply Edit, then Save.");
    }

    private void removeReward() {
        if (selectedRewardIndex == null) {
            setStatus("Select a reward first.");
            return;
        }
        rewardEntries.remove((int) selectedRewardIndex);
        // Explicitly cleared rather than left to the index-bounds check in init() - a removal from
        // the middle of the list leaves the same numeric index still in bounds (pointing at
        // whatever shifted into that slot), which would otherwise look like that entry got selected.
        selectedRewardIndex = null;
        this.init(this.width, this.height);
        setStatus("Reward removed - hit Apply Edit, then Save.");
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
            case SUBMIT_ITEMS -> {
                JsonArray items = new JsonArray();
                for (String part : trimmed.split(";")) {
                    String entry = part.trim();
                    if (entry.isEmpty()) continue;
                    String[] pair = entry.split("\\s*[xX]\\s*", 2);
                    Identifier id = Identifier.parse(pair[0].trim());
                    int count = pair.length == 2 ? Integer.parseInt(pair[1].trim()) : 1;
                    if (count < 1) throw new IllegalArgumentException("submission counts must be positive");
                    JsonObject item = new JsonObject();
                    item.addProperty("id", id.toString());
                    item.addProperty("count", count);
                    items.add(item);
                }
                if (items.isEmpty()) throw new IllegalArgumentException("add at least one item to submit");
                obj.add("codex_submission", items);
            }
            case STATISTIC -> {
                String[] parts = trimmed.split(">=", 2);
                if (parts.length != 2) throw new IllegalArgumentException("use statistic_id >= target");
                String statistic = parts[0].trim();
                String[] statisticParts = statistic.split("\\|", 2);
                if (statisticParts.length != 2) throw new IllegalArgumentException("use category|id >= target");
                if (!List.of("custom", "mined", "crafted", "used", "broken", "picked_up", "dropped", "killed", "killed_by").contains(statisticParts[0])) {
                    throw new IllegalArgumentException("unknown statistic category");
                }
                Identifier.parse(statisticParts[1].trim());
                int target = Integer.parseInt(parts[1].trim());
                if (target < 0) throw new IllegalArgumentException("statistic target must be non-negative");
                JsonObject task = new JsonObject();
                task.addProperty("id", statistic);
                task.addProperty("target", target);
                obj.add("codex_statistic", task);
            }
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
            case CHANGED_DIMENSION -> {
                if (!trimmed.isEmpty()) {
                    conditions.addProperty("to", trimmed);
                }
            }
            case LOCATION_STRUCTURE, LOCATION_BIOME -> {
                if (!trimmed.isEmpty()) {
                    JsonObject location = new JsonObject();
                    location.addProperty(type == CriterionType.LOCATION_STRUCTURE ? "structures" : "biomes", trimmed);
                    JsonObject predicateInner = new JsonObject();
                    predicateInner.add("location", location);
                    conditions.add("player", wrapEntityProperties(predicateInner));
                }
            }
            case ITEM_USED_ON_BLOCK -> {
                String[] parts = trimmed.split(";", 2);
                String block = parts.length > 0 && !parts[0].isBlank() ? parts[0].trim() : "minecraft:stone";
                String item = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "minecraft:stick";
                JsonArray location = new JsonArray();

                JsonObject blockCheck = new JsonObject();
                blockCheck.addProperty("condition", "minecraft:location_check");
                JsonObject blockPredicate = new JsonObject();
                JsonObject blockField = new JsonObject();
                blockField.addProperty("blocks", block);
                blockPredicate.add("block", blockField);
                blockCheck.add("predicate", blockPredicate);
                location.add(blockCheck);

                JsonObject toolCheck = new JsonObject();
                toolCheck.addProperty("condition", "minecraft:match_tool");
                JsonObject toolPredicate = new JsonObject();
                toolPredicate.addProperty("items", item);
                toolCheck.add("predicate", toolPredicate);
                location.add(toolCheck);

                conditions.add("location", location);
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
        // Codex-only criterion metadata deliberately has no vanilla conditions payload. Read it
        // before the vanilla-shape guard so reopening an editor modal never erases its value.
        if (type == CriterionType.SUBMIT_ITEMS && criterionJson.has("codex_submission")) {
            try {
                List<String> parts = new ArrayList<>();
                for (JsonElement element : criterionJson.getAsJsonArray("codex_submission")) {
                    JsonObject item = element.getAsJsonObject();
                    parts.add(item.get("id").getAsString() + " x" + item.get("count").getAsInt());
                }
                return String.join("; ", parts);
            } catch (RuntimeException ignored) { return ""; }
        }
        if (type == CriterionType.STATISTIC && criterionJson.has("codex_statistic")) {
            try {
                JsonObject task = criterionJson.getAsJsonObject("codex_statistic");
                return task.get("id").getAsString() + " >= " + task.get("target").getAsInt();
            } catch (RuntimeException ignored) { return ""; }
        }
        if (!criterionJson.has("conditions") || !criterionJson.get("conditions").isJsonObject()) {
            return "";
        }
        JsonObject conditions = criterionJson.getAsJsonObject("conditions");
        try {
            return switch (type) {
                case SUBMIT_ITEMS, STATISTIC -> "";
                case ITEM_OBTAINED -> conditions.getAsJsonArray("items").get(0).getAsJsonObject().get("items").getAsString();
                case PLAYER_KILLED_ENTITY, ENTITY_KILLED_PLAYER -> {
                    if (!conditions.has("entity")) {
                        yield "";
                    }
                    JsonObject predicate = conditions.getAsJsonArray("entity").get(0).getAsJsonObject().getAsJsonObject("predicate");
                    yield predicate.has("type") ? predicate.get("type").getAsString() : "";
                }
                case CHANGED_DIMENSION -> conditions.has("to") ? conditions.get("to").getAsString() : "";
                case LOCATION_STRUCTURE, LOCATION_BIOME -> {
                    JsonObject location = locationPredicate(criterionJson, "player");
                    if (location == null) {
                        yield "";
                    }
                    String key = type == CriterionType.LOCATION_STRUCTURE ? "structures" : "biomes";
                    yield location.has(key) ? location.get(key).getAsString() : "";
                }
                case ITEM_USED_ON_BLOCK -> {
                    if (!conditions.has("location")) {
                        yield "";
                    }
                    JsonArray checks = conditions.getAsJsonArray("location");
                    String block = "";
                    String item = "";
                    for (JsonElement checkElement : checks) {
                        JsonObject check = checkElement.getAsJsonObject();
                        String condition = check.has("condition") ? check.get("condition").getAsString() : "";
                        JsonObject predicate = check.has("predicate") ? check.getAsJsonObject("predicate") : null;
                        if (predicate == null) {
                            continue;
                        }
                        if ("minecraft:location_check".equals(condition) && predicate.has("block")) {
                            JsonObject blockField = predicate.getAsJsonObject("block");
                            if (blockField.has("blocks") && blockField.get("blocks").isJsonPrimitive()) {
                                block = blockField.get("blocks").getAsString();
                            }
                        } else if ("minecraft:match_tool".equals(condition) && predicate.has("items")
                                && predicate.get("items").isJsonPrimitive()) {
                            item = predicate.get("items").getAsString();
                        }
                    }
                    yield block + ";" + item;
                }
                case RECIPE_UNLOCKED -> conditions.has("recipe") ? conditions.get("recipe").getAsString() : "";
                default -> "";
            };
        } catch (RuntimeException e) {
            // Shape didn't match what this type expects (e.g. hand-edited, from a mod, or a
            // block/item list rather than a single value) - fall back to blank rather than crash;
            // the raw JSON is still intact under the hood since we only ever replace an entry
            // wholesale via applyCriterion.
            return "";
        }
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
        JsonObject rewards = new JsonObject();
        JsonArray recipes = new JsonArray();
        JsonArray loot = new JsonArray();
        JsonArray items = new JsonArray();
        for (JsonObject entry : rewardEntries) {
            RewardType type = RewardType.valueOf(entry.get("type").getAsString());
            switch (type) {
                case EXPERIENCE -> rewards.addProperty("experience", entry.get("amount").getAsInt());
                case FUNCTION -> rewards.addProperty("function", entry.get("id").getAsString());
                case RECIPE -> recipes.add(entry.get("id").getAsString());
                case LOOT_TABLE -> loot.add(entry.get("id").getAsString());
                case ITEM -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("id", entry.get("id").getAsString());
                    item.addProperty("count", entry.get("count").getAsInt());
                    items.add(item);
                }
            }
        }
        if (!recipes.isEmpty()) {
            rewards.add("recipes", recipes);
        }
        if (!loot.isEmpty()) {
            rewards.add("loot", loot);
        }
        if (!items.isEmpty()) {
            rewards.add("codex_items", items);
        }
        return rewards;
    }

    private void applyEdit() {
        if (selectedEditWidget == null) {
            setStatus("Select an advancement first.");
            return;
        }
        Identifier id = selectedEditWidget.node().holder().id();
        JsonObject base = dirty.containsKey(id) ? dirty.get(id) : rawFullJson.get(id);
        if (base == null) {
            setStatus("No base data for this advancement.");
            return;
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
        updated.add("display", DisplayInfo.CODEC.encodeStart(ops, updatedDisplay).getOrThrow());

        JsonObject criteriaJson = new JsonObject();
        for (Map.Entry<String, JsonObject> e : criteriaEntries.entrySet()) {
            criteriaJson.add(e.getKey(), e.getValue());
        }
        updated.add("criteria", criteriaJson);

        // Requirements name the criteria groups that must be met to complete the advancement -
        // always rebuilt from the current mode + criteria list rather than only when criteria keys
        // change, so the Requirements picker stays authoritative instead of silently keeping a
        // stale grouping from before it existed.
        JsonArray requirements = new JsonArray();
        if (requirementModeButton.getValue() == RequirementMode.ANY && criteriaEntries.size() > 1) {
            JsonArray group = new JsonArray();
            for (String name : criteriaEntries.keySet()) {
                group.add(name);
            }
            requirements.add(group);
        } else {
            for (String name : criteriaEntries.keySet()) {
                JsonArray group = new JsonArray();
                group.add(name);
                requirements.add(group);
            }
        }
        updated.add("requirements", requirements);

        JsonObject rewards;
        try {
            rewards = buildRewardsJson();
        } catch (RuntimeException e) {
            setStatus("Invalid reward: " + e.getMessage());
            return;
        }
        updated.add("rewards", rewards);
        updated.addProperty("codex_claimable", claimableRewardsCheckbox.selected());

        dirty.put(id, updated);
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
                    && !json.getAsJsonObject("criteria").isEmpty();
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
            if (editMode) NetworkManager.sendToServer(new RequestAdvancementSnapshotPayload());
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
        isScrolling = false;
        if (event.button() == 0) layoutDragWidget = null;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (iconPickerOpen) {
            int columns = iconGridColumns();
            int totalRows = (iconSearchResults.size() + columns - 1) / columns;
            int visibleRows = (iconGridBottom() - iconGridTop()) / ICON_CELL;
            int maxScroll = Math.max(0, totalRows - visibleRows);
            iconGridScroll = Mth.clamp(iconGridScroll - (int) Math.signum(scrollY), 0, maxScroll);
            return true;
        }
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

    @Override
    public void onRemoveAdvancementRoot(AdvancementNode node) {
        viewTabs.remove(node.holder().id());
    }

    @Override
    public void onAddAdvancementTask(AdvancementNode node) {
        CodexAdvancementTab tab = getViewTab(node);
        if (tab != null) {
            tab.addAdvancement(node);
            tab.applyLayout(ClientAdvancementLayout.positions());
        }
    }

    @Override
    public void onRemoveAdvancementTask(AdvancementNode node) {
        // A server datapack reload removes the old task graph and then adds the replacement graph.
        // Keeping the old tab here made normal view show pre-save titles/rewards until reopening
        // the world, even though edit mode held the newly-saved raw snapshot. Drop the clone and
        // let the ensuing add callbacks rebuild it from the authoritative new nodes.
        viewTabs.clear();
        selectedViewTab = null;
    }

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
