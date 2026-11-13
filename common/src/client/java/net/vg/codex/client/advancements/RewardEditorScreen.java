package net.vg.codex.client.advancements;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.vg.codex.advancement.QuestRewards.RewardType;

import java.util.function.Consumer;

/** A reward is a private draft until Save; pickers and Cancel never mutate the quest. */
public final class RewardEditorScreen extends QuestEditorDialog {
    private final JsonObject original;
    private final Consumer<JsonObject> apply;
    private final Runnable remove;
    private RewardType type;
    private String value;
    private String count;
    private String bagName = "Treasure Bag";
    private String bagModel = "";

    public RewardEditorScreen(Screen parent, JsonObject entry, Consumer<JsonObject> apply, Runnable remove) {
        super(parent, entry == null ? "Add Reward" : "Edit Reward");
        original = entry == null ? null : entry.deepCopy();
        this.apply = apply;
        this.remove = remove;
        type = entry == null ? RewardType.ITEM : RewardType.valueOf(entry.get("type").getAsString());
        value = entry == null ? "" : type == RewardType.EXPERIENCE ? entry.get("amount").getAsString() : entry.get("id").getAsString();
        if (entry != null && entry.has("bag_name")) bagName = entry.get("bag_name").getAsString();
        if (entry != null && entry.has("bag_model")) bagModel = entry.get("bag_model").getAsString();
        count = entry != null && entry.has("count") ? entry.get("count").getAsString() : "1";
    }

    @Override protected void init() {
        super.init();
        addRenderableWidget(CycleButton.builder((RewardType kind) -> Component.literal(kind.label), type)
                .withValues(RewardType.values()).create(left + 12, top + 32, panelWidth - 24, 20, Component.literal("Type"), (button, kind) -> {
                    type = kind; value = kind == RewardType.EXPERIENCE ? "5" : ""; count = "1"; error = ""; rebuildWidgets();
                }));
        if (type == RewardType.EXPERIENCE || type == RewardType.FUNCTION) {
            EditBox input = addRenderableWidget(new EditBox(font, left + 12, top + 76, panelWidth - 24, 20, Component.literal(type == RewardType.EXPERIENCE ? "XP points" : "Function ID")));
            input.setMaxLength(32767); input.setValue(value); input.setResponder(text -> { value = text; error = ""; });
            setInitialFocus(input);
        } else {
            Button choice = addRenderableWidget(Button.builder(Component.literal((type == RewardType.ITEM ? "    " : "") + selectedName()), b -> pick())
                    .bounds(left + 12, top + 68, panelWidth - 24, 24).build());
            if (!value.isBlank()) choice.setTooltip(Tooltip.create(Component.literal(value)));
        }
        if (type == RewardType.ITEM || type == RewardType.TREASURE_BAG) {
            EditBox quantity = addRenderableWidget(new EditBox(font, left + 88, top + 104, panelWidth - 100, 20, Component.literal("Quantity")));
            quantity.setMaxLength(8); quantity.setValue(count); quantity.setResponder(text -> { count = text; error = ""; });
        }
        if (type == RewardType.TREASURE_BAG) {
            EditBox name = addRenderableWidget(new EditBox(font, left + 12, top + 132, panelWidth - 24, 20, Component.literal("Bag display name")));
            name.setMaxLength(80); name.setValue(bagName); name.setResponder(v -> { bagName = v; error = ""; });
            addRenderableWidget(Button.builder(Component.literal(bagModel.isEmpty() ? "Appearance: Treasure Bag" : "Appearance: " + bagModel), b -> minecraft.setScreen(new ItemPickerScreen(this, id -> { bagModel = id.toString(); error = ""; })))
                    .bounds(left + 12, top + 160, panelWidth - 24, 20).build()).setTooltip(Tooltip.create(Component.literal("Use any item model as the bag's appearance. The item remains a treasure bag.")));
        }
        footer(original == null ? "Add Reward" : "Save Reward", this::save, remove);
    }

    private String selectedName() {
        if (value.isBlank()) return switch (type) {
            case ITEM -> "Choose Item...";
            case RECIPE -> "Choose Recipe...";
            case RANDOM_TREASURE -> "Choose Treasure List...";
            default -> "Choose Loot Table...";
        };
        if (type == RewardType.ITEM) return item().getHoverName().getString();
        return type == RewardType.RECIPE ? ClientRewardPresentation.pretty(Identifier.parse(value)) : ClientRewardPresentation.poolName(Identifier.parse(value));
    }

    private ItemStack item() {
        try { return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(value))); }
        catch (RuntimeException invalid) { return ItemStack.EMPTY; }
    }

    private void pick() {
        if (type == RewardType.ITEM) minecraft.setScreen(new ItemPickerScreen(this, id -> { value = id.toString(); error = ""; }));
        else {
            var choices = type == RewardType.RECIPE ? ClientRecipeIds.all() : ClientTreasureLists.lootTables();
            var lists = ClientTreasureLists.lists();
            minecraft.setScreen(new IdentifierSelectionScreen(this, type == RewardType.RECIPE ? "Select Recipe" : "Select Treasure / Loot Table", choices,
                    id -> Component.literal(type == RewardType.RECIPE ? ClientRewardPresentation.pretty(id) : ClientRewardPresentation.poolName(id)),
                    id -> { value = id.toString(); error = ""; }));
        }
    }

    private void save() {
        JsonObject entry = original != null && type.name().equals(original.get("type").getAsString()) ? original.deepCopy() : new JsonObject();
        entry.addProperty("type", type.name());
        entry.addProperty("codex_id", original != null && original.has("codex_id") ? original.get("codex_id").getAsString() : java.util.UUID.randomUUID().toString());
        if (type == RewardType.EXPERIENCE) {
            int amount = Integer.parseInt(value.trim());
            if (amount < 1 || amount > 1_000_000) throw new IllegalArgumentException("XP must be 1–1000000");
            entry.addProperty("amount", amount);
        } else {
            if (value.isBlank()) throw new IllegalArgumentException("Choose the reward first");
            Identifier id = Identifier.parse(value.trim());
            if (type == RewardType.ITEM) {
                if (!BuiltInRegistries.ITEM.containsKey(id) || item().isEmpty() || item().is(Items.AIR)) throw new IllegalArgumentException("Choose a registered item");
                int quantity = Integer.parseInt(count.trim());
                if (quantity < 1 || quantity > 1_000_000) throw new IllegalArgumentException("Quantity must be 1 to 1000000");
                entry.addProperty("count", quantity);
            }
            if (type == RewardType.TREASURE_BAG) {
                int quantity = Integer.parseInt(count.trim()); if (quantity < 1 || quantity > 4096) throw new IllegalArgumentException("Bag quantity must be 1–4096");
                entry.addProperty("count", quantity); entry.addProperty("bag_name", bagName);
                if (!bagModel.isBlank()) entry.addProperty("bag_model", Identifier.parse(bagModel).toString());
            }
            entry.addProperty("id", id.toString());
        }
        apply.accept(entry);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) {
        super.extractRenderState(graphics, x, y, tick);
        if (type == RewardType.ITEM) {
            text(graphics, "Quantity", 110, 0xFFBBBBBB);
            if (!value.isBlank()) graphics.fakeItem(item(), left + 17, top + 72);
        } else if (type == RewardType.TREASURE_BAG) text(graphics, "Quantity", 110, 0xFFBBBBBB);
        else if (type == RewardType.EXPERIENCE || type == RewardType.FUNCTION) text(graphics, type == RewardType.EXPERIENCE ? "Experience points" : "Datapack function ID", 62, 0xFFBBBBBB);
        if (type != RewardType.TREASURE_BAG) text(graphics, type == RewardType.RANDOM_TREASURE || type == RewardType.LOOT_TABLE ? "Rolled by the server when earned or claimed." : "Save updates the draft. Save the quest to publish it.", 140, 0xFFBBBBBB);
    }
}
