package net.vg.codex.client.advancements;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Draft-only item rows: Cancel never changes the criterion; Apply returns validated JSON. */
public final class ItemRequirementsScreen extends CodexScreen {
    private final Screen parent;
    private final Consumer<JsonArray> apply;
    private final List<JsonObject> items = new ArrayList<>();
    private final List<String> counts = new ArrayList<>();
    private int page;
    private int rows;
    private int left;
    private int panelWidth;
    private String error = "";

    public ItemRequirementsScreen(Screen parent, JsonArray source, Consumer<JsonArray> apply) {
        super(Component.literal("Deliver Items")); this.parent = parent; this.apply = apply;
        for (JsonElement value : source) {
            JsonObject item = value.getAsJsonObject().deepCopy(); items.add(item);
            counts.add(item.has("count") ? item.get("count").getAsString() : "1");
        }
    }

    @Override protected void init() {
        panelWidth = Math.min(390, width - 24); left = (width - panelWidth) / 2;
        rows = Math.max(1, Math.min(7, (height - 140) / 28));
        page = Math.min(page, Math.max(0, (items.size() - 1) / rows));
        for (int index = page * rows; index < Math.min(items.size(), (page + 1) * rows); index++) {
            final int entryIndex = index;
            int y = 54 + (index % rows) * 28;
            var resolved = row(items.get(index));
            Identifier id = resolved.icon();
            String label = resolved.label();
            addRenderableWidget(Button.builder(Component.literal("    " + label), b -> minecraft.setScreen(new ItemPickerScreen(this, picked -> { items.get(entryIndex).remove("tag"); items.get(entryIndex).addProperty("id", picked.toString()); })))
                    .bounds(left, y, panelWidth - 144, 22).build());
            addRenderableWidget(Button.builder(Component.literal("Props"), b -> minecraft.setScreen(new DeliveryPropertiesScreen(this, items.get(entryIndex), updated -> items.set(entryIndex, updated)))).bounds(left + panelWidth - 140, y, 44, 22).build());
            EditBox quantity = addRenderableWidget(new EditBox(font, left + panelWidth - 92, y, 62, 22, Component.literal("Required quantity")));
            quantity.setMaxLength(7); quantity.setValue(counts.get(index)); quantity.setResponder(value -> counts.set(entryIndex, value));
            addRenderableWidget(Button.builder(Component.literal("X"), b -> { items.remove(entryIndex); counts.remove(entryIndex); rebuildWidgets(); }).bounds(left + panelWidth - 26, y, 26, 22).build());
        }
        int footer = 60 + rows * 28;
        addRenderableWidget(Button.builder(Component.literal("Add Item"), b -> minecraft.setScreen(new ItemPickerScreen(this, id -> {
            JsonObject item = new JsonObject(); item.addProperty("id", id.toString()); item.addProperty("count", 1);
            items.add(item); counts.add("1"); page = (items.size() - 1) / rows;
        }))).bounds(left, footer, 90, 20).build()).active = items.size() < 64;
        addRenderableWidget(Button.builder(Component.literal("Add Tag"), b -> minecraft.setScreen(new IdentifierSelectionScreen(this, "Item Tag", BuiltInRegistries.ITEM.listTagIds().map(net.minecraft.tags.TagKey::location).toList(), id -> Component.literal(ClientRewardPresentation.pretty(id)), id -> {
            JsonObject row = new JsonObject(); row.addProperty("tag", id.toString()); row.addProperty("count", 1); items.add(row); counts.add("1"); page = (items.size() - 1) / rows;
        })) ).bounds(left + 96, footer, 90, 20).build()).active = items.size() < 64;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(left + 192, footer, 24, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(left + 220, footer, 24, 20).build()).active = (page + 1) * rows < items.size();
        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> save()).bounds(left, footer + 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(left + 106, footer + 26, 100, 20).build());
    }

    private void save() {
        try {
            if (items.isEmpty()) throw new IllegalArgumentException("Add at least one item.");
            JsonArray result = new JsonArray();
            for (int i = 0; i < items.size(); i++) {
                int count = Integer.parseInt(counts.get(i));
                if (count < 1 || count > 1_000_000) throw new IllegalArgumentException("Quantity must be 1–1,000,000.");
                JsonObject item = items.get(i).deepCopy(); item.addProperty("count", count); result.add(item);
            }
            JsonObject task = new JsonObject(); task.add("items", result); net.vg.codex.advancement.SubmissionPlan.resolve(task, minecraft.level.registryAccess());
            apply.accept(result); minecraft.setScreen(parent);
        } catch (RuntimeException invalid) { error = invalid.getMessage() == null ? "Invalid quantity." : invalid.getMessage(); }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xED202020);
        graphics.text(font, title, left, 12, 0xFFFFFFFF, false);
        graphics.text(font, "Submitting consumes items; partial deliveries are saved.", left, 28, 0xFFBBBBBB, false);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        for (int i = page * rows; i < Math.min(items.size(), (page + 1) * rows); i++) {
            graphics.fakeItem(new ItemStack(BuiltInRegistries.ITEM.getValue(row(items.get(i)).icon())), left + 4, 57 + (i % rows) * 28);
        }
        if (!error.isEmpty()) graphics.text(font, error, left, height - 14, 0xFFFF7777, false);
    }

    private net.vg.codex.advancement.SubmissionPlan.Requirement row(JsonObject row) {
        try { JsonObject task = new JsonObject(); JsonArray rows = new JsonArray(); rows.add(row); task.add("items", rows); return net.vg.codex.advancement.SubmissionPlan.resolve(task, minecraft.level.registryAccess()).get(0); }
        catch (RuntimeException invalid) { return new net.vg.codex.advancement.SubmissionPlan.Requirement(net.vg.codex.advancement.SubmissionPlan.key(row), BuiltInRegistries.ITEM.getKey(net.minecraft.world.item.Items.PAPER), "Invalid requirement (edit properties)", 1, null); }
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
