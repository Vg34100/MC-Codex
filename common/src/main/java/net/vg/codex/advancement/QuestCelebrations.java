package net.vg.codex.advancement;

import com.google.gson.JsonObject;
import dev.architectury.networking.NetworkManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.vg.codex.network.QuestInfoPayload;

/** Optional presentation only; never changes rewards or advancement completion. */
public final class QuestCelebrations {
    private QuestCelebrations() { }
    public static void completed(ServerPlayer p, Identifier id) {
        JsonObject q=QuestDefinitions.get(id);
        if(!q.has("celebration")||!QuestDefinitions.flag(q.getAsJsonObject("celebration"),"enabled"))return;
        JsonObject result=q.getAsJsonObject("celebration").deepCopy();result.addProperty("action","milestone");result.addProperty("success",true);result.addProperty("message","Chapter complete");
        JsonObject packet=new JsonObject();packet.add("result",result);
        if(QuestCycles.shared(q)){
            JsonObject state=QuestRuntime.state(p,id);if(!state.has("participants"))return;
            for(var member:state.getAsJsonArray("participants")){ServerPlayer online=p.level().getServer().getPlayerList().getPlayer(java.util.UUID.fromString(member.getAsString()));if(online!=null)NetworkManager.sendToPlayer(online,new QuestInfoPayload(id,packet.toString()));}
        }else NetworkManager.sendToPlayer(p,new QuestInfoPayload(id,packet.toString()));
    }
}
