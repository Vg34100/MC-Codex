package net.vg.codex.advancement;

import com.google.gson.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import java.util.Set;
import java.util.function.Predicate;

/** Optional dialogue conditions: every group is required; one alternative per group suffices. */
public final class QuestConditions {
    private QuestConditions() { }
    public static void validate(JsonArray groups) {
        if(groups.size()>8)throw new IllegalArgumentException("At most eight condition groups");
        for(JsonElement g:groups){JsonArray rows=g.getAsJsonArray();if(rows.isEmpty()||rows.size()>8)throw new IllegalArgumentException("Condition groups need one to eight alternatives");
            for(JsonElement value:rows){JsonObject c=value.getAsJsonObject();Identifier.parse(c.get("quest").getAsString());String state=c.get("state").getAsString();
                if(!Set.of("completed","accepted","claimed","choice").contains(state))throw new IllegalArgumentException("Unknown dialogue condition");
                if(state.equals("choice")&&(!c.has("choice")||c.get("choice").getAsString().isBlank()||c.get("choice").getAsString().length()>80))throw new IllegalArgumentException("Select a story choice");
                if(c.has("negate"))c.get("negate").getAsBoolean();
            }
        }
    }
    public static boolean test(JsonArray groups,Predicate<JsonObject> condition){
        for(JsonElement group:groups){boolean any=false;for(JsonElement value:group.getAsJsonArray()){JsonObject c=value.getAsJsonObject();boolean matched=condition.test(c);any|=QuestDefinitions.flag(c,"negate")?!matched:matched;}if(!any)return false;}return true;
    }
    public static boolean available(ServerPlayer p,JsonObject choice){
        if(!choice.has("when"))return true;
        return test(choice.getAsJsonArray("when"),c->{
            Identifier id=Identifier.parse(c.get("quest").getAsString());var holder=p.level().getServer().getAdvancements().get(id);if(holder==null)return false;
            JsonObject state=QuestRuntime.state(p,id);boolean complete=QuestDefinitions.repeat(QuestDefinitions.get(id))?QuestDefinitions.flag(state,"completed"):p.getAdvancements().getOrStartProgress(holder).isDone();
            return switch(c.get("state").getAsString()){
                case "accepted"->QuestDefinitions.flag(state,"accepted");case "completed"->complete;case "claimed"->complete&&QuestRuntime.allClaimed(p,id);
                case "choice"->state.has("choice")&&state.get("choice").equals(c.get("choice"));default->false;
            };
        });
    }
}
