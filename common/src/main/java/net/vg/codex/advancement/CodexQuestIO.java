package net.vg.codex.advancement;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Codex-only quest state which must not be placed in a vanilla advancement document. Claimable
 * rewards live here so the source advancement stays compatible and does not auto-grant them.
 */
public final class CodexQuestIO {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path REWARDS_FILE = Path.of("codex", "claimable_rewards.json");
    private static final Path CLAIMS_DIR = Path.of("codex", "claims");
    private static final Path SUBMISSIONS_FILE = Path.of("codex", "submission_tasks.json");
    private static final Path SUBMISSIONS_DIR = Path.of("codex", "submissions");
    private static final Path STATISTICS_FILE = Path.of("codex", "statistic_tasks.json");

    private CodexQuestIO() {
    }

    public static Map<Identifier, JsonObject> readClaimableRewards(Path packDir) {
        Map<Identifier, JsonObject> result = new HashMap<>();
        Path file = packDir.resolve(REWARDS_FILE);
        if (Files.notExists(file)) return result;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry.getValue().isJsonObject()) result.put(Identifier.parse(entry.getKey()), entry.getValue().getAsJsonObject());
            }
        } catch (IOException | RuntimeException ignored) {
            // A malformed hand-edited sidecar should not prevent the server from starting.
        }
        return result;
    }

    public static void writeClaimableRewards(Path packDir, Map<Identifier, JsonObject> rewards) {
        JsonObject root = new JsonObject();
        rewards.forEach((id, json) -> root.add(id.toString(), json));
        write(packDir.resolve(REWARDS_FILE), root);
    }

    public static Set<Identifier> readClaims(Path packDir, UUID playerId) {
        Set<Identifier> result = new HashSet<>();
        Path file = packDir.resolve(CLAIMS_DIR).resolve(playerId + ".json");
        if (Files.notExists(file)) return result;
        try {
            for (JsonElement entry : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) {
                result.add(Identifier.parse(entry.getAsString()));
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return result;
    }

    public static void writeClaims(Path packDir, UUID playerId, Set<Identifier> claims) {
        com.google.gson.JsonArray root = new com.google.gson.JsonArray();
        claims.stream().map(Identifier::toString).sorted().forEach(root::add);
        write(packDir.resolve(CLAIMS_DIR).resolve(playerId + ".json"), root);
    }

    /** Per-reward claim state. Reads the legacy id array as a completed whole-quest claim so old
     * saves never grant an already-collected reward a second time. */
    public static Map<Identifier, Set<Integer>> readClaimIndexes(Path packDir, UUID playerId) {
        Map<Identifier, Set<Integer>> result = new HashMap<>();
        Path file = packDir.resolve(CLAIMS_DIR).resolve(playerId + ".json");
        if (Files.notExists(file)) return result;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (root.isJsonArray()) {
                for (JsonElement entry : root.getAsJsonArray()) result.put(Identifier.parse(entry.getAsString()), Set.of(-1));
            } else if (root.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                    Set<Integer> indexes = new HashSet<>();
                    for (JsonElement index : entry.getValue().getAsJsonArray()) indexes.add(index.getAsInt());
                    result.put(Identifier.parse(entry.getKey()), indexes);
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        return result;
    }

    public static void writeClaimIndexes(Path packDir, UUID playerId, Map<Identifier, Set<Integer>> claims) {
        JsonObject root = new JsonObject();
        claims.forEach((id, indexes) -> {
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            indexes.stream().sorted().forEach(array::add);
            root.add(id.toString(), array);
        });
        write(packDir.resolve(CLAIMS_DIR).resolve(playerId + ".json"), root);
    }

    public static String taskKey(Identifier advancementId, String criterion) {
        return advancementId + "#" + criterion;
    }

    /** Maps task key to its item requirement object ({@code {items:[{id,count}]}}). */
    public static Map<String, JsonObject> readSubmissionTasks(Path packDir) {
        return readObjectMap(packDir.resolve(SUBMISSIONS_FILE));
    }

    public static void writeSubmissionTasks(Path packDir, Map<String, JsonObject> tasks) {
        writeObjectMap(packDir.resolve(SUBMISSIONS_FILE), tasks);
    }

    public static Map<String, JsonObject> readStatisticTasks(Path packDir) { return readObjectMap(packDir.resolve(STATISTICS_FILE)); }
    public static void writeStatisticTasks(Path packDir, Map<String, JsonObject> tasks) { writeObjectMap(packDir.resolve(STATISTICS_FILE), tasks); }

    /** Maps task key to item-id -> amount already consumed by this player. */
    public static Map<String, Map<Identifier, Integer>> readSubmissionProgress(Path packDir, UUID playerId) {
        Map<String, Map<Identifier, Integer>> result = new HashMap<>();
        Path file = packDir.resolve(SUBMISSIONS_DIR).resolve(playerId + ".json");
        if (Files.notExists(file)) return result;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> task : root.entrySet()) {
                if (!task.getValue().isJsonObject()) continue;
                Map<Identifier, Integer> values = new HashMap<>();
                for (Map.Entry<String, JsonElement> amount : task.getValue().getAsJsonObject().entrySet()) {
                    values.put(Identifier.parse(amount.getKey()), amount.getValue().getAsInt());
                }
                result.put(task.getKey(), values);
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return result;
    }

    public static void writeSubmissionProgress(Path packDir, UUID playerId, Map<String, Map<Identifier, Integer>> progress) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Map<Identifier, Integer>> task : progress.entrySet()) {
            JsonObject values = new JsonObject();
            task.getValue().forEach((id, amount) -> values.addProperty(id.toString(), amount));
            root.add(task.getKey(), values);
        }
        write(packDir.resolve(SUBMISSIONS_DIR).resolve(playerId + ".json"), root);
    }

    private static Map<String, JsonObject> readObjectMap(Path file) {
        Map<String, JsonObject> result = new HashMap<>();
        if (Files.notExists(file)) return result;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry.getValue().isJsonObject()) result.put(entry.getKey(), entry.getValue().getAsJsonObject());
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return result;
    }

    private static void writeObjectMap(Path file, Map<String, JsonObject> values) {
        JsonObject root = new JsonObject();
        values.forEach((key, value) -> root.add(key, value));
        write(file, root);
    }

    private static void write(Path file, JsonElement json) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write Codex quest state", e);
        }
    }
}
