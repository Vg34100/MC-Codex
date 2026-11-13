package net.vg.codex.client.advancements;

import com.google.gson.JsonArray;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.vg.codex.advancement.AdvancementDocument;

import java.util.*;
import java.util.function.Consumer;

/** Every group is required (AND); any selected criterion satisfies its group (OR). */
public final class RequirementsScreen extends CodexScreen {
    private final Screen parent;
    private final List<String> names;
    private final List<Set<String>> groups = new ArrayList<>();
    private final Consumer<JsonArray> apply;
    private int group;
    private int page;
    private int rows;
    private int left;
    private int panelWidth;
    private String error = "";

    public RequirementsScreen(Screen parent, Set<String> criteria, JsonArray source, Consumer<JsonArray> apply) {
        super(Component.literal("Requirement Groups")); this.parent = parent; this.apply = apply;
        names = List.copyOf(criteria);
        if (source != null) source.forEach(value -> { Set<String> entries = new LinkedHashSet<>(); value.getAsJsonArray().forEach(name -> entries.add(name.getAsString())); groups.add(entries); });
        if (groups.isEmpty()) names.forEach(name -> groups.add(new LinkedHashSet<>(Set.of(name))));
    }

    @Override protected void init() {
        panelWidth = Math.min(380, width - 24); left = (width - panelWidth) / 2;
        rows = Math.max(1, Math.min(8, (height - 156) / 22));
        addRenderableWidget(Button.builder(Component.literal("< Group"), b -> { group--; rebuildWidgets(); }).bounds(left, 46, 88, 20).build()).active = group > 0;
        addRenderableWidget(Button.builder(Component.literal("Group >"), b -> { group++; rebuildWidgets(); }).bounds(left + 92, 46, 88, 20).build()).active = group + 1 < groups.size();
        addRenderableWidget(Button.builder(Component.literal("+ Group"), b -> { groups.add(new LinkedHashSet<>()); group = groups.size() - 1; rebuildWidgets(); }).bounds(left + 184, 46, 88, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Remove"), b -> { groups.remove(group); group = Math.max(0, group - 1); rebuildWidgets(); }).bounds(left + 276, 46, panelWidth - 276, 20).build()).active = groups.size() > 1;
        for (int i = page * rows; i < Math.min(names.size(), (page + 1) * rows); i++) {
            String name = names.get(i);
            addRenderableWidget(Button.builder(Component.literal((groups.get(group).contains(name) ? "[X] " : "[ ] ") + name), b -> {
                if (!groups.get(group).remove(name)) groups.get(group).add(name); rebuildWidgets();
            }).bounds(left, 74 + (i % rows) * 22, panelWidth, 20).build());
        }
        int footer = 80 + rows * 22;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(left, footer, 28, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(left + 32, footer, 28, 20).build()).active = (page + 1) * rows < names.size();
        addRenderableWidget(Button.builder(Component.literal("Apply Groups"), b -> save()).bounds(left + 66, footer, 116, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(left + 188, footer, 90, 20).build());
    }

    private void save() {
        JsonArray result = new JsonArray();
        for (Set<String> entries : groups) { JsonArray array = new JsonArray(); entries.forEach(array::add); result.add(array); }
        try { AdvancementDocument.validateGroups(result, new LinkedHashSet<>(names)); apply.accept(result); minecraft.setScreen(parent); }
        catch (RuntimeException invalid) { error = invalid.getMessage(); }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xED202020);
        graphics.text(font, title.getString() + " — " + (group + 1) + "/" + groups.size(), left, 12, 0xFFFFFFFF, false);
        graphics.text(font, "Complete every group. Within a group, any checked criterion counts.", left, 28, 0xFFBBBBBB, false);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (!error.isEmpty()) graphics.text(font, error, left, height - 14, 0xFFFF7777, false);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
