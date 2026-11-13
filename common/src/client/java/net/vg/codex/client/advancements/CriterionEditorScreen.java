package net.vg.codex.client.advancements;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vg.codex.advancement.QuestCriteria;
import net.vg.codex.advancement.QuestCriteria.CriterionType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Visual criterion draft. Complex native/modded predicates keep their exact JSON. */
public final class CriterionEditorScreen extends QuestEditorDialog {
    private final BiConsumer<String, JsonObject> apply;
    private final Runnable remove;
    private final java.util.Map<Identifier, JsonObject> definitions;
    private String name;
    private CriterionType type;
    private String value;
    private JsonObject draft;

    public CriterionEditorScreen(Screen parent, String name, JsonObject entry, java.util.Map<Identifier, JsonObject> definitions, BiConsumer<String, JsonObject> apply, Runnable remove) {
        super(parent, entry == null ? "Add Criterion" : "Edit Criterion");
        this.definitions = definitions; this.name = name; this.apply = apply; this.remove = remove;
        draft = entry == null ? null : entry.deepCopy();
        type = entry == null ? CriterionType.ITEM_OBTAINED : CriterionType.fromJson(entry);
        value = entry == null ? "" : QuestCriteria.extractValue(type, entry);
    }

    @Override protected void init() {
        super.init();
        EditBox key = addRenderableWidget(new EditBox(font, left + 58, top + 32, panelWidth - 70, 18, Component.literal("Criterion key")));
        key.setMaxLength(256); key.setValue(name); key.setEditable(remove == null); key.setResponder(text -> name = text);
        if (remove != null) key.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Existing progress uses this stable key. Add a new criterion for a different objective.")));
        addRenderableWidget(CycleButton.builder((CriterionType kind) -> Component.literal(kind.label), type)
                .withValues(CriterionType.values()).create(left + 12, top + 58, panelWidth - 24, 20, Component.literal("Type"), (button, kind) -> {
                    type = kind; draft = null; value = ""; error = ""; rebuildWidgets();
                }));
        if (type.hasValue) addRenderableWidget(Button.builder(Component.literal(actionLabel()), b -> configure())
                .bounds(left + 12, top + 88, panelWidth - 24, 22).build());
        if (type == CriterionType.ITEM_USED_ON_BLOCK) addRenderableWidget(Button.builder(Component.literal("Choose Item Used..."), b -> {
            minecraft.setScreen(new ItemPickerScreen(this, id -> { value = blockPart() + ";" + id; draft = null; }));
        }).bounds(left + 12, top + 116, panelWidth - 24, 20).build());
        if (type == CriterionType.ITEM_OBTAINED) addRenderableWidget(Button.builder(Component.literal("Choose Item Tag..."), b -> {
            var tags = BuiltInRegistries.ITEM.getTags().map(tag -> tag.key().location()).toList();
            minecraft.setScreen(new IdentifierSelectionScreen(this, "Select Item Tag", tags, id -> { value = "#" + id; draft = null; error = ""; }));
        }).bounds(left + 12, top + 116, panelWidth - 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Copy Existing Native Objective…"), b -> {
            minecraft.setScreen(new IdentifierSelectionScreen(this, "Copy Objective from Quest", definitions.keySet(), id -> {
                try { return net.minecraft.network.chat.ComponentSerialization.CODEC.parse(net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, minecraft.level.registryAccess()), definitions.get(id).getAsJsonObject("display").get("title")).getOrThrow(); } catch (RuntimeException missing) { return Component.literal(id.toString()); }
            }, id -> {
                JsonObject criteria=definitions.get(id).getAsJsonObject("criteria"); java.util.List<Identifier> keys=new java.util.ArrayList<>(); java.util.Map<Identifier,String> names=new java.util.HashMap<>(); int i=0;
                for(String criterionName:criteria.keySet()){Identifier option=Identifier.fromNamespaceAndPath("codex","criterion/"+i++);keys.add(option);names.put(option,criterionName);}
                minecraft.setScreen(new IdentifierSelectionScreen(this,"Native Objective",keys,option->Component.literal(names.get(option).replace('_',' ')),option->{draft=criteria.getAsJsonObject(names.get(option)).deepCopy();type=CriterionType.fromJson(draft);value=QuestCriteria.extractValue(type,draft);}));
            }));
        }).bounds(left + 12, top + panelHeight - 76, panelWidth - 24, 20).build());
        footer(remove == null ? "Add Criterion" : "Save Criterion", this::save, remove);
    }

    private String actionLabel() {
        return switch (type) {
            case SUBMIT_ITEMS -> "Edit Delivery Items...";
            case STATISTIC -> "Configure Statistic...";
            case CUSTOM -> "Edit Criterion JSON...";
            case ITEM_OBTAINED -> value.isBlank() ? "Choose Item..." : value.startsWith("#") ? "Item tag: " + value.substring(1) : "Item: " + new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(value))).getHoverName().getString();
            case ITEM_USED_ON_BLOCK -> "Choose Block...";
            default -> value.isBlank() ? "Choose " + type.label + "..." : value;
        };
    }

    private String blockPart() { return value.contains(";") ? value.split(";", 2)[0] : "minecraft:stone"; }

    private void configure() {
        if (type == CriterionType.SUBMIT_ITEMS) {
            JsonArray rows = draft != null && draft.has("codex_submission") ? draft.getAsJsonArray("codex_submission") : new JsonArray();
            minecraft.setScreen(new ItemRequirementsScreen(this, rows, result -> {
                draft = new JsonObject(); draft.addProperty("trigger", "minecraft:impossible"); draft.add("codex_submission", result);
            }));
        } else if (type == CriterionType.STATISTIC) {
            JsonObject task = draft != null && draft.has("codex_statistic") ? draft.getAsJsonObject("codex_statistic") : new JsonObject();
            minecraft.setScreen(new StatisticTaskScreen(this, task, result -> {
                draft = new JsonObject(); draft.addProperty("trigger", "minecraft:impossible"); draft.add("codex_statistic", result);
            }));
        } else if (type == CriterionType.CUSTOM) {
            minecraft.setScreen(new JsonDocumentScreen(this, draft == null ? QuestCriteria.buildCriterionJson(CriterionType.IMPOSSIBLE, "") : draft, result -> {
                if (!result.has("trigger") || !result.get("trigger").isJsonPrimitive()) throw new IllegalArgumentException("A trigger ID is required");
                Identifier.parse(result.get("trigger").getAsString()); draft = result.deepCopy();
            }));
        } else if (type == CriterionType.ITEM_OBTAINED) {
            minecraft.setScreen(new ItemPickerScreen(this, id -> { value = id.toString(); draft = null; }));
        } else {
            List<Identifier> entries = new ArrayList<>();
            switch (type) {
                case PLAYER_KILLED_ENTITY, ENTITY_KILLED_PLAYER -> BuiltInRegistries.ENTITY_TYPE.keySet().forEach(entries::add);
                case RECIPE_UNLOCKED, RECIPE_CRAFTED -> entries.addAll(ClientRecipeIds.all());
                case CHANGED_DIMENSION -> { if (minecraft.getConnection() != null) minecraft.getConnection().levels().forEach(level -> entries.add(level.identifier())); }
                case LOCATION_BIOME -> { if (minecraft.level != null) minecraft.level.registryAccess().lookupOrThrow(Registries.BIOME).keySet().forEach(entries::add); }
                case LOCATION_STRUCTURE -> { if (minecraft.level != null) minecraft.level.registryAccess().lookupOrThrow(Registries.STRUCTURE).keySet().forEach(entries::add); }
                case ITEM_USED_ON_BLOCK -> BuiltInRegistries.BLOCK.keySet().forEach(entries::add);
                default -> { }
            }
            minecraft.setScreen(new IdentifierSelectionScreen(this, "Select " + type.label, entries, id -> {
                value = type == CriterionType.ITEM_USED_ON_BLOCK ? id + ";" + (value.contains(";") ? value.split(";", 2)[1] : "minecraft:stick") : id.toString(); draft = null;
            }));
        }
    }

    private void save() {
        if (name.isBlank()) throw new IllegalArgumentException("A criterion key is required");
        if (draft == null) {
            if (type.hasValue && value.isBlank()) throw new IllegalArgumentException("Configure the objective first");
            draft = QuestCriteria.buildCriterionJson(type, value);
        }
        apply.accept(name.trim(), draft.deepCopy());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) {
        super.extractRenderState(graphics, x, y, tick);
        text(graphics, "Key", 37, 0xFFBBBBBB);
        String summary = switch (type) {
            case SUBMIT_ITEMS -> draft != null && draft.has("codex_submission") ? draft.getAsJsonArray("codex_submission").size() + " item requirements; submitted items are consumed." : "Add items and quantities. Partial submissions are saved.";
            case STATISTIC -> draft != null && draft.has("codex_statistic") ? "Statistic configured. Open to review the target." : "Choose a statistic and cumulative target.";
            case CUSTOM -> "Advanced conditions are preserved exactly.";
            case TICK -> "Completes automatically while the player is present.";
            case IMPOSSIBLE -> "Requires a command or an external completion action.";
            default -> value.isBlank() ? "Choose a target using the picker." : value;
        };
        int summaryY = type == CriterionType.ITEM_USED_ON_BLOCK || type == CriterionType.ITEM_OBTAINED ? 146 : 124;
        if(summaryY + font.lineHeight <= panelHeight - 82) text(graphics, summary, summaryY, 0xFFBBBBBB);
    }
}
