package net.vg.codex.network;

import com.google.gson.JsonObject;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.vg.codex.advancement.*;
import java.util.*;

/** Bounded subscriptions: a detail page and at most three pinned quests per connection. */
public final class QuestNetworking {
    private static final Map<UUID, LinkedHashSet<Identifier>> WATCHED = new HashMap<>();
    private static final Map<UUID, QuestRequestBudget> LIMITS = new HashMap<>();
    private static final Map<UUID, Identifier> VIEWS = new HashMap<>();
    private static final Map<UUID, JsonObject> PRESENTED = new HashMap<>();
    private static final Map<UUID, JsonObject> TAB_SUMMARIES = new HashMap<>();
    private QuestNetworking() { }
    public static void refresh(ServerPlayer player) { overview(player); }
    public static void view(ServerPlayer player, Identifier root) {
        // An integrated server may pause before its next tick when this screen opens.
        AdvancementPackSync.flushQueuedRewardsTo(player);
        VIEWS.put(player.getUUID(), root);
        // Explicit requests/retries always receive a full snapshot, even for the same root.
        PRESENTED.remove(player.getUUID());
        TAB_SUMMARIES.remove(player.getUUID());
        overview(player);
    }
    private static void overview(ServerPlayer player) {
        try (QuestReadScope reads = QuestReadScope.open()) {
        Identifier root = VIEWS.get(player.getUUID()); if (root == null) return;
        // Menu requests still run while singleplayer is paused. Keep native node frames in
        // step with the authoritative quest summary instead of waiting for a player tick.
        player.getAdvancements().flushDirty(player, true);
        JsonObject tabs = QuestTabSummary.snapshot(player);
        if (!tabs.equals(TAB_SUMMARIES.put(player.getUUID(), tabs))) {
            JsonObject batch = new JsonObject(); boolean first = true;
            for (var entry : tabs.entrySet()) {
                if ((batch.size() != 0) && batch.toString().length() + entry.getKey().length() + entry.getValue().toString().length() > 48000) {
                    NetworkManager.sendToPlayer(player, new TabSummaryPayload(batch.toString(), first)); first = false; batch = new JsonObject();
                }
                batch.add(entry.getKey(), entry.getValue());
                if (batch.size() >= 128) { NetworkManager.sendToPlayer(player, new TabSummaryPayload(batch.toString(), first)); first = false; batch = new JsonObject(); }
            }
            NetworkManager.sendToPlayer(player, new TabSummaryPayload(batch.toString(), first));
        }
        var server = player.level().getServer(); JsonObject snapshot = new JsonObject();
        Set<Identifier> ids = new LinkedHashSet<>(CodexQuestIO.readClaimableRewards(AdvancementPackSync.externalPackDir()).keySet());
        QuestDefinitions.all().keySet().forEach(key -> ids.add(Identifier.parse(key)));
        for (Identifier id : ids) {
            var node = server.getAdvancements().tree().get(id); if (node == null) continue;
            while (node.parent() != null) node = node.parent();
            if (node.holder().id().equals(root)) snapshot.add(id.toString(), QuestRuntime.presentation(player, id));
        }
        if (snapshot.equals(PRESENTED.put(player.getUUID(), snapshot))) return;
        JsonObject chunk = new JsonObject(); boolean replace = true; int size = 2;
        for (var entry : snapshot.entrySet()) {
            int next = entry.getKey().length() + entry.getValue().toString().length() + 8;
            if (size + next > 48000) { NetworkManager.sendToPlayer(player, new QuestPresentationPayload(root, chunk.toString(), replace)); chunk = new JsonObject(); replace = false; size = 2; }
            chunk.add(entry.getKey(), entry.getValue()); size += next;
        }
        NetworkManager.sendToPlayer(player, new QuestPresentationPayload(root, chunk.toString(), replace));
        }
    }
    public static void reset() { WATCHED.clear(); LIMITS.clear(); VIEWS.clear(); PRESENTED.clear(); TAB_SUMMARIES.clear(); }
    public static void register() {
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(QuestInfoPayload.TYPE, QuestInfoPayload.STREAM_CODEC);
            NetworkManager.registerS2CPayloadType(QuestPresentationPayload.TYPE, QuestPresentationPayload.STREAM_CODEC);
            NetworkManager.registerS2CPayloadType(TabSummaryPayload.TYPE, TabSummaryPayload.STREAM_CODEC);
        }
        NetworkManager.registerReceiver(NetworkManager.c2s(), QuestActionPayload.TYPE, QuestActionPayload.STREAM_CODEC, (payload, context) -> context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) handle(player, payload);
        }));
    }
    /** Shared dispatch for the actual packet receiver and paused-navigation regression scenarios. */
    public static void handle(ServerPlayer p, QuestActionPayload payload) {
        if (!LIMITS.computeIfAbsent(p.getUUID(), ignored -> new QuestRequestBudget()).acquire(System.nanoTime())) {
            failure(p, payload.id(), payload.action(), "Too many quest requests at once. Please retry shortly."); return;
        }
        if (p.level().getServer().getAdvancements().get(payload.id()) == null) {
            failure(p, payload.id(), payload.action(), "This advancement is no longer available. Return to the advancement tree."); return;
        }
        try {
            switch (payload.action()) {
                case "view" -> { view(p, payload.id()); return; }
                case "unview" -> { VIEWS.remove(p.getUUID()); PRESENTED.remove(p.getUUID()); return; }
                case "watch" -> {
                    var set = WATCHED.computeIfAbsent(p.getUUID(), u -> new LinkedHashSet<>());
                    if (set.size() < 4 || set.contains(payload.id())) set.add(payload.id());
                    JsonObject info = QuestRuntime.info(p, payload.id());
                    info.addProperty("request", payload.value());
                    send(p, payload.id(), info); return;
                }
                case "unwatch" -> { WATCHED.getOrDefault(p.getUUID(), new LinkedHashSet<>()).remove(payload.id()); return; }
                case "accept", "choice", "submit", "claim", "mailbox" -> {
                    act(p, payload.id(), payload.action(), payload.value(), Optional.empty(), -1); return;
                }
                default -> { return; }
            }
        } catch (RuntimeException invalid) {
            org.slf4j.LoggerFactory.getLogger(QuestNetworking.class).warn("Rejected quest action {} for {}", payload.action(), p.getUUID(), invalid);
            failure(p, payload.id(), payload.action(), "The quest could not be updated. Try again or check the server log.");
        }
    }
    public static void act(ServerPlayer player, Identifier id, String action, String value, Optional<Identifier> selected, int reward) {
        if (player.level().getServer().getAdvancements().get(id) == null) return;
        try {
            JsonObject result = QuestActions.perform(player, id, action, value, selected, reward);
            JsonObject info = QuestRuntime.info(player, id); info.add("result", result); send(player, id, info);
            Set<UUID> recipients = QuestCycles.shared(QuestDefinitions.get(id)) ? net.vg.codex.party.CodexPartyService.members(player.level().getServer(), player.getUUID()) : Set.of(player.getUUID());
            for (UUID uuid : recipients) {
                ServerPlayer member = player.level().getServer().getPlayerList().getPlayer(uuid); if (member == null) continue;
                overview(member);
                for (Identifier watched : WATCHED.getOrDefault(uuid, new LinkedHashSet<>())) send(member, watched);
                AdvancementPackSync.sendClaimableRewardsTo(member);
                if (action.equals("accept") || action.equals("submit")) { AdvancementPackSync.sendSubmissionTasksTo(member); CodexStatisticTasks.sendProgress(member); }
            }
        } catch (RuntimeException invalid) {
            org.slf4j.LoggerFactory.getLogger(QuestNetworking.class).error("Quest action failed: {} {}", action, id, invalid);
            failure(player, id, action, "The action could not be completed. Check the server log before retrying.");
        }
    }
    private static void failure(ServerPlayer player, Identifier id, String message) { failure(player, id, "", message); }
    private static void failure(ServerPlayer player, Identifier id, String action, String message) {
        JsonObject result = new JsonObject(); result.addProperty("success", false); result.addProperty("action", action); result.addProperty("message", message);
        JsonObject info = new JsonObject(); info.add("result", result); send(player, id, info);
    }
    public static void send(ServerPlayer player, Identifier id) { send(player, id, QuestRuntime.info(player, id)); }
    private static void send(ServerPlayer player, Identifier id, JsonObject info) {
        String json = info.toString();
        if (json.length() <= 65536) NetworkManager.sendToPlayer(player, new QuestInfoPayload(id, json));
        else failure(player, id, "This quest is too large to display. Ask its author to shorten it.");
    }
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        WATCHED.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
        VIEWS.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
        PRESENTED.keySet().removeIf(uuid -> !VIEWS.containsKey(uuid));
        TAB_SUMMARIES.keySet().removeIf(uuid -> !VIEWS.containsKey(uuid));
        LIMITS.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            overview(p);
            for (Identifier id : WATCHED.getOrDefault(p.getUUID(), new LinkedHashSet<>())) {
                try { send(p, id); } catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(QuestNetworking.class).error("Cannot refresh quest {}", id, error); }
            }
        }
    }
}
