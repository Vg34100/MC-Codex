package net.vg.codex.advancement;

import com.google.gson.*;
import net.minecraft.resources.Identifier;
import java.util.*;

/** Lossless operations on authoring snapshots. No runtime progress or registry decoding. */
public final class PackAuthoring {
    private PackAuthoring() { }
    public record Issue(Identifier quest, boolean error, String message) { }
    public static List<Issue> diagnose(Map<Identifier, JsonObject> definitions, Set<Identifier> lootTables) {
        List<Issue> out = new ArrayList<>();
        definitions.forEach((id, d) -> {
            try {
                AdvancementValidation.validateParents(id, d, definitions::get);
                if (d.has("requirements")) AdvancementDocument.validateGroups(d.getAsJsonArray("requirements"), d.getAsJsonObject("criteria").keySet());
                JsonObject q = d.has("codex_quest") ? d.getAsJsonObject("codex_quest") : new JsonObject();
                QuestDefinitions.validateAdvancement(q, d);
                if (q.has("requires_choice")) {
                    JsonObject gate = q.getAsJsonObject("requires_choice");
                    Identifier source = Identifier.parse(gate.get("quest").getAsString());
                    JsonObject story = definitions.get(source);
                    if (story == null || !hasChoice(story, gate.get("choice").getAsString())) out.add(new Issue(id, true, "Branch refers to a missing story or choice"));
                    Set<Identifier> visited = new HashSet<>(); visited.add(id);
                    while (source != null) {
                        if (!visited.add(source)) { out.add(new Issue(id, true, "Story branch dependency cycle")); break; }
                        JsonObject ancestor = definitions.get(source);
                        JsonObject rules = ancestor != null && ancestor.has("codex_quest") ? ancestor.getAsJsonObject("codex_quest") : new JsonObject();
                        source = rules.has("requires_choice") ? Identifier.parse(rules.getAsJsonObject("requires_choice").get("quest").getAsString()) : null;
                    }
                }
                for (JsonElement c : choices(d)) {
                    JsonObject choice = c.getAsJsonObject();
                    if (choice.has("when")) for (JsonElement group : choice.getAsJsonArray("when")) for (JsonElement value : group.getAsJsonArray()) {
                        JsonObject condition=value.getAsJsonObject(); Identifier source=Identifier.parse(condition.get("quest").getAsString());
                        if (!definitions.containsKey(source)) out.add(new Issue(id,true,"Dialogue condition refers to missing quest " + source));
                        else if (condition.get("state").getAsString().equals("choice") && !hasChoice(definitions.get(source),condition.get("choice").getAsString())) out.add(new Issue(id,true,"Dialogue condition refers to missing choice in " + source));
                    }
                    if (!choice.has("next_quest")) continue;
                    Identifier next = Identifier.parse(choice.get("next_quest").getAsString());
                    JsonObject destination = definitions.get(next);
                    if (destination == null) out.add(new Issue(id, true, "Choice links to missing chapter " + next));
                    else {
                        JsonObject rules = destination.has("codex_quest") ? destination.getAsJsonObject("codex_quest") : new JsonObject();
                        JsonObject gate = rules.has("requires_choice") ? rules.getAsJsonObject("requires_choice") : new JsonObject();
                        if (!id.toString().equals(gate.has("quest") ? gate.get("quest").getAsString() : "") || !choice.get("id").equals(gate.get("choice")))
                            out.add(new Issue(id, false, "Chapter link is navigation only; it does not require this choice: " + next));
                    }
                }
                if (lootTables != null && d.has("rewards") && d.getAsJsonObject("rewards").has("loot"))
                    for (JsonElement l : d.getAsJsonObject("rewards").getAsJsonArray("loot")) {
                        Identifier table = Identifier.parse(l.getAsString());
                        if (!lootTables.contains(table)) out.add(new Issue(id, true, "Missing reward loot table " + table));
                    }
            } catch (RuntimeException invalid) { out.add(new Issue(id, true, Objects.toString(invalid.getMessage(), "Invalid definition"))); }
        });
        out.sort(Comparator.comparing((Issue i) -> !i.error()).thenComparing(i -> i.quest().toString()));
        return out;
    }
    public static JsonArray choices(JsonObject definition) {
        if (!definition.has("codex_quest")) return new JsonArray();
        JsonObject q = definition.getAsJsonObject("codex_quest");
        if (!q.has("dialogue") || !q.getAsJsonObject("dialogue").has("choices")) return new JsonArray();
        return q.getAsJsonObject("dialogue").getAsJsonArray("choices");
    }
    public static boolean hasChoice(JsonObject d, String key) {
        for (JsonElement c : choices(d)) if (key.equals(c.getAsJsonObject().get("id").getAsString())) return true;
        return false;
    }
    public static Set<Identifier> subtree(Map<Identifier, JsonObject> definitions, Identifier root, boolean children) {
        Set<Identifier> result = new LinkedHashSet<>(); result.add(root);
        if (children) { boolean changed; do { changed = false; for (var e : definitions.entrySet()) if (e.getValue().has("parent") && result.contains(Identifier.parse(e.getValue().get("parent").getAsString()))) changed |= result.add(e.getKey()); } while (changed); }
        return result;
    }
    public static Map<Identifier, JsonObject> duplicate(Map<Identifier, JsonObject> definitions, Identifier root, Identifier target, boolean children) {
        if (!definitions.containsKey(root)) throw new IllegalArgumentException("Select an existing quest");
        Set<Identifier> sources = subtree(definitions, root, children);
        Map<Identifier, Identifier> ids = new LinkedHashMap<>(); ids.put(root, target);
        int index = 1;
        for (Identifier id : sources) if (!id.equals(root)) ids.put(id, Identifier.fromNamespaceAndPath(target.getNamespace(), target.getPath() + "/copy_" + index++));
        for (Identifier id : ids.values()) if (definitions.containsKey(id)) throw new IllegalArgumentException("Already exists: " + id);
        Map<Identifier, JsonObject> out = new LinkedHashMap<>();
        ids.forEach((oldId, newId) -> {
            JsonObject d = definitions.get(oldId).deepCopy(); d.remove("codex_migration");
            remap(d, "parent", ids);
            if (d.has("codex_quest") && d.getAsJsonObject("codex_quest").has("requires_choice")) remap(d.getAsJsonObject("codex_quest").getAsJsonObject("requires_choice"), "quest", ids);
            for (JsonElement choice : choices(d)) remap(choice.getAsJsonObject(), "next_quest", ids);
            out.put(newId, d);
        });
        return out;
    }
    private static void remap(JsonObject d, String field, Map<Identifier, Identifier> ids) {
        if (d.has(field)) { Identifier mapped = ids.get(Identifier.parse(d.get(field).getAsString())); if (mapped != null) d.addProperty(field, mapped.toString()); }
    }
    public static Map<Identifier, JsonObject> linkBranches(Map<Identifier, JsonObject> definitions, Identifier story, JsonObject metadata) {
        Map<Identifier, JsonObject> changes = new LinkedHashMap<>();
        JsonObject wrapper = new JsonObject(); wrapper.add("codex_quest", metadata);
        JsonObject oldStory = definitions.get(story);
        if (oldStory != null) for (JsonElement c : choices(oldStory)) {
            JsonObject old = c.getAsJsonObject();
            if (!QuestDefinitions.flag(old, "gate_chapter") || !old.has("next_quest")) continue;
            boolean retained = false;
            for (JsonElement n : choices(wrapper)) { JsonObject next = n.getAsJsonObject(); if (old.get("id").equals(next.get("id")) && old.get("next_quest").equals(next.get("next_quest")) && QuestDefinitions.flag(next, "gate_chapter")) retained = true; }
            if (!retained) clearGate(definitions, changes, story, old);
        }
        for (JsonElement c : choices(wrapper)) {
            JsonObject choice = c.getAsJsonObject();
            if (choice.has("gate_chapter") && !choice.get("gate_chapter").getAsBoolean() && choice.has("next_quest")) clearGate(definitions, changes, story, choice);
            if (!QuestDefinitions.flag(choice, "gate_chapter") || !choice.has("next_quest")) continue;
            Identifier next = Identifier.parse(choice.get("next_quest").getAsString());
            if (next.equals(story)) throw new IllegalArgumentException("A story cannot lock itself behind its own choice");
            JsonObject d = changes.containsKey(next) ? changes.get(next) : definitions.containsKey(next) ? definitions.get(next).deepCopy() : null;
            if (d == null) throw new IllegalArgumentException("Missing next chapter: " + next);
            JsonObject q = d.has("codex_quest") ? d.getAsJsonObject("codex_quest") : new JsonObject();
            JsonObject gate = new JsonObject(); gate.addProperty("quest", story.toString()); gate.add("choice", choice.get("id").deepCopy());
            if (q.has("requires_choice") && !q.getAsJsonObject("requires_choice").equals(gate)) throw new IllegalArgumentException("Chapter already requires another choice: " + next);
            q.add("requires_choice", gate); d.add("codex_quest", q); changes.put(next, d);
        }
        return changes;
    }
    private static void clearGate(Map<Identifier, JsonObject> definitions, Map<Identifier, JsonObject> changes, Identifier story, JsonObject choice) {
        Identifier next = Identifier.parse(choice.get("next_quest").getAsString());
        JsonObject source = changes.containsKey(next) ? changes.get(next) : definitions.get(next);
        if (source == null || !source.has("codex_quest")) return;
        JsonObject d = source.deepCopy(), q = d.getAsJsonObject("codex_quest");
        if (!q.has("requires_choice")) return;
        JsonObject gate = q.getAsJsonObject("requires_choice");
        if (story.toString().equals(gate.get("quest").getAsString()) && choice.get("id").equals(gate.get("choice"))) { q.remove("requires_choice"); changes.put(next, d); }
    }
}
