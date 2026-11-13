package net.vg.codex.advancement;

import com.google.gson.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** IDs follow rewards through editor reordering. Original index ledgers remain readable. */
public final class QuestRewardIdentity {
    private QuestRewardIdentity() { }
    public static JsonObject attach(Identifier id, JsonObject rewards) {
        JsonObject result = rewards.deepCopy(); result.remove("codex_ids");
        var entries = QuestRewards.entries(result, id); JsonObject q = QuestDefinitions.get(id);
        JsonArray ids = q.has("reward_ids") && q.getAsJsonArray("reward_ids").size() == entries.size() ? q.getAsJsonArray("reward_ids").deepCopy() : new JsonArray();
        if (ids.isEmpty()) for (int i = 0; i < entries.size(); i++) ids.add(UUID.nameUUIDFromBytes((id + "/" + i + "/" + entries.get(i)).getBytes(StandardCharsets.UTF_8)).toString());
        result.add("codex_ids", ids); return result;
    }
    public static void validate(JsonObject rewards, Identifier id) {
        if (!rewards.has("codex_ids")) return;
        JsonArray ids = rewards.getAsJsonArray("codex_ids"); JsonObject copy = rewards.deepCopy(); copy.remove("codex_ids");
        if (ids.size() != QuestRewards.entries(copy, id).size()) throw new IllegalArgumentException("Reward identity count does not match rewards");
        Set<String> used = new HashSet<>(); for (JsonElement e : ids) if (e.getAsString().length() > 80 || e.getAsString().isBlank() || !used.add(e.getAsString())) throw new IllegalArgumentException("Reward identities must be short and unique");
    }
    public static Path file(ServerPlayer p, Identifier id) { return QuestRuntime.claimsDirectory(p, id).resolve("codex/claim_keys/" + QuestCycles.rewardOwner(p, id) + ".json"); }
    public static JsonObject ledger(ServerPlayer p, Identifier id) {
        Path f = file(p, id);
        try { return Files.exists(f) ? JsonParser.parseString(Files.readString(f)).getAsJsonObject() : new JsonObject(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot read reward identities", e); }
    }
    public static Set<String> claimed(ServerPlayer p, Identifier id, JsonObject rewards) {
        JsonObject ledger = ledger(p, id); Set<String> claimed = new HashSet<>();
        if (ledger.has(id.toString())) { for (JsonElement e : ledger.getAsJsonArray(id.toString())) claimed.add(e.getAsString()); return claimed; }
        Set<Integer> legacy = CodexQuestIO.readClaimIndexes(QuestRuntime.claimsDirectory(p, id), QuestCycles.rewardOwner(p, id)).getOrDefault(id, Set.of());
        if (legacy.contains(-1)) { claimed.add("*"); return claimed; }
        JsonObject q = QuestDefinitions.get(id);
        JsonArray ids = q.has("reward_ids_legacy") ? q.getAsJsonArray("reward_ids_legacy") : attach(id, rewards).getAsJsonArray("codex_ids");
        for (int i : legacy) if (i >= 0 && i < ids.size()) claimed.add(ids.get(i).getAsString());
        return claimed;
    }
    public static Set<Integer> indexes(ServerPlayer p, Identifier id, JsonObject rewards) {
        Set<String> keys = claimed(p, id, rewards); if (keys.contains("*")) return Set.of(-1);
        Set<Integer> indexes = new HashSet<>(); JsonArray ids = attach(id, rewards).getAsJsonArray("codex_ids");
        for (int i = 0; i < ids.size(); i++) if (keys.contains(ids.get(i).getAsString())) indexes.add(i);
        return indexes;
    }
}
