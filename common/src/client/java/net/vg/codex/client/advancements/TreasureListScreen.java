package net.vg.codex.client.advancements;

import com.google.gson.*;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.vg.codex.network.SaveTreasureListPayload;

import java.util.*;

/** Named reusable treasure pools; all randomness and validation run on the server. */
public final class TreasureListScreen extends CodexScreen {
    private final Screen parent;
    private Identifier id;
    private JsonObject draft;
    private int page;
    private int rows;
    private int left;
    private int panelWidth;
    private String status = "";
    private boolean pending;

    public TreasureListScreen(Screen parent) { super(Component.literal("Treasure Lists")); this.parent = parent; }

    @Override protected void init() {
        panelWidth = Math.min(460, width - 24); left = (width - panelWidth) / 2;
        rows = Math.max(1, Math.min(7, (height - 184) / 26));
        if (draft == null) { browser(); return; }
        field(left, 34, panelWidth - 112, draft, "name", "Treasure list name");
        field(left + panelWidth - 106, 34, 70, draft, "rolls", "Rolls");
        JsonArray entries = draft.getAsJsonArray("entries");
        page = Math.min(page, Math.max(0, (entries.size() - 1) / rows));
        int labelWidth = panelWidth - 172;
        for (int i = page * rows; i < Math.min(entries.size(), (page + 1) * rows); i++) {
            int index = i; JsonObject entry = entries.get(i).getAsJsonObject(); Identifier itemId = Identifier.parse(entry.get("id").getAsString());
            boolean table = entry.has("table") && entry.get("table").getAsBoolean();
            int y = 80 + (i % rows) * 26;
            String label = table ? itemId.getPath() : "    " + new ItemStack(BuiltInRegistries.ITEM.getValue(itemId)).getHoverName().getString();
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                if (table) minecraft.setScreen(new IdentifierSelectionScreen(this, "Select Loot Table", ClientTreasureLists.lootTables(), selected -> entry.addProperty("id", selected.toString())));
                else minecraft.setScreen(new ItemPickerScreen(this, selected -> entry.addProperty("id", selected.toString())));
            }).bounds(left, y, labelWidth, 22).build());
            if (!table) { field(left + labelWidth + 4, y, 44, entry, "min", "Minimum quantity"); field(left + labelWidth + 52, y, 44, entry, "max", "Maximum quantity"); }
            field(left + labelWidth + 100, y, 40, entry, "weight", "Weight");
            addRenderableWidget(Button.builder(Component.literal("X"), b -> { entries.remove(index); rebuildWidgets(); }).bounds(left + panelWidth - 26, y, 26, 22).build());
        }
        int footer = 86 + rows * 26;
        addRenderableWidget(Button.builder(Component.literal("Add Item"), b -> minecraft.setScreen(new ItemPickerScreen(this, selected -> addEntry(selected, false)))).bounds(left, footer, 92, 20).build()).active = entries.size() < 64;
        addRenderableWidget(Button.builder(Component.literal("Add Loot Table"), b -> minecraft.setScreen(new IdentifierSelectionScreen(this, "Select Loot Table", ClientTreasureLists.lootTables(), selected -> addEntry(selected, true)))).bounds(left + 98, footer, 116, 20).build()).active = entries.size() < 64;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(left + 220, footer, 26, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(left + 250, footer, 26, 20).build()).active = (page + 1) * rows < entries.size();
        addRenderableWidget(Button.builder(Component.literal("Save Treasure List"), b -> save()).bounds(left, footer + 26, 138, 20).build()).active = !pending;
        addRenderableWidget(Button.builder(Component.literal("Cancel / Back"), b -> { draft = null; page = 0; rebuildWidgets(); }).bounds(left + 144, footer + 26, 112, 20).build()).active = !pending;
    }

    private void browser() {
        List<Map.Entry<String, JsonElement>> lists = new ArrayList<>(ClientTreasureLists.lists().entrySet());
        for (int i = page * rows; i < Math.min(lists.size(), (page + 1) * rows); i++) {
            var entry = lists.get(i);
            addRenderableWidget(Button.builder(Component.literal(entry.getValue().getAsJsonObject().get("name").getAsString()), b -> {
                id = Identifier.parse(entry.getKey()); draft = entry.getValue().getAsJsonObject().deepCopy(); page = 0; rebuildWidgets();
            }).bounds(left, 38 + (i % rows) * 26, panelWidth, 22).build());
        }
        int y = 44 + rows * 26;
        addRenderableWidget(Button.builder(Component.literal("New Treasure List"), b -> {
            id = null; draft = new JsonObject(); draft.addProperty("name", "New Treasure"); draft.addProperty("rolls", 1); draft.add("entries", new JsonArray()); page = 0; rebuildWidgets();
        }).bounds(left, y, 138, 20).build());
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(left + 144, y, 26, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(left + 174, y, 26, 20).build()).active = (page + 1) * rows < lists.size();
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(left + 206, y, 76, 20).build());
    }

    private void addEntry(Identifier item, boolean table) {
        JsonObject entry = new JsonObject(); entry.addProperty("id", item.toString()); entry.addProperty("table", table);
        entry.addProperty("min", 1); entry.addProperty("max", 1); entry.addProperty("weight", 1);
        draft.getAsJsonArray("entries").add(entry); page = (draft.getAsJsonArray("entries").size() - 1) / rows;
    }

    private void field(int x, int y, int width, JsonObject object, String key, String label) {
        EditBox field = addRenderableWidget(new EditBox(font, x, y, width, 20, Component.literal(label)));
        field.setMaxLength(key.equals("name") ? 80 : 5); field.setValue(object.get(key).getAsString()); field.setResponder(value -> object.addProperty(key, value));
        field.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(label)));
    }

    private void save() {
        try {
            String name = draft.get("name").getAsString().trim();
            if (name.isEmpty()) throw new IllegalArgumentException("Give the list a name.");
            if (id == null) {
                String path = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
                Identifier created = Identifier.fromNamespaceAndPath("codex", path);
                if (ClientTreasureLists.lists().has(created.toString())) throw new IllegalArgumentException("That name already exists; edit its existing list.");
                id = created;
            }
            // Use the same bounds server-side; item/reference validation is authoritative there.
            net.vg.codex.advancement.TreasureLists.compile(draft, new HashSet<>(ClientTreasureLists.lootTables()));
            NetworkManager.sendToServer(new SaveTreasureListPayload(id, draft.toString())); pending = true; status = "Saving…"; rebuildWidgets();
        } catch (RuntimeException invalid) { status = invalid.getMessage(); }
    }

    public void saved(String message) { status = message; pending = false; rebuildWidgets(); }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) {
        graphics.fill(0, 0, width, height, 0xED202020); graphics.text(font, title, left, 14, 0xFFFFFFFF, false);
        if (draft != null) {
            graphics.text(font, "Rolls", left + panelWidth - 32, 40, 0xFFBBBBBB, false);
            int labels = left + panelWidth - 172;
            graphics.text(font, "Min     Max    Weight", labels + 4, 66, 0xFFBBBBBB, false);
        }
        super.extractRenderState(graphics, x, y, tick);
        if (draft != null) for (int i = page * rows; i < Math.min(draft.getAsJsonArray("entries").size(), (page + 1) * rows); i++) {
            JsonObject entry = draft.getAsJsonArray("entries").get(i).getAsJsonObject();
            if (!entry.has("table") || !entry.get("table").getAsBoolean()) graphics.fakeItem(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(entry.get("id").getAsString()))), left + 4, 83 + (i % rows) * 26);
        }
        if (!status.isEmpty()) graphics.text(font, status, left, height - 18, 0xFFFFDD88, false);
    }
    @Override public void onClose() { if (!pending) minecraft.setScreen(parent); }
}
