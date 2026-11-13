package net.vg.codex.validation;

import com.google.gson.*;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.vg.codex.advancement.*;
import net.vg.codex.party.CodexPartyService;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Opt-in dedicated server with actual socket clients. No embedded/simulated player creation. */
public final class TransportServer {
    private static final boolean GUI_ONLY=Boolean.getBoolean("codex.test.gui_only");
    private static final Path CONTROL=Path.of(System.getProperty("codex.test.control"));
    private record Step(String label,String actor,JsonObject command,Consumer<MinecraftServer> prepare,Predicate<MinecraftServer> verify) { }
    private static final List<Step> steps=new ArrayList<>(); private static final List<String> passed=new ArrayList<>(),failed=new ArrayList<>();
    private static int tick,stage,age; private static long nonce; private static boolean initialized,finished; private static ServerPlayer oldBob; private static int applesA,applesB;
    private static ServerPlayer player(MinecraftServer server,String actor){return server.getPlayerList().getPlayerByName(actor.equals("alice")?"CodexTransportA":"CodexTransportB");}
    private static Identifier id(String value){return Identifier.parse(value);}
    private static JsonObject command(String op,String quest,String action,String value){JsonObject c=new JsonObject();c.addProperty("op",op);if(quest!=null)c.addProperty("id",quest);if(action!=null)c.addProperty("action",action);if(value!=null)c.addProperty("value",value);return c;}
    private static JsonObject ack(String actor){
        Path folder=CONTROL.resolve("acks").resolve(actor);
        try(var files=Files.list(folder)){Path latest=files.filter(p->p.getFileName().toString().endsWith(".json")).max(Comparator.comparing(p->p.getFileName().toString())).orElse(null);return latest==null?new JsonObject():JsonParser.parseString(Files.readString(latest)).getAsJsonObject();}
        catch(java.io.IOException missing){return new JsonObject();}
    }
    private static void issue(String actor,JsonObject command){JsonObject c=command.deepCopy();c.addProperty("nonce",++nonce);AtomicJson.write(CONTROL.resolve("commands").resolve(actor).resolve(String.format(Locale.ROOT,"%020d.json",nonce)),c);}
    private static boolean done(MinecraftServer s,String actor,String quest){var h=s.getAdvancements().get(id(quest));return h!=null&&player(s,actor).getAdvancements().getOrStartProgress(h).isDone();}
    private static int count(ServerPlayer p,Item item){int amount=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))amount+=p.getInventory().getItem(i).getCount();return amount;}
    private static void add(String label,String actor,JsonObject c,Consumer<MinecraftServer> prepare,Predicate<MinecraftServer> verify){steps.add(new Step(label,actor,c,prepare,verify));}
    private static boolean observed(String actor,Predicate<JsonObject> predicate){JsonObject a=ack(actor);return a.has("info")&&predicate.test(a.getAsJsonObject("info"));}
    public static void install(){TickEvent.SERVER_POST.register(TransportServer::tick);}
    private static void plan(MinecraftServer s){
        String delivery="codex_test:delivery",reward="codex_test:reward",root="codex_demo:camp_chronicle/root";
        add("Alice is a real TCP client","alice",command("observe",root,null,null),v->{},v->ack("alice").has("tcp")&&ack("alice").get("tcp").getAsBoolean());
        add("Bob is a real TCP client","bob",command("observe",root,null,null),v->{},v->ack("bob").has("tcp")&&ack("bob").get("tcp").getAsBoolean());
        Identifier each=id("codex_test:each_delivery");
        if(!System.getProperty("codex.test.phase","initial").equals("restart")){
        QuestDefinitions.save(each,com.google.gson.JsonParser.parseString("{\"acceptance\":true,\"repeat\":{\"enabled\":true,\"scope\":\"party\",\"rewards\":\"each\",\"cooldown_seconds\":0}}").getAsJsonObject());
        var tasks=CodexQuestIO.readSubmissionTasks(AdvancementPackSync.externalPackDir());tasks.put(CodexQuestIO.taskKey(each,"done"),com.google.gson.JsonParser.parseString("{\"items\":[{\"id\":\"minecraft:stick\",\"count\":2}]}").getAsJsonObject());CodexQuestIO.writeSubmissionTasks(AdvancementPackSync.externalPackDir(),tasks);
        var rewards=CodexQuestIO.readClaimableRewards(AdvancementPackSync.externalPackDir());rewards.put(each,com.google.gson.JsonParser.parseString("{\"codex_items\":[{\"id\":\"minecraft:apple\",\"count\":1}]}").getAsJsonObject());CodexQuestIO.writeClaimableRewards(AdvancementPackSync.externalPackDir(),rewards);
        }
        if(System.getProperty("codex.test.phase","initial").equals("restart")){
            add("Real-client party survives dedicated restart","bob",command("watch",delivery,null,null),v->{},v->CodexPartyService.members(v,player(v,"bob").getUUID()).size()==2&&done(v,"bob",delivery));
            add("Real-client reward claim survives restart without duplication","alice",command("quest",reward,"claim",""),v->{},v->count(player(v,"alice"),Items.DIAMOND)==2);
            add("Real-client saved story survives restart","alice",command("watch",root,null,null),v->{},v->QuestRuntime.state(player(v,"alice"),id(root)).has("choice"));
            add("Per-participant reward claims survive real dedicated restart","bob",command("watch",each.toString(),null,null),v->{},v->QuestCycles.individual(player(v,"bob"),each)&&QuestRuntime.allClaimed(player(v,"alice"),each)&&QuestRuntime.allClaimed(player(v,"bob"),each));
            return;
        }
        add("Party invite travels through the client command connection","alice",command("command",null,null,"codex party invite CodexTransportB"),v->{},v->true);
        add("Party acceptance travels through the second client","bob",command("command",null,null,"codex party accept"),v->{},v->CodexPartyService.members(v,player(v,"alice").getUUID()).size()==2);
        add("Ordinary vanilla advancement shares across actual connections","bob",command("watch","minecraft:story/mine_stone",null,null),v->{var h=v.getAdvancements().get(id("minecraft:story/mine_stone"));for(String key:h.value().criteria().keySet())player(v,"alice").getAdvancements().award(h,key);},v->done(v,"bob","minecraft:story/mine_stone"));
        add("First actual client partially submits and consumes inventory","alice",command("quest",delivery,"submit","done"),v->{player(v,"alice").getInventory().add(new ItemStack(Items.IRON_INGOT,3));},v->count(player(v,"alice"),Items.IRON_INGOT)==0&&!done(v,"alice",delivery));
        add("Second actual client completes the shared delivery","bob",command("quest",delivery,"submit","done"),v->{player(v,"bob").getInventory().add(new ItemStack(Items.IRON_INGOT,2));player(v,"bob").getInventory().add(new ItemStack(Items.GOLD_INGOT,2));},v->count(player(v,"bob"),Items.IRON_INGOT)==0&&count(player(v,"bob"),Items.GOLD_INGOT)==0&&done(v,"alice",delivery)&&done(v,"bob",delivery));
        add("Real client claims legitimate item XP recipe function and loot rewards","alice",command("quest",reward,"claim",""),v->{player(v,"alice").getAdvancements().award(v.getAdvancements().get(id(reward)),"done");},v->count(player(v,"alice"),Items.DIAMOND)==2&&count(player(v,"alice"),Items.EMERALD)==1&&player(v,"alice").totalExperience>=7);
        add("Duplicate claim over TCP does not grant a second set","alice",command("quest",reward,"claim",""),v->{},v->count(player(v,"alice"),Items.DIAMOND)==2&&count(player(v,"alice"),Items.EMERALD)==1);
        add("Actual party accepts individual reward entitlements","alice",command("quest",each.toString(),"accept",""),v->{applesA=count(player(v,"alice"),Items.APPLE);applesB=count(player(v,"bob"),Items.APPLE);},v->QuestCycles.individual(player(v,"alice"),each)&&QuestCycles.eligible(player(v,"bob"),each,QuestRuntime.state(player(v,"bob"),each)));
        add("Actual participant completes the shared individual-reward objective","alice",command("quest",each.toString(),"submit","done"),v->player(v,"alice").getInventory().add(new ItemStack(Items.STICK,2)),v->QuestRuntime.ready(player(v,"bob"),each));
        add("Alice claims only her own share over TCP","alice",command("quest",each.toString(),"claim",""),v->{},v->count(player(v,"alice"),Items.APPLE)==applesA+1&&!QuestRuntime.allClaimed(player(v,"bob"),each));
        add("Individual duplicate claim does not consume Bob's share","alice",command("quest",each.toString(),"claim",""),v->{},v->count(player(v,"alice"),Items.APPLE)==applesA+1&&!QuestRuntime.cycleClaimed(player(v,"alice"),each));
        add("Next cycle cannot discard Bob's pending share","alice",command("quest",each.toString(),"accept",""),v->{},v->QuestRuntime.cycle(QuestRuntime.state(player(v,"alice"),each))==0);
        add("Bob receives his own once-only reward over TCP","bob",command("quest",each.toString(),"claim",""),v->{},v->count(player(v,"bob"),Items.APPLE)==applesB+1&&QuestRuntime.cycleClaimed(player(v,"alice"),each));
        add("Bob's duplicate individual claim is rejected","bob",command("quest",each.toString(),"claim",""),v->{},v->count(player(v,"bob"),Items.APPLE)==applesB+1);
        for(int i=0;i<24;i++){final int round=i;add("Actual client tree navigation "+(i+1),"alice",command("view",i%2==0?root:"minecraft:story/root",null,null),v->{},v->true);}
        add("Choice saves after repeated real-client navigation","alice",command("quest",root,"choice","campkeeper"),v->{},v->QuestRuntime.state(player(v,"alice"),id(root)).has("choice"));
        add("Chosen chapter is requested over the real connection","alice",command("watch","codex_demo:camp_chronicle/builder",null,null),v->{},v->true);
        add("Chosen chapter arrives fully loaded and unlocked","alice",command("observe","codex_demo:camp_chronicle/builder",null,null),v->{},v->observed("alice",info->info.has("definition")&&info.get("unlocked").getAsBoolean()));
        add("Excluded chapter is requested over the real connection","alice",command("watch","codex_demo:camp_chronicle/wanderer",null,null),v->{},v->true);
        add("Excluded chapter stays readable with a lock","alice",command("observe","codex_demo:camp_chronicle/wanderer",null,null),v->{},v->observed("alice",info->info.has("definition")&&!info.get("unlocked").getAsBoolean()));
        add("Actual client disconnects and reconnects with the same identity","bob",command("reconnect",null,null,null),v->{oldBob=player(v,"bob");},v->player(v,"bob")!=null&&player(v,"bob")!=oldBob&&done(v,"bob",delivery)&&CodexPartyService.members(v,player(v,"bob").getUUID()).size()==2);
        add("Reconnected client refreshes quest data","bob",command("watch",delivery,null,null),v->{},v->true);
        add("Reconnected client sees its persisted completion","bob",command("observe",delivery,null,null),v->{},v->observed("bob",info->info.has("ready")&&info.get("ready").getAsBoolean()));
        addGuiStep();
    }
    private static void guiPlan(){
        add("A rendered client connects over real TCP","alice",command("observe","codex_test:reward",null,null),v->{},v->ack("alice").has("tcp")&&ack("alice").get("tcp").getAsBoolean());
        add("Reward preview data loads over the real connection","alice",command("watch","codex_test:reward",null,null),v->{},v->true);
        addGuiStep();
    }
    private static void addGuiStep(){
        add("Story, conditions, branch gate and milestone are authored through real GUI widgets","alice",command("gui",null,null,null),v->{
            player(v,"alice").setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            v.getPlayerList().op(player(v,"alice").nameAndId());
        },v->{
            JsonObject story=QuestDefinitions.get(id("codex_test:gui_story")),chapter=QuestDefinitions.get(id("codex_test:gui_chapter"));
            return AdvancementPackIO.readBaseJson(AdvancementPackSync.externalPackDir(),v.getResourceManager(),id("codex_test:gui_story")).getAsJsonObject("criteria").size()==2 && ack("alice").has("gui_authored")&&story.has("dialogue")&&story.getAsJsonObject("dialogue").get("intro").getAsString().contains("in-game GUI")&&story.getAsJsonObject("dialogue").getAsJsonArray("choices").get(0).getAsJsonObject().has("when")&&story.has("celebration")&&QuestDefinitions.flag(story.getAsJsonObject("celebration"),"enabled")&&chapter.has("requires_choice")&&chapter.getAsJsonObject("requires_choice").get("quest").getAsString().equals("codex_test:gui_story");
        });
    }
    private static void tick(MinecraftServer s){
        if(finished)return;if(++tick>12000){finish(s,"Timed out waiting for actual clients");return;}
        if(!initialized){if(player(s,"alice")==null||(!GUI_ONLY&&player(s,"bob")==null))return;initialized=true;if(GUI_ONLY)guiPlan();else plan(s);}
        if(stage>=steps.size()){finish(s,null);return;}Step step=steps.get(stage);
        try{if(age++==0){step.prepare.accept(s);issue(step.actor,step.command);}
            JsonObject a=ack(step.actor);if(age<12||!a.has("nonce")||a.get("nonce").getAsLong()!=nonce)return;
            if(a.has("error")){finish(s,step.label+": "+a.get("error").getAsString());return;}
            if(step.verify.test(s)){passed.add(step.label);stage++;age=0;}else if(age>400)finish(s,"Failed: "+step.label);
        }catch(Throwable error){error.printStackTrace();finish(s,step.label+": "+error);}
    }
    private static void finish(MinecraftServer s,String failure){
        if(finished)return;finished=true;if(failure!=null)failed.add(failure);
        JsonObject report=new JsonObject();JsonArray ok=new JsonArray(),bad=new JsonArray();passed.forEach(ok::add);failed.forEach(bad::add);report.add("passed",ok);report.add("failures",bad);report.addProperty("players",GUI_ONLY?"one actual rendered Minecraft client; GUI authoring only":"two actual rendered Minecraft clients over loopback TCP");report.add("alice",ack("alice"));if(!GUI_ONLY)report.add("bob",ack("bob"));AtomicJson.write(Path.of("results.json"),report);
        issue("alice",command("quit",null,null,null));if(!GUI_ONLY)issue("bob",command("quit",null,null,null));
        Thread shutdown=new Thread(()->{try{Thread.sleep(1500);}catch(InterruptedException ignored){}s.halt(true);System.exit(failed.isEmpty()?0:1);},"codex-transport-shutdown");shutdown.setDaemon(true);shutdown.start();
    }
}
