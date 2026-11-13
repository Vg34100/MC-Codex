package net.vg.codex.client.advancements;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.*;
import net.minecraft.resources.*;
import net.vg.codex.advancement.*;
import java.util.*;
import java.util.function.Consumer;

/** Dialogue conditions use familiar AND groups with explicit OR alternatives. */
public final class DialogueConditionsScreen extends CodexScreen {
    private final Screen parent; private final Map<Identifier,JsonObject> definitions; private final Consumer<JsonArray> apply; private final JsonArray groups;
    private int left,size,page; private String error="";
    public DialogueConditionsScreen(Screen parent,Map<Identifier,JsonObject> definitions,JsonArray source,Consumer<JsonArray> apply){super(Component.literal("Show Choice When…"));this.parent=parent;this.definitions=definitions;this.groups=source.deepCopy();this.apply=apply;}
    private String name(Identifier id){try{return ComponentSerialization.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE,minecraft.level.registryAccess()),definitions.get(id).getAsJsonObject("display").get("title")).getOrThrow().getString();}catch(RuntimeException ignored){return id.toString();}}
    private String label(JsonObject c){return (QuestDefinitions.flag(c,"negate")?"NOT ":"")+switch(c.get("state").getAsString()){case "accepted"->"Accepted ";case "claimed"->"Claimed rewards: ";case "choice"->"Chose a branch: ";default->"Completed ";}+name(Identifier.parse(c.get("quest").getAsString()));}
    private record Row(int group,int index){}
    @Override protected void init(){
        size=Math.min(520,width-24);left=(width-size)/2;List<Row> list=new ArrayList<>();for(int g=0;g<groups.size();g++)for(int i=0;i<groups.get(g).getAsJsonArray().size();i++)list.add(new Row(g,i));
        int rows=Math.max(1,(height-160)/26);page=Math.min(page,Math.max(0,(list.size()-1)/rows));
        for(int i=page*rows;i<Math.min(list.size(),(page+1)*rows);i++){
            Row r=list.get(i);JsonObject c=groups.get(r.group).getAsJsonArray().get(r.index).getAsJsonObject();int y=78+(i%rows)*26;
            addRenderableWidget(Button.builder(Component.literal("Group "+(r.group+1)+": "+label(c)),b->edit(r.group,r.index)).bounds(left,y,size-58,22).build()).setTooltip(Tooltip.create(Component.literal(label(c)+"\nAll groups required; any alternative inside each group.")));
            addRenderableWidget(Button.builder(Component.literal("+"),b->edit(r.group,-1)).bounds(left+size-54,y,24,22).build()).active=groups.get(r.group).getAsJsonArray().size()<8;
            addRenderableWidget(Button.builder(Component.literal("X"),b->{JsonArray group=groups.get(r.group).getAsJsonArray();group.remove(r.index);if(group.isEmpty())groups.remove(r.group);rebuildWidgets();}).bounds(left+size-26,y,26,22).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Add Required Group"),b->edit(-1,-1)).bounds(left,height-84,size-64,20).build()).active=groups.size()<8;
        addRenderableWidget(Button.builder(Component.literal("<"),b->{page--;rebuildWidgets();}).bounds(left+size-58,height-84,26,20).build()).active=page>0;
        addRenderableWidget(Button.builder(Component.literal(">"),b->{page++;rebuildWidgets();}).bounds(left+size-28,height-84,28,20).build()).active=(page+1)*rows<list.size();
        addRenderableWidget(Button.builder(Component.literal("Apply Conditions"),b->{try{QuestConditions.validate(groups);apply.accept(groups.deepCopy());minecraft.setScreen(parent);}catch(RuntimeException invalid){error=invalid.getMessage();}}).bounds(left,height-32,size/2-3,20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"),b->onClose()).bounds(left+size/2+3,height-32,size/2-3,20).build());
    }
    private void edit(int group,int index){JsonObject c=index>=0?groups.get(group).getAsJsonArray().get(index).getAsJsonObject().deepCopy():new JsonObject();if(!c.has("quest")){c.addProperty("quest",definitions.keySet().iterator().next().toString());c.addProperty("state","completed");}
        minecraft.setScreen(new ConditionEditor(this,c,updated->{if(group<0){JsonArray row=new JsonArray();row.add(updated);groups.add(row);}else if(index<0)groups.get(group).getAsJsonArray().add(updated);else groups.get(group).getAsJsonArray().set(index,updated);}));}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float dt){g.fill(0,0,width,height,0xED202020);g.text(font,title,left,14,-1,false);g.text(font,"All groups required; + adds an OR alternative.",left,42,0xFFCCCCAA,false);g.text(font,groups.isEmpty()?"No conditions: stage rules alone decide availability.":"Click a condition to edit its quest and state.",left,56,0xFFCCCCAA,false);super.extractRenderState(g,x,y,dt);if(!error.isEmpty())g.text(font,font.plainSubstrByWidth(error,size),left,height-52,0xFFFF7777,false);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    private final class ConditionEditor extends CodexScreen {
        private final Screen parent;private final JsonObject condition;private final Consumer<JsonObject> apply;private String error="";
        ConditionEditor(Screen parent,JsonObject c,Consumer<JsonObject> apply){super(Component.literal("Dialogue Condition"));this.parent=parent;this.condition=c;this.apply=apply;}
        @Override protected void init(){
            int size=Math.min(480,width-24),left=(width-size)/2;
            addRenderableWidget(Button.builder(Component.literal("Quest: "+name(Identifier.parse(condition.get("quest").getAsString()))),b->minecraft.setScreen(new IdentifierSelectionScreen(this,"Condition Quest",definitions.keySet(),id->Component.literal(name(id)),id->{condition.addProperty("quest",id.toString());condition.remove("choice");}))).bounds(left,50,size,20).build());
            addRenderableWidget(CycleButton.builder((String v)->Component.literal(switch(v){case "accepted"->"Quest accepted";case "claimed"->"All rewards claimed";case "choice"->"Story choice saved";default->"Quest completed";}),condition.get("state").getAsString()).withValues("completed","accepted","claimed","choice").create(left,78,size,20,Component.literal("Require"),(b,value)->{condition.addProperty("state",value);rebuildWidgets();}));
            addRenderableWidget(CycleButton.onOffBuilder(QuestDefinitions.flag(condition,"negate")).create(left,106,size,20,Component.literal("Invert (must NOT match)"),(b,value)->condition.addProperty("negate",value)));
            if(condition.get("state").getAsString().equals("choice")){
                JsonObject source=definitions.get(Identifier.parse(condition.get("quest").getAsString()));JsonArray choices=source==null?new JsonArray():PackAuthoring.choices(source);List<String> keys=new ArrayList<>();for(JsonElement c:choices)keys.add(c.getAsJsonObject().get("id").getAsString());
                if(!keys.isEmpty()){
                    if(!condition.has("choice")||!keys.contains(condition.get("choice").getAsString()))condition.addProperty("choice",keys.getFirst());
                    addRenderableWidget(CycleButton.builder((String key)->{for(JsonElement c:choices)if(c.getAsJsonObject().get("id").getAsString().equals(key))return Component.literal(c.getAsJsonObject().get("label").getAsString());return Component.literal(key);},condition.get("choice").getAsString()).withValues(keys).create(left,134,size,20,Component.literal("Choice"),(b,key)->condition.addProperty("choice",key)));
                }else error="Select a quest with story choices";
            }
            addRenderableWidget(Button.builder(Component.literal("Save Condition"),b->{try{JsonArray group=new JsonArray();group.add(condition);JsonArray groups=new JsonArray();groups.add(group);QuestConditions.validate(groups);apply.accept(condition.deepCopy());minecraft.setScreen(parent);}catch(RuntimeException invalid){error=invalid.getMessage();}}).bounds(left,height-32,size/2-3,20).build());
            addRenderableWidget(Button.builder(Component.literal("Cancel"),b->onClose()).bounds(left+size/2+3,height-32,size/2-3,20).build());
        }
        @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float dt){g.fill(0,0,width,height,0xED202020);g.text(font,title,(width-Math.min(480,width-24))/2,14,-1,false);super.extractRenderState(g,x,y,dt);if(!error.isEmpty())g.text(font,error,12,height-50,0xFFFF7777,false);}
        @Override public void onClose(){minecraft.setScreen(parent);}
    }
}
