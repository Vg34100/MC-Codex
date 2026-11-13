package net.vg.codex.advancement;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Presentation only: never completes, revokes or claims an advancement. */
public final class QuestTabSummary {
    private QuestTabSummary() { }
    public static JsonObject snapshot(ServerPlayer player) {
        var server = player.level().getServer();
        var rewards = CodexQuestIO.readClaimableRewards(AdvancementPackSync.externalPackDir());
        JsonObject metadata = QuestDefinitions.all(), out = new JsonObject();
        for (var holder : server.getAdvancements().getAllAdvancements()) {
            if (holder.value().display().isEmpty()) continue;
            var node = server.getAdvancements().tree().get(holder.id()); if (node == null) continue;
            while (node.parent() != null) node = node.parent();
            Identifier root = node.holder().id(); String key = root.toString();
            if (!out.has(key)) {
                JsonObject tab = new JsonObject();
                JsonObject rootRules = metadata.has(key) ? metadata.getAsJsonObject(key) : new JsonObject();
                tab.addProperty("quest_tab", QuestDefinitions.flag(rootRules, "quest_tab"));
                tab.addProperty("total", 0); tab.addProperty("completed", 0); tab.addProperty("attention", false);
                tab.addProperty("rewards",0); tab.addProperty("acceptance",0); tab.addProperty("choices",0); out.add(key, tab);
            }
            JsonObject tab = out.getAsJsonObject(key);
            JsonObject rules = metadata.has(holder.id().toString()) ? metadata.getAsJsonObject(holder.id().toString()) : new JsonObject();
            boolean nativeDone = player.getAdvancements().getOrStartProgress(holder).isDone();
            boolean done = nativeDone, attention = false;
            boolean choices=rules.has("dialogue") && rules.getAsJsonObject("dialogue").has("choices");
            if (QuestDefinitions.managedLocal(rules) || rules.has("requires_choice") || choices || rewards.containsKey(holder.id())) {
                JsonObject state = QuestRuntime.presentation(player, holder.id());
                if (QuestDefinitions.flag(state, "excluded")) continue;
                // Finished includes required decisions and earned claims. An optional next repeat
                // cycle is available in the quest, but does not nag a player who finished this one.
                done = QuestDefinitions.flag(state, "ready") && !QuestDefinitions.flag(state, "claim_pending") && !QuestDefinitions.flag(state, "choice_pending");
                boolean unlocked=QuestDefinitions.flag(state,"unlocked");
                boolean reward=unlocked && QuestDefinitions.flag(state,"claim_ready");
                boolean accept=unlocked && !done && QuestDefinitions.flag(state,"can_accept");
                boolean choice=unlocked && !done && QuestDefinitions.flag(state,"choice_pending");
                attention=!done && (reward || accept || choice);
                if(reward)tab.addProperty("rewards",tab.get("rewards").getAsInt()+1);
                if(accept)tab.addProperty("acceptance",tab.get("acceptance").getAsInt()+1);
                if(choice)tab.addProperty("choices",tab.get("choices").getAsInt()+1);
            }
            tab.addProperty("total", tab.get("total").getAsInt() + 1);
            if (done) tab.addProperty("completed", tab.get("completed").getAsInt() + 1);
            if (attention) tab.addProperty("attention", true);
        }
        return out;
    }
}
