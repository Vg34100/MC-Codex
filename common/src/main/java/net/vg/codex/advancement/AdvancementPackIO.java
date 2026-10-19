package net.vg.codex.advancement;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads and writes vanilla advancement JSON (data/<namespace>/advancement/<path>.json) inside the
 * editable pack folder, going through the real {@link Advancement#CODEC} so files round-trip
 * exactly as vanilla itself would read/write them - including overwriting a vanilla advancement
 * id like minecraft:story/root.
 */
public final class AdvancementPackIO {
    private static final String ADVANCEMENT_DIR = "advancement";
    private static final String LOOT_TABLE_DIR = "loot_table";
    // Namespaced under this prefix (within the advancement's own namespace) so a generated table
    // can never collide with a hand-authored one, and is trivially recognizable/cleanable.
    private static final String GENERATED_LOOT_PREFIX = "codex_generated/";
    /** Codex-only presentation data deliberately lives outside data/, so vanilla ignores it. */
    private static final Path LAYOUT_FILE = Path.of("codex", "layout.json");
    private static final String SUFFIX = ".json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AdvancementPackIO() {
    }

    /** A server-authored tree-space position (the same pixel space used by advancement widgets). */
    public record NodePosition(int x, int y) {
    }

    /**
     * Reads optional editor layout data. Keeping this outside advancement JSON means an exported
     * datapack remains fully valid without Codex while Codex clients can share an authored map.
     */
    public static Map<Identifier, NodePosition> readLayout(Path packDir) {
        Map<Identifier, NodePosition> result = new HashMap<>();
        Path file = packDir.resolve(LAYOUT_FILE);
        if (Files.notExists(file)) return result;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("positions") || !root.get("positions").isJsonObject()) return result;
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("positions").entrySet()) {
                try {
                    JsonObject point = entry.getValue().getAsJsonObject();
                    result.put(Identifier.parse(entry.getKey()), new NodePosition(point.get("x").getAsInt(), point.get("y").getAsInt()));
                } catch (RuntimeException ignored) {
                    // A single hand-edited bad entry should not hide every other custom position.
                }
            }
            return result;
        } catch (IOException | RuntimeException e) {
            return result;
        }
    }

    public static void writeLayout(Path packDir, Map<Identifier, NodePosition> positions) {
        JsonObject root = new JsonObject();
        JsonObject entries = new JsonObject();
        for (Map.Entry<Identifier, NodePosition> entry : positions.entrySet()) {
            JsonObject point = new JsonObject();
            point.addProperty("x", entry.getValue().x());
            point.addProperty("y", entry.getValue().y());
            entries.add(entry.getKey().toString(), point);
        }
        root.add("positions", entries);
        writeJson(packDir.resolve(LAYOUT_FILE), root);
    }

    public static List<AdvancementHolder> readAll(Path packDir, HolderLookup.Provider registries) {
        List<AdvancementHolder> result = new ArrayList<>();
        Path dataDir = packDir.resolve("data");
        if (Files.notExists(dataDir)) {
            return result;
        }
        try (Stream<Path> namespaces = Files.list(dataDir)) {
            for (Path namespaceDir : namespaces.filter(Files::isDirectory).toList()) {
                String namespace = namespaceDir.getFileName().toString();
                Path advancementDir = namespaceDir.resolve(ADVANCEMENT_DIR);
                if (Files.notExists(advancementDir)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(advancementDir)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(SUFFIX)).toList()) {
                        String relative = advancementDir.relativize(file).toString().replace('\\', '/');
                        String path = relative.substring(0, relative.length() - SUFFIX.length());
                        Identifier id = Identifier.fromNamespaceAndPath(namespace, path);
                        result.add(new AdvancementHolder(id, readOne(file, registries)));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    private static Advancement readOne(Path file, HolderLookup.Provider registries) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonElement json = JsonParser.parseString(text);
        return Advancement.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), json).getOrThrow();
    }

    public static void write(Path packDir, AdvancementHolder holder, HolderLookup.Provider registries) {
        JsonElement json = Advancement.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), holder.value()).getOrThrow();
        writeJson(fileFor(packDir, holder.id()), json.getAsJsonObject());
    }

    /**
     * True if this id already resolves to a file, either in the editable pack folder or in
     * whatever datapacks/vanilla data are currently loaded.
     */
    public static boolean exists(Path packDir, ResourceProvider resources, Identifier id) {
        return Files.exists(fileFor(packDir, id)) || resources.getResource(resourceLocationFor(id)).isPresent();
    }

    /**
     * Patches only whichever top-level JSON keys are present in {@code edited} (typically
     * "display", "criteria"/"requirements", "rewards", and "parent" for a reparent) into an
     * already-existing advancement (vanilla or otherwise), leaving everything else untouched. This
     * is pure JSON key replacement, no codec involved: the client that produced {@code edited}
     * never decodes a full {@code Advancement} either (see {@code RawAdvancement}), so this stays
     * consistent with treating the file as JSON end to end rather than round-tripping through
     * {@link Advancement#CODEC}, which some vanilla advancements fail to re-encode even though they
     * loaded fine (decode is more lenient than encode).
     */
    public static void patchRawFields(Path packDir, ResourceProvider resources, Identifier id, JsonObject edited) {
        JsonObject json = readBaseJson(packDir, resources, id);
        for (String key : List.of("display", "criteria", "requirements", "rewards", "parent")) {
            if (edited.has(key)) {
                json.add(key, edited.get(key));
            }
        }
        writeJson(fileFor(packDir, id), json);
    }

    /**
     * Writes a brand-new advancement (no existing file to patch) from raw JSON, validating it
     * against the real {@link Advancement#CODEC} first (using the server's full registry access,
     * where that codec is safe to use) so a malformed submission is rejected with a clear error
     * instead of writing a file the game can't load.
     */
    public static void writeRaw(Path packDir, Identifier id, JsonObject json, HolderLookup.Provider registries) {
        Advancement.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), json).getOrThrow();
        writeJson(fileFor(packDir, id), json);
    }

    private static JsonObject readBaseJson(Path packDir, ResourceProvider resources, Identifier id) {
        Path localFile = fileFor(packDir, id);
        try {
            if (Files.exists(localFile)) {
                return JsonParser.parseString(Files.readString(localFile, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            try (BufferedReader reader = resources.getResourceOrThrow(resourceLocationFor(id)).openAsReader()) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeJson(Path file, JsonObject json) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Identifier resourceLocationFor(Identifier id) {
        return Identifier.fromNamespaceAndPath(id.getNamespace(), ADVANCEMENT_DIR + "/" + id.getPath() + SUFFIX);
    }

    public static void delete(Path packDir, Identifier id) {
        try {
            Files.deleteIfExists(fileFor(packDir, id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Deletes {@code ids} and, transitively, every advancement whose "parent" field (scanned
     * directly off disk, not from whatever tree the client happened to have loaded) points at one
     * of them. This is a server-side safety net independent of the client's own descendant
     * computation - a stale/out-of-sync client-side tree would otherwise leave an orphaned child
     * behind whose parent no longer exists, which is silently dropped with a "Couldn't load
     * advancements" warning on every future load rather than actually being cleaned up. Returns
     * every id actually deleted (requested ones plus any cascaded descendants).
     */
    public static Set<Identifier> deleteWithDescendants(Path packDir, Collection<Identifier> ids) {
        Map<Identifier, Identifier> parentOf = new HashMap<>();
        Path dataDir = packDir.resolve("data");
        if (Files.exists(dataDir)) {
            try (Stream<Path> namespaces = Files.list(dataDir)) {
                for (Path namespaceDir : namespaces.filter(Files::isDirectory).toList()) {
                    String namespace = namespaceDir.getFileName().toString();
                    Path advancementDir = namespaceDir.resolve(ADVANCEMENT_DIR);
                    if (Files.notExists(advancementDir)) {
                        continue;
                    }
                    try (Stream<Path> files = Files.walk(advancementDir)) {
                        for (Path file : files.filter(p -> p.toString().endsWith(SUFFIX)).toList()) {
                            String relative = advancementDir.relativize(file).toString().replace('\\', '/');
                            String path = relative.substring(0, relative.length() - SUFFIX.length());
                            Identifier id = Identifier.fromNamespaceAndPath(namespace, path);
                            try {
                                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                                if (json.has("parent")) {
                                    parentOf.put(id, Identifier.parse(json.get("parent").getAsString()));
                                }
                            } catch (IOException | RuntimeException e) {
                                // Unreadable/malformed file - not this method's job to fix, skip it.
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        Set<Identifier> toDelete = new HashSet<>(ids);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<Identifier, Identifier> entry : parentOf.entrySet()) {
                if (toDelete.contains(entry.getValue()) && toDelete.add(entry.getKey())) {
                    changed = true;
                }
            }
        }

        for (Identifier id : toDelete) {
            delete(packDir, id);
        }
        return toDelete;
    }

    private static Path fileFor(Path packDir, Identifier id) {
        return packDir.resolve("data").resolve(id.getNamespace()).resolve(ADVANCEMENT_DIR).resolve(id.getPath() + SUFFIX);
    }

    /**
     * Pulls "the one representative item" out of a single criterion's JSON, for the hover box's
     * "Required" hint row - only meaningful for the two curated trigger shapes that actually
     * reference an item ({@code minecraft:inventory_changed}'s item predicate list,
     * {@code minecraft:item_used_on_block}'s match_tool check); every other trigger (location,
     * tick, kill-entity, ...) has no natural item and is simply skipped. Shared by both the
     * server-side broadcast (real synced criteria) and the client-side editor (raw, possibly
     * unsaved criteria JSON) so the two can never drift out of sync on what counts as "has an
     * item". A criterion with multiple items (a real "any of these" list) only ever contributes
     * its first - showing every alternative would misleadingly read as "all of these required".
     */
    public static Optional<Identifier> extractCriterionItemId(JsonObject criterion) {
        if (!criterion.has("trigger") || !criterion.has("conditions") || !criterion.get("conditions").isJsonObject()) {
            return Optional.empty();
        }
        String trigger = criterion.get("trigger").getAsString();
        JsonObject conditions = criterion.getAsJsonObject("conditions");
        try {
            if ("minecraft:inventory_changed".equals(trigger) && conditions.has("items")) {
                JsonArray items = conditions.getAsJsonArray("items");
                if (!items.isEmpty()) {
                    JsonObject first = items.get(0).getAsJsonObject();
                    if (first.has("items") && first.get("items").isJsonPrimitive()) {
                        return Optional.of(Identifier.parse(first.get("items").getAsString()));
                    }
                }
            } else if ("minecraft:item_used_on_block".equals(trigger) && conditions.has("location")) {
                for (JsonElement checkElement : conditions.getAsJsonArray("location")) {
                    JsonObject check = checkElement.getAsJsonObject();
                    String condition = check.has("condition") ? check.get("condition").getAsString() : null;
                    if ("minecraft:match_tool".equals(condition) && check.has("predicate")) {
                        JsonObject predicate = check.getAsJsonObject("predicate");
                        if (predicate.has("items") && predicate.get("items").isJsonPrimitive()) {
                            return Optional.of(Identifier.parse(predicate.get("items").getAsString()));
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // Malformed/unexpected shape (hand-edited file, or a different mod reusing these
            // trigger ids with its own schema) - just skip the hint, not worth failing over.
        }
        return Optional.empty();
    }

    // ---- Item rewards (via an auto-generated loot table, since vanilla's AdvancementRewards has
    // no item field of its own - "rewards.loot" pointing at one of these is the real vanilla grant
    // mechanism; everything below just keeps that generated file in sync with the editor's list). ----

    /** Deterministic per-advancement id, so re-saving always regenerates the same file rather than
     * accumulating orphans, and a fresh snapshot can always find it back given just the advancement id. */
    public static Identifier generatedLootTableId(Identifier advancementId) {
        return Identifier.fromNamespaceAndPath(advancementId.getNamespace(), GENERATED_LOOT_PREFIX + advancementId.getPath());
    }

    private static Path lootTableFileFor(Path packDir, Identifier lootTableId) {
        return packDir.resolve("data").resolve(lootTableId.getNamespace()).resolve(LOOT_TABLE_DIR).resolve(lootTableId.getPath() + SUFFIX);
    }

    /**
     * Writes {@code itemRewards} (a JSON array of {@code {"id": "...", "count": N}} objects, the
     * editor's own working format) as a real vanilla loot table - one pool per item, each with a
     * guaranteed single roll, so every listed item is granted every time rather than only one being
     * picked from a shared pool.
     */
    public static void writeGeneratedLootTable(Path packDir, Identifier advancementId, JsonArray itemRewards) {
        JsonObject lootTable = new JsonObject();
        lootTable.addProperty("type", "minecraft:advancement_reward");
        JsonArray pools = new JsonArray();
        for (JsonElement e : itemRewards) {
            JsonObject item = e.getAsJsonObject();
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "minecraft:item");
            entry.addProperty("name", item.get("id").getAsString());
            int count = item.has("count") ? item.get("count").getAsInt() : 1;
            if (count != 1) {
                JsonObject setCount = new JsonObject();
                setCount.addProperty("function", "minecraft:set_count");
                setCount.addProperty("count", count);
                JsonArray functions = new JsonArray();
                functions.add(setCount);
                entry.add("functions", functions);
            }
            JsonArray entries = new JsonArray();
            entries.add(entry);
            JsonObject pool = new JsonObject();
            pool.addProperty("rolls", 1);
            pool.add("entries", entries);
            pools.add(pool);
        }
        lootTable.add("pools", pools);
        writeJson(lootTableFileFor(packDir, generatedLootTableId(advancementId)), lootTable);
    }

    public static void deleteGeneratedLootTable(Path packDir, Identifier advancementId) {
        try {
            Files.deleteIfExists(lootTableFileFor(packDir, generatedLootTableId(advancementId)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reverses {@link #writeGeneratedLootTable} - reads the on-disk generated loot table (if any)
     * back into the editor's {@code {"id","count"}} working format, so reopening the editor on an
     * advancement that already has item rewards shows them populated and editable instead of blank.
     * Only ever reads a file this class itself wrote, so the shape is trusted; malformed/hand-edited
     * entries are just skipped rather than failing the whole snapshot.
     */
    public static JsonArray readGeneratedItemRewards(Path packDir, Identifier advancementId) {
        JsonArray result = new JsonArray();
        Path file = lootTableFileFor(packDir, generatedLootTableId(advancementId));
        if (Files.notExists(file)) {
            return result;
        }
        try {
            JsonObject lootTable = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!lootTable.has("pools") || !lootTable.get("pools").isJsonArray()) {
                return result;
            }
            for (JsonElement poolEl : lootTable.getAsJsonArray("pools")) {
                if (!poolEl.isJsonObject()) continue;
                JsonObject pool = poolEl.getAsJsonObject();
                if (!pool.has("entries") || !pool.get("entries").isJsonArray()) continue;
                for (JsonElement entryEl : pool.getAsJsonArray("entries")) {
                    if (!entryEl.isJsonObject()) continue;
                    JsonObject entry = entryEl.getAsJsonObject();
                    if (!entry.has("name")) continue;
                    int count = 1;
                    if (entry.has("functions") && entry.get("functions").isJsonArray()) {
                        for (JsonElement fnEl : entry.getAsJsonArray("functions")) {
                            if (fnEl.isJsonObject() && fnEl.getAsJsonObject().has("count")) {
                                count = fnEl.getAsJsonObject().get("count").getAsInt();
                            }
                        }
                    }
                    JsonObject reward = new JsonObject();
                    reward.addProperty("id", entry.get("name").getAsString());
                    reward.addProperty("count", count);
                    result.add(reward);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }
}
