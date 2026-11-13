package net.vg.codex.client.advancements;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Shared searchable visual item picker used by quest and treasure editors. */
public final class ItemPickerScreen extends CodexScreen {
    private final Screen parent;
    private final Consumer<Identifier> selected;
    private String query = "";
    private int page;
    private List<Item> matches = List.of();
    private int rows;
    private int left;
    private int top;
    private int panelWidth;

    public ItemPickerScreen(Screen parent, Consumer<Identifier> selected) {
        super(Component.literal("Select Item"));
        this.parent = parent;
        this.selected = selected;
    }

    @Override protected void init() {
        panelWidth = Math.min(360, width - 24);
        left = (width - panelWidth) / 2;
        top = 30;
        rows = Math.max(1, Math.min(9, (height - 116) / 24));
        EditBox search = addRenderableWidget(new EditBox(font, left, top, panelWidth, 20, Component.literal("Search items")));
        search.setMaxLength(256);
        search.setValue(query);
        search.setResponder(value -> { query = value; page = 0; refresh(); });
        refresh();
        setInitialFocus(search);
    }

    private void refresh() {
        String needle = query.toLowerCase(Locale.ROOT);
        matches = BuiltInRegistries.ITEM.stream().filter(i -> i != Items.AIR)
                .filter(i -> BuiltInRegistries.ITEM.getKey(i).toString().contains(needle) || new ItemStack(i).getHoverName().getString().toLowerCase(Locale.ROOT).contains(needle)).toList();
        // Rebuild result widgets while preserving the search widget and its keyboard focus.
        for (var child : List.copyOf(children())) if (child instanceof Button button) removeWidget(button);
        int start = page * rows;
        for (int index = start; index < Math.min(matches.size(), start + rows); index++) {
            Item item = matches.get(index);
            addRenderableWidget(Button.builder(Component.literal("    " + new ItemStack(item).getHoverName().getString()), button -> {
                selected.accept(BuiltInRegistries.ITEM.getKey(item));
                if (minecraft.screen == this) minecraft.setScreen(parent);
            }).bounds(left, top + 28 + (index - start) * 24, panelWidth, 22).build());
        }
        int footer = top + 32 + rows * 24;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { if (page > 0) { page--; refresh(); } }).bounds(left, footer, 32, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; refresh(); }).bounds(left + 36, footer, 32, 20).build()).active = start + rows < matches.size();
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(left + panelWidth - 90, footer, 90, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xED202020);
        graphics.text(font, title, left, 14, 0xFFFFFFFF, false);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        for (int row = 0; row < rows && page * rows + row < matches.size(); row++) {
            graphics.fakeItem(new ItemStack(matches.get(page * rows + row)), left + 5, top + 31 + row * 24);
        }
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
