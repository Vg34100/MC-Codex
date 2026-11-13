package net.vg.codex.advancement;

import com.google.gson.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Authoring metadata compiles to ordinary loot tables; vanilla remains the reward engine. */
public final class TreasureLists {
    private TreasureLists() { }

    public static Identifier tableId(Identifier list) {
        return Identifier.fromNamespaceAndPath(list.getNamespace(), "codex_treasure/" + list.getPath());
    }

    public static JsonObject read(Path pack) {
        Path file = pack.resolve("codex/treasure_lists.json");
        if (!Files.exists(file)) return new JsonObject();
        try { return JsonParser.parseString(Files.readString(file)).getAsJsonObject(); }
        catch (Exception error) { throw new IllegalStateException("Cannot read treasure lists", error); }
    }

    public static JsonObject compile(JsonObject definition, Set<Identifier> knownTables) {
        int rolls = integer(definition, "rolls", 1, 64);
        JsonArray source = definition.getAsJsonArray("entries");
        if (source.isEmpty() || source.size() > 64) throw new IllegalArgumentException("A treasure list needs 1–64 entries");
        JsonArray entries = new JsonArray();
        for (JsonElement value : source) {
            JsonObject entry = value.getAsJsonObject();
            JsonObject loot = new JsonObject();
            loot.addProperty("weight", integer(entry, "weight", 1, 10_000));
            Identifier id = Identifier.parse(entry.get("id").getAsString());
            if (entry.has("table") && entry.get("table").getAsBoolean()) {
                if (!knownTables.contains(id)) throw new IllegalArgumentException("Unknown loot table: " + id);
                loot.addProperty("type", "minecraft:loot_table");
                //? if <1.21 {
                /*loot.addProperty("name", id.toString());
                *///?} else {
                loot.addProperty("value", id.toString());
                //?}
            } else {
                if (!BuiltInRegistries.ITEM.containsKey(id) || id.equals(Identifier.withDefaultNamespace("air"))) throw new IllegalArgumentException("Unknown item: " + id);
                int min = integer(entry, "min", 1, 4096); int max = integer(entry, "max", min, 4096);
                loot.addProperty("type", "minecraft:item"); loot.addProperty("name", id.toString());
                JsonObject function = new JsonObject();
                //? if >=26.3 {
                /*function.addProperty("type", "minecraft:set_count");
                *///?} else {
                function.addProperty("function", "minecraft:set_count");
                //?}
                JsonObject range = new JsonObject(); range.addProperty("type", "minecraft:uniform"); range.addProperty("min", min); range.addProperty("max", max);
                function.add("count", range);
                //? if >=26.3 {
                /*loot.add("modifier", function);
                *///?} else {
                JsonArray functions = new JsonArray(); functions.add(function); loot.add("functions", functions);
                //?}
            }
            entries.add(loot);
        }
        JsonObject pool = new JsonObject(); pool.addProperty("rolls", rolls); pool.add("entries", entries);
        JsonArray pools = new JsonArray(); pools.add(pool);
        JsonObject table = new JsonObject(); table.addProperty("type", "minecraft:advancement_reward"); table.add("pools", pools);
        return table;
    }

    public static void save(Path pack, Identifier id, JsonObject definition, Set<Identifier> knownTables) {
        String name = definition.get("name").getAsString().trim();
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("Treasure name must be 1–80 characters");
        Path tableFile = AdvancementPackIO.safeResourceFile(pack, tableId(id), "loot_table");
        JsonObject lists = read(pack); lists.add(id.toString(), definition.deepCopy());
        rejectCycles(lists);
        JsonObject table = compile(definition, knownTables);
        AtomicJson.write(tableFile, table);
        AtomicJson.write(pack.resolve("codex/treasure_lists.json"), lists);
    }

    private static int integer(JsonObject value, String key, int min, int max) {
        int result = value.get(key).getAsBigDecimal().intValueExact();
        if (result < min || result > max) throw new IllegalArgumentException(key + " must be " + min + "–" + max);
        return result;
    }

    private static void rejectCycles(JsonObject lists) {
        Map<Identifier, Set<Identifier>> edges = new HashMap<>();
        for (var list : lists.entrySet()) {
            Set<Identifier> references = new HashSet<>();
            for (var value : list.getValue().getAsJsonObject().getAsJsonArray("entries")) {
                JsonObject entry = value.getAsJsonObject();
                if (entry.has("table") && entry.get("table").getAsBoolean()) references.add(Identifier.parse(entry.get("id").getAsString()));
            }
            edges.put(tableId(Identifier.parse(list.getKey())), references);
        }
        for (Identifier id : edges.keySet()) visit(id, edges, new HashSet<>());
    }

    private static void visit(Identifier id, Map<Identifier, Set<Identifier>> edges, Set<Identifier> path) {
        if (!path.add(id)) throw new IllegalArgumentException("Treasure lists cannot reference each other in a cycle");
        for (Identifier child : edges.getOrDefault(id, Set.of())) if (edges.containsKey(child)) visit(child, edges, path);
        path.remove(id);
    }
}
