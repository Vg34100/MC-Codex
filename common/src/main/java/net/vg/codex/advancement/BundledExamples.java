package net.vg.codex.advancement;

import com.google.gson.*;
import dev.architectury.platform.Platform;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Installs editable native examples, tracking ownership at sidecar-entry granularity. */
public final class BundledExamples {
    private BundledExamples() { }
    public static final List<String> GROUPS = List.of("vanilla", "showcase", "sagittary", "sleepcycle");

    public static void install() {
        if (Boolean.getBoolean("codex.examples.disabled")) return;
        try {
            Path config = Platform.getConfigFolder().resolve("codex-examples.json");
            JsonObject settings = read(config);
            boolean changed = false;
            for (String group : GROUPS) {
                if (!settings.has(group)) { settings.addProperty(group, true); changed = true; }
                if (!settings.get(group).isJsonPrimitive() || !settings.get(group).getAsJsonPrimitive().isBoolean())
                    throw new IllegalArgumentException("Example setting must be true or false: " + group);
            }
            if (changed) AtomicJson.write(config, settings);
            Set<String> enabled = new HashSet<>();
            for (String group : GROUPS) if (settings.get(group).getAsBoolean()
                    && (!group.equals("sagittary") && !group.equals("sleepcycle") || Platform.isModLoaded(group))) enabled.add(group);
            apply(AdvancementPackSync.externalPackDir(),
                    AdvancementPackSync.externalPackDir().getParent().resolve("bundled_examples.json"), enabled);
        } catch (Exception error) {
            LoggerFactory.getLogger(BundledExamples.class).error("Cannot prepare bundled Codex examples; existing authoring data retained", error);
        }
    }

    private static JsonObject resource(String path) throws IOException {
        try (InputStream input = BundledExamples.class.getResourceAsStream("/examples/codex/" + path)) {
            if (input == null) throw new IOException("Missing bundled example " + path);
            JsonObject example=JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            if(path.startsWith("vanilla/data/minecraft/advancement/")) {
                // Vanilla definitions follow the running game. Examples only enhance their display.
                String nativePath="/"+path.substring("vanilla/".length());
                //? if <1.21 {
                /*nativePath=nativePath.replace("/advancement/","/advancements/");
                *///?}
                try(InputStream original=net.minecraft.advancements.Advancement.class.getResourceAsStream(nativePath)) {
                    if(original==null)throw new IOException("Missing native advancement "+nativePath);
                    JsonObject nativeDefinition=JsonParser.parseString(new String(original.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                    if(example.has("display")) {
                        JsonObject display=nativeDefinition.has("display")?nativeDefinition.getAsJsonObject("display"):new JsonObject();
                        example.getAsJsonObject("display").entrySet().forEach(e->display.add(e.getKey(),e.getValue().deepCopy()));
                        nativeDefinition.add("display",display);
                    }
                    return nativeDefinition;
                }
            }
            if(example.has("criteria"))for(var entry:example.getAsJsonObject("criteria").entrySet())entry.setValue(CriterionFormats.generated(entry.getValue().getAsJsonObject()));
            // This bundled table contains one set_count modifier per item entry.
            // Authored/custom loot tables never pass through the example installer.
            //? if >=26.3 {
            /*if(path.equals("showcase/data/codex_demo/loot_table/codex_treasure/field_provisions.json")) {
                for(JsonElement pool:example.getAsJsonArray("pools"))for(JsonElement value:pool.getAsJsonObject().getAsJsonArray("entries")) {
                    JsonObject entry=value.getAsJsonObject();JsonArray functions=entry.remove("functions").getAsJsonArray();
                    JsonObject modifier=functions.get(0).getAsJsonObject();modifier.add("type",modifier.remove("function"));entry.add("modifier",modifier);
                }
            }
            *///?}
            return example;
        }
    }
    private static JsonObject read(Path file) throws IOException {
        return Files.isRegularFile(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject() : new JsonObject();
    }

    /** Also used by isolated installation/migration checks. Never writes player progression. */
    public static void apply(Path pack, Path history, Set<String> enabled) throws IOException {
        JsonObject manifest = resource("manifest.json"), ledger = read(history), previous = ledger.deepCopy();
        Map<Path, JsonObject> documents = new LinkedHashMap<>(), before = new LinkedHashMap<>();
        for (String group : GROUPS) for (JsonElement name : manifest.getAsJsonArray(group)) {
            Path file = pack.resolve(name.getAsString()).toAbsolutePath().normalize();
            if (!file.startsWith(pack.toAbsolutePath().normalize())) throw new IOException("Invalid example path");
            if (!documents.containsKey(file)) { JsonObject current = read(file); documents.put(file, current); before.put(file, current.deepCopy()); }
        }
        for (String group : GROUPS) {
            JsonObject records = ledger.has(group) ? ledger.getAsJsonObject(group) : new JsonObject();
            ledger.add(group, records);
            // Keep edited trees intact, including their parents, conditions and reward tables.
            // A switch must never leave an author's customized example with missing dependencies.
            if (!enabled.contains(group) && hasEdits(pack, records, documents)) {
                LoggerFactory.getLogger(BundledExamples.class).info("Keeping customized {} examples and their dependencies; remove manually after exporting your edits", group);
                continue;
            }
            for (JsonElement name : manifest.getAsJsonArray(group)) {
                String relative = name.getAsString();
                Path file = pack.resolve(relative).toAbsolutePath().normalize();
                if (!file.startsWith(pack.toAbsolutePath().normalize())) throw new IOException("Invalid example path");
                if (!documents.containsKey(file)) { JsonObject current = read(file); documents.put(file, current); before.put(file, current.deepCopy()); }
                JsonObject current = documents.get(file), incoming = resource(group + "/" + relative);
                boolean entries = relative.startsWith("codex/");
                for (String key : entries ? incoming.keySet() : Set.of("$file")) {
                    String unit = relative + "#" + key;
                    JsonElement value = entries ? incoming.get(key) : incoming;
                    JsonElement actual = entries ? current.get(key) : current.isEmpty() ? null : current;
                    JsonObject record = records.has(unit) ? records.getAsJsonObject(unit) : null;
                    if (enabled.contains(group)) {
                        if (record == null) {
                            record = new JsonObject(); record.addProperty("owned", actual == null); record.addProperty("active", actual == null);
                            record.add("baseline", value.deepCopy()); records.add(unit, record);
                            if (actual == null) put(current, key, value, entries);
                        } else if (record.get("owned").getAsBoolean() && !record.get("active").getAsBoolean() && actual == null) {
                            // Re-enable only entries this switch removed, not author-deleted entries.
                            put(current, key, value, entries); record.addProperty("active", true); record.add("baseline",value.deepCopy());
                        } else if(record.get("owned").getAsBoolean() && record.get("active").getAsBoolean()
                                && actual!=null && actual.equals(record.get("baseline")) && !actual.equals(value)) {
                            // Only untouched, installer-owned examples follow a new game/schema version.
                            put(current,key,value,entries);record.add("baseline",value.deepCopy());
                        }
                    } else if (record != null && record.get("owned").getAsBoolean() && record.get("active").getAsBoolean()
                            && actual != null && actual.equals(record.get("baseline"))) {
                        if (entries) current.remove(key); else current.asMap().clear();
                        record.addProperty("active", false);
                    }
                }
            }
        }
        // Parse all input first. A malformed authored file never causes partial deletion.
        for (var entry : documents.entrySet()) {
            Path file = entry.getKey(); JsonObject document = entry.getValue();
            if (document.equals(before.get(file))) continue;
            if (document.isEmpty()) {
                if (Files.exists(file)) { Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING); Files.delete(file); }
            } else {
                if(Files.exists(file))Files.copy(file,file.resolveSibling(file.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING);
                AtomicJson.write(file,document);
            }
        }
        if (!ledger.equals(previous)) AtomicJson.write(history, ledger);
    }
    private static boolean hasEdits(Path pack, JsonObject records, Map<Path, JsonObject> documents) {
        for (var entry : records.entrySet()) {
            JsonObject record = entry.getValue().getAsJsonObject();
            if (!record.get("owned").getAsBoolean() || !record.get("active").getAsBoolean()) continue;
            int separator = entry.getKey().indexOf('#');
            String relative = entry.getKey().substring(0, separator), key = entry.getKey().substring(separator + 1);
            JsonObject document = documents.get(pack.resolve(relative).toAbsolutePath().normalize());
            if (document == null) continue;
            JsonElement value = relative.startsWith("codex/") ? document.get(key) : document.isEmpty() ? null : document;
            if (value != null && !value.equals(record.get("baseline"))) return true;
        }
        return false;
    }
    private static void put(JsonObject document, String key, JsonElement value, boolean entry) {
        if (entry) document.add(key, value.deepCopy());
        else { document.asMap().clear(); value.getAsJsonObject().entrySet().forEach(e -> document.add(e.getKey(), e.getValue().deepCopy())); }
    }
}
