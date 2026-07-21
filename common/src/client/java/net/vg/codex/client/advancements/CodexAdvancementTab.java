package net.vg.codex.client.advancements;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
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
import java.util.Map;
import java.util.Optional;

/**
 * Recreation of vanilla's {@code AdvancementTab} (one root advancement's tab button + scrollable
 * tree of {@link CodexAdvancementWidget}s). Vanilla's own {@code AdvancementTabType} enum (which
 * has the exact rounded-tab sprite composition and 4-side overflow layout) is package-private and
 * inaccessible from here, so tab-button chrome is a simplified single-row strip using vanilla's
 * real sprite textures stretched into a fixed box rather than the exact 3-slice cap composition -
 * a known simplification to revisit after a visual look. Tree content (widgets, connecting lines,
 * background) reuses vanilla's own layout ({@link net.minecraft.advancements.TreeNodePosition})
 * and sprites ({@code AdvancementWidgetType}, which is public) exactly.
 */
public final class CodexAdvancementTab {
    private static final int TAB_WIDTH = 28;
    private static final int TAB_HEIGHT = 32;
    private static final Identifier TAB_SPRITE = Identifier.withDefaultNamespace("advancements/tab_above_middle");
    private static final Identifier TAB_SPRITE_SELECTED = Identifier.withDefaultNamespace("advancements/tab_above_middle_selected");

    private final int index;
    private final AdvancementNode rootNode;
    private final DisplayInfo display;
    private final ItemStack icon;
    private final Component title;
    private final CodexAdvancementWidget root;
    private final Map<AdvancementHolder, CodexAdvancementWidget> widgets = new HashMap<>();

    private double scrollX;
    private double scrollY;
    private int minX;
    private int minY;
    private int maxX;
    private int maxY;
    private boolean centered;

    private CodexAdvancementTab(int index, AdvancementNode rootNode, DisplayInfo display) {
        this.index = index;
        this.rootNode = rootNode;
        this.display = display;
        this.icon = display.getIcon().create();
        this.title = display.getTitle();
        this.root = new CodexAdvancementWidget(rootNode, display);
        addWidget(root, rootNode.holder());
    }

    public static CodexAdvancementTab create(int tabIndex, AdvancementNode rootNode) {
        Optional<DisplayInfo> displayInfo = rootNode.advancement().display();
        if (displayInfo.isEmpty()) {
            return null;
        }
        return new CodexAdvancementTab(tabIndex, rootNode, displayInfo.get());
    }

    public AdvancementNode getRootNode() {
        return rootNode;
    }

    public Component getTitle() {
        return title;
    }

    public CodexAdvancementWidget getWidget(AdvancementHolder holder) {
        return widgets.get(holder);
    }

    public void addAdvancement(AdvancementNode node) {
        if (widgets.containsKey(node.holder()) || node.advancement().display().isEmpty()) {
            return;
        }
        AdvancementNode parentNode = node.parent();
        if (parentNode == null) {
            return;
        }
        CodexAdvancementWidget parentWidget = widgets.get(parentNode.holder());
        if (parentWidget == null) {
            // AdvancementTree.tasks is a plain (unordered) Set, so replay order from
            // ClientAdvancements.setListener does not guarantee a parent is added before its
            // children - walk the actual AdvancementNode.parent() chain (always valid regardless
            // of set iteration order) to build any missing ancestors first.
            addAdvancement(parentNode);
            parentWidget = widgets.get(parentNode.holder());
            if (parentWidget == null) {
                return;
            }
        }
        CodexAdvancementWidget widget = new CodexAdvancementWidget(node, node.advancement().display().get());
        parentWidget.addChild(widget);
        addWidget(widget, node.holder());
    }

    private void addWidget(CodexAdvancementWidget widget, AdvancementHolder holder) {
        widgets.put(holder, widget);
        minX = Math.min(minX, widget.x() - 30);
        minY = Math.min(minY, widget.y() - 30);
        maxX = Math.max(maxX, widget.x() + 30);
        maxY = Math.max(maxY, widget.y() + 30);
    }

    public CodexAdvancementWidget findHovered(int contentX, int contentY, int contentWidth, int contentHeight, double mouseX, double mouseY) {
        if (mouseX < contentX || mouseY < contentY || mouseX >= contentX + contentWidth || mouseY >= contentY + contentHeight) {
            return null;
        }
        double localX = mouseX - contentX + Mth.floor(scrollX);
        double localY = mouseY - contentY + Mth.floor(scrollY);
        for (CodexAdvancementWidget widget : widgets.values()) {
            if (widget.isMouseOver(localX, localY)) {
                return widget;
            }
        }
        return null;
    }

    public void scroll(double dx, double dy, int contentWidth, int contentHeight) {
        if (maxX - minX > contentWidth) {
            scrollX = Mth.clamp(scrollX + dx, minX, maxX - contentWidth);
        }
        if (maxY - minY > contentHeight) {
            scrollY = Mth.clamp(scrollY + dy, minY, maxY - contentHeight);
        }
    }

    private int tabX(int screenX) {
        return screenX + 8 + index * TAB_WIDTH;
    }

    private int tabY(int screenY) {
        return screenY - TAB_HEIGHT + 4;
    }

    public boolean isMouseOverTabButton(int screenX, int screenY, double mouseX, double mouseY) {
        int x = tabX(screenX);
        int y = tabY(screenY);
        return mouseX >= x && mouseX < x + TAB_WIDTH && mouseY >= y && mouseY < y + TAB_HEIGHT;
    }

    public void extractTab(GuiGraphicsExtractor extractor, int screenX, int screenY, boolean selected) {
        int x = tabX(screenX);
        int y = tabY(screenY);
        extractor.blitSprite(RenderPipelines.GUI_TEXTURED, selected ? TAB_SPRITE_SELECTED : TAB_SPRITE, x, y, TAB_WIDTH, TAB_HEIGHT);
        extractor.fakeItem(icon, x + (TAB_WIDTH - 16) / 2, y + (TAB_HEIGHT - 16) / 2);
    }

    public void extractIcon(GuiGraphicsExtractor extractor, int screenX, int screenY) {
        // Icon is drawn as part of extractTab for this simplified single-pass tab strip.
    }

    public void extractContents(GuiGraphicsExtractor extractor, int contentX, int contentY, int contentWidth, int contentHeight, boolean showAll) {
        if (!centered) {
            scrollX = (double) (maxX + minX) / 2 - (double) contentWidth / 2;
            scrollY = (double) (maxY + minY) / 2 - (double) contentHeight / 2;
            centered = true;
        }

        extractor.enableScissor(contentX, contentY, contentX + contentWidth, contentY + contentHeight);
        extractor.pose().pushMatrix();
        extractor.pose().translate((float) contentX, (float) contentY);

        Identifier background = display.getBackground()
                .map(ClientAsset.ResourceTexture::texturePath)
                .orElse(TextureManager.INTENTIONAL_MISSING_TEXTURE);

        int offsetX = Mth.floor(scrollX);
        int offsetY = Mth.floor(scrollY);
        int tileOffsetX = offsetX % 16;
        int tileOffsetY = offsetY % 16;
        for (int tileX = -1; tileX <= contentWidth / 16 + 1; tileX++) {
            for (int tileY = -1; tileY <= contentHeight / 16 + 1; tileY++) {
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
