package net.vg.codex.advancement;

import com.google.gson.*;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.nio.file.*;
import java.util.*;

/** Optional personal or shared quest cycles. Ordinary advancements and party sharing are never reset. */
public final class QuestRuntime {
    private QuestRuntime() { }
    static Path file(ServerPlayer p) { return QuestState.directory(p.level().getServer()).resolve("codex/player_quests/" + p.getUUID() + ".json"); }
    private static JsonObject states(ServerPlayer p, Identifier id) { return read(p, QuestCycles.file(p, id)).deepCopy(); }
    public static JsonObject states(ServerPlayer p) { return read(p, file(p)).deepCopy(); }
    private static JsonObject read(ServerPlayer p, Path path) {
        QuestJournal.recover(p.level().getServer());
        try { return QuestReadScope.read(path); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot read quest cycles", e); }
    }
    public static JsonObject state(ServerPlayer p, Identifier id) {
        JsonObject all = read(p, QuestCycles.file(p,id)); return all.has(id.toString()) ? all.getAsJsonObject(id.toString()).deepCopy() : new JsonObject();
    }
    public static void put(ServerPlayer p, Identifier id, JsonObject state) {
        JsonObject all = states(p, id); all.add(id.toString(), state); AtomicJson.write(QuestCycles.file(p, id), all);
    }
    public static boolean unlocked(ServerPlayer p, JsonObject definition) {
        if (!definition.has("requires_choice")) return true;
        JsonObject gate = definition.getAsJsonObject("requires_choice");
        JsonObject prior = state(p, Identifier.parse(gate.get("quest").getAsString()));
        return prior.has("choice") && prior.get("choice").getAsString().equals(gate.get("choice").getAsString());
    }
    public static boolean ready(ServerPlayer p, Identifier id) {
        JsonObject definition = QuestDefinitions.get(id);
        if (!unlocked(p, definition)) return false;
        if (QuestDefinitions.managed(id)) {
            JsonObject state = refresh(p, id);
            return QuestCycles.eligible(p, id, state) && QuestDefinitions.flag(state, "accepted") && QuestDefinitions.flag(state, "completed");
        }
        AdvancementHolder h = p.level().getServer().getAdvancements().get(id);
        return h != null && p.getAdvancements().getOrStartProgress(h).isDone();
    }
    public static Path claimsDirectory(ServerPlayer p, Identifier id) {
        Path root = QuestState.directory(p.level().getServer());
        if (!QuestDefinitions.repeat(QuestDefinitions.get(id))) return root;
        // Resource paths are validated identifiers; no platform path separators are accepted.
        if (QuestCycles.shared(QuestDefinitions.get(id))) return root.resolve("codex/shared_cycles/" + id.getNamespace() + "/" + id.getPath() + "/" + QuestCycles.owner(p, id) + "/" + cycle(state(p, id)));
        return root.resolve("codex/cycles/" + id.getNamespace() + "/" + id.getPath() + "/" + cycle(state(p, id)));
    }
    public static void completed(ServerPlayer p, AdvancementHolder advancement) {
        // Recipe-book unlocks have no quest tooltip; recording a day for thousands of invisible
        // entries makes bulk vanilla grants rewrite the player journal thousands of times.
        if (advancement.value().display().isEmpty()) return;
        try (QuestReadScope scope=QuestReadScope.open()) {
        if (QuestDefinitions.repeat(QuestDefinitions.get(advancement.id())) || !p.getAdvancements().getOrStartProgress(advancement).isDone()) return;
        JsonObject state = state(p, advancement.id());
        if (!state.has("completed_day")) { state.addProperty("completed_day", p.level().getOverworldClockTime() / 24000L); put(p, advancement.id(), state); QuestCelebrations.completed(p, advancement.id()); }
        }
    }
    public static int cycle(JsonObject state) { return state.has("cycle") ? state.get("cycle").getAsInt() : 0; }
    public static boolean allClaimed(ServerPlayer p, Identifier id) {
        JsonObject rewards = CodexQuestIO.readClaimableRewards(AdvancementPackSync.externalPackDir()).get(id);
        if (rewards == null) return true;
        Set<Integer> claims = QuestRewardIdentity.indexes(p, id, QuestTransactions.completeRewards(AdvancementPackSync.externalPackDir(), id, rewards));
        if (claims.contains(-1)) return true;
        int count = QuestTransactions.rewardCount(AdvancementPackSync.externalPackDir(), id, rewards);
        for (int i = 0; i < count; i++) if (!claims.contains(i)) return false;
        return true;
    }
    public static boolean cycleClaimed(ServerPlayer p, Identifier id) {
        if(!QuestCycles.individual(p,id))return allClaimed(p,id);
        JsonObject state=state(p,id), rewards=CodexQuestIO.readClaimableRewards(AdvancementPackSync.externalPackDir()).get(id);
        if(rewards==null)return true;if(!state.has("participants"))return false;
        JsonArray keys=QuestRewardIdentity.attach(id,QuestTransactions.completeRewards(AdvancementPackSync.externalPackDir(),id,rewards)).getAsJsonArray("codex_ids");
        Set<UUID> members=net.vg.codex.party.CodexPartyService.members(p.level().getServer(),p.getUUID());
        for(JsonElement value:state.getAsJsonArray("participants")) {
            UUID member=UUID.fromString(value.getAsString());if(!members.contains(member))continue;
            try {
                JsonObject ledger=QuestReadScope.read(claimsDirectory(p,id).resolve("codex/claim_keys/"+member+".json"));
                Set<String> claimed=new HashSet<>();if(ledger.has(id.toString()))for(JsonElement key:ledger.getAsJsonArray(id.toString()))claimed.add(key.getAsString());
                for(JsonElement key:keys)if(!claimed.contains("*")&&!claimed.contains(key.getAsString()))return false;
            }catch(java.io.IOException error){throw new IllegalStateException("Cannot read participant claims",error);}
        }
        return true;
    }
    public static boolean accept(ServerPlayer p, Identifier id) {
        JsonObject definition = QuestDefinitions.get(id), state = state(p, id);
        if (!QuestDefinitions.managed(id) || !unlocked(p, definition)) return false;
        if (QuestDefinitions.flag(state, "accepted")) {
            if (!QuestDefinitions.repeat(definition) || !QuestDefinitions.flag(state, "completed") || !cycleClaimed(p, id)) return false;
            if (state.has("next_available") && System.currentTimeMillis() < state.get("next_available").getAsLong()) return false;
            JsonObject next = new JsonObject(); next.addProperty("cycle", cycle(state) + 1); state = next;
        }
        state.addProperty("accepted", true);
        JsonObject baseline = new JsonObject();
        for (var entry : CodexQuestIO.readStatisticTasks(AdvancementPackSync.externalPackDir()).entrySet()) {
            if (entry.getKey().startsWith(id + "#")) baseline.addProperty(entry.getKey(), CodexStatisticTasks.value(p, entry.getValue().get("id").getAsString()));
        }
        state.add("baseline", baseline); QuestCycles.start(p, id, state); put(p, id, state); refresh(p, id); return true;
    }
    public static int statistic(ServerPlayer p, Identifier id, String key, JsonObject task, JsonObject state) {
        if (QuestCycles.shared(QuestDefinitions.get(id))) return QuestCycles.statistic(state, key);
        int actual = CodexStatisticTasks.value(p, task.get("id").getAsString());
        JsonObject baseline = state.has("baseline") ? state.getAsJsonObject("baseline") : new JsonObject();
        return QuestDefinitions.repeat(QuestDefinitions.get(id)) ? Math.max(0, actual - (baseline.has(key) ? baseline.get(key).getAsInt() : actual)) : actual;
    }
    public static JsonObject refresh(ServerPlayer p, Identifier id) {
        JsonObject s = state(p, id), definition = QuestDefinitions.get(id);
        if (!QuestDefinitions.flag(s, "accepted")) return s;
        if (QuestDefinitions.flag(s, "completed")) {
            AdvancementHolder completedHolder = p.level().getServer().getAdvancements().get(id);
            if (completedHolder != null && s.has("completed_criteria")) for (JsonElement criterion : s.getAsJsonArray("completed_criteria")) p.getAdvancements().award(completedHolder, criterion.getAsString());
            return s;
        }
        AdvancementHolder holder = p.level().getServer().getAdvancements().get(id);
        if (holder == null) return s;
        boolean changed = QuestCycles.updateStatistics(p, id, s);
        boolean done;
        if (QuestDefinitions.repeat(definition)) {
            Set<String> completed = new HashSet<>();
            var submissions = CodexQuestIO.readSubmissionTasks(AdvancementPackSync.externalPackDir());
            var statistics = CodexQuestIO.readStatisticTasks(AdvancementPackSync.externalPackDir());
            JsonObject delivered = s.has("delivered") ? s.getAsJsonObject("delivered") : new JsonObject();
            for (String criterion : holder.value().criteria().keySet()) {
                String key = CodexQuestIO.taskKey(id, criterion);
                if (submissions.containsKey(key)) {
                    JsonObject counts = delivered.has(criterion) ? delivered.getAsJsonObject(criterion) : new JsonObject();
                    boolean satisfied = SubmissionPlan.requirements(submissions.get(key)).entrySet().stream().allMatch(e -> counts.has(e.getKey().toString()) && counts.get(e.getKey().toString()).getAsInt() >= e.getValue());
                    if (satisfied) completed.add(criterion);
                } else if (statistics.containsKey(key) && statistic(p, id, key, statistics.get(key), s) >= statistics.get(key).get("target").getAsInt()) completed.add(criterion);
            }
            done = holder.value().requirements().test(completed::contains);
            if (done) { JsonArray names = new JsonArray(); completed.stream().sorted().forEach(names::add); s.add("completed_criteria", names); }
            // The vanilla advancement completes once. Subsequent cycles are entirely sidecar state.
            for (String criterion : completed) p.getAdvancements().award(holder, criterion);
        } else done = p.getAdvancements().getOrStartProgress(holder).isDone();
        if (done) {
            s.addProperty("completed", true); s.addProperty("completed_day", p.level().getOverworldClockTime() / 24000L);
            if (QuestDefinitions.repeat(definition)) s.addProperty("next_available", QuestCycles.nextAvailable(definition, System.currentTimeMillis()));
            put(p, id, s);
            QuestCelebrations.completed(p, id);
        } else if (changed) { put(p, id, s);
        }
        return s;
    }
    public static int submit(ServerPlayer p, Identifier id, String criterion, Optional<Identifier> selected) {
        return submit(p, id, criterion, selected, new LinkedHashMap<>());
    }
    static int submit(ServerPlayer p, Identifier id, String criterion, Optional<Identifier> selected, Map<Identifier, Integer> consumed) {
        JsonObject definition = QuestDefinitions.get(id), state = refresh(p, id);
        if (!unlocked(p, definition) || !QuestCycles.eligible(p, id, state) || !QuestDefinitions.flag(state, "accepted") || QuestDefinitions.flag(state, "completed")) return 0;
        if (!QuestDefinitions.repeat(definition)) return QuestTransactions.submitOrdinary(p, id, criterion, selected, consumed);
        JsonObject task = CodexQuestIO.readSubmissionTasks(AdvancementPackSync.externalPackDir()).get(CodexQuestIO.taskKey(id, criterion));
        if (task == null) return 0;
        Map<Identifier, Integer> requirements = SubmissionPlan.requirements(task);
        JsonObject delivered = state.has("delivered") ? state.getAsJsonObject("delivered") : new JsonObject();
        JsonObject counts = delivered.has(criterion) ? delivered.getAsJsonObject(criterion) : new JsonObject();
        Map<Identifier, Integer> previous = new LinkedHashMap<>(); counts.entrySet().forEach(e -> previous.put(Identifier.parse(e.getKey()), e.getValue().getAsInt()));
        SubmissionPlan.Allocation allocation = SubmissionPlan.allocate(p.getInventory(), SubmissionPlan.resolve(task, p.registryAccess()), previous, selected);
        allocation.added().forEach((item, count) -> counts.addProperty(item.toString(), previous.getOrDefault(item, 0) + count));
        allocation.consumed().forEach((item, count) -> consumed.merge(item, count, Integer::sum));
        Map<Integer, Integer> removals = allocation.removals(); int total = allocation.total();
        if (total == 0) return 0;
        delivered.add(criterion, counts); state.add("delivered", delivered);
        if (QuestCycles.shared(definition)) {
            JsonObject contributions = state.getAsJsonObject("contributions"); String member = p.getUUID().toString();
            contributions.addProperty(member, (contributions.has(member) ? contributions.get(member).getAsInt() : 0) + total);
        }
        QuestJournal.Plan transaction = new QuestJournal.Plan(p); transaction.remove(removals);
        JsonObject all = states(p, id); all.add(id.toString(), state);
        transaction.update(QuestCycles.file(p, id), all); transaction.commit();
        refresh(p, id); p.level().getServer().getPlayerList().saveAll(); return total;
    }
    private static boolean dialogueStarted(ServerPlayer p, Identifier id, JsonObject q, JsonObject state) {
        if (QuestDefinitions.managedLocal(q)) return QuestDefinitions.flag(state, "accepted");
        var holder = p.level().getServer().getAdvancements().get(id);
        return holder != null && p.getAdvancements().getOrStartProgress(holder).hasProgress();
    }
    public static boolean choose(ServerPlayer p, Identifier id, String key) {
        JsonObject q = QuestDefinitions.get(id), s = state(p, id);
        if (!unlocked(p, q) || !QuestCycles.eligible(p, id, s) || s.has("choice") || !q.has("dialogue")) return false;
        JsonObject d = q.getAsJsonObject("dialogue");
        if (!d.has("choices")) return false;
        for (JsonElement e : d.getAsJsonArray("choices")) if (e.getAsJsonObject().get("id").getAsString().equals(key)) {
            JsonObject choice = e.getAsJsonObject();
            if (!QuestConditions.available(p, choice) || !QuestDefinitions.choiceAvailable(choice, ready(p, id), dialogueStarted(p, id, q, s))) return false;
            s.addProperty("choice", key); put(p, id, s); return true;
        }
        return false;
    }
    public static JsonObject presentation(ServerPlayer p, Identifier id) {
        return QuestReadScope.memo(java.util.List.of("presentation", p.getUUID(), id), () -> presentationUncached(p,id));
    }
    private static JsonObject presentationUncached(ServerPlayer p, Identifier id) {
        JsonObject q = QuestDefinitions.get(id), s = refresh(p, id), out = new JsonObject();
        boolean unlocked = unlocked(p, q), ready = ready(p, id), claimed = allClaimed(p, id);
        boolean excluded = q.has("requires_choice") && state(p, Identifier.parse(q.getAsJsonObject("requires_choice").get("quest").getAsString())).has("choice") && !unlocked;
        boolean repeat = QuestDefinitions.repeat(q), accepted = QuestDefinitions.flag(s, "accepted"), completed = QuestDefinitions.flag(s, "completed");
        long wait = s.has("next_available") ? Math.max(0, (s.get("next_available").getAsLong() - System.currentTimeMillis() + 999) / 1000) : 0;
        boolean eligible = QuestCycles.eligible(p, id, s);
        out.addProperty("unlocked", unlocked); out.addProperty("excluded", excluded); out.addProperty("ready", ready);
        out.addProperty("managed", QuestDefinitions.managedLocal(q)); out.addProperty("accepted", accepted); out.addProperty("repeat", repeat);
        out.addProperty("can_accept", unlocked && eligible && QuestDefinitions.managedLocal(q) && (!accepted || repeat && completed && claimed && cycleClaimed(p,id) && wait == 0));
        out.addProperty("claim_ready", ready && !claimed); out.addProperty("claim_pending", !claimed);
        out.addProperty("dialogue", q.has("dialogue") && !q.getAsJsonObject("dialogue").isEmpty());
        boolean choicePending = false;
        if (!s.has("choice") && q.has("dialogue") && q.getAsJsonObject("dialogue").has("choices"))
            for (JsonElement choice : q.getAsJsonObject("dialogue").getAsJsonArray("choices")) choicePending |= QuestConditions.available(p, choice.getAsJsonObject()) && QuestDefinitions.choiceAvailable(choice.getAsJsonObject(), ready, dialogueStarted(p, id, q, s));
        out.addProperty("choice_pending", choicePending);
        out.addProperty("choice", s.has("choice")); out.addProperty("available_in", wait);
        if (!unlocked) out.addProperty("reason", excluded ? "Unavailable: you chose the other story branch." : "Choose this story branch first.");
        else if (!eligible) out.addProperty("reason", "Join the next party cycle to participate.");
        return out;
    }
    public static JsonObject info(ServerPlayer p, Identifier id) {
        JsonObject info = new JsonObject(), q = QuestDefinitions.get(id), s = refresh(p, id);
        info.addProperty("dialogue_started", dialogueStarted(p, id, q, s));
        info.addProperty("eligible", QuestCycles.eligible(p, id, s)); info.addProperty("shared", QuestCycles.shared(q));
        info.addProperty("available_in", s.has("next_available") ? Math.max(0, (s.get("next_available").getAsLong() - System.currentTimeMillis() + 999) / 1000) : 0);
        JsonObject availability = new JsonObject();
        if (q.has("dialogue") && q.getAsJsonObject("dialogue").has("choices")) for (JsonElement c : q.getAsJsonObject("dialogue").getAsJsonArray("choices")) {
            JsonObject choice=c.getAsJsonObject(); availability.addProperty(choice.get("id").getAsString(), QuestConditions.available(p,choice));
        }
        info.add("choice_availability",availability);
        JsonObject reasons=new JsonObject();
        if (q.has("dialogue") && q.getAsJsonObject("dialogue").has("choices")) for (JsonElement value:q.getAsJsonObject("dialogue").getAsJsonArray("choices")) {
            JsonObject choice=value.getAsJsonObject(); if (!choice.has("when")) continue; StringBuilder reason=new StringBuilder("Requires all groups:\n");
            for(JsonElement group:choice.getAsJsonArray("when")){java.util.List<String> alternatives=new java.util.ArrayList<>();for(JsonElement rule:group.getAsJsonArray()){
                JsonObject c=rule.getAsJsonObject();Identifier target=Identifier.parse(c.get("quest").getAsString());var h=p.level().getServer().getAdvancements().get(target);String name=h!=null&&h.value().display().isPresent()?h.value().display().get().getTitle().getString():target.toString();
                alternatives.add((QuestDefinitions.flag(c,"negate")?"NOT ":"")+c.get("state").getAsString().replace('_',' ')+": "+name);
            }reason.append("• ").append(String.join(" OR ",alternatives)).append('\n');}reasons.addProperty(choice.get("id").getAsString(),reason.toString());
        }
        info.add("choice_reasons",reasons);
        info.addProperty("claimable", CodexQuestIO.readClaimableRewards(AdvancementPackSync.externalPackDir()).containsKey(id));
        info.add("definition", q); info.add("state", s); info.addProperty("unlocked", unlocked(p, q));
        info.addProperty("ready", ready(p, id)); info.addProperty("all_claimed", allClaimed(p, id)); info.addProperty("cycle_claimed",cycleClaimed(p,id)); info.addProperty("individual_rewards",QuestCycles.individual(p,id));
        AdvancementHolder h = p.level().getServer().getAdvancements().get(id);
        JsonArray tasks = new JsonArray();
        if (h != null) {
            var progress = p.getAdvancements().getOrStartProgress(h);
            for (String criterion : h.value().criteria().keySet()) {
                JsonObject t = new JsonObject(); t.addProperty("criterion", criterion);
                t.addProperty("done", progress.getCriterion(criterion) != null && progress.getCriterion(criterion).isDone());
                String key = CodexQuestIO.taskKey(id, criterion);
                JsonObject delivery = CodexQuestIO.readSubmissionTasks(AdvancementPackSync.externalPackDir()).get(key);
                JsonObject stat = CodexQuestIO.readStatisticTasks(AdvancementPackSync.externalPackDir()).get(key);
                if (delivery != null) {
                    JsonArray items = new JsonArray();
                    Map<Identifier, Integer> ordinary = CodexQuestIO.readSubmissionProgress(QuestState.directory(p.level().getServer()), net.vg.codex.party.CodexPartyService.progressOwner(p.level().getServer(), p.getUUID())).getOrDefault(key, Map.of());
                    JsonObject counts = s.has("delivered") && s.getAsJsonObject("delivered").has(criterion) ? s.getAsJsonObject("delivered").getAsJsonObject(criterion) : new JsonObject();
                    for (var e : SubmissionPlan.resolve(delivery, p.registryAccess())) {
                        JsonObject item = new JsonObject(); item.addProperty("id", e.icon().toString()); item.addProperty("label", e.label()); item.addProperty("key", e.key().toString()); item.addProperty("required", e.count());
                        item.addProperty("submitted", QuestDefinitions.repeat(q) ? (counts.has(e.key().toString()) ? counts.get(e.key().toString()).getAsInt() : 0) : ordinary.getOrDefault(e.key(), 0)); items.add(item);
                    }
                    t.add("items", items);
                }
                if (stat != null) { t.addProperty("value", statistic(p, id, key, stat, s)); t.addProperty("target", stat.get("target").getAsInt()); }
                if (QuestDefinitions.repeat(q)) {
                    boolean done = QuestDefinitions.flag(s, "accepted");
                    if (t.has("items")) for (JsonElement e : t.getAsJsonArray("items")) { JsonObject item = e.getAsJsonObject(); done &= item.get("submitted").getAsInt() >= item.get("required").getAsInt(); }
                    if (t.has("target")) done &= t.get("value").getAsInt() >= t.get("target").getAsInt();
                    t.addProperty("done", done);
                }
                tasks.add(t);
            }
        }
        JsonObject rewards = CodexQuestIO.readClaimableRewards(AdvancementPackSync.externalPackDir()).get(id);
        if (rewards != null) { rewards = rewards.deepCopy(); if (!rewards.has("codex_items")) rewards.add("codex_items", AdvancementPackIO.readGeneratedItemRewards(AdvancementPackSync.externalPackDir(), id)); info.add("rewards", rewards); }
        info.add("tasks", tasks);
        JsonArray outcomes = new JsonArray();
        if (s.has("choice")) {
            Set<Identifier> next = new LinkedHashSet<>();
            for (var entry : QuestDefinitions.all().entrySet()) {
                JsonObject metadata = entry.getValue().getAsJsonObject();
                if (metadata.has("requires_choice")) { JsonObject gate = metadata.getAsJsonObject("requires_choice"); if (gate.get("quest").getAsString().equals(id.toString()) && gate.get("choice").equals(s.get("choice"))) next.add(Identifier.parse(entry.getKey())); }
            }
            if (q.has("dialogue") && q.getAsJsonObject("dialogue").has("choices")) for (JsonElement e : q.getAsJsonObject("dialogue").getAsJsonArray("choices")) { JsonObject c = e.getAsJsonObject(); if (c.get("id").equals(s.get("choice")) && c.has("next_quest")) next.add(Identifier.parse(c.get("next_quest").getAsString())); }
            for (Identifier target : next) {
                var holder = p.level().getServer().getAdvancements().get(target); if (holder == null || holder.value().display().isEmpty() || !unlocked(p, QuestDefinitions.get(target))) continue;
                JsonObject outcome = new JsonObject(); outcome.addProperty("id", target.toString()); outcome.addProperty("title", holder.value().display().get().getTitle().getString()); outcome.addProperty("description", holder.value().display().get().getDescription().getString()); outcomes.add(outcome);
            }
        }
        info.add("outcomes", outcomes);
        JsonObject box = QuestJournal.mailbox(p); info.addProperty("stored_rewards", box.has("items") ? box.getAsJsonArray("items").size() : 0);
        return info;
    }
}
