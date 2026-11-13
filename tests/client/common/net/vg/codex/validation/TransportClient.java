package net.vg.codex.validation;

import com.google.gson.*;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vg.codex.advancement.AtomicJson;
import net.vg.codex.client.advancements.*;
import net.vg.codex.network.*;
import java.nio.file.*;

/** Real rendered client, real TCP packets. Files only coordinate the isolated local test actors. */
public final class TransportClient {
    private static final Path CONTROL=Path.of(System.getProperty("codex.test.control"));
    private static final String ACTOR=System.getProperty("codex.test.actor");
    private static long nonce=-1, reconnectAt; private static int ticks; private static boolean connecting, gui;
    private TransportClient() { }
    public static void install(){ ClientTickEvent.CLIENT_POST.register(mc->tick(mc)); }
    private static void connect(Minecraft mc){ connecting=true;String host="127.0.0.1:"+System.getProperty("codex.test.port"); ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(host),new ServerData("Codex isolated test",host,ServerData.Type.OTHER),false,null); }
    private static void report(JsonObject value){
        Path target=CONTROL.resolve("acks").resolve(ACTOR).resolve(String.format(java.util.Locale.ROOT,"%020d.json",value.get("nonce").getAsLong()));
        if(!Files.exists(target))AtomicJson.write(target,value);
    }
    private static Path latestCommand()throws java.io.IOException{
        Path folder=CONTROL.resolve("commands").resolve(ACTOR);if(!Files.isDirectory(folder))return null;
        try(var files=Files.list(folder)){return files.filter(p->p.getFileName().toString().endsWith(".json")).max(java.util.Comparator.comparing(p->p.getFileName().toString())).orElse(null);}
    }
    private static void tick(Minecraft mc){
        if(++ticks%5!=0)return;
        try {
            if(mc.getConnection()==null){if(reconnectAt>0&&System.nanoTime()>=reconnectAt){reconnectAt=0;connecting=false;}if(!connecting&&mc.screen instanceof TitleScreen)connect(mc);return;}
            if(gui){if(!GuiAuthoringScenario.tick(mc))return;gui=false;JsonObject report=new JsonObject();report.addProperty("nonce",nonce);report.addProperty("gui_authored",true);report.addProperty("tcp",!mc.getConnection().getConnection().isMemoryConnection());report(report);return;}
            Path command=latestCommand();if(command==null)return;JsonObject c=JsonParser.parseString(Files.readString(command)).getAsJsonObject();long next=c.get("nonce").getAsLong();if(next==nonce)return;nonce=next;
            String op=c.get("op").getAsString(); JsonObject report=new JsonObject();report.addProperty("nonce",nonce);report.addProperty("tcp",!mc.getConnection().getConnection().isMemoryConnection());report.addProperty("remote",mc.getConnection().getConnection().getRemoteAddress().toString());
            Identifier id=c.has("id")?Identifier.parse(c.get("id").getAsString()):Identifier.parse("codex_demo:camp_chronicle/root");
            switch(op){
                case "gui" -> {gui=true;GuiAuthoringScenario.start();return;}
                case "command" -> mc.getConnection().sendCommand(c.get("value").getAsString());
                case "quest" -> ClientQuests.action(id,c.get("action").getAsString(),c.has("value")?c.get("value").getAsString():"");
                case "watch" -> ClientQuests.retry(id);
                case "view" -> {ClientQuests.view(id);mc.setScreen(new CodexAdvancementsScreen(mc.getConnection().getAdvancements(),null));}
                case "observe" -> {report.add("info",ClientQuests.info(id).deepCopy());report.add("presentation",ClientQuests.presentation(id).deepCopy());}
                case "reconnect" -> {reconnectAt=System.nanoTime()+1_500_000_000L;mc.disconnect(new TitleScreen(),false);}
                case "quit" -> {report(report);mc.stop();return;}
                default -> throw new IllegalArgumentException("Unknown isolated client step: "+op);
            }
            report(report);
        }catch(Throwable error){gui=false;JsonObject report=new JsonObject();report.addProperty("nonce",nonce);report.addProperty("error",error.toString());report(report);}
    }
}
