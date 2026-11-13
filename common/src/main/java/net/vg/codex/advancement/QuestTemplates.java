package net.vg.codex.advancement;

import com.google.gson.*;

/** Templates only produce editable vanilla advancement criteria (plus optional Codex sidecars). */
public final class QuestTemplates {
    private QuestTemplates() { }
    public enum Type {
        OBTAIN("Obtain an item"), DELIVER("Deliver items"), DEFEAT("Defeat an entity"),
        EXPLORE_BIOME("Explore a biome"), EXPLORE_DIMENSION("Reach a dimension"),
        CRAFT("Craft an item"), MILESTONE("Reach a statistic milestone"), SEQUENCE("Complete several objectives"),
        CUSTOM("Blank custom quest");
        public final String label;
        Type(String label) { this.label = label; }
    }

    public static JsonObject criteria(Type type) {
        JsonObject result = new JsonObject();
        var kind = switch (type) {
            case OBTAIN, SEQUENCE -> QuestCriteria.CriterionType.ITEM_OBTAINED;
            case DELIVER -> QuestCriteria.CriterionType.SUBMIT_ITEMS;
            case DEFEAT -> QuestCriteria.CriterionType.PLAYER_KILLED_ENTITY;
            case EXPLORE_BIOME -> QuestCriteria.CriterionType.LOCATION_BIOME;
            case EXPLORE_DIMENSION -> QuestCriteria.CriterionType.CHANGED_DIMENSION;
            case CRAFT -> QuestCriteria.CriterionType.RECIPE_CRAFTED;
            case MILESTONE -> QuestCriteria.CriterionType.STATISTIC;
            case CUSTOM -> QuestCriteria.CriterionType.IMPOSSIBLE;
        };
        String value = switch (type) {
            case OBTAIN, SEQUENCE -> "minecraft:oak_log";
            case DELIVER -> "minecraft:iron_ingot x16";
            case DEFEAT -> "minecraft:zombie";
            case EXPLORE_BIOME -> "minecraft:plains";
            case EXPLORE_DIMENSION -> "minecraft:the_nether";
            case CRAFT -> "minecraft:crafting_table";
            case MILESTONE -> "mined|minecraft:stone >= 16";
            case CUSTOM -> "";
        };
        result.add("objective", QuestCriteria.buildCriterionJson(kind, value));
        if (type == Type.SEQUENCE) result.add("craft", QuestCriteria.buildCriterionJson(QuestCriteria.CriterionType.RECIPE_CRAFTED, "minecraft:crafting_table"));
        return result;
    }
}
