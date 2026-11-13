package net.vg.codex.advancement;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;

/** Lossless document operations. Never rebuild a mod's source through the vanilla codec. */
public final class AdvancementDocument {
    private AdvancementDocument() { }

    public enum Requirements {
        ALL("All criteria"), ANY("Any criterion"), CUSTOM("Custom groups (preserved)");
        public final String label;
        Requirements(String label) { this.label = label; }

        public static Requirements fromJson(JsonArray groups, int criteriaCount) {
            if (groups == null || groups.isEmpty()) return ALL;
            if (groups.size() == 1 && groups.get(0).isJsonArray() && groups.get(0).getAsJsonArray().size() == criteriaCount && criteriaCount > 1) return ANY;
            if (groups.size() == criteriaCount && groups.asList().stream().allMatch(g -> g.isJsonArray() && g.getAsJsonArray().size() == 1)) return ALL;
            return CUSTOM;
        }
    }

    public static JsonObject patch(JsonObject original, JsonObject edits) {
        JsonObject result = original.deepCopy();
        edits.entrySet().forEach(entry -> {
            if (entry.getValue().isJsonNull()) result.remove(entry.getKey());
            else result.add(entry.getKey(), entry.getValue().deepCopy());
        });
        return result;
    }

    public static JsonArray requirements(JsonObject original, Set<String> names, Requirements mode) {
        JsonArray old = original.has("requirements") && original.get("requirements").isJsonArray() ? original.getAsJsonArray("requirements") : null;
        Set<String> oldNames = original.has("criteria") ? original.getAsJsonObject("criteria").keySet() : Set.of();
        if (oldNames.equals(names) && mode == Requirements.fromJson(old, oldNames.size())) return old == null ? null : old.deepCopy();
        if (mode == Requirements.CUSTOM) {
            if (old == null) throw new IllegalArgumentException("Define custom requirement groups first");
            JsonArray groups = new JsonArray();
            Set<String> referenced = new LinkedHashSet<>();
            for (JsonElement value : old) {
                JsonArray group = new JsonArray();
                for (JsonElement name : value.getAsJsonArray()) if (names.contains(name.getAsString())) {
                    group.add(name.deepCopy()); referenced.add(name.getAsString());
                }
                if (!group.isEmpty()) groups.add(group);
            }
            // New objectives are required groups; existing alternatives retain their structure.
            for (String name : names) if (!referenced.contains(name)) { JsonArray group = new JsonArray(); group.add(name); groups.add(group); }
            validateGroups(groups, names);
            return groups;
        }
        JsonArray groups = new JsonArray();
        if (mode == Requirements.ANY) {
            JsonArray group = new JsonArray(); names.forEach(group::add); groups.add(group);
        } else for (String name : names) { JsonArray group = new JsonArray(); group.add(name); groups.add(group); }
        return groups;
    }

    public static JsonArray renameReference(JsonArray groups, String before, String after) {
        JsonArray result = new JsonArray();
        for (var value : groups) {
            JsonArray group = new JsonArray();
            for (var name : value.getAsJsonArray()) group.add(name.getAsString().equals(before) ? after : name.getAsString());
            result.add(group);
        }
        return result;
    }

    public static void validateGroups(JsonArray groups, Set<String> criteria) {
        if (groups.isEmpty()) throw new IllegalArgumentException("At least one requirement group is needed");
        Set<String> referenced = new LinkedHashSet<>();
        for (JsonElement element : groups) {
            JsonArray group = element.getAsJsonArray();
            if (group.isEmpty()) throw new IllegalArgumentException("Every group needs at least one criterion");
            for (JsonElement name : group) {
                if (!criteria.contains(name.getAsString())) throw new IllegalArgumentException("Unknown criterion in requirements: " + name.getAsString());
                referenced.add(name.getAsString());
            }
        }
        if (!referenced.equals(criteria)) throw new IllegalArgumentException("Every criterion must appear in a requirement group");
    }
}
