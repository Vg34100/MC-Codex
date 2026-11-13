package net.vg.codex.client.advancements;

import com.google.gson.*;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vg.codex.network.TabSummaryPayload;
import java.util.*;

/** Native border palettes with restrained, frame-local motion and readable action hints. */
public final class ClientTabSummaries {
    private static JsonObject summaries = new JsonObject();
    private static Object connection;
    private static final Map<String,Long> completedAt = new HashMap<>();
    private record Hover(float amount,long at) { }
    private static final Map<String,Hover> hovered = new HashMap<>();
    private ClientTabSummaries() { }
    private static void connected() {
        Object next=Minecraft.getInstance().getConnection();
        if(connection!=next){connection=next;summaries=new JsonObject();completedAt.clear();hovered.clear();}
    }
    public static void receive(TabSummaryPayload packet) {
        connected();JsonObject previous=summaries;
        if(packet.replace())summaries=new JsonObject();
        long now=System.nanoTime();completedAt.entrySet().removeIf(e->now-e.getValue()>2_000_000_000L);
        for(var entry:JsonParser.parseString(packet.json()).getAsJsonObject().entrySet()) {
            JsonObject incoming=entry.getValue().getAsJsonObject();
            if(previous.has(entry.getKey()) && !finished(previous.getAsJsonObject(entry.getKey())) && finished(incoming))completedAt.put(entry.getKey(),now);
            summaries.add(entry.getKey(),incoming);
        }
    }
    public static JsonObject get(Identifier root) { connected(); return summaries.has(root.toString()) ? summaries.getAsJsonObject(root.toString()) : new JsonObject(); }
    private static boolean finished(JsonObject t) { return t.has("total") && t.get("total").getAsInt()>0 && t.get("completed").getAsInt()==t.get("total").getAsInt(); }
    public static boolean complete(Identifier root) { return finished(get(root)); }
    private static String style(Identifier root) { if(complete(root))return "gold"; JsonObject t=get(root); return t.has("quest_tab") && t.get("quest_tab").getAsBoolean()?"quest":""; }
    public static Identifier tabSprite(Identifier vanilla, Identifier root) { String style=style(root); return style.isEmpty()?vanilla:Identifier.fromNamespaceAndPath("codex",vanilla.getPath()+"_"+style); }
    public static Identifier windowTexture(Identifier vanilla, Identifier root) { String style=style(root); return style.isEmpty()?vanilla:Identifier.fromNamespaceAndPath("codex","textures/gui/advancements/window_"+style+".png"); }
    public static float iconScale(Identifier root) { return iconScale(root,false); }
    public static float iconScale(Identifier root, boolean over) {
        JsonObject t=get(root);if(!CodexClientSettings.animations)return 1;
        long now=System.nanoTime();String key=root.toString();Hover last=hovered.get(key);
        float amount=last==null?0:last.amount();
        float ease=last==null?0.25F:Math.min(1,(now-last.at())/90_000_000F);
        amount+=( (over?1:0)-amount)*ease;hovered.put(key,new Hover(amount,now));
        float scale=1+amount*0.045F;
        Long at=completedAt.get(key);double age=at==null?2:(now-at)/1_000_000_000.0;
        if(age<0.5)scale+=(float)Math.sin(age/0.5*Math.PI)*0.10F;
        else if(!finished(t) && t.has("attention") && t.get("attention").getAsBoolean()) {
            double phase=now/1_000_000_000.0%4;
            if(phase<1.2)scale+=(float)Math.sin(phase/1.2*Math.PI)*0.08F;
        }
        return scale;
    }
    /** A single soft pass inside the title bar after completion; never covers gameplay. */
    public static void completionSheen(GuiGraphicsExtractor g,Identifier root,int left,int top,int width) {
        if(!CodexClientSettings.animations || !complete(root))return;
        Long at=completedAt.get(root.toString());if(at==null)return;
        double age=(System.nanoTime()-at)/650_000_000.0;if(age<0 || age>1)return;
        int min=left+9,max=left+width-9,center=(int)(min-18+(max-min+36)*age);
        for(int i=-18;i<=18;i++) {
            int x=center+i;if(x<min || x>=max)continue;
            int alpha=(int)((1-Math.abs(i)/19.0)*32*Math.sin(Math.PI*age));
            g.fill(x,top+3,x+1,top+16,(alpha<<24)|0xFFF0B8);
        }
    }
    public static List<Component> tooltip(Identifier root,Component title) {
        JsonObject t=get(root);List<Component> lines=new ArrayList<>();
        var heading=title.copy().withStyle(ChatFormatting.BOLD);
        if(finished(t))heading.withStyle(ChatFormatting.GOLD);
        lines.add(heading);if(t.isEmpty())return lines;
        lines.add(Component.literal(t.get("completed").getAsInt()+" / "+t.get("total").getAsInt()+" quests finished").withStyle(ChatFormatting.GRAY));
        if(finished(t))lines.add(Component.literal("Chapter finished").withStyle(ChatFormatting.GOLD,ChatFormatting.ITALIC));
        else {
            action(lines,t,"rewards","quest with rewards to claim","quests with rewards to claim",ChatFormatting.GOLD);
            action(lines,t,"acceptance","quest ready to accept","quests ready to accept",ChatFormatting.GREEN);
            action(lines,t,"choices","story decision waiting","story decisions waiting",ChatFormatting.LIGHT_PURPLE);
            if(!t.get("attention").getAsBoolean())lines.add(Component.literal("In progress").withStyle(ChatFormatting.DARK_GRAY,ChatFormatting.ITALIC));
        }
        return lines;
    }
    private static void action(List<Component> lines,JsonObject t,String key,String one,String many,ChatFormatting color) {
        if(t.has(key) && t.get(key).getAsInt()>0){int count=t.get(key).getAsInt();lines.add(Component.literal(count+" "+(count==1?one:many)).withStyle(color));}
    }
}
