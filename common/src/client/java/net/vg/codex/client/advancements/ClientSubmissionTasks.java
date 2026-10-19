package net.vg.codex.client.advancements;
import net.minecraft.resources.Identifier;
import net.vg.codex.network.SubmissionTasksPayload;
import java.util.*;
public final class ClientSubmissionTasks {
 private static Map<Identifier,List<SubmissionTasksPayload.Task>> tasks=Map.of(); private ClientSubmissionTasks(){}
 public static void set(List<SubmissionTasksPayload.Task> list){Map<Identifier,List<SubmissionTasksPayload.Task>> map=new HashMap<>();for(var t:list)map.computeIfAbsent(t.advancementId(),k->new ArrayList<>()).add(t);tasks=map;}
 public static List<SubmissionTasksPayload.Task> forAdvancement(Identifier id){return tasks.getOrDefault(id,List.of());}
}
