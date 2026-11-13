package net.vg.codex.advancement;

import com.google.gson.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.vg.codex.party.CodexPartyService;
import java.nio.file.Path;
import java.util.UUID;

/** Optional schedules and party-owned cycles. No vanilla progress is ever revoked. */
public final class QuestCycles {
    private QuestCycles() { }
    public static boolean shared(JsonObject q) { return QuestDefinitions.repeat(q) && q.getAsJsonObject("repeat").has("scope") && q.getAsJsonObject("repeat").get("scope").getAsString().equals("party"); }
    public static UUID owner(ServerPlayer p, Identifier id) { return shared(QuestDefinitions.get(id)) ? CodexPartyService.progressOwner(p.level().getServer(), p.getUUID()) : p.getUUID(); }
    /** Reward policy is fixed when a cycle starts; edits apply to the next cycle. */
    public static boolean individual(ServerPlayer p, Identifier id) {
        if(!shared(QuestDefinitions.get(id)))return false;
        JsonObject state=QuestRuntime.state(p,id);
        return state.has("reward_mode") && state.get("reward_mode").getAsString().equals("each");
    }
    public static UUID rewardOwner(ServerPlayer p, Identifier id) { return individual(p,id)?p.getUUID():owner(p,id); }
    static Path file(ServerPlayer p, Identifier id) {
        if (!shared(QuestDefinitions.get(id))) return QuestRuntime.file(p);
        return QuestState.directory(p.level().getServer()).resolve("codex/party_quests/" + owner(p, id) + ".json");
    }
    public static long nextAvailable(JsonObject q, long now) {
        JsonObject r = q.getAsJsonObject("repeat");
        String schedule = r.has("schedule") ? r.get("schedule").getAsString() : "cooldown";
        long period = schedule.equals("daily_utc") ? 86_400_000L : schedule.equals("periodic") ? r.get("period_seconds").getAsLong() * 1000L : 0;
        return period > 0 ? (Math.floorDiv(now, period) + 1) * period : now + (r.has("cooldown_seconds") ? r.get("cooldown_seconds").getAsLong() * 1000L : 0);
    }
    public static boolean eligible(ServerPlayer p, Identifier id, JsonObject state) {
        if (!shared(QuestDefinitions.get(id)) || !QuestDefinitions.flag(state, "accepted")) return true;
        if (!state.has("participants")) return false;
        for (JsonElement e : state.getAsJsonArray("participants")) if (e.getAsString().equals(p.getUUID().toString())) return true;
        return false;
    }
    static void start(ServerPlayer p, Identifier id, JsonObject state) {
        if (!shared(QuestDefinitions.get(id))) return;
        JsonObject repeat=QuestDefinitions.get(id).getAsJsonObject("repeat"); state.addProperty("reward_mode",repeat.has("rewards")?repeat.get("rewards").getAsString():"group");
        JsonArray members = new JsonArray(); CodexPartyService.members(p.level().getServer(), p.getUUID()).stream().sorted().forEach(u -> members.add(u.toString()));
        state.add("participants", members); state.add("baselines", new JsonObject()); state.add("stat_values", new JsonObject()); state.add("contributions", new JsonObject());
        updateStatistics(p, id, state);
    }
    /** Offline values are retained. A member offline at acceptance starts counting on first observation. */
    static boolean updateStatistics(ServerPlayer p, Identifier id, JsonObject s) {
        if (!shared(QuestDefinitions.get(id)) || !s.has("participants")) return false;
        JsonObject baselines = s.getAsJsonObject("baselines"), values = s.getAsJsonObject("stat_values");
        boolean changed = false;
        var tasks = CodexQuestIO.readStatisticTasks(AdvancementPackSync.externalPackDir());
        for (JsonElement member : s.getAsJsonArray("participants")) {
            String key = member.getAsString(); UUID uuid = UUID.fromString(key);
            ServerPlayer online = p.level().getServer().getPlayerList().getPlayer(uuid);
            if (online == null || !CodexPartyService.progressOwner(p.level().getServer(), uuid).equals(owner(p, id))) continue;
            JsonObject baseline = baselines.has(key) ? baselines.getAsJsonObject(key) : new JsonObject();
            JsonObject progress = values.has(key) ? values.getAsJsonObject(key) : new JsonObject();
            for (var task : tasks.entrySet()) if (task.getKey().startsWith(id + "#")) {
                int actual = CodexStatisticTasks.value(online, task.getValue().get("id").getAsString());
                if (!baseline.has(task.getKey())) { baseline.addProperty(task.getKey(), actual); changed = true; }
                int delta = Math.max(0, actual - baseline.get(task.getKey()).getAsInt());
                if (!progress.has(task.getKey()) || delta > progress.get(task.getKey()).getAsInt()) { progress.addProperty(task.getKey(), delta); changed = true; }
            }
            baselines.add(key, baseline); values.add(key, progress);
        }
        return changed;
    }
    static int statistic(JsonObject s, String key) {
        long total = 0;
        if (s.has("stat_values")) for (var e : s.getAsJsonObject("stat_values").entrySet()) if (e.getValue().getAsJsonObject().has(key)) total += e.getValue().getAsJsonObject().get(key).getAsInt();
        return (int)Math.min(Integer.MAX_VALUE, total);
    }
}
