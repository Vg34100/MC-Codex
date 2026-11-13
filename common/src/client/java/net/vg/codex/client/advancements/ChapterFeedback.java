package net.vg.codex.client.advancements;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;

/** One short, optional chapter banner. Artwork remains inside its own panel. */
public final class ChapterFeedback {
    private static String title="", subtitle="";private static ItemStack icon=ItemStack.EMPTY;private static long started;
    private ChapterFeedback() { }
    public static void show(Identifier id,String questTitle,JsonObject settings){
        Minecraft mc=Minecraft.getInstance();title=settings.has("title")&&!settings.get("title").getAsString().isBlank()?settings.get("title").getAsString():"Chapter complete";subtitle=questTitle;
        var node=mc.getConnection()==null?null:mc.getConnection().getAdvancements().getTree().get(id);icon=node!=null&&node.advancement().display().isPresent()?node.advancement().display().get().getIcon().create():new ItemStack(Items.BOOK);started=System.nanoTime();
        if(CodexClientSettings.sounds&&settings.has("sound")){var key=Identifier.tryParse(settings.get("sound").getAsString());if(key!=null&&BuiltInRegistries.SOUND_EVENT.containsKey(key))mc.getSoundManager().play(SimpleSoundInstance.forUI(BuiltInRegistries.SOUND_EVENT.getValue(key),1F,0.3F));}
    }
    public static void render(GuiGraphicsExtractor g){
        Minecraft mc=Minecraft.getInstance();if(mc.player==null||started==0)return;float age=(System.nanoTime()-started)/1_000_000_000F;if(age>=4){started=0;return;}
        float opacity=CodexClientSettings.animations?Math.min(1,Math.min(age/0.18F,(4-age)/0.45F)):1;int a=Math.round(255*opacity),w=Math.min(300,mc.getWindow().getGuiScaledWidth()-24),x=(mc.getWindow().getGuiScaledWidth()-w)/2,y=18;
        g.fill(x,y,x+w,y+44,(Math.round(180*opacity)<<24)|0x17231F);g.fill(x,y,x+2,y+44,(a<<24)|0xE6C374);g.fakeItem(icon,x+10,y+14);
        g.text(mc.font,mc.font.plainSubstrByWidth(title,w-50),x+34,y+9,(a<<24)|0xFFE1A1,false);g.text(mc.font,mc.font.plainSubstrByWidth(subtitle,w-50),x+34,y+25,(a<<24)|0xFFFFFF,false);
    }
    public static void clear(){started=0;}
}
