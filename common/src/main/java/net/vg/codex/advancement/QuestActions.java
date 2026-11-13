package net.vg.codex.advancement;

import com.google.gson.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** The same authoritative result is used by detail pages, tree shortcuts and tests. */
public final class QuestActions {
    private QuestActions() { }
    public static JsonObject perform(ServerPlayer p, Identifier id, String action, String value, Optional<Identifier> selected, int reward) {
        boolean success = false;
        String message;
        JsonObject items = new JsonObject();
        JsonObject q = QuestDefinitions.get(id), state = QuestRuntime.refresh(p, id);
        switch (action) {
            case "accept" -> {
                success = QuestRuntime.accept(p, id);
                message = success ? "Quest accepted. Your objectives are now active." : unavailable(p, id, q, state, "Finish the current cycle and claim its rewards first.");
            }
            case "submit" -> {
                Map<Identifier, Integer> consumed = new LinkedHashMap<>();
                int count = QuestTransactions.submit(p, id, value, selected, consumed);
                success = count > 0;
                consumed.forEach((item, amount) -> items.addProperty(item.toString(), amount));
                message = success ? "Delivered " + count + " item(s). These were removed from your inventory." : unavailable(p, id, q, state,
                    QuestRuntime.ready(p, id) ? "This delivery is already complete." : "No matching items to deliver. Check the remaining quantities below.");
            }
            case "claim" -> {
                int count = QuestTransactions.claim(p, id, reward); success = count > 0;
                message = success ? "Claimed " + count + " reward(s). Full inventory rewards are stored safely." : unavailable(p, id, q, state,
                    QuestRuntime.ready(p, id) ? "These rewards have already been claimed." : "Complete the objectives before claiming rewards.");
            }
            case "choice" -> {
                success = QuestRuntime.choose(p, id, value);
                message = success ? "Your story choice has been saved. See the new chapter below." : state.has("choice") ? "You have already chosen a chapter. Your saved outcome is shown below." : "This story choice is not available yet.";
            }
            case "mailbox" -> {
                success = QuestJournal.collect(p); message = success ? "Stored rewards collected." : "Make space in your inventory before collecting stored rewards.";
            }
            default -> throw new IllegalArgumentException("Unknown quest action");
        }
        JsonObject result = new JsonObject(); result.addProperty("action", action); result.addProperty("success", success); result.addProperty("message", message); result.add("items", items);
        return result;
    }
    private static String unavailable(ServerPlayer p, Identifier id, JsonObject q, JsonObject s, String fallback) {
        if (!QuestCycles.eligible(p, id, s)) return "You joined after this party cycle began. Join the next cycle.";
        if (!QuestRuntime.unlocked(p, q)) return "Choose the required story chapter first.";
        if (QuestDefinitions.managedLocal(q) && !QuestDefinitions.flag(s, "accepted")) return "Accept this quest first. No items were consumed.";
        if (s.has("next_available") && s.get("next_available").getAsLong() > System.currentTimeMillis()) return "The next cycle is not available yet.";
        return fallback;
    }
}
