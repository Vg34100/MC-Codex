package net.vg.codex.advancement;

import com.google.gson.*;
import net.minecraft.resources.Identifier;
import java.util.*;

/** Conversion for the curated criterion forms; arbitrary criteria remain raw JSON. */
public final class QuestCriteria {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private QuestCriteria() { }
    public enum StatisticCategory {
        CUSTOM("custom", "Custom"), MINED("mined", "Blocks mined"), CRAFTED("crafted", "Items crafted"),
        USED("used", "Items used"), BROKEN("broken", "Items broken"), PICKED_UP("picked_up", "Items picked up"),
        DROPPED("dropped", "Items dropped"), KILLED("killed", "Entities killed"), KILLED_BY("killed_by", "Killed by entities");
        public final String key;
        public final String label;
        StatisticCategory(String key, String label) { this.key = key; this.label = label; }
        public static StatisticCategory fromValue(String value) {
            int split = value.indexOf('|');
            if (split > 0) for (StatisticCategory category : values()) if (category.key.equals(value.substring(0, split))) return category;
            return CUSTOM;
        }
    }


    public enum CriterionType {
        IMPOSSIBLE("minecraft:impossible", "Impossible", false, null),
        TICK("minecraft:tick", "Always (tick)", false, null),
        ITEM_OBTAINED("minecraft:inventory_changed", "Item Obtained", true, "minecraft:item_id"),
        PLAYER_KILLED_ENTITY("minecraft:player_killed_entity", "Player Killed Entity", true, "minecraft:entity_type"),
        ENTITY_KILLED_PLAYER("minecraft:entity_killed_player", "Entity Killed Player", true, "minecraft:entity_type"),
        CHANGED_DIMENSION("minecraft:changed_dimension", "Changed Dimension", true, "minecraft:the_end"),
        // "minecraft:location" covers both - disambiguated by shape, not trigger id, in fromJson().
        LOCATION_STRUCTURE("minecraft:location", "Reached Location (Structure)", true, "minecraft:stronghold"),
        LOCATION_BIOME("minecraft:location", "Reached Location (Biome)", true, "minecraft:jungle"),
        ITEM_USED_ON_BLOCK("minecraft:item_used_on_block", "Item Used On Block (block;item)", true, "minecraft:lodestone;minecraft:compass"),
        RECIPE_CRAFTED("minecraft:recipe_crafted", "Craft a Recipe", true, "minecraft:crafting_table"),
        RECIPE_UNLOCKED("minecraft:recipe_unlocked", "Recipe Unlocked", true, "minecraft:diamond_pickaxe"),
        SUBMIT_ITEMS("minecraft:impossible", "Submit Items", true, "minecraft:iron_ingot x16; minecraft:gold_ingot x8"),
        STATISTIC("minecraft:impossible", "Statistic Threshold", true, "custom|minecraft:animals_bred >= 100"),
        CUSTOM(null, "Custom (raw JSON)", true, "{}");

        public final String triggerId;
        public final String label;
        public final boolean hasValue;
        public final String exampleValue;

        CriterionType(String triggerId, String label, boolean hasValue, String exampleValue) {
            this.triggerId = triggerId;
            this.label = label;
            this.hasValue = hasValue;
            this.exampleValue = exampleValue;
        }

        /** Reads the shape of {@code json}, not just its trigger id, since location's two curated
         * sub-types ({@link #LOCATION_STRUCTURE}, {@link #LOCATION_BIOME}) share one trigger. */
        public static CriterionType fromJson(JsonObject json) {
            if (json.has("codex_submission")) {
                return safeType(SUBMIT_ITEMS, json);
            }
            if (json.has("codex_statistic")) {
                return safeType(STATISTIC, json);
            }
            if (!json.has("trigger")) {
                return CUSTOM;
            }
            String triggerId = json.get("trigger").getAsString();
            if (LOCATION_STRUCTURE.triggerId.equals(triggerId)) {
                JsonObject location = locationPredicate(json, "player");
                return safeType(location != null && location.has("biomes") ? LOCATION_BIOME : LOCATION_STRUCTURE, json);
            }
            for (CriterionType type : values()) {
                if (triggerId.equals(type.triggerId)) {
                    return safeType(type, json);
                }
            }
            return CUSTOM;
        }
    }

    private static CriterionType safeType(CriterionType type, JsonObject json) {
        try { return buildCriterionJson(type, extractValue(type, json)).equals(json) ? type : CriterionType.CUSTOM; }
        catch (RuntimeException unsupported) { return CriterionType.CUSTOM; }
    }

    /**
     * Digs out the {@code conditions.<key>[0].predicate.location} object vanilla's
     * {@code minecraft:entity_properties} condition wrapper nests location checks in (see
     * {@link #wrapEntityProperties}), or {@code null} if the shape doesn't match.
     */
    private static JsonObject locationPredicate(JsonObject criterionJson, String key) {
        criterionJson=CriterionFormats.authoringView(criterionJson);
        if (!criterionJson.has("conditions")) {
            return null;
        }
        JsonObject conditions = criterionJson.getAsJsonObject("conditions");
        if (!conditions.has(key) || !conditions.get(key).isJsonArray() || conditions.getAsJsonArray(key).isEmpty()) {
            return null;
        }
        JsonElement first = conditions.getAsJsonArray(key).get(0);
        if (!first.isJsonObject() || !first.getAsJsonObject().has("predicate")) {
            return null;
        }
        JsonObject predicate = first.getAsJsonObject().getAsJsonObject("predicate");
        return predicate.has("location") ? predicate.getAsJsonObject("location") : null;
    }

    public static JsonObject buildCriterionJson(CriterionType type, String value) {
        if (type == CriterionType.CUSTOM) {
            String text = value.isBlank() ? "{}" : value;
            return JsonParser.parseString(text).getAsJsonObject();
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("trigger", type.triggerId);
        JsonObject conditions = new JsonObject();
        String trimmed = value.trim();
        switch (type) {
            case SUBMIT_ITEMS -> {
                JsonArray items = new JsonArray();
                for (String part : trimmed.split(";")) {
                    String entry = part.trim();
                    if (entry.isEmpty()) continue;
                    String[] pair = entry.split("\\s*[xX]\\s*", 2);
                    Identifier id = Identifier.parse(pair[0].trim());
                    int count = pair.length == 2 ? Integer.parseInt(pair[1].trim()) : 1;
                    if (count < 1) throw new IllegalArgumentException("submission counts must be positive");
                    JsonObject item = new JsonObject();
                    item.addProperty("id", id.toString());
                    item.addProperty("count", count);
                    items.add(item);
                }
                if (items.isEmpty()) throw new IllegalArgumentException("add at least one item to submit");
                obj.add("codex_submission", items);
            }
            case STATISTIC -> {
                String[] parts = trimmed.split(">=", 2);
                if (parts.length != 2) throw new IllegalArgumentException("use statistic_id >= target");
                String statistic = parts[0].trim();
                String[] statisticParts = statistic.split("\\|", 2);
                if (statisticParts.length != 2) throw new IllegalArgumentException("use category|id >= target");
                if (!List.of("custom", "mined", "crafted", "used", "broken", "picked_up", "dropped", "killed", "killed_by").contains(statisticParts[0])) {
                    throw new IllegalArgumentException("unknown statistic category");
                }
                Identifier.parse(statisticParts[1].trim());
                int target = Integer.parseInt(parts[1].trim());
                if (target < 0) throw new IllegalArgumentException("statistic target must be non-negative");
                JsonObject task = new JsonObject();
                task.addProperty("id", statistic);
                task.addProperty("target", target);
                obj.add("codex_statistic", task);
            }
            case ITEM_OBTAINED -> {
                JsonArray items = new JsonArray();
                JsonObject item = new JsonObject();
                item.addProperty("items", trimmed.isEmpty() ? "minecraft:stone" : trimmed);
                items.add(item);
                conditions.add("items", items);
            }
            case PLAYER_KILLED_ENTITY, ENTITY_KILLED_PLAYER -> {
                if (!trimmed.isEmpty()) {
                    JsonObject predicateInner = new JsonObject();
                    predicateInner.addProperty("type", trimmed);
                    conditions.add("entity", wrapEntityProperties(predicateInner));
                }
            }
            case CHANGED_DIMENSION -> {
                if (!trimmed.isEmpty()) {
                    conditions.addProperty("to", trimmed);
                }
            }
            case LOCATION_STRUCTURE, LOCATION_BIOME -> {
                if (!trimmed.isEmpty()) {
                    JsonObject location = new JsonObject();
                    location.addProperty(type == CriterionType.LOCATION_STRUCTURE ? "structures" : "biomes", trimmed);
                    JsonObject predicateInner = new JsonObject();
                    predicateInner.add("location", location);
                    conditions.add("player", wrapEntityProperties(predicateInner));
                }
            }
            case ITEM_USED_ON_BLOCK -> {
                String[] parts = trimmed.split(";", 2);
                String block = parts.length > 0 && !parts[0].isBlank() ? parts[0].trim() : "minecraft:stone";
                String item = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "minecraft:stick";
                JsonArray location = new JsonArray();

                JsonObject blockCheck = new JsonObject();
                blockCheck.addProperty("condition", "minecraft:location_check");
                JsonObject blockPredicate = new JsonObject();
                JsonObject blockField = new JsonObject();
                blockField.addProperty("blocks", block);
                blockPredicate.add("block", blockField);
                blockCheck.add("predicate", blockPredicate);
                location.add(blockCheck);

                JsonObject toolCheck = new JsonObject();
                toolCheck.addProperty("condition", "minecraft:match_tool");
                JsonObject toolPredicate = new JsonObject();
                toolPredicate.addProperty("items", item);
                toolCheck.add("predicate", toolPredicate);
                location.add(toolCheck);

                conditions.add("location", location);
            }
            case RECIPE_UNLOCKED -> conditions.addProperty("recipe", trimmed);
            case RECIPE_CRAFTED -> conditions.addProperty("recipe_id", trimmed);
            default -> {
                // IMPOSSIBLE / TICK take no fields.
            }
        }
        if ((conditions.size() != 0)) {
            obj.add("conditions", conditions);
        }
        return CriterionFormats.generated(obj);
    }

    private static JsonArray wrapEntityProperties(JsonObject predicateInner) {
        JsonObject condition = new JsonObject();
        condition.addProperty("condition", "minecraft:entity_properties");
        condition.addProperty("entity", "this");
        condition.add("predicate", predicateInner);
        JsonArray array = new JsonArray();
        array.add(condition);
        return array;
    }

    /** Best-effort reverse of {@link #buildCriterionJson} for populating the value box on select. */
    public static String extractValue(CriterionType type, JsonObject criterionJson) {
        criterionJson=CriterionFormats.authoringView(criterionJson);
        if (type == CriterionType.CUSTOM) {
            return GSON.toJson(criterionJson);
        }
        // Codex-only criterion metadata deliberately has no vanilla conditions payload. Read it
        // before the vanilla-shape guard so reopening an editor modal never erases its value.
        if (type == CriterionType.SUBMIT_ITEMS && criterionJson.has("codex_submission")) {
            try {
                List<String> parts = new ArrayList<>();
                for (JsonElement element : criterionJson.getAsJsonArray("codex_submission")) {
                    JsonObject item = element.getAsJsonObject();
                    parts.add(item.get("id").getAsString() + " x" + item.get("count").getAsInt());
                }
                return String.join("; ", parts);
            } catch (RuntimeException ignored) { return ""; }
        }
        if (type == CriterionType.STATISTIC && criterionJson.has("codex_statistic")) {
            try {
                JsonObject task = criterionJson.getAsJsonObject("codex_statistic");
                return task.get("id").getAsString() + " >= " + task.get("target").getAsInt();
            } catch (RuntimeException ignored) { return ""; }
        }
        if (!criterionJson.has("conditions") || !criterionJson.get("conditions").isJsonObject()) {
            return "";
        }
        JsonObject conditions = criterionJson.getAsJsonObject("conditions");
        try {
            return switch (type) {
                case SUBMIT_ITEMS, STATISTIC -> "";
                case ITEM_OBTAINED -> conditions.getAsJsonArray("items").get(0).getAsJsonObject().get("items").getAsString();
                case PLAYER_KILLED_ENTITY, ENTITY_KILLED_PLAYER -> {
                    if (!conditions.has("entity")) {
                        yield "";
                    }
                    JsonObject predicate = conditions.getAsJsonArray("entity").get(0).getAsJsonObject().getAsJsonObject("predicate");
                    yield predicate.has("type") ? predicate.get("type").getAsString() : "";
                }
                case CHANGED_DIMENSION -> conditions.has("to") ? conditions.get("to").getAsString() : "";
                case LOCATION_STRUCTURE, LOCATION_BIOME -> {
                    JsonObject location = locationPredicate(criterionJson, "player");
                    if (location == null) {
                        yield "";
                    }
                    String key = type == CriterionType.LOCATION_STRUCTURE ? "structures" : "biomes";
                    yield location.has(key) ? location.get(key).getAsString() : "";
                }
                case ITEM_USED_ON_BLOCK -> {
                    if (!conditions.has("location")) {
                        yield "";
                    }
                    JsonArray checks = conditions.getAsJsonArray("location");
                    String block = "";
                    String item = "";
                    for (JsonElement checkElement : checks) {
                        JsonObject check = checkElement.getAsJsonObject();
                        String condition = check.has("condition") ? check.get("condition").getAsString() : "";
                        JsonObject predicate = check.has("predicate") ? check.getAsJsonObject("predicate") : null;
                        if (predicate == null) {
                            continue;
                        }
                        if ("minecraft:location_check".equals(condition) && predicate.has("block")) {
                            JsonObject blockField = predicate.getAsJsonObject("block");
                            if (blockField.has("blocks") && blockField.get("blocks").isJsonPrimitive()) {
                                block = blockField.get("blocks").getAsString();
                            }
                        } else if ("minecraft:match_tool".equals(condition) && predicate.has("items")
                                && predicate.get("items").isJsonPrimitive()) {
                            item = predicate.get("items").getAsString();
                        }
                    }
                    yield block + ";" + item;
                }
                case RECIPE_UNLOCKED -> conditions.has("recipe") ? conditions.get("recipe").getAsString() : "";
                case RECIPE_CRAFTED -> conditions.has("recipe_id") ? conditions.get("recipe_id").getAsString() : "";
                default -> "";
            };
        } catch (RuntimeException e) {
            // Shape didn't match what this type expects (e.g. hand-edited, from a mod, or a
            // block/item list rather than a single value) - fall back to blank rather than crash;
            // the raw JSON is still intact under the hood since we only ever replace an entry
            // wholesale via applyCriterion.
            return "";
        }
    }

}
