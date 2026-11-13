package net.vg.codex.validation;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import net.vg.codex.client.advancements.*;
import java.util.*;

/** Exercises real widget callbacks in a rendered test client; no OS pointer or keyboard events. */
public final class GuiAuthoringScenario {
    private static final Identifier STORY=Identifier.parse("codex_test:gui_story");
    private static CodexAdvancementsScreen editor;
    private static int stage,wait;private static long started;
    private GuiAuthoringScenario(){}
    public static void start(){stage=0;wait=0;started=System.nanoTime();}
    private static void click(Minecraft mc,String text){
        AbstractButton button=mc.screen.children().stream().filter(c->c instanceof AbstractButton b&&b.getMessage().getString().equals(text)).map(c->(AbstractButton)c).findFirst().orElseThrow(()->new IllegalStateException("Missing button '"+text+"' in "+mc.screen.getTitle().getString()));
        if(!button.active)throw new IllegalStateException("Inactive button "+text);button.onPress(new KeyEvent(257,0,0));
    }
    private static void prefix(Minecraft mc,String text){AbstractButton b=mc.screen.children().stream().filter(c->c instanceof AbstractButton a&&a.getMessage().getString().startsWith(text)).map(c->(AbstractButton)c).findFirst().orElseThrow(()->new IllegalStateException("Missing button prefix "+text));b.onPress(new KeyEvent(257,0,0));}
    private static void edit(Minecraft mc,String value){mc.screen.children().stream().filter(c->c instanceof EditBox).map(c->(EditBox)c).findFirst().orElseThrow().setValue(value);}
    private static void storyText(Minecraft mc,String value){mc.screen.children().stream().filter(c->c instanceof MultiLineEditBox).map(c->(MultiLineEditBox)c).findFirst().orElseThrow().setValue(value);}
    private static void shot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,"creator-"+name+".png",mc.getMainRenderTarget(),1,message->{});}
    private static void select(Minecraft mc,Identifier id)throws Exception {
        var method=CodexAdvancementsScreen.class.getDeclaredMethod("selectEditNodeById",Identifier.class);method.setAccessible(true);method.invoke(editor,id);
        for(String name:List.of("formAdvancementId","criteriaEntriesSelection")){var field=CodexAdvancementsScreen.class.getDeclaredField(name);field.setAccessible(true);field.set(editor,null);}
        editor.init(mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight());
    }
    public static boolean tick(Minecraft mc)throws Exception{
        if(System.nanoTime()-started>180_000_000_000L)throw new IllegalStateException("GUI authoring timed out at step "+stage);
        if(wait-->0)return false;wait=2;
        switch(stage){
            case 0 -> mc.getConnection().sendCommand("codex advancements edit");
            case 1 -> {
                if(!(mc.screen instanceof CodexAdvancementsScreen)){wait=2;return false;}
                editor=(CodexAdvancementsScreen)mc.screen;
                var mode=CodexAdvancementsScreen.class.getDeclaredField("editMode");mode.setAccessible(true);if(!mode.getBoolean(mc.screen))return false;
                var raw=CodexAdvancementsScreen.class.getDeclaredField("rawFullJson");raw.setAccessible(true);if(!((java.util.Map<?,?>)raw.get(mc.screen)).containsKey(STORY))return false;
                var selected=CodexAdvancementsScreen.class.getDeclaredMethod("selectEditNodeById",Identifier.class);selected.setAccessible(true);selected.invoke(mc.screen,STORY);
                for(String name:List.of("formAdvancementId","criteriaEntriesSelection")){var field=CodexAdvancementsScreen.class.getDeclaredField(name);field.setAccessible(true);field.set(mc.screen,null);}
                mc.screen.init(mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight());
                var widget=CodexAdvancementsScreen.class.getDeclaredField("selectedEditWidget");widget.setAccessible(true);var selectedWidget=(CodexAdvancementWidget)widget.get(mc.screen);if(selectedWidget==null||!selectedWidget.node().holder().id().equals(STORY))throw new IllegalStateException("GUI setup did not select the requested fixture");
            }
            case 2 -> click(mc,"Quest Story & Rules");
            case 3 -> storyText(mc,"This introduction was written through the in-game GUI.");
            case 4 -> click(mc,"Choices");
            case 5 -> click(mc,"Add Choice");
            case 6 -> edit(mc,"A GUI-authored branch");
            case 7 -> click(mc,"Link Next Chapter…");
            case 8 -> edit(mc,"GUI Chapter Test");
            case 9 -> click(mc,"GUI Chapter Test");
            case 10 -> storyText(mc,"Your decision opens the chapter and records this outcome.");
            case 11 -> click(mc,"Show Choice When…");
            case 12 -> click(mc,"Add Required Group");
            case 13 -> prefix(mc,"Quest: ");
            case 14 -> edit(mc,"Reward Scenario");
            case 15 -> click(mc,"Reward Scenario");
            case 16 -> shot(mc,"condition");
            case 17 -> click(mc,"Save Condition");
            case 18 -> click(mc,"Apply Conditions");
            case 19 -> shot(mc,"story-choice");
            case 20 -> click(mc,"Save Choice");
            case 21 -> click(mc,"Apply to Draft");
            case 22 -> {click(mc,"Save");wait=16;}
            case 23 -> click(mc,"Pack Creator Tools");
            case 24 -> shot(mc,"problems");
            case 25 -> click(mc,"Preview");
            case 26 -> shot(mc,"preview-fresh");
            case 27 -> {prefix(mc,"Preview state:");prefix(mc,"Preview state:");}
            case 28 -> shot(mc,"preview-complete");
            case 29 -> click(mc,"Duplicate");
            case 30 -> shot(mc,"duplicate");
            case 31 -> click(mc,"Bulk Rules");
            case 32 -> shot(mc,"bulk-rules");
            case 33 -> click(mc,"Back to Editor");
            case 34 -> click(mc,"Quest Story & Rules");
            case 35 -> click(mc,"Effects");
            case 36 -> {prefix(mc,"Chapter celebration:");edit(mc,"My first authored chapter");}
            case 37 -> shot(mc,"effects");
            case 38 -> click(mc,"Preview Celebration");
            case 39 -> shot(mc,"celebration");
            case 40 -> click(mc,"Apply to Draft");
            case 41 -> {click(mc,"Save");wait=16;}
            case 42 -> {
                JsonObject info=ClientQuests.info(Identifier.parse("codex_test:reward"));
                mc.setScreen(new RewardPreviewScreen(mc.screen,Identifier.parse("codex_test:reward"),info.getAsJsonObject("rewards"),true));
            }
            case 43 -> {prefix(mc,"    Random Treasure:");wait=3;}
            case 44 -> {
                shot(mc,"reward-preview");
                JsonArray rows=JsonParser.parseString("[{\"tag\":\"minecraft:logs\",\"count\":4},{\"id\":\"minecraft:oak_log\",\"count\":1,\"components\":{\"minecraft:custom_name\":\"Camp Timber\"}}]").getAsJsonArray();mc.setScreen(new ItemRequirementsScreen(mc.screen,rows,ignored->{}));
            }
            case 45 -> shot(mc,"delivery-tags");
            case 46 -> {List<Button> props=mc.screen.children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().equals("Props")).map(c->(Button)c).toList();props.get(1).onPress(new KeyEvent(257,0,0));}
            case 47 -> shot(mc,"delivery-properties");
            case 48 -> {
                mc.setScreen(editor);select(mc,Identifier.parse("codex_test:scale/root"));wait=12;
            }
            case 49 -> {shot(mc,"large-tree");JsonObject metric=new JsonObject();metric.addProperty("nodes",501);metric.addProperty("fps",mc.getFps());metric.addProperty("configured_fps_cap",15);metric.addProperty("purpose","rendered GUI smoke check, not an uncapped benchmark");net.vg.codex.advancement.AtomicJson.write(java.nio.file.Path.of("gui-performance.json"),metric);}
            case 50 -> select(mc,STORY);
            case 51 -> click(mc,"+ Add Criterion");
            case 52 -> click(mc,"Copy Existing Native Objective…");
            case 53 -> edit(mc,"Reward Scenario");
            case 54 -> click(mc,"Reward Scenario");
            case 55 -> shot(mc,"native-objective-picker");
            case 56 -> click(mc,"done");
            case 57 -> shot(mc,"copied-native-objective");
            case 58 -> click(mc,"Add Criterion");
            case 59 -> {click(mc,"Save");wait=16;}
            case 60 -> select(mc,Identifier.parse("codex_test:each_delivery"));
            case 61 -> click(mc,"Quest Story & Rules");
            case 62 -> click(mc,"Rules");
            case 63 -> edit(mc,"invalid");
            case 64 -> click(mc,"Apply to Draft");
            case 65 -> {if(!mc.screen.getTitle().getString().equals("Quest Story & Rules"))throw new IllegalStateException("Invalid duration was accepted");edit(mc,"0");}
            case 66 -> click(mc,"Apply to Draft");
            case 67 -> {if(!(mc.screen instanceof CodexAdvancementsScreen))throw new IllegalStateException("Correcting duration did not recover Apply");click(mc,"Save");wait=16;}
            case 68 -> {select(mc,STORY);mc.getWindow().setWindowed(520,240);wait=10;}
            case 69 -> click(mc,"Quest Story & Rules");
            case 70 -> click(mc,"Choices");
            case 71 -> click(mc,"A GUI-authored branch");
            case 72 -> shot(mc,"compact-choice");
            case 73 -> click(mc,"Edit Outcome Text…");
            case 74 -> shot(mc,"compact-outcome");
            case 75 -> click(mc,"Cancel");
            case 76 -> click(mc,"Cancel");
            case 77 -> click(mc,"Cancel");
            case 78 -> {mc.getWindow().setWindowed(640,360);wait=10;}
            case 79 -> select(mc,Identifier.parse("codex_test:reward"));
            case 80 -> click(mc,"Pack Creator Tools");
            case 81 -> click(mc,"Migrate");
            case 82 -> shot(mc,"migration");
            case 83 -> click(mc,"Export Migration Plan");
            case 84 -> {shot(mc,"migration-exported");mc.setScreen(null);return true;}
            default -> throw new IllegalStateException("Unknown GUI authoring stage");
        }
        stage++;return false;
    }
}
