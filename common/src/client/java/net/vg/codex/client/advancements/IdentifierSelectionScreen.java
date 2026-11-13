package net.vg.codex.client.advancements;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** Searchable server/registry choices for data that has no natural item icon. */
public final class IdentifierSelectionScreen extends CodexScreen {
    private final Screen parent;
    private final List<Identifier> entries;
    private final Consumer<Identifier> selected;
    private final Function<Identifier, Component> label;
    private String query = "";
    private int page;
    private int rows;
    private int left;
    private int panelWidth;

    public IdentifierSelectionScreen(Screen parent, String title, Collection<Identifier> entries, Consumer<Identifier> selected) {
        this(parent, title, entries, id -> Component.literal(id.toString()), selected);
    }

    public IdentifierSelectionScreen(Screen parent, String title, Collection<Identifier> entries, Function<Identifier, Component> label, Consumer<Identifier> selected) {
        super(Component.literal(title)); this.parent = parent; this.entries = entries.stream().sorted().toList(); this.selected = selected; this.label = label;
    }

    @Override protected void init() {
        panelWidth = Math.min(400, width - 24); left = (width - panelWidth) / 2; rows = Math.max(1, Math.min(9, (height - 108) / 22));
        EditBox search = addRenderableWidget(new EditBox(font, left, 32, panelWidth, 20, Component.literal("Search choices")));
        search.setMaxLength(256);
        search.setValue(query); search.setResponder(value -> { query = value; page = 0; refresh(); }); refresh(); setInitialFocus(search);
    }

    private void refresh() {
        for (var child : List.copyOf(children())) if (child instanceof Button button) removeWidget(button);
        String needle = query.toLowerCase(Locale.ROOT);
        List<Identifier> matches = entries.stream().filter(id -> id.toString().contains(needle) || label.apply(id).getString().toLowerCase(Locale.ROOT).contains(needle)).toList();
        for (int i = page * rows; i < Math.min(matches.size(), (page + 1) * rows); i++) {
            Identifier id = matches.get(i);
            addRenderableWidget(Button.builder(label.apply(id), b -> { selected.accept(id); if (minecraft.screen == this) minecraft.setScreen(parent); }).bounds(left, 58 + (i % rows) * 22, panelWidth, 20).build()).setTooltip(Tooltip.create(Component.literal(id.toString())));
        }
        int y = 64 + rows * 22;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; refresh(); }).bounds(left, y, 28, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; refresh(); }).bounds(left + 32, y, 28, 20).build()).active = (page + 1) * rows < matches.size();
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(left + 68, y, 100, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) {
        graphics.fill(0, 0, width, height, 0xED202020); graphics.text(font, title, left, 14, 0xFFFFFFFF, false);
        super.extractRenderState(graphics, x, y, tick);
        QuestFeedback.render(graphics);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
