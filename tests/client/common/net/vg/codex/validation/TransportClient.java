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
    private static long nonce=-1, reconnectAt, connectingSince; private static int ticks,connectAttempts; private static boolean connecting, gui;
    private TransportClient() { }
    public static void install(){ ClientTickEvent.CLIENT_POST.register(mc->tick(mc)); }
    private static void connect(Minecraft mc){
        connecting=true;connectingSince=System.nanoTime();connectAttempts++;String host="127.0.0.1:"+System.getProperty("codex.test.port");
        //? if <1.21 {
        /*ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(host),new ServerData("Codex isolated test",host,false),false);
        *///?} else {
        ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(host),new ServerData("Codex isolated test",host,ServerData.Type.OTHER),false,null);
        //?}
    }
    private static boolean loading(Minecraft mc){
        //? if >=26.2 {
        /*return mc.gui.overlay()!=null;
        *///?} else {
        return mc.getOverlay()!=null;
        //?}
    }
    private static void disconnect(Minecraft mc){
        //? if <1.21 {
        /*mc.clearLevel(new TitleScreen());
        *///?} else {
        mc.disconnect(new TitleScreen(),false);
        //?}
    }
    private static void awaitConnection(Minecraft mc)throws Exception {
        long now=System.nanoTime();
        if(connecting&&mc.screen!=null&&mc.screen.getClass().getSimpleName().equals("DisconnectedScreen")) {
            StringBuilder reason=new StringBuilder(mc.screen.getTitle().getString());
            for(var field:mc.screen.getClass().getDeclaredFields())if(Component.class.isAssignableFrom(field.getType())) {
                if(!field.trySetAccessible())continue;Object value=field.get(mc.screen);if(value instanceof Component component)reason.append(": ").append(component.getString());
            }
            JsonObject diagnostic=new JsonObject();diagnostic.addProperty("actor",ACTOR);diagnostic.addProperty("attempt",connectAttempts);diagnostic.addProperty("reason",reason.toString());
            AtomicJson.write(CONTROL.resolve("login-"+ACTOR+".json"),diagnostic);
            System.err.println("CODEX CLIENT LOGIN "+diagnostic);
            if(connectAttempts>=3)throw new IllegalStateException("Test login failed after three attempts: "+reason);
            connecting=false;reconnectAt=now+2_000_000_000L;mc.setScreen(new TitleScreen());
        }
        if(connecting&&now-connectingSince>90_000_000_000L)throw new IllegalStateException("Test login stalled for90s: "+(mc.screen==null?"no screen":mc.screen.getClass().getSimpleName()));
        if(reconnectAt>0&&now>=reconnectAt){reconnectAt=0;connecting=false;}
        if(!connecting&&reconnectAt==0&&!loading(mc)&&mc.screen instanceof TitleScreen)connect(mc);
    }
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
            if(mc.getConnection()==null){awaitConnection(mc);return;}
            if(gui){if(!GuiAuthoringScenario.tick(mc))return;gui=false;JsonObject report=new JsonObject();report.addProperty("nonce",nonce);report.addProperty("gui_authored",true);report.addProperty("gui_steps",GuiAuthoringScenario.completedSteps());report.addProperty("tcp",!mc.getConnection().getConnection().isMemoryConnection());report(report);return;}
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
                case "reconnect" -> {connecting=false;connectAttempts=0;reconnectAt=System.nanoTime()+1_500_000_000L;disconnect(mc);}
                case "quit" -> {report(report);mc.stop();return;}
                default -> throw new IllegalArgumentException("Unknown isolated client step: "+op);
            }
            report(report);
        } catch (Throwable error) {
            error.printStackTrace();
            JsonObject report = new JsonObject();
            report.addProperty("nonce", nonce);
            report.addProperty("error", error.toString());
            if (gui) {
                report.addProperty("gui_step", GuiAuthoringScenario.completedSteps() - 1);
                report.addProperty("screen", mc.screen == null ? "<none>" : mc.screen.getTitle().getString());
            }
            gui = false;
            report(report);
            if (nonce < 0) System.exit(1);
        }
    }
}
