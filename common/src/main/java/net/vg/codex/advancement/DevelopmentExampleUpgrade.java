package net.vg.codex.advancement;

import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Retire only the untouched old story. Any authored change preserves the entire tree. */
public final class DevelopmentExampleUpgrade {
    private DevelopmentExampleUpgrade() { }
    public static boolean retireJournal(Path source, Path destination, Path backup) throws IOException {
        Map<Path, JsonObject> expected = new LinkedHashMap<>(); Set<String> ids = new HashSet<>();
        try (var files = Files.walk(source.resolve("data/codex_demo/advancement/field_journal"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(f); expected.put(relative, read(f));
                ids.add("codex_demo:field_journal/" + f.getFileName().toString().replace(".json", ""));
            }
        }
        for (var entry : expected.entrySet()) { Path target = destination.resolve(entry.getKey()); if (Files.exists(target) && !read(target).equals(entry.getValue())) return false; }
        // Protect extra nodes/children created in the GUI, including children in another namespace.
        if (Files.isDirectory(destination.resolve("data"))) try (var files = Files.walk(destination.resolve("data"))) {
            for (Path f : files.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".json") && f.toString().replace('\\', '/').contains("/advancement/")).toList()) {
                Path relative = destination.relativize(f); if (expected.containsKey(relative)) continue;
                JsonObject object = read(f);
                if (relative.toString().replace('\\', '/').contains("/field_journal/") || object.has("parent") && ids.contains(object.get("parent").getAsString())) return false;
            }
        }
        Map<Path, JsonObject> updates = new LinkedHashMap<>();
        for (String name : List.of("submission_tasks", "claimable_rewards", "quests")) {
            Path relative = Path.of("codex/" + name + ".json"), target = destination.resolve(relative), baseline = source.resolve(relative);
            if (!Files.exists(target)) continue;
            JsonObject current = read(target), original = Files.exists(baseline) ? read(baseline) : new JsonObject();
            for (var entry : current.entrySet()) {
                String id = entry.getKey().split("#", 2)[0];
                if (id.startsWith("codex_demo:field_journal/") && (!original.has(entry.getKey()) || !original.get(entry.getKey()).equals(entry.getValue()))) return false;
            }
            JsonObject updated = current.deepCopy(); for (String key : new ArrayList<>(updated.keySet())) if (key.split("#", 2)[0].startsWith("codex_demo:field_journal/")) updated.remove(key);
            if (!current.equals(updated)) updates.put(relative, updated);
        }
        // Copy all originals before deleting anything. Shared treasure lists/tables stay in place.
        Set<Path> affected = new LinkedHashSet<>(expected.keySet()); affected.addAll(updates.keySet());
        for (Path relative : affected) if (Files.exists(destination.resolve(relative))) { Path copy = backup.resolve(relative); Files.createDirectories(copy.getParent()); if (!Files.exists(copy)) Files.copy(destination.resolve(relative), copy); }
        for (Path relative : expected.keySet()) Files.deleteIfExists(destination.resolve(relative));
        for (var entry : updates.entrySet()) AtomicJson.write(destination.resolve(entry.getKey()), entry.getValue());
        return true;
    }
    public static void labelPreservedJournal(Path source, Path destination, Path backup) throws IOException {
        Path relative = Path.of("data/codex_demo/advancement/field_journal/root.json");
        if (!Files.exists(destination.resolve(relative))) return;
        JsonObject original = read(source.resolve(relative)), current = read(destination.resolve(relative));
        JsonElement title = current.getAsJsonObject("display").get("title");
        if (!title.isJsonPrimitive() || !title.equals(original.getAsJsonObject("display").get("title"))) return;
        Path copy = backup.resolve(relative); Files.createDirectories(copy.getParent()); if (!Files.exists(copy)) Files.copy(destination.resolve(relative), copy);
        current.getAsJsonObject("display").addProperty("title", title.getAsString() + " (Legacy Demo)");
        AtomicJson.write(destination.resolve(relative), current);
    }
    private static JsonObject read(Path file) throws IOException { return JsonParser.parseString(Files.readString(file)).getAsJsonObject(); }
}
