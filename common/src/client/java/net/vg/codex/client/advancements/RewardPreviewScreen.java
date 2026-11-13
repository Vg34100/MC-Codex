package net.vg.codex.client.advancements;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vg.codex.advancement.QuestRewards;
import java.util.List;

/** Read-only potential rewards, never a roll or claim. */
public final class RewardPreviewScreen extends CodexScreen {
    private final Screen parent;private final List<JsonObject> rewards;private final boolean manual;private int left,size,page,selected,rows;
    public RewardPreviewScreen(Screen parent,Identifier id,JsonObject rewards,boolean manual){super(Component.literal("Reward Preview"));this.parent=parent;this.rewards=QuestRewards.entries(rewards,id);this.manual=manual;}
    @Override protected void init(){
        size=Math.min(560,width-24);left=(width-size)/2;int column=Math.min(170,size/3);rows=Math.max(1,(height-128)/24);page=Math.min(page,Math.max(0,(rewards.size()-1)/rows));
        for(int i=page*rows;i<Math.min(rewards.size(),(page+1)*rows);i++){int index=i;JsonObject reward=rewards.get(i);String label=ClientRewardPresentation.label(reward);
            Button button=addRenderableWidget(Button.builder(Component.literal("    "+label),b->{selected=index;rebuildWidgets();}).bounds(left,64+i%rows*24,column,22).build());button.active=selected!=i;button.setTooltip(Tooltip.create(Component.literal(label)));}
        String detail=rewards.isEmpty()?"No rewards configured.":ClientRewardPresentation.details(rewards.get(Math.min(selected,rewards.size()-1)));
        addRenderableWidget(new FittingMultiLineTextWidget(left+column+10,64,size-column-10,Math.max(30,height-110),Component.literal(detail),font));
        addRenderableWidget(Button.builder(Component.literal("<"),b->{page--;rebuildWidgets();}).bounds(left,height-60,26,20).build()).active=page>0;
        addRenderableWidget(Button.builder(Component.literal(">"),b->{page++;rebuildWidgets();}).bounds(left+32,height-60,26,20).build()).active=(page+1)*rows<rewards.size();
        addRenderableWidget(Button.builder(Component.literal("Back"),b->onClose()).bounds(left,height-32,size,20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float dt){
        g.fill(0,0,width,height,0xED202020);g.text(font,title,left,14,-1,false);g.text(font,manual?"Claim manually after completion":"Granted automatically on completion",left,40,0xFFCCCCAA,false);super.extractRenderState(g,x,y,dt);
        for(int i=page*rows;i<Math.min(rewards.size(),(page+1)*rows);i++)g.fakeItem(ClientRewardPresentation.icon(rewards.get(i)),left+4,67+i%rows*24);
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
}
