package net.vg.codex.client.advancements;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.vg.codex.advancement.QuestCriteria.StatisticCategory;

import java.util.*;
import java.util.function.Consumer;

/** Category, registry selection and numeric target instead of category|id >= count syntax. */
public final class StatisticTaskScreen extends CodexScreen {
    private final Screen parent;
    private final Consumer<JsonObject> apply;
    private final JsonObject source;
    private StatisticCategory category;
    private Identifier selected;
    private String target;
    private String error = "";
    private int left;
    private int panelWidth;

    public StatisticTaskScreen(Screen parent, JsonObject source, Consumer<JsonObject> apply) {
        super(Component.literal("Statistic Objective")); this.parent = parent; this.apply = apply; this.source = source.deepCopy();
        String value = source.has("id") ? source.get("id").getAsString() : "custom|minecraft:jump";
        category = StatisticCategory.fromValue(value); selected = Identifier.parse(value.split("\\|", 2)[1]);
        target = source.has("target") ? source.get("target").getAsString() : "16";
    }

    @Override protected void init() {
        panelWidth = Math.min(360, width - 24); left = (width - panelWidth) / 2;
        addRenderableWidget(CycleButton.builder((StatisticCategory value) -> Component.literal(value.label), category).withValues(StatisticCategory.values())
                .create(left, 42, panelWidth, 20, Component.literal("Statistic"), (button, value) -> { category = value; selected = null; rebuildWidgets(); }));
        String name = selected == null ? "Choose target…" : selected.getPath().replace('_', ' ');
        if (selected != null && itemCategory()) name = new ItemStack(BuiltInRegistries.ITEM.getValue(selected)).getHoverName().getString();
        addRenderableWidget(Button.builder(Component.literal(name), b -> pick()).bounds(left, 70, panelWidth, 22).build());
        EditBox amount = addRenderableWidget(new EditBox(font, left + 100, 102, panelWidth - 100, 20, Component.literal("Required total")));
        amount.setMaxLength(10); amount.setValue(target); amount.setResponder(value -> target = value);
        addRenderableWidget(Button.builder(Component.literal("Apply Objective"), b -> save()).bounds(left, 146, 138, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(left + 144, 146, 90, 20).build());
    }

    private boolean itemCategory() { return switch (category) { case CRAFTED, USED, BROKEN, PICKED_UP, DROPPED -> true; default -> false; }; }

    private void pick() {
        if (itemCategory()) { minecraft.setScreen(new ItemPickerScreen(this, value -> selected = value)); return; }
        List<Identifier> options = new ArrayList<>();
        switch (category) {
            case CUSTOM -> BuiltInRegistries.CUSTOM_STAT.forEach(stat -> options.add(BuiltInRegistries.CUSTOM_STAT.getKey(stat)));
            case MINED -> BuiltInRegistries.BLOCK.forEach(block -> options.add(BuiltInRegistries.BLOCK.getKey(block)));
            case KILLED, KILLED_BY -> BuiltInRegistries.ENTITY_TYPE.forEach(entity -> options.add(BuiltInRegistries.ENTITY_TYPE.getKey(entity)));
            default -> { }
        }
        minecraft.setScreen(new IdentifierSelectionScreen(this, "Select " + category.label, options, value -> selected = value));
    }

    private void save() {
        try {
            int total = Integer.parseInt(target);
            if (selected == null || total < 1) throw new IllegalArgumentException("Choose a target and a positive required total.");
            JsonObject result = source.deepCopy(); result.addProperty("id", category.key + "|" + selected); result.addProperty("target", total);
            apply.accept(result); minecraft.setScreen(parent);
        } catch (RuntimeException invalid) { error = invalid.getMessage(); }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) {
        graphics.fill(0, 0, width, height, 0xED202020); graphics.text(font, title, left, 14, 0xFFFFFFFF, false);
        graphics.text(font, "Required total", left, 108, 0xFFBBBBBB, false);
        graphics.text(font, "Uses the player's cumulative Minecraft statistic.", left, 130, 0xFFBBBBBB, false);
        super.extractRenderState(graphics, x, y, tick);
        if (!error.isEmpty()) graphics.text(font, font.plainSubstrByWidth(error, panelWidth), left, height - 18, 0xFFFF8888, false);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
