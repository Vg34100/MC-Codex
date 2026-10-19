package net.vg.codex.advancement;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.core.registries.BuiltInRegistries;
import dev.architectury.networking.NetworkManager;
import net.vg.codex.network.StatisticProgressPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/** Evaluates custom-stat thresholds once per second and awards their inert vanilla criteria. */
public final class CodexStatisticTasks {
    private static int ticks;
    private static final Logger LOGGER = LoggerFactory.getLogger(CodexStatisticTasks.class);
    private CodexStatisticTasks() {}

    public static void tick(MinecraftServer server) {
        if (++ticks % 20 != 0) return;
        var tasks = CodexQuestIO.readStatisticTasks(AdvancementPackSync.externalPackDir());
        if (tasks.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            evaluatePlayer(player, tasks);
        }
    }

    /** Player-tick fallback for integrated servers, and the join path for already-earned stats. */
    public static void tickPlayer(ServerPlayer player) {
        if (player.tickCount % 20 != 0) return;
        var tasks = CodexQuestIO.readStatisticTasks(AdvancementPackSync.externalPackDir());
        if (!tasks.isEmpty()) evaluatePlayer(player, tasks);
    }

    public static void evaluateAndSend(ServerPlayer player) {
        var tasks = CodexQuestIO.readStatisticTasks(AdvancementPackSync.externalPackDir());
        evaluatePlayer(player, tasks);
    }

    private static void evaluatePlayer(ServerPlayer player, java.util.Map<String, JsonObject> tasks) {
        for (var entry : tasks.entrySet()) {
            int split = entry.getKey().lastIndexOf('#');
            if (split < 1) continue;
            try {
                Identifier advancementId = Identifier.parse(entry.getKey().substring(0, split));
                String criterion = entry.getKey().substring(split + 1);
                JsonObject task = entry.getValue();
                String statistic = task.get("id").getAsString();
                int target = task.get("target").getAsInt();
                if (value(player, statistic) >= target) {
                    var holder = player.level().getServer().getAdvancements().get(advancementId);
                    if (holder != null) player.getAdvancements().award(holder, criterion);
                }
            } catch (RuntimeException e) {
                // Keep one malformed task isolated, but do not swallow the diagnostic: doing so
                // made a completely non-functioning statistic quest indistinguishable from one
                // whose target simply had not been reached.
                LOGGER.error("Failed to evaluate Codex statistic task {} for {}", entry.getKey(), player.getScoreboardName(), e);
            }
        }
        sendProgress(player, tasks);
    }

    public static void sendProgress(ServerPlayer player) {
        sendProgress(player, CodexQuestIO.readStatisticTasks(AdvancementPackSync.externalPackDir()));
    }

    private static void sendProgress(ServerPlayer player, java.util.Map<String, JsonObject> tasks) {
        ArrayList<StatisticProgressPayload.Entry> entries = new ArrayList<>();
        for (var entry : tasks.entrySet()) {
            int split = entry.getKey().lastIndexOf('#');
            if (split < 1) continue;
            try {
                String encoded = entry.getValue().get("id").getAsString();
                int target = entry.getValue().get("target").getAsInt();
                entries.add(new StatisticProgressPayload.Entry(
                        Identifier.parse(entry.getKey().substring(0, split)), entry.getKey().substring(split + 1),
                        format(player, encoded), formatTarget(player, encoded, target)));
            } catch (RuntimeException e) {
                LOGGER.error("Failed to format Codex statistic task {} for {}", entry.getKey(), player.getScoreboardName(), e);
            }
        }
        NetworkManager.sendToPlayer(player, new StatisticProgressPayload(entries));
    }

    /** `custom|id`, `mined|block`, `crafted|item`, `used|item`, `broken|item`, `picked_up|item`,
     * `dropped|item`, `killed|entity`, and `killed_by|entity` cover vanilla and mod registries. */
    private static int value(ServerPlayer player, String encoded) {
        String[] parts = encoded.contains("|") ? encoded.split("\\|", 2) : new String[]{"custom", encoded};
        Identifier id = Identifier.parse(parts[1]);
        return switch (parts[0]) {
            // CUSTOM is a registry whose values happen to be Identifiers.  Passing a freshly
            // parsed Identifier straight to StatType.get skips that registry lookup; StatType then
            // cannot find a key for the value and crashes while building the stat name.  Resolve
            // it exactly as the other categories resolve block/item/entity values first.
            case "custom" -> player.getStats().getValue(Stats.CUSTOM.get(BuiltInRegistries.CUSTOM_STAT.get(id).orElseThrow().value()));
            case "mined" -> player.getStats().getValue(Stats.BLOCK_MINED.get(BuiltInRegistries.BLOCK.get(id).orElseThrow().value()));
            case "crafted" -> player.getStats().getValue(Stats.ITEM_CRAFTED.get(BuiltInRegistries.ITEM.get(id).orElseThrow().value()));
            case "used" -> player.getStats().getValue(Stats.ITEM_USED.get(BuiltInRegistries.ITEM.get(id).orElseThrow().value()));
            case "broken" -> player.getStats().getValue(Stats.ITEM_BROKEN.get(BuiltInRegistries.ITEM.get(id).orElseThrow().value()));
            case "picked_up" -> player.getStats().getValue(Stats.ITEM_PICKED_UP.get(BuiltInRegistries.ITEM.get(id).orElseThrow().value()));
            case "dropped" -> player.getStats().getValue(Stats.ITEM_DROPPED.get(BuiltInRegistries.ITEM.get(id).orElseThrow().value()));
            case "killed" -> player.getStats().getValue(Stats.ENTITY_KILLED.get(BuiltInRegistries.ENTITY_TYPE.get(id).orElseThrow().value()));
            case "killed_by" -> player.getStats().getValue(Stats.ENTITY_KILLED_BY.get(BuiltInRegistries.ENTITY_TYPE.get(id).orElseThrow().value()));
            default -> throw new IllegalArgumentException("unknown statistic category " + parts[0]);
        };
    }

    /** Delegates to Minecraft's Stat formatter, preserving time, distance, and decimal units. */
    private static String format(ServerPlayer player, String encoded) {
        return statistic(player, encoded).format(value(player, encoded));
    }

    private static String formatTarget(ServerPlayer player, String encoded, int target) {
        return statistic(player, encoded).format(target);
    }

    private static net.minecraft.stats.Stat<?> statistic(ServerPlayer player, String encoded) {
        String[] parts = encoded.contains("|") ? encoded.split("\\|", 2) : new String[]{"custom", encoded};
        Identifier id = Identifier.parse(parts[1]);
        return switch (parts[0]) {
            case "custom" -> Stats.CUSTOM.get(BuiltInRegistries.CUSTOM_STAT.get(id).orElseThrow().value());
            case "mined" -> Stats.BLOCK_MINED.get(BuiltInRegistries.BLOCK.get(id).orElseThrow().value());
            case "crafted" -> Stats.ITEM_CRAFTED.get(BuiltInRegistries.ITEM.get(id).orElseThrow().value());
            case "used" -> Stats.ITEM_USED.get(BuiltInRegistries.ITEM.get(id).orElseThrow().value());
            case "broken" -> Stats.ITEM_BROKEN.get(BuiltInRegistries.ITEM.get(id).orElseThrow().value());
            case "picked_up" -> Stats.ITEM_PICKED_UP.get(BuiltInRegistries.ITEM.get(id).orElseThrow().value());
            case "dropped" -> Stats.ITEM_DROPPED.get(BuiltInRegistries.ITEM.get(id).orElseThrow().value());
            case "killed" -> Stats.ENTITY_KILLED.get(BuiltInRegistries.ENTITY_TYPE.get(id).orElseThrow().value());
            case "killed_by" -> Stats.ENTITY_KILLED_BY.get(BuiltInRegistries.ENTITY_TYPE.get(id).orElseThrow().value());
            default -> throw new IllegalArgumentException("unknown statistic category " + parts[0]);
        };
    }
}
