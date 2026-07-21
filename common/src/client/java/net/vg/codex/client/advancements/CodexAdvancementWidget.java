package net.vg.codex.client.advancements;

import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.advancements.AdvancementWidgetType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Recreation of vanilla's {@code AdvancementWidget} (icon + frame + connecting lines for one
 * advancement node), driven by the same {@link DisplayInfo} x/y vanilla itself uses (populated by
 * {@link net.minecraft.advancements.TreeNodePosition#run}), reusing vanilla's own
 * {@link AdvancementWidgetType} sprites so the frame/box art is pixel-identical to vanilla.
 */
public final class CodexAdvancementWidget {
    private final AdvancementNode node;
    private DisplayInfo display;
    private final ItemStack icon;
    private final int x;
    private final int y;
    private final List<CodexAdvancementWidget> children = new ArrayList<>();
    private AdvancementProgress progress;

    CodexAdvancementWidget(AdvancementNode node, DisplayInfo display) {
        this.node = node;
        this.display = display;
        this.icon = display.getIcon().create();
        this.x = Mth.floor(display.getX() * 28.0F);
        this.y = Mth.floor(display.getY() * 27.0F);
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

    public AdvancementProgress progress() {
        return progress;
    }

    void setProgress(AdvancementProgress progress) {
        this.progress = progress;
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

    public void extractRenderState(GuiGraphicsExtractor extractor, int originX, int originY, boolean showAll) {
        if (isVisible(showAll)) {
            AdvancementWidgetType widgetType = progress != null && progress.getPercent() >= 1.0F
                    ? AdvancementWidgetType.OBTAINED
                    : AdvancementWidgetType.UNOBTAINED;
            extractor.blitSprite(RenderPipelines.GUI_TEXTURED, widgetType.frameSprite(display.getType()),
                    originX + x + 3, originY + y, 26, 26);
            extractor.fakeItem(icon, originX + x + 8, originY + y + 5);
        }
        for (CodexAdvancementWidget child : children) {
            child.extractRenderState(extractor, originX, originY, showAll);
        }
    }

    public void extractConnectivity(GuiGraphicsExtractor extractor, CodexAdvancementWidget parent, int originX, int originY, boolean shadow) {
        int color = shadow ? 0xFF000000 : -1;
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
}
