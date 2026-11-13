package net.vg.codex.advancement;

import com.google.gson.*;
import net.minecraft.resources.Identifier;
import java.util.*;

/** One canonical reward order for the editor, previews and individual server claims. */
public final class QuestRewards {
    private QuestRewards() { }
    public enum RewardType {
        EXPERIENCE("Experience"), ITEM("Item"), RECIPE("Recipe"), LOOT_TABLE("Loot Table"),
        RANDOM_TREASURE("Random Treasure"), TREASURE_BAG("Treasure Bag"), FUNCTION("Function");
        public final String label;
        RewardType(String label) { this.label = label; }
    }

    public static List<JsonObject> entries(JsonObject rewards, Identifier advancement) {
        List<JsonObject> result = new ArrayList<>();
        if (rewards.has("experience") && rewards.get("experience").getAsInt() > 0) {
            JsonObject entry = new JsonObject(); entry.addProperty("type", RewardType.EXPERIENCE.name()); entry.add("amount", rewards.get("experience")); result.add(entry);
        }
        if (rewards.has("codex_items")) for (var value : rewards.getAsJsonArray("codex_items")) {
            JsonObject entry = value.getAsJsonObject().deepCopy(); if (entry.has("bag_pool")) { entry.addProperty("type", RewardType.TREASURE_BAG.name()); entry.add("id", entry.get("bag_pool")); }
            else entry.addProperty("type", RewardType.ITEM.name());
            if (!entry.has("count")) entry.addProperty("count", 1); result.add(entry);
        }
        for (String key : List.of("recipes", "loot")) if (rewards.has(key)) for (var value : rewards.getAsJsonArray(key)) {
            String id = value.getAsString();
            if (key.equals("loot") && advancement != null && id.equals(AdvancementPackIO.generatedLootTableId(advancement).toString())) continue;
            RewardType type = key.equals("recipes") ? RewardType.RECIPE : id.contains(":codex_treasure/") ? RewardType.RANDOM_TREASURE : RewardType.LOOT_TABLE;
            JsonObject entry = new JsonObject(); entry.addProperty("type", type.name()); entry.addProperty("id", id); result.add(entry);
        }
        if (rewards.has("function")) {
            JsonObject entry = new JsonObject(); entry.addProperty("type", RewardType.FUNCTION.name()); entry.add("id", rewards.get("function")); result.add(entry);
        }
        if (rewards.has("codex_ids")) {
            JsonArray ids = rewards.getAsJsonArray("codex_ids");
            for (int i = 0; i < Math.min(ids.size(), result.size()); i++) result.get(i).add("codex_id", ids.get(i));
        }
        return result;
    }

    private static int order(JsonObject entry) {
        return switch (RewardType.valueOf(entry.get("type").getAsString())) {
            case EXPERIENCE -> 0; case ITEM, TREASURE_BAG -> 1; case RECIPE -> 2; case LOOT_TABLE, RANDOM_TREASURE -> 3; case FUNCTION -> 4;
        };
    }
    public static JsonObject serialize(List<JsonObject> entries, JsonObject original, Identifier advancement) {
        if (entries.equals(entries(original, advancement))) return original.deepCopy();
        JsonObject result = original.deepCopy();
        for (String key : List.of("experience", "function", "recipes", "loot", "codex_items", "codex_ids")) result.remove(key);
        JsonArray items = new JsonArray(); JsonArray recipes = new JsonArray(); JsonArray loot = new JsonArray();
        for (JsonObject entry : entries) switch (RewardType.valueOf(entry.get("type").getAsString())) {
            case EXPERIENCE -> result.add("experience", entry.get("amount"));
            case FUNCTION -> result.add("function", entry.get("id"));
            case RECIPE -> recipes.add(entry.get("id"));
            case LOOT_TABLE, RANDOM_TREASURE -> loot.add(entry.get("id"));
            case ITEM -> { JsonObject item = entry.deepCopy(); item.remove("type"); item.remove("codex_id"); items.add(item); }
            case TREASURE_BAG -> {
                JsonObject item = entry.deepCopy(); item.remove("type"); item.remove("codex_id"); item.add("bag_pool", item.get("id")); item.addProperty("id", "codex:treasure_bag");
                com.google.gson.JsonObject components = new JsonObject(), custom = new JsonObject(); custom.add("codex_pool", item.get("bag_pool")); components.add("minecraft:custom_data", custom);
                if (item.has("bag_name") && !item.get("bag_name").getAsString().isBlank()) { JsonObject name = new JsonObject(); name.add("text", item.get("bag_name")); components.add("minecraft:custom_name", name); }
                if (item.has("bag_model")) components.add("minecraft:item_model", item.get("bag_model"));
                item.add("components", components); items.add(item);
            }
        }
        if (!items.isEmpty()) result.add("codex_items", items);
        if (!recipes.isEmpty()) result.add("recipes", recipes);
        if (!loot.isEmpty()) result.add("loot", loot);
        JsonArray ids = new JsonArray();
        entries.stream().sorted(java.util.Comparator.comparingInt(QuestRewards::order)).forEach(entry -> ids.add(entry.has("codex_id") ? entry.get("codex_id").getAsString() : java.util.UUID.randomUUID().toString()));
        result.add("codex_ids", ids);
        return result;
    }
}
