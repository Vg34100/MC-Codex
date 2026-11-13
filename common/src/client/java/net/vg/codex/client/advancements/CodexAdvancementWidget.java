package net.vg.codex.client.advancements;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.advancements.AdvancementWidgetType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.vg.codex.advancement.AdvancementPackIO;
import net.vg.codex.network.SubmissionTasksPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Recreation of vanilla's {@code AdvancementWidget} (icon + frame + connecting lines for one
 * advancement node), driven by the same {@link DisplayInfo} x/y vanilla itself uses (populated by
 * {@link net.minecraft.advancements.TreeNodePosition#run}), reusing vanilla's own
 * {@link AdvancementWidgetType} sprites so the frame/box art is pixel-identical to vanilla.
 */
public final class CodexAdvancementWidget {
    // Confirmed via AdvancementWidget's own bytecode rather than guessed.
    private static final Identifier TITLE_BOX_SPRITE = Identifier.withDefaultNamespace("advancements/title_box");
    private static final int TITLE_MAX_WIDTH = 220;
    private static final int TITLE_MIN_WIDTH = 80;
    private static final int REWARD_CHIP_SIZE = 18;
    // Text/icon insets are mirrored when the card opens left.
    private static final int PROGRESS_GAP = 6;
    // Gap between the reward row and whatever's above it (description, or title if there's no
    // description), and between the last row of chips and the box's own bottom edge - separate
    // constants since the top one used to double as a label's own line height before the "To
    // gain:" label was removed, and the bottom one needs to be roomier than that gap on its own.
    // Keep the original breathing room below the icon row, but open a little more space between
    // the literal description text and the first icon row.
    private static final int REWARD_ROW_TOP_PAD = 6;
    private static final int REWARD_ROW_BOTTOM_PAD = 6;
    // Space reserved for the vertical divider between the Required/To-gain columns (a 1px line
    // plus a few px of breathing room on each side) - only used when both columns have content.
    private static final int TABLE_DIVIDER_WIDTH = 7;

    private final AdvancementNode node;
    private DisplayInfo display;
    private final ItemStack icon;
    private int x;
    private int y;
    private final List<CodexAdvancementWidget> children = new ArrayList<>();
    private AdvancementProgress progress;
    // Edit mode's Advancement object is a locally-built dummy with AdvancementRewards.EMPTY always
    // (it has no reward data of its own by design), so without this, edit-mode hover would never
    // show rewards at all even right after applying them in the editor. Set per-widget from the
    // screen's own effectiveJson() (see CodexAdvancementsScreen#rebuildEditTree) - null for every
    // view-mode widget, which keeps using the real synced AdvancementRewards below.
    private JsonObject rewardsOverride;
    // Same idea as rewardsOverride, for the "Required" column - edit mode's dummy Advancement has
    // no real criteria data either. Null in view mode (fall back to the broadcast cache below).
    private List<Identifier> requiredItemsOverride;
    private List<ClientAdvancementRequiredItems.Hint> requiredHintsOverride;

    CodexAdvancementWidget(AdvancementNode node, DisplayInfo display) {
        this.node = node;
        this.display = display;
        this.icon = display.getIcon().create();
        //? if >=26.3 {
        /*this.x = Mth.floor(node.x() * 28.0F);
        this.y = Mth.floor(node.y() * 27.0F);
        *///?} else {
        this.x = Mth.floor(display.getX() * 28.0F);
        this.y = Mth.floor(display.getY() * 27.0F);
        //?}

    }

    public AdvancementNode node() {
        return node;
    }

    public DisplayInfo display() {
        return display;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    /** Codex's authored layout override; vanilla's generated DisplayInfo coordinates stay intact. */
    void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public AdvancementProgress progress() {
        return progress;
    }

    void setProgress(AdvancementProgress progress) {
        // Unsaved editor nodes have no server progress until their first save/reload.
        if (progress == null) {
            this.progress = null;
            previousProgress = -1;
            return;
        }
        float next = progress.getPercent();
        if (previousProgress >= 0 && next > previousProgress) {
            java.util.Set<String> items = new java.util.HashSet<>();
            ClientAdvancementRequiredItems.forAdvancement(node.holder().id()).forEach(i -> items.add(i.toString()));
            QuestFeedback.pulse(node.holder().id(), next >= 1 ? "complete" : "obtain", items);
        }
        previousProgress = next;
        this.progress = progress;
    }

    /** Edit mode only - see {@link #rewardsOverride}'s own comment. {@code rewardsJson} is the
     * advancement's current "rewards" object (dirty-if-present, else the last-known raw one), or an
     * empty object for a rewardless/not-yet-applied node - always non-null in edit mode, so
     * buildRewardChips can tell "edit mode with no rewards" (empty object, override active) apart
     * from "view mode" (no override at all, fall back to the real synced AdvancementRewards). */
    void applyRewardsOverride(JsonObject rewardsJson) {
        this.rewardsOverride = rewardsJson;
    }

    /** Edit mode only - see {@link #requiredItemsOverride}. {@code itemIds} is never null in edit
     * mode (an empty list for a node with no item-bearing criteria still marks the override active,
     * same reasoning as {@link #applyRewardsOverride}). */
    void applyRequiredItemsOverride(List<Identifier> itemIds) {
        this.requiredItemsOverride = itemIds;
    }

    void applyRequiredHintsOverride(List<ClientAdvancementRequiredItems.Hint> hints) {
        this.requiredHintsOverride = hints;
    }

    /** Edit mode only: updates the display shown after an in-place title/description edit. */
    public void setDisplay(DisplayInfo display) {
        this.display = display;
    }

    void addChild(CodexAdvancementWidget child) {
        children.add(child);
    }

    public boolean isMouseOver(double localX, double localY) {
        return localX >= x && localX < x + 26 && localY >= y && localY < y + 26;
    }

    private boolean isVisible(boolean showAll) {
        if (showAll) {
            return true;
        }
        return !display.isHidden() || (progress != null && progress.isDone());
    }

    private boolean hovered;
    private float hoverAmount;
    private long lastFrame;
    private float previousProgress = -1;
    public void hovered(boolean value) { hovered = value; }

    private float percent() {
        JsonObject state = rewardsOverride == null ? ClientQuests.presentation(node.holder().id()) : new JsonObject();
        if ((state.size() != 0)) {
            if (!state.get("unlocked").getAsBoolean()) return 0;
            if (state.get("ready").getAsBoolean()) return 1;
            if (state.get("managed").getAsBoolean() || progress != null && progress.isDone()) return 0;
        }
        return progress == null ? 0 : progress.getPercent();
    }
    private static boolean flag(JsonObject json, String key) { return json.has(key) && json.get(key).getAsBoolean(); }

    /** The tree and the card use this same renderer; the covered base icon is omitted. */
    private void extractIcon(GuiGraphicsExtractor g, int px, int py) {
        Identifier id = node.holder().id(); JsonObject state = rewardsOverride == null ? ClientQuests.presentation(id) : new JsonObject();
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(0.05F, (now - lastFrame) / 1_000_000_000F); lastFrame = now;
        hoverAmount += ((hovered && CodexClientSettings.animations ? 1F : 0F) - hoverAmount) * (1F - (float)Math.exp(-18F * dt));
        if (!CodexClientSettings.animations) hoverAmount = 0;
        float hold = QuestFeedback.hold(id), accept = QuestFeedback.pulse(id, "accept");
        float complete = QuestFeedback.pulse(id, "complete"), claim = QuestFeedback.pulse(id, "claim");
        float pop = (float)Math.sin(Math.PI * Math.max(complete, Math.max(accept, claim)));
        float scale = 1 + hoverAmount * 0.075F - hold * 0.035F + pop * 0.10F;
        boolean locked = rewardsOverride == null && ClientQuests.locked(id);
        var type = percent() >= 1 ? AdvancementWidgetType.OBTAINED : AdvancementWidgetType.UNOBTAINED;
        g.pose().pushMatrix();
        g.pose().translate(px + 16, py + 13 - hoverAmount); g.pose().scale(scale); g.pose().translate(-px - 16, -py - 13);
        if (hoverAmount > 0.02F) g.fill(px + 4, py + 3, px + 29, py + 28, ((int)(hoverAmount * 100) << 24));
        g.blitSprite(RenderPipelines.GUI_TEXTURED, type.frameSprite(display.getType()), px + 3, py, 26, 26);
        g.fakeItem(icon, px + 8, py + 5);
        if (hold > 0 || accept > 0) {
            float fill = accept > 0 ? 1 : hold;
            int edge = px + 3 + Math.round(26 * fill);
            g.enableScissor(px + 3, py, edge, py + 26);
            int alpha = accept > 0 ? (int)(170 * Math.min(1, accept * 2)) : 140;
            g.blitSprite(RenderPipelines.GUI_TEXTURED, type.frameSprite(display.getType()), px + 3, py, 26, 26, (alpha << 24) | 0x65F68D);
            g.fill(px + 6, py + 3, px + 26, py + 23, (Math.max(0, alpha / 3) << 24) | 0x41ED75);
            g.disableScissor();
        }
        if (complete > 0 || claim > 0) {
            float amount = Math.max(complete, claim);
            int edge = px + 3 + Math.round(26 * (1 - amount));
            g.enableScissor(edge, py, Math.min(px + 29, edge + 4), py + 26);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, type.frameSprite(display.getType()), px + 3, py, 26, 26, 0xCFFFF1A0);
            g.disableScissor();
            int color = ((int)(amount * 220) << 24) | 0xFFEB92;
            for (int i = 0; i < 4; i++) { int sx = px + 6 + i * 6, sy = py + 3 + (i % 2) * 18; g.fill(sx, sy, sx + 2, sy + 2, color); }
        }
        // Keep the lock shade inside the sprite silhouette, including transparent frame corners.
        if (locked) g.blitSprite(RenderPipelines.GUI_TEXTURED, type.frameSprite(display.getType()), px + 3, py, 26, 26,
                flag(state, "excluded") ? 0xBD252525 : 0x85252525);
        else {
            boolean finished = flag(state, "ready") && !flag(state, "claim_pending") && !flag(state, "repeat");
            List<Integer> dots = new ArrayList<>();
            if (flag(state, "can_accept")) dots.add(0xFF65E7CE);
            if (flag(state, "claim_pending") || ClientClaimableRewards.contains(id)) dots.add(flag(state, "claim_ready") || ClientClaimableRewards.contains(id) ? 0xFFFFD24A : 0xFF92754B);
            if (flag(state, "dialogue") && (!finished || flag(state, "choice_pending"))) dots.add(0xFFCEAAFF);
            if (flag(state, "repeat")) dots.add(0xFF86BFFF);
            // One short horizontal row anchored to the upper-right edge.
            for (int i = 0; i < dots.size(); i++) dot(g, px + 25 - i * 4, py + 1, dots.get(i));
        }
        g.pose().popMatrix();
    }
    private static void dot(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x, y, x + 4, y + 4, 0xFF232323); g.fill(x + 1, y + 1, x + 3, y + 3, color);
    }
    public void extractRenderState(GuiGraphicsExtractor extractor, int originX, int originY, boolean showAll) {
        if (isVisible(showAll) && !hovered) extractIcon(extractor, originX + x, originY + y);
        for (CodexAdvancementWidget child : children) child.extractRenderState(extractor, originX, originY, showAll);
    }

    public void extractConnectivity(GuiGraphicsExtractor extractor, CodexAdvancementWidget parent, int originX, int originY, boolean shadow) {
        float unlock = QuestFeedback.pulse(node.holder().id(), "unlock");
        int color = shadow ? 0xFF000000 : ClientQuests.locked(node.holder().id()) ? 0xFF656565 : unlock > 0 ? 0xFF9BEAC3 : -1;
        int parentCenterX = originX + parent.x + 13;
        int parentCenterY = originY + parent.y + 13;
        int centerX = originX + x + 13;
        int centerY = originY + y + 13;
        if (shadow) {
            extractor.horizontalLine(centerX, parentCenterX, parentCenterY - 1, color);
            extractor.horizontalLine(centerX + 1, parentCenterX, parentCenterY, color);
            extractor.horizontalLine(centerX, parentCenterX, parentCenterY + 1, color);
            extractor.horizontalLine(centerX, centerX - 1, centerY - 1, color);
            extractor.horizontalLine(centerX, centerX - 1, centerY, color);
            extractor.horizontalLine(centerX, centerX - 1, centerY + 1, color);
            extractor.verticalLine(centerX - 1, centerY, parentCenterY, color);
            extractor.verticalLine(centerX + 1, centerY, parentCenterY, color);
        } else {
            extractor.horizontalLine(centerX, parentCenterX, parentCenterY, color);
            extractor.horizontalLine(centerX, centerX, centerY, color);
            extractor.verticalLine(centerX, centerY, parentCenterY, color);
        }
        for (CodexAdvancementWidget child : children) {
            child.extractConnectivity(extractor, this, originX, originY, shadow);
        }
    }

    public void extractChildConnectivity(GuiGraphicsExtractor extractor, int originX, int originY, boolean shadow) {
        for (CodexAdvancementWidget child : children) {
            child.extractConnectivity(extractor, this, originX, originY, shadow);
        }
    }

    /** A single icon+count (item/xp) or icon+tooltip-only (recipe/loot/function) row entry in the
     * hover box's "To gain:" row. {@code count <= 0} means "no count decoration, tooltip only". */
    private record RewardChip(ItemStack icon, int count, Component tooltip) {
    }

    /** The hover box's on-screen bounding rect, for hit-testing whether the mouse is still within
     * it (see {@link CodexAdvancementsScreen}'s hover-persist logic) - not just the icon border. */
    public record HoverBounds(int x, int y, int width, int height) {
        public boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    /** One side of the Required/To-gain table - its own chip list, x offset, width, and row
     * capacity, so each column wraps independently of the other. */
    private record TableColumn(List<RewardChip> chips, int x, int width, int chipsPerRow) {
        int rows() {
            return chips.isEmpty() ? 0 : (chips.size() + chipsPerRow - 1) / chipsPerRow;
        }
    }

    private record Layout(
            List<FormattedCharSequence> titleLines,
            List<FormattedCharSequence> descLines,
            boolean hasDescription,
            boolean hasTable,
            TableColumn requiredColumn,
            TableColumn rewardColumn,
            boolean showDivider,
            int dividerX,
            int tableRows,
            int boxWidth,
            int titleBoxHeight,
            int descBoxHeight,
            int tableBoxHeight,
            boolean flipX,
            boolean flipY,
            int boxX,
            int boxTop,
            int titleY,
            int descY,
            int tableY,
            Component progressText,
            int progressWidth,
            int filledWidth,
            int splitWidth,
            AdvancementWidgetType leftType,
            AdvancementWidgetType rightType,
            AdvancementWidgetType frameType
    ) {
        int totalHeight() {
            return titleBoxHeight + descBoxHeight + tableBoxHeight;
        }
    }

    /**
     * Builds the reward "chips" shown in the hover box's "To gain" column - XP and item rewards get
     * a real icon+count, recipes/loot tables/functions (which don't have a meaningful icon of their
     * own) get a generic paper icon with the referenced id as a hover-only tooltip. Both view and
     * edit mode go through the same raw-JSON parsing ({@link #buildRewardChipsFromJson}); vanilla's
     * real client-side {@code Advancement} object never carries real reward data at all (confirmed
     * via bytecode - {@code Advancement.STREAM_CODEC} hardcodes {@code AdvancementRewards.EMPTY} on
     * decode, for every client, always), so {@link ClientAdvancementRewards} - populated from a
     * broadcast built server-side, same idea as {@link ClientAdvancementRequiredItems} - is view
     * mode's only real source, exactly like {@link #rewardsOverride} is edit mode's.
     */
    private List<RewardChip> buildRewardChips() {
        JsonObject rewards = rewardsOverride != null
                ? rewardsOverride
                : ClientAdvancementRewards.forAdvancement(node.holder().id());
        return rewards != null ? buildRewardChipsFromJson(rewards) : List.of();
    }

    /**
     * Shared parsing for both reward sources above - the raw JSON shape
     * {@code CodexAdvancementsScreen} itself builds/parses for the editor (see
     * {@code buildRewardsJson}/{@code loadRewardEntries}): "experience" (int), "recipes"/"loot" (id
     * arrays, generated-loot-table id already excluded by whichever source built this JSON),
     * "function" (a single id), and "codex_items" (item rewards - not a real vanilla reward field,
     * see {@code AdvancementRewardsPayload}'s doc comment for why).
     */
    private List<RewardChip> buildRewardChipsFromJson(JsonObject rewards) {
        List<RewardChip> chips = new ArrayList<>();
        if (rewards.has("experience")) {
            int experience = rewards.get("experience").getAsInt();
            if (experience > 0) {
                chips.add(new RewardChip(new ItemStack(Items.EXPERIENCE_BOTTLE), experience, Component.literal("Experience")));
            }
        }
        if (rewards.has("codex_items") && rewards.get("codex_items").isJsonArray()) {
            for (JsonElement e : rewards.getAsJsonArray("codex_items")) {
                JsonObject item = e.getAsJsonObject();
                Identifier itemId = Identifier.parse(item.get("id").getAsString());
                int count = item.has("count") ? item.get("count").getAsInt() : 1;
                Item itemType = BuiltInRegistries.ITEM.getValue(itemId);
                ItemStack stack = new ItemStack(itemType, count);
                chips.add(new RewardChip(stack, count, stack.getHoverName()));
            }
        }
        if (rewards.has("recipes") && rewards.get("recipes").isJsonArray()) {
            for (JsonElement e : rewards.getAsJsonArray("recipes")) {
                chips.add(new RewardChip(new ItemStack(Items.PAPER), 0, Component.literal("Recipe: " + e.getAsString())));
            }
        }
        if (rewards.has("loot") && rewards.get("loot").isJsonArray()) {
            String generatedLootId = AdvancementPackIO.generatedLootTableId(node.holder().id()).toString();
            for (JsonElement e : rewards.getAsJsonArray("loot")) {
                String lootId = e.getAsString();
                if (lootId.equals(generatedLootId)) {
                    continue;
                }
                boolean treasure = lootId.contains(":codex_treasure/");
                String label = treasure ? "Random treasure: " + lootId.substring(lootId.indexOf(":codex_treasure/") + ":codex_treasure/".length()).replace('_', ' ') : "Loot table: " + lootId;
                chips.add(new RewardChip(new ItemStack(treasure ? Items.CHEST : Items.PAPER), 0, Component.literal(label)));
            }
        }
        if (rewards.has("function")) {
            chips.add(new RewardChip(new ItemStack(Items.PAPER), 0, Component.literal("Function: " + rewards.get("function").getAsString())));
        }
        return chips;
    }

    /** The hover box's "Required" column. Normal inventory criteria show their item icons, while
     * Codex submission criteria also show the remaining count and the consumed/required total in
     * their tooltip. This makes the explicit submit action discoverable from the quest itself. */
    private List<RewardChip> buildRequiredChips() {
        List<Identifier> itemIds = requiredItemsOverride != null
                ? requiredItemsOverride
                : ClientAdvancementRequiredItems.forAdvancement(node.holder().id());
        List<RewardChip> chips = new ArrayList<>();
        for (Identifier itemId : itemIds) {
            Item itemType = BuiltInRegistries.ITEM.getValue(itemId);
            ItemStack stack = new ItemStack(itemType);
            chips.add(new RewardChip(stack, 0, stack.getHoverName()));
        }
        for (var task : ClientSubmissionTasks.forAdvancement(node.holder().id())) {
            for (var item : task.items()) {
                Item itemType = BuiltInRegistries.ITEM.getValue(item.itemId());
                int remaining = Math.max(0, item.required() - item.submitted());
                ItemStack stack = new ItemStack(itemType, Math.max(1, remaining));
                chips.add(new RewardChip(stack, remaining, remaining > 0
                        ? Component.literal("Click to submit " + (item.label().isEmpty() ? stack.getHoverName().getString() : item.label()) + ": "
                        + item.submitted() + "/" + item.required())
                        : Component.literal("Submitted " + (item.label().isEmpty() ? stack.getHoverName().getString() : item.label()) + ": "
                        + item.submitted() + "/" + item.required())));
            }
        }
        List<ClientAdvancementRequiredItems.Hint> hints = requiredHintsOverride != null
                ? requiredHintsOverride : ClientAdvancementRequiredItems.hintsForAdvancement(node.holder().id());
        for (ClientAdvancementRequiredItems.Hint hint : hints) {
            Item itemType = BuiltInRegistries.ITEM.getValue(hint.iconItemId());
            if (itemType != null) {
                chips.add(new RewardChip(new ItemStack(itemType), 0, hint.tooltip()));
            }
        }
        return chips;
    }

    private Layout computeLayout(Font font, int boxOriginX, int boxOriginY, int screenWidth, int windowContentBottom) {
        Style frameStyle = Style.EMPTY.withColor(display.getType().getChatColor());
        Component styledDescription = ComponentUtils.mergeStyles(display.getDescription(), frameStyle);
        var description = styledDescription.copy();
        for (String hint : rewardsOverride == null ? ClientQuests.hoverHints(node.holder().id()) : java.util.List.<String>of()) {
            if (!description.getString().isBlank()) description.append("\n");
            description.append(Component.literal(hint).withStyle(Style.EMPTY.withColor(0xBFCAC8)));
        }
        styledDescription = description;

        // Custom statistic thresholds are evaluated server-side and carry Minecraft's own unit
        // formatting (cm, m, h, decimals). Prefer that live value over the generic criterion
        // count whenever this quest has a statistic task.
        Component statisticProgress = ClientStatisticProgress.forAdvancement(node.holder().id());
        Component progressText = statisticProgress != null ? statisticProgress : (progress != null ? progress.getProgressText() : null);
        int progressWidth = progressText != null ? font.width(progressText) : 0;
        // Whatever needs to be reserved past the title's own text before the box's right edge -
        // either room for the progress counter (its own width, a gap before it, and its own
        // trailing margin) or, when there's no counter, a flat padding so the title's last
        // character doesn't just sit on the box's own border.
        int titleRightReserve = progressWidth > 0 ? progressWidth + PROGRESS_GAP : 0;

        // Title text starts 32px in (after the icon); description starts 8px in (no icon there).
        // Wrapping each at a width that already reserves its own left padding (and, for the
        // title, its right-side reserve) guarantees a wrapped line, once drawn with that padding,
        // never runs past the box's own right edge - the previous version wrapped both at the same
        // raw width and only reserved padding afterward, which could push the actual drawn text
        // past a box sized from the unpadded wrap width.
        int titleWrapWidth = Math.max(20, TITLE_MAX_WIDTH - 40 - titleRightReserve);
        int descWrapWidth = Math.max(20, TITLE_MAX_WIDTH - 16);

        List<FormattedCharSequence> titleLines = font.split(display.getTitle(), titleWrapWidth);
        boolean hasDescription = !styledDescription.getString().isBlank();
        List<FormattedCharSequence> descLines = hasDescription
                ? font.split(styledDescription, descWrapWidth) : List.of();

        int maxTitleLineWidth = 0;
        for (FormattedCharSequence line : titleLines) {
            maxTitleLineWidth = Math.max(maxTitleLineWidth, font.width(line));
        }
        int maxDescLineWidth = 0;
        for (FormattedCharSequence line : descLines) {
            maxDescLineWidth = Math.max(maxDescLineWidth, font.width(line));
        }
        List<RewardChip> requiredChips = buildRequiredChips();
        List<RewardChip> rewardChips = buildRewardChips();
        boolean hasRequired = !requiredChips.isEmpty();
        boolean hasRewards = !rewardChips.isEmpty();
        boolean hasTable = hasRequired || hasRewards;
        boolean showDivider = hasRequired && hasRewards;

        // Natural (unwrapped) width the table would need at its own size - feeds into boxWidth
        // below just like the title/description do, so the box grows to fit a short reward/required
        // list instead of always sizing purely off the text and leaving the table oddly cramped.
        int dividerSpace = showDivider ? TABLE_DIVIDER_WIDTH : 0;
        int tableNaturalWidth = requiredChips.size() * REWARD_CHIP_SIZE + dividerSpace + rewardChips.size() * REWARD_CHIP_SIZE;
        int tableContentWidth = hasTable ? tableNaturalWidth + 16 : 0;

        int titleContentWidth = maxTitleLineWidth + 40 + titleRightReserve;
        int descContentWidth = maxDescLineWidth + 16;
        int boxWidth = Mth.clamp(Math.max(Math.max(titleContentWidth, descContentWidth), tableContentWidth), TITLE_MIN_WIDTH, TITLE_MAX_WIDTH);

        // Now that boxWidth is final, split its content width (minus the divider, if any) between
        // the two columns and let each wrap independently at its own share.
        int tableAvailableWidth = boxWidth - 16;
        int leftColumnWidth;
        int rightColumnWidth;
        if (showDivider) {
            int available = Math.max(0, tableAvailableWidth - TABLE_DIVIDER_WIDTH);
            leftColumnWidth = available / 2;
            rightColumnWidth = available - leftColumnWidth;
        } else if (hasRequired) {
            leftColumnWidth = tableAvailableWidth;
            rightColumnWidth = 0;
        } else {
            leftColumnWidth = 0;
            rightColumnWidth = tableAvailableWidth;
        }
        int leftChipsPerRow = Math.max(1, leftColumnWidth / REWARD_CHIP_SIZE);
        int rightChipsPerRow = Math.max(1, rightColumnWidth / REWARD_CHIP_SIZE);
        // Absolute x filled in below, once boxX (which depends on flipX, computed further down) is
        // known - 0 here is a placeholder replaced before the Layout is actually built.
        TableColumn requiredColumn = new TableColumn(requiredChips, 0, leftColumnWidth, leftChipsPerRow);
        TableColumn rewardColumn = new TableColumn(rewardChips, 0, rightColumnWidth, rightChipsPerRow);
        int tableRows = Math.max(requiredColumn.rows(), rewardColumn.rows());

        int titleBoxHeight = 9 * titleLines.size() + 9 + 8;
        int descBoxHeight = hasDescription ? 14 + 9 * descLines.size() : 0;
        int tableBoxHeight = hasTable ? REWARD_ROW_TOP_PAD + tableRows * REWARD_CHIP_SIZE + REWARD_ROW_BOTTOM_PAD : 0;

        boolean flipX = boxOriginX + boxWidth + 26 >= screenWidth;
        boolean flipY = boxOriginY + titleBoxHeight + descBoxHeight + tableBoxHeight >= windowContentBottom;

        float percent = percent();
        int filledWidth = Mth.floor(percent * boxWidth);
        AdvancementWidgetType leftType;
        AdvancementWidgetType rightType;
        AdvancementWidgetType frameType;
        if (percent >= 1.0F) {
            filledWidth = boxWidth / 2;
            leftType = rightType = frameType = AdvancementWidgetType.OBTAINED;
        } else if (filledWidth < 2) {
            filledWidth = boxWidth / 2;
            leftType = rightType = frameType = AdvancementWidgetType.UNOBTAINED;
        } else if (filledWidth > boxWidth - 2) {
            filledWidth = boxWidth / 2;
            leftType = rightType = AdvancementWidgetType.OBTAINED;
            frameType = AdvancementWidgetType.UNOBTAINED;
        } else {
            leftType = AdvancementWidgetType.OBTAINED;
            rightType = frameType = AdvancementWidgetType.UNOBTAINED;
        }
        int splitWidth = boxWidth - filledWidth;

        int boxX = flipX ? boxOriginX - boxWidth + 26 + 6 : boxOriginX;
        int totalHeight = titleBoxHeight + descBoxHeight + tableBoxHeight;
        int boxTop;
        int titleY;
        int descY;
        int tableY;
        if (flipY) {
            // The icon/frame never move (see the "always sit at the widget's own fixed screen
            // position" comment below), so the title row - which must stay visually attached to the
            // icon - has to end up at the BOTTOM of the flipped box, right where the icon already
            // is, rather than the top; otherwise the box's bottom (still title-shaped) would land
            // squarely on top of the icon instead of the description/table content. Matches
            // vanilla's own title-at-bottom-when-flipped behavior.
            boxTop = boxOriginY + 26 - totalHeight;
            descY = boxTop;
            tableY = descY + descBoxHeight;
            titleY = tableY + tableBoxHeight;
        } else {
            boxTop = boxOriginY;
            titleY = boxTop;
            descY = titleY + titleBoxHeight;
            tableY = descY + descBoxHeight;
        }

        int leftColumnX = boxX + 8;
        int rightColumnX = showDivider ? leftColumnX + leftColumnWidth + dividerSpace : boxX + 8;
        int dividerX = leftColumnX + leftColumnWidth + dividerSpace / 2;
        requiredColumn = new TableColumn(requiredColumn.chips(), leftColumnX, requiredColumn.width(), requiredColumn.chipsPerRow());
        rewardColumn = new TableColumn(rewardColumn.chips(), rightColumnX, rewardColumn.width(), rewardColumn.chipsPerRow());

        return new Layout(titleLines, descLines, hasDescription, hasTable, requiredColumn, rewardColumn,
                showDivider, dividerX, tableRows, boxWidth, titleBoxHeight, descBoxHeight, tableBoxHeight,
                flipX, flipY, boxX, boxTop, titleY, descY, tableY,
                progressText, progressWidth, filledWidth, splitWidth, leftType, rightType, frameType);
    }

    /**
     * Pure layout, no drawing - lets the screen hit-test "is the mouse still within the hover box"
     * every frame without needing to actually be rendering it, which is what makes the hover
     * persist while the mouse moves off the icon and into the box itself (e.g. to read a reward's
     * tooltip) instead of vanishing the instant it leaves the icon's own 26x26 border.
     */
    public HoverBounds computeHoverBounds(Font font, int boxOriginX, int boxOriginY, int screenWidth, int windowContentBottom) {
        Layout layout = computeLayout(font, boxOriginX, boxOriginY, screenWidth, windowContentBottom);
        return new HoverBounds(layout.boxX(), layout.boxTop(), layout.boxWidth(), layout.totalHeight());
    }

    /** Returns the exact submission item whose hover icon was clicked, or {@code null}. This is
     * intentionally narrower than the whole quest card: clicking iron only submits iron's task,
     * while the old right-click-the-node shortcut remains available for convenience. */
    public record SubmissionHit(SubmissionTasksPayload.Task task, Identifier itemId) { }

    public SubmissionHit submissionAt(Font font, int boxOriginX, int boxOriginY,
                                                     int screenWidth, int windowContentBottom, int mouseX, int mouseY) {
        List<SubmissionTasksPayload.Task> tasks = ClientSubmissionTasks.forAdvancement(node.holder().id());
        if (tasks.isEmpty()) return null;
        Layout layout = computeLayout(font, boxOriginX, boxOriginY, screenWidth, windowContentBottom);
        TableColumn column = layout.requiredColumn();
        int normalItemCount = (requiredItemsOverride != null ? requiredItemsOverride
                : ClientAdvancementRequiredItems.forAdvancement(node.holder().id())).size();
        int itemIndex = 0;
        int rowStartY = layout.tableY() + REWARD_ROW_TOP_PAD;
        for (int row = 0; row < layout.tableRows(); row++) {
            int rowStart = row * column.chipsPerRow();
            if (rowStart >= column.chips().size()) break;
            int rowEnd = Math.min(column.chips().size(), rowStart + column.chipsPerRow());
            int rowWidth = (rowEnd - rowStart) * REWARD_CHIP_SIZE;
            int rowX = column.x() + Math.max(0, (column.width() - rowWidth) / 2);
            for (int index = rowStart; index < rowEnd; index++) {
                int chipX = rowX + (index - rowStart) * REWARD_CHIP_SIZE;
                int chipY = rowStartY + row * REWARD_CHIP_SIZE;
                if (mouseX >= chipX && mouseX < chipX + 16 && mouseY >= chipY && mouseY < chipY + 16 && index >= normalItemCount) {
                    int submissionIndex = index - normalItemCount;
                    for (SubmissionTasksPayload.Task task : tasks) {
                        if (submissionIndex < task.items().size()) {
                            SubmissionTasksPayload.ItemProgress item = task.items().get(submissionIndex);
                            return item.submitted() < item.required() ? new SubmissionHit(task, item.key()) : null;
                        }
                        submissionIndex -= task.items().size();
                    }
                }
                itemIndex++;
            }
        }
        return null;
    }

    public record HoverItem(ItemStack item, int x, int y) { }

    /** Uses the rendered card geometry, including wrapped and flipped columns. */
    public HoverItem hoverIngredientAt(Font font, int originX, int originY, int screenWidth, int bottom, int mouseX, int mouseY) {
        Layout layout = computeLayout(font, originX, originY, screenWidth, bottom);
        for (TableColumn column : List.of(layout.requiredColumn(), layout.rewardColumn())) {
            for (int row = 0; row < column.rows(); row++) {
                int start = row * column.chipsPerRow(), end = Math.min(column.chips().size(), start + column.chipsPerRow());
                int rowX = column.x() + Math.max(0, (column.width() - (end - start) * REWARD_CHIP_SIZE) / 2);
                int chipY = layout.tableY() + REWARD_ROW_TOP_PAD + row * REWARD_CHIP_SIZE;
                for (int i = start; i < end; i++) {
                    int chipX = rowX + (i - start) * REWARD_CHIP_SIZE;
                    if (mouseX >= chipX && mouseX < chipX + 16 && mouseY >= chipY && mouseY < chipY + 16)
                        return new HoverItem(column.chips().get(i).icon().copy(), chipX, chipY);
                }
            }
        }
        return null;
    }

    /** Returns the clicked reward's stable index, or -1 outside the visible reward icons. */
    public int rewardChipAt(Font font, int boxOriginX, int boxOriginY,
                                  int screenWidth, int windowContentBottom, int mouseX, int mouseY) {
        Layout layout = computeLayout(font, boxOriginX, boxOriginY, screenWidth, windowContentBottom);
        TableColumn column = layout.rewardColumn();
        int rowStartY = layout.tableY() + REWARD_ROW_TOP_PAD;
        for (int row = 0; row < layout.tableRows(); row++) {
            int rowStart = row * column.chipsPerRow();
            if (rowStart >= column.chips().size()) break;
            int rowEnd = Math.min(column.chips().size(), rowStart + column.chipsPerRow());
            int rowWidth = (rowEnd - rowStart) * REWARD_CHIP_SIZE;
            int rowX = column.x() + Math.max(0, (column.width() - rowWidth) / 2);
            for (int index = rowStart; index < rowEnd; index++) {
                int chipX = rowX + (index - rowStart) * REWARD_CHIP_SIZE;
                int chipY = rowStartY + row * REWARD_CHIP_SIZE;
                if (mouseX >= chipX && mouseX < chipX + 16 && mouseY >= chipY && mouseY < chipY + 16) return index;
            }
        }
        return -1;
    }

    /**
     * Vanilla's real hover box - not a generic floating tooltip, but an expanding {@code title_box}
     * panel attached to the icon itself, with a progress-colored split background and the frame+icon
     * redrawn inside. Transcribed from {@code AdvancementWidget.extractHover}'s bytecode (constants,
     * layout math, the OBTAINED/UNOBTAINED split-background branching) rather than guessed. Two
     * knowing simplifications versus vanilla: the box width uses a direct max-line-width computation
     * instead of vanilla's optimal-wrap-width search (same visual result, simpler code), and the
     * sprite choice when there's no description is always {@code title_box} rather than vanilla's
     * separate no-description sprite branch - a decision that only affects a handful of description-less
     * root advancements. Extended beyond vanilla with an optional "To gain:" reward row.
     *
     * @param boxOriginX this widget's actual on-screen X (see {@link CodexAdvancementTab#widgetScreenX})
     * @param screenWidth the real game window width, for flipping the box left instead of right
     * @param windowContentBottom the bottom edge of our own window's content area, for flipping down
     * @param mouseX/mouseY current mouse position, for the reward chips' hover-only tooltips
     */
    public void extractHover(GuiGraphicsExtractor extractor, Font font, int boxOriginX, int boxOriginY,
                              int screenWidth, int windowContentBottom, int mouseX, int mouseY) {
        Layout layout = computeLayout(font, boxOriginX, boxOriginY, screenWidth, windowContentBottom);

        if (layout.hasDescription() || layout.hasTable()) {
            extractor.blitSprite(RenderPipelines.GUI_TEXTURED, TITLE_BOX_SPRITE, layout.boxX(), layout.boxTop(), layout.boxWidth(), layout.totalHeight());
        } else {
            extractor.blitSprite(RenderPipelines.GUI_TEXTURED, TITLE_BOX_SPRITE, layout.boxX(), layout.boxTop(), layout.boxWidth(), layout.titleBoxHeight());
        }
        if (layout.leftType() != layout.rightType()) {
            extractor.blitSprite(RenderPipelines.GUI_TEXTURED, layout.leftType().boxSprite(), 200, layout.titleBoxHeight(), 0, 0,
                    layout.boxX(), layout.titleY(), layout.filledWidth(), layout.titleBoxHeight());
            extractor.blitSprite(RenderPipelines.GUI_TEXTURED, layout.rightType().boxSprite(), 200, layout.titleBoxHeight(), 200 - layout.splitWidth(), 0,
                    layout.boxX() + layout.filledWidth(), layout.titleY(), layout.splitWidth(), layout.titleBoxHeight());
        } else {
            extractor.blitSprite(RenderPipelines.GUI_TEXTURED, layout.leftType().boxSprite(), layout.boxX(), layout.titleY(), layout.boxWidth(), layout.titleBoxHeight());
        }
        extractIcon(extractor, boxOriginX, boxOriginY);

        int titleTextX = layout.flipX() ? layout.boxX() + 8 : boxOriginX + 32;
        extractMultilineText(extractor, font, layout.titleLines(), titleTextX, layout.titleY() + 9, -1);
        if (layout.progressText() != null) {
            // On a left-opening card the fixed icon occupies the right end of the header.
            // Reserve its full32px column there, mirroring the title's left-side icon inset.
            int progressRight = layout.boxX() + layout.boxWidth() - (layout.flipX() ? 32 : 8);
            extractor.text(font, layout.progressText(), progressRight - layout.progressWidth(), layout.titleY() + 9, -1);
        }
        if (layout.hasDescription()) {
            // Equal breathing room above/below text, including upward-opening cards.
            int descTextY = layout.descY() + 8;
            extractMultilineText(extractor, font, layout.descLines(), layout.boxX() + 8, descTextY, -1);
        }
        if (layout.hasTable()) {
            extractTable(extractor, font, layout, mouseX, mouseY);
        }
    }

    /** Draws both columns (whichever are non-empty) plus the divider between them, when both are
     * present. */
    private void extractTable(GuiGraphicsExtractor extractor, Font font, Layout layout, int mouseX, int mouseY) {
        int rowStartY = layout.tableY() + REWARD_ROW_TOP_PAD;
        Component hoveredTooltip = drawChipColumn(extractor, font, layout.requiredColumn(), rowStartY, layout.tableRows(), mouseX, mouseY);
        Component rewardHovered = drawChipColumn(extractor, font, layout.rewardColumn(), rowStartY, layout.tableRows(), mouseX, mouseY,
                node.holder().id());
        if (hoveredTooltip == null) {
            hoveredTooltip = rewardHovered;
        }
        if (layout.showDivider()) {
            int dividerTop = rowStartY;
            int dividerBottom = rowStartY + layout.tableRows() * REWARD_CHIP_SIZE;
            extractor.verticalLine(layout.dividerX(), dividerTop, dividerBottom, 0x55FFFFFF);
        }
        if (hoveredTooltip != null) {
            extractor.setComponentTooltipForNextFrame(font, List.of(hoveredTooltip), mouseX, mouseY);
        }
    }

    /** Each row is centered independently within the column's own width (rather than the whole
     * column left-aligned) so a short final row - or the common case of just one or two chips
     * total - doesn't look stranded against the column's edge. Rendered against {@code tableRows}
     * (shared with the other column) rather than this column's own row count, so both columns'
     * rows line up even when one has more content than the other. */
    private Component drawChipColumn(GuiGraphicsExtractor extractor, Font font, TableColumn column, int rowStartY, int tableRows, int mouseX, int mouseY) {
        return drawChipColumn(extractor, font, column, rowStartY, tableRows, mouseX, mouseY, null);
    }

    private Component drawChipColumn(GuiGraphicsExtractor extractor, Font font, TableColumn column, int rowStartY, int tableRows, int mouseX, int mouseY,
                                     Identifier claimAdvancementId) {
        List<RewardChip> chips = column.chips();
        if (chips.isEmpty()) {
            return null;
        }
        Component hoveredTooltip = null;
        for (int row = 0; row < tableRows; row++) {
            int rowStart = row * column.chipsPerRow();
            if (rowStart >= chips.size()) {
                break;
            }
            int rowEnd = Math.min(chips.size(), rowStart + column.chipsPerRow());
            int rowWidth = (rowEnd - rowStart) * REWARD_CHIP_SIZE;
            int rowX = column.x() + Math.max(0, (column.width() - rowWidth) / 2);
            int chipY = rowStartY + row * REWARD_CHIP_SIZE;
            for (int i = rowStart; i < rowEnd; i++) {
                RewardChip chip = chips.get(i);
                int chipX = rowX + (i - rowStart) * REWARD_CHIP_SIZE;
                float pulse = claimAdvancementId == null ? QuestFeedback.itemPulse(node.holder().id(), chip.icon()) : QuestFeedback.pulse(node.holder().id(), "claim");
                if (pulse > 0) extractor.fill(chipX - 1, chipY - 1, chipX + 17, chipY + 17, ((int)(pulse * 80) << 24) | (claimAdvancementId == null ? 0x66E4BD : 0xFFE195));
                extractor.pose().pushMatrix();
                float bounce = (float)Math.sin(pulse * Math.PI) * 0.08F;
                extractor.pose().translate(chipX + 8, chipY + 8); extractor.pose().scale(1 + bounce); extractor.pose().translate(-chipX - 8, -chipY - 8);
                extractor.fakeItem(chip.icon(), chipX, chipY);
                extractor.pose().popMatrix();
                if (chip.tooltip() != null && chip.tooltip().getString().startsWith("Click to submit")) {
                    // Teal is distinct from the gold claim marker: this is a per-item submit
                    // action, and clicking this exact icon consumes only this requirement.
                    extractor.fill(chipX + 12, chipY, chipX + 16, chipY + 4, 0xFF003F45);
                    extractor.fill(chipX + 13, chipY + 1, chipX + 15, chipY + 3, 0xFF3DFFF3);
                }
                if (claimAdvancementId != null && ClientClaimableRewards.contains(claimAdvancementId, i)) {
                    extractor.fill(chipX + 12, chipY, chipX + 16, chipY + 4, 0xFF5A3D00);
                    extractor.fill(chipX + 13, chipY + 1, chipX + 15, chipY + 3, 0xFFFFD24A);
                }
                if (chip.count() > 0) {
                    extractor.itemDecorations(font, chip.icon(), chipX, chipY, String.valueOf(chip.count()));
                }
                if (chip.tooltip() != null && mouseX >= chipX && mouseX < chipX + 16 && mouseY >= chipY && mouseY < chipY + 16) {
                    hoveredTooltip = chip.tooltip();
                    if (!chip.icon().isEmpty() && dev.architectury.platform.Platform.isModLoaded("jei"))
                        hoveredTooltip = hoveredTooltip.copy().append(Component.literal(" · JEI: recipe / uses keys"));
                }
            }
        }
        return hoveredTooltip;
    }

    private void extractMultilineText(GuiGraphicsExtractor extractor, Font font, List<FormattedCharSequence> lines, int x, int y, int color) {
        for (int i = 0; i < lines.size(); i++) {
            extractor.text(font, lines.get(i), x, y + i * 9, color);
        }
    }
}
