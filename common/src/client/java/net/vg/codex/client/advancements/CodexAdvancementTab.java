package net.vg.codex.client.advancements;

import com.google.gson.JsonObject;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Recreation of vanilla's {@code AdvancementTab} (one root advancement's tab button + scrollable
 * tree of {@link CodexAdvancementWidget}s). Deliberately does NOT replicate vanilla's own
 * package-private {@code AdvancementTabType} 4-sided overflow behavior (ABOVE/BELOW/LEFT/RIGHT,
 * wrapping around the window once each side fills up) - at this screen's much larger, resizable
 * window size, that layout put tabs inside the tree content itself, running off the left edge of
 * the screen, or overlapping the edit panel/footer buttons, none of which vanilla's original fixed
 * 252x140 window ever had to account for. Instead every tab lives in a single top row (see
 * {@link CodexAdvancementsScreen}'s tab-row building/rendering), with {@code <}/{@code >} paging
 * arrows once more tabs exist than fit the window's current width - unbounded tab count, not
 * limited by how much perimeter the window happens to have. This class itself no longer knows or
 * cares where on screen it's drawn; the screen passes an explicit x/y each frame. Tree content
 * (widgets, connecting lines, background) still reuses vanilla's own layout
 * ({@link net.minecraft.advancements.TreeNodePosition}) and sprites ({@code AdvancementWidgetType},
 * which is public) exactly.
 */
public final class CodexAdvancementTab {
    // Only vanilla's "above" tab art is used now (single top row) - reused as-is, no new art.
    public static final int TAB_WIDTH = 28;
    public static final int TAB_HEIGHT = 32;
    public static final int TAB_SLOT = TAB_WIDTH + 4;
    private static final int ICON_OFFSET_X = 6;
    private static final int ICON_OFFSET_Y = 9;

    /** Which 3-slice sprite segment a slot at this row position uses - {@code first}/{@code last}
     * describe the position within the currently *rendered* row (which may be a mid-list page, not
     * the absolute first/last tab overall), so the rounded end caps always land on whatever the
     * row's own visible edges are, arrows included. */
    public static Identifier tabSprite(boolean first, boolean last, boolean selected) {
        String base = first ? "tab_above_left" : last ? "tab_above_right" : "tab_above_middle";
        return Identifier.withDefaultNamespace("advancements/" + base + (selected ? "_selected" : ""));
    }

    private final AdvancementNode rootNode;
    private DisplayInfo display;
    private ItemStack icon;
    private Component title;
    private final CodexAdvancementWidget root;
    // Keyed by Identifier, not AdvancementHolder - AdvancementHolder is a record over the full
    // Advancement (including its whole criteria map), so its equals/hashCode is only as reliable
    // as every criterion type's own equals/hashCode. A lookup miss here (e.g. onUpdateAdvancementProgress
    // never finding the widget it just updated) silently drops that advancement's progress from
    // view without any error - confirmed as the cause of "A Balanced Diet" (40 criteria - much more
    // surface area for a mismatch) never showing a progress counter while simpler advancements did.
    // Identifier is a plain value type with unambiguous equality, so this class of bug can't recur.
    private final Map<Identifier, CodexAdvancementWidget> widgets = new HashMap<>();

    private double scrollX;
    private double scrollY;
    private int minX;
    private int minY;
    private int maxX;
    private int maxY;
    private int lastContentWidth = -1;
    private int lastContentHeight = -1;

    private CodexAdvancementTab(AdvancementNode rootNode, DisplayInfo display) {
        this.rootNode = rootNode;
        this.display = display;
        this.icon = display.getIcon().create();
        this.title = display.getTitle();
        this.root = new CodexAdvancementWidget(rootNode, display);
        addWidget(root, rootNode.holder().id());
    }

    public static CodexAdvancementTab create(AdvancementNode rootNode) {
        Optional<DisplayInfo> displayInfo = rootNode.advancement().display();
        if (displayInfo.isEmpty()) {
            return null;
        }
        return new CodexAdvancementTab(rootNode, displayInfo.get());
    }

    public AdvancementNode getRootNode() {
        return rootNode;
    }

    public CodexAdvancementWidget getRoot() {
        return root;
    }

    public Component getTitle() {
        return title;
    }

    /**
     * The tab button's own icon/title are captured once at construction from the root's display -
     * unlike a node's widget (whose display can be swapped live via {@link CodexAdvancementWidget#setDisplay}),
     * the tab strip button has no such hook, so editing a tab's root title/icon needs an explicit
     * refresh here or the tab button goes stale even though the tree content updates fine.
     */
    public void refreshDisplay(DisplayInfo display) {
        this.display = display;
        this.icon = display.getIcon().create();
        this.title = display.getTitle();
    }

    public CodexAdvancementWidget findById(Identifier id) {
        return widgets.get(id);
    }

    public boolean contains(Identifier id) {
        return widgets.containsKey(id);
    }

    /**
     * Edit mode's tree is a separate, locally-built structure with no player-progress tracking of
     * its own (it shows every advancement including hidden/locked ones, unrelated to what the
     * player has actually done) - but it's still useful to show progress where we happen to know
     * it, cross-referenced from view mode's own live-updated widgets by id.
     */
    public void applyProgress(Function<Identifier, AdvancementProgress> lookup) {
        for (CodexAdvancementWidget widget : widgets.values()) {
            widget.setProgress(lookup.apply(widget.node().holder().id()));
        }
    }

    /** Edit mode only - see {@link CodexAdvancementWidget#applyRewardsOverride}'s own comment. */
    public void applyRewardsOverride(Function<Identifier, JsonObject> lookup) {
        for (CodexAdvancementWidget widget : widgets.values()) {
            widget.applyRewardsOverride(lookup.apply(widget.node().holder().id()));
        }
    }

    /** Edit mode only - see {@link CodexAdvancementWidget#applyRequiredItemsOverride}. */
    public void applyRequiredItemsOverride(Function<Identifier, List<Identifier>> lookup) {
        for (CodexAdvancementWidget widget : widgets.values()) {
            widget.applyRequiredItemsOverride(lookup.apply(widget.node().holder().id()));
        }
    }

    /** Edit mode only - non-item criterion hints such as recipe unlocks. */
    public void applyRequiredHintsOverride(Function<Identifier, List<ClientAdvancementRequiredItems.Hint>> lookup) {
        for (CodexAdvancementWidget widget : widgets.values()) {
            widget.applyRequiredHintsOverride(lookup.apply(widget.node().holder().id()));
        }
    }

    /** Applies Codex's optional server-authored coordinates after vanilla has built the tree. */
    public void applyLayout(Map<Identifier, int[]> positions) {
        minX = minY = Integer.MAX_VALUE;
        maxX = maxY = Integer.MIN_VALUE;
        for (Map.Entry<Identifier, CodexAdvancementWidget> entry : widgets.entrySet()) {
            int[] position = positions.get(entry.getKey());
            if (position != null) {
                entry.getValue().setPosition(position[0], position[1]);
            }
            CodexAdvancementWidget widget = entry.getValue();
            minX = Math.min(minX, widget.x() - 30);
            minY = Math.min(minY, widget.y() - 30);
            maxX = Math.max(maxX, widget.x() + 30);
            maxY = Math.max(maxY, widget.y() + 30);
        }
        lastContentWidth = -1;
        lastContentHeight = -1;
    }

    public void addAdvancement(AdvancementNode node) {
        if (widgets.containsKey(node.holder().id()) || node.advancement().display().isEmpty()) {
            return;
        }
        AdvancementNode parentNode = node.parent();
        if (parentNode == null) {
            return;
        }
        CodexAdvancementWidget parentWidget = widgets.get(parentNode.holder().id());
        if (parentWidget == null) {
            // AdvancementTree.tasks is a plain (unordered) Set, so replay order from
            // ClientAdvancements.setListener does not guarantee a parent is added before its
            // children - walk the actual AdvancementNode.parent() chain (always valid regardless
            // of set iteration order) to build any missing ancestors first.
            addAdvancement(parentNode);
            parentWidget = widgets.get(parentNode.holder().id());
            if (parentWidget == null) {
                return;
            }
        }
        CodexAdvancementWidget widget = new CodexAdvancementWidget(node, node.advancement().display().get());
        parentWidget.addChild(widget);
        addWidget(widget, node.holder().id());
    }

    private void addWidget(CodexAdvancementWidget widget, Identifier id) {
        widgets.put(id, widget);
        minX = Math.min(minX, widget.x() - 30);
        minY = Math.min(minY, widget.y() - 30);
        maxX = Math.max(maxX, widget.x() + 30);
        maxY = Math.max(maxY, widget.y() + 30);
    }

    public CodexAdvancementWidget findHovered(int contentX, int contentY, int contentWidth, int contentHeight, float zoom, double mouseX, double mouseY) {
        if (mouseX < contentX || mouseY < contentY || mouseX >= contentX + contentWidth || mouseY >= contentY + contentHeight) {
            return null;
        }
        double localX = (mouseX - contentX) / zoom + Mth.floor(scrollX);
        double localY = (mouseY - contentY) / zoom + Mth.floor(scrollY);
        for (CodexAdvancementWidget widget : widgets.values()) {
            if (widget.isMouseOver(localX, localY)) {
                return widget;
            }
        }
        return null;
    }

    /** Inverse of the localX/localY math in {@link #findHovered} - a widget's real on-screen pixel
     * position, accounting for this tab's current scroll offset and zoom, for positioning the hover
     * box against the actual rendered icon rather than the mouse cursor. */
    public int widgetScreenX(CodexAdvancementWidget widget, int contentX, float zoom) {
        return contentX + Math.round((widget.x() - Mth.floor(scrollX)) * zoom);
    }

    public int widgetScreenY(CodexAdvancementWidget widget, int contentY, float zoom) {
        return contentY + Math.round((widget.y() - Mth.floor(scrollY)) * zoom);
    }

    public void scroll(double dx, double dy, int contentWidth, int contentHeight, float zoom) {
        int visibleWidth = Mth.ceil(contentWidth / zoom);
        int visibleHeight = Mth.ceil(contentHeight / zoom);
        if (maxX - minX > visibleWidth) {
            scrollX = Mth.clamp(scrollX + dx, minX, maxX - visibleWidth);
        }
        if (maxY - minY > visibleHeight) {
            scrollY = Mth.clamp(scrollY + dy, minY, maxY - visibleHeight);
        }
    }

    /** Icon is a separate pass from the tab background (matching vanilla) so an adjacent tab's
     * background never gets drawn on top of this one's icon. */
    public void extractIcon(GuiGraphicsExtractor extractor, int x, int y) {
        extractor.fakeItem(icon, x + ICON_OFFSET_X, y + ICON_OFFSET_Y);
    }

    public void extractContents(GuiGraphicsExtractor extractor, int contentX, int contentY, int contentWidth, int contentHeight, float zoom, boolean showAll) {
        // Re-centering (and the tile/scroll math below) works in tree-space, i.e. the amount of
        // tree actually visible through the viewport - which shrinks as zoom increases.
        int visibleWidth = Mth.ceil(contentWidth / zoom);
        int visibleHeight = Mth.ceil(contentHeight / zoom);

        // Re-center whenever the visible area actually changes (window resized or zoom changed),
        // not just once ever - otherwise a scroll position computed for one size can end up
        // clamped/frozen out of view once the viewport grows or shrinks to a different size.
        if (visibleWidth != lastContentWidth || visibleHeight != lastContentHeight) {
            scrollX = (double) (maxX + minX) / 2 - (double) visibleWidth / 2;
            scrollY = (double) (maxY + minY) / 2 - (double) visibleHeight / 2;
            lastContentWidth = visibleWidth;
            lastContentHeight = visibleHeight;
        }

        extractor.enableScissor(contentX, contentY, contentX + contentWidth, contentY + contentHeight);
        extractor.pose().pushMatrix();
        extractor.pose().translate((float) contentX, (float) contentY);
        extractor.pose().scale(zoom);

        Identifier background = display.getBackground()
                .map(ClientAsset.ResourceTexture::texturePath)
                .orElse(TextureManager.INTENTIONAL_MISSING_TEXTURE);

        int offsetX = Mth.floor(scrollX);
        int offsetY = Mth.floor(scrollY);
        int tileOffsetX = offsetX % 16;
        int tileOffsetY = offsetY % 16;
        for (int tileX = -1; tileX <= visibleWidth / 16 + 1; tileX++) {
            for (int tileY = -1; tileY <= visibleHeight / 16 + 1; tileY++) {
                extractor.blit(RenderPipelines.GUI_TEXTURED, background,
                        -tileOffsetX + 16 * tileX, -tileOffsetY + 16 * tileY, 0.0F, 0.0F, 16, 16, 16, 16);
            }
        }

        int drawX = -offsetX;
        int drawY = -offsetY;
        root.extractChildConnectivity(extractor, drawX, drawY, true);
        root.extractChildConnectivity(extractor, drawX, drawY, false);
        root.extractRenderState(extractor, drawX, drawY, showAll);

        extractor.pose().popMatrix();
        extractor.disableScissor();
    }
}
