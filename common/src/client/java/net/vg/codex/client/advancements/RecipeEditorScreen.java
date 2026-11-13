package net.vg.codex.client.advancements;

import com.google.gson.*;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.vg.codex.network.RecipeEditorRequest;
import net.vg.codex.recipe.RecipeDocuments;
import java.util.*;

/** Ordinary recipe documents edited with ghost ingredients, never consuming the author's items. */
public final class RecipeEditorScreen extends CodexScreen {
 private final Screen parent;
 private JsonObject document;
 private JsonArray entries=new JsonArray();
 private String query="",identifier="",hash="-",status="",loaded="";
 private int offset,total,left,size,rows;
 private boolean listLoaded,pending,override,hasOriginal,restoreConfirm;
 public RecipeEditorScreen(Screen parent){super(Component.literal("Recipe Editor"));this.parent=parent;}
 public static void request(JsonObject request){NetworkManager.sendToServer(new RecipeEditorRequest(request.toString()));}
 private void send(String action){JsonObject r=new JsonObject();r.addProperty("action",action);r.addProperty("id",identifier);r.addProperty("hash",hash);if(action.equals("save"))r.add("document",document.deepCopy());pending=true;status="Working…";request(r);rebuildWidgets();}
 private void list(){JsonObject r=new JsonObject();r.addProperty("action","list");r.addProperty("query",query);r.addProperty("offset",offset);r.addProperty("limit",rows);pending=true;request(r);}
 public void receive(JsonObject response){
  pending=false;restoreConfirm=false;
  if(response.has("error")){if(document==null)listLoaded=true;status=response.get("error").getAsString();rebuildWidgets();return;}
  status=response.has("status")?response.get("status").getAsString():"";
  if(response.has("entries")){listLoaded=true;entries=response.getAsJsonArray("entries");total=response.get("total").getAsInt();offset=response.get("offset").getAsInt();}
  if(response.has("id")){
   identifier=response.get("id").getAsString();hash=response.get("hash").getAsString();override=response.get("override").getAsBoolean();hasOriginal=response.get("has_original").getAsBoolean();
   document=response.has("document")?response.getAsJsonObject("document").deepCopy():null;loaded=document==null?"":document.toString();
   if(document==null)status="Recipe is no longer present. Search again.";
  }
  rebuildWidgets();
 }
 @Override protected void init(){
  size=Math.min(500,width-24);left=(width-size)/2;rows=Math.max(1,Math.min(12,(height-175)/24));
  if(document==null)browser();else editor();
  if(pending)for(var child:children())if(child instanceof AbstractWidget w)w.active=false;
 }
 private Button button(String text,int x,int y,int w,Runnable action){return addRenderableWidget(Button.builder(Component.literal(text),b->action.run()).bounds(x,y,w,20).build());}
 private void browser(){
  EditBox search=addRenderableWidget(new EditBox(font,left,34,size-86,20,Component.literal("Search recipes")));search.setValue(query);search.setMaxLength(128);search.setResponder(v->query=v);
  button("Search",left+size-80,34,80,()->{offset=0;list();rebuildWidgets();});
  int count=Math.min(rows,entries.size());
  for(int i=0;i<count;i++){JsonObject e=entries.get(i).getAsJsonObject();String id=e.get("id").getAsString();String label="    "+ClientRewardPresentation.pretty(Identifier.parse(id))+(e.get("override").getAsBoolean()?" [edited]":"");button(label,left,64+i*24,size,()->{identifier=id;send("open");}).setTooltip(Tooltip.create(Component.literal(id)));}
  int y=height-100;
  button("<",left,y,28,()->{offset=Math.max(0,offset-rows);list();rebuildWidgets();}).active=offset>0;
  button(">",left+34,y,28,()->{offset+=rows;list();rebuildWidgets();}).active=offset+rows<total;
  int w=(size-12)/3;
  button("New Shaped",left,height-74,w,()->create("crafting_shaped"));
  button("New Shapeless",left+w+6,height-74,w,()->create("crafting_shapeless"));
  button("New Cooking",left+2*(w+6),height-74,w,()->create("smelting"));
  button("Back",left,height-32,size,()->minecraft.setScreen(parent));
  if(!listLoaded&&!pending){status="Search recipes to edit an existing recipe, or create a new one.";list();}
 }
 private void create(String type){document=RecipeDocuments.blank(type);identifier="codex:custom_recipe";hash="-";override=false;hasOriginal=false;loaded="";status="";rebuildWidgets();}
 private void editor(){
  EditBox id=addRenderableWidget(new EditBox(font,left,34,size,20,Component.literal("Recipe identifier")));id.setMaxLength(256);id.setValue(identifier);id.setEditable(loaded.isEmpty());id.setResponder(v->identifier=v);id.setTooltip(Tooltip.create(Component.literal("New recipes need a unique name. Existing recipe identifiers stay unchanged.")));
  boolean supported=RecipeDocuments.supported(document);
  String type=RecipeDocuments.type(document);
  if(loaded.isEmpty()&&Set.of("minecraft:smelting","minecraft:blasting","minecraft:smoking","minecraft:campfire_cooking").contains(type)){
   addRenderableWidget(CycleButton.builder((String t)->Component.literal(cookingName(t)),type.substring(type.indexOf(':')+1)).withValues("smelting","blasting","smoking","campfire_cooking").create(left,64,size,20,Component.literal("Cooking"),(b,t)->{document.addProperty("type","minecraft:"+t);rebuildWidgets();}));
  }else button(type.replace("minecraft:","").replace('_',' '),left,64,size,()->{}).active=false;
  if(supported){
   JsonElement[] slots=RecipeDocuments.slots(document);boolean crafting=type.contains("crafting_");int n=crafting?9:1;
   for(int i=0;i<n;i++){final int slot=i;button("",left+12+(i%3)*30,110+(i/3)*30,26,()->ingredient(slot));}
   button("Choose Output…",left+132,110,size-144,()->minecraft.setScreen(new ItemPickerScreen(this,picked->{
    JsonObject result=document.has("result")&&document.get("result").isJsonObject()?document.getAsJsonObject("result").deepCopy():new JsonObject();result.addProperty("id",picked.toString());if(!result.has("count"))result.addProperty("count",1);document.add("result",result);
    if(loaded.isEmpty()&&identifier.equals("codex:custom_recipe"))identifier="codex:custom/"+picked.getPath();
   })));
   if(document.has("result")&&document.get("result").isJsonObject()){
    JsonObject out=document.getAsJsonObject("result");EditBox count=addRenderableWidget(new EditBox(font,left+176,148,80,20,Component.literal("Output quantity")));count.setValue(out.has("count")?out.get("count").getAsString():"1");count.setMaxLength(3);count.setResponder(v->{try{out.addProperty("count",Integer.parseInt(v));}catch(NumberFormatException e){out.addProperty("count",v);}});count.setTooltip(Tooltip.create(Component.literal("Number of items produced by one craft")));
    //? if <26.1 {
    /*if(!crafting){count.setValue("1");count.setEditable(false);count.setTooltip(Tooltip.create(Component.literal("This Minecraft version produces one item per cooking operation.")));}
    *///?}

   }
   if(!crafting){numeric("experience","Experience",left,194,0);numeric("cookingtime","Cooking time (ticks)",left+size/2,194,200);}
   button("Clear Ingredients",left,crafting?218:226,size,()->{if(crafting){if(type.endsWith("shaped")){document.add("key",new JsonObject());JsonArray p=new JsonArray();p.add("   ");p.add("   ");p.add("   ");document.add("pattern",p);}else document.add("ingredients",new JsonArray());}else document.remove("ingredient");rebuildWidgets();});
  }
  button("Advanced JSON…",left,height-106,size,()->minecraft.setScreen(new JsonDocumentScreen(this,document,d->{document=d.deepCopy();status="";})));
  button(restoreConfirm?"Confirm Remove Override":hasOriginal?"Restore Original Recipe":"Remove Custom Recipe",left,height-80,size,()->{if(!restoreConfirm){restoreConfirm=true;status="Click again to remove only the Codex file. A backup is kept.";rebuildWidgets();}else send("restore");}).active=override;
  button("Save Recipe",left,height-32,size/2-3,()->{try{Identifier.parse(identifier);send("save");}catch(RuntimeException e){pending=false;status=e.getMessage();rebuildWidgets();}});
  button("Cancel / Back",left+size/2+3,height-32,size/2-3,this::cancel);
 }
 private String cookingName(String type){return switch(type){case "blasting"->"Blast furnace";case "smoking"->"Smoker";case "campfire_cooking"->"Campfire";default->"Furnace";};}
 private void numeric(String key,String label,int x,int y,int fallback){EditBox box=addRenderableWidget(new EditBox(font,x,y,size/2-8,20,Component.literal(label)));box.setValue(document.has(key)?document.get(key).getAsString():""+fallback);box.setMaxLength(9);box.setResponder(v->{try{if(key.equals("experience"))document.addProperty(key,Float.parseFloat(v));else document.addProperty(key,Integer.parseInt(v));}catch(NumberFormatException e){document.addProperty(key,v);}});box.setTooltip(Tooltip.create(Component.literal(label)));}
 private void ingredient(int slot){minecraft.setScreen(new IngredientScreen(slot));}
 private void cancel(){if(!loaded.equals(document.toString()))minecraft.setScreen(new ConfirmScreen(ok->{if(ok){document=null;listLoaded=false;status="";offset=0;}minecraft.setScreen(this);},Component.literal("Discard recipe changes?"),Component.literal("Changes have not been saved.")));else{document=null;listLoaded=false;status="";offset=0;rebuildWidgets();}}
 private ItemStack icon(JsonElement e){e=net.vg.codex.recipe.RecipeFormats.simpleIngredient(e);try{if(e!=null&&e.isJsonPrimitive()){String s=e.getAsString();if(s.startsWith("#")){var tag=net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,Identifier.parse(s.substring(1)));return BuiltInRegistries.ITEM.get(tag).flatMap(t->t.stream().findFirst()).map(h->new ItemStack(h.value())).orElse(new ItemStack(Items.PAPER));}return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(s)));}}catch(RuntimeException ignored){}return e==null?ItemStack.EMPTY:new ItemStack(Items.PAPER);}
 private String ingredientName(JsonElement e){e=net.vg.codex.recipe.RecipeFormats.simpleIngredient(e);if(e==null)return "Empty — click to select an ingredient";if(e.isJsonPrimitive()){String s=e.getAsString();return s.startsWith("#")?"Any "+ClientRewardPresentation.pretty(Identifier.parse(s.substring(1))):icon(e).getHoverName().getString();}return "Advanced ingredient — preserved. Click to replace, or edit JSON.";}
 @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float dt){
  g.fill(0,0,width,height,0xED202020);g.text(font,title,left,14,-1,false);super.extractRenderState(g,x,y,dt);
  if(document==null){for(int i=0;i<Math.min(rows,entries.size());i++){JsonObject e=entries.get(i).getAsJsonObject();if(e.has("icon"))g.fakeItem(icon(e.get("icon")),left+4,67+i*24);}}
  else if(RecipeDocuments.supported(document)){
   JsonElement[] slots=RecipeDocuments.slots(document);int n=RecipeDocuments.type(document).contains("crafting_")?9:1;
   for(int i=0;i<n;i++){int sx=left+12+(i%3)*30,sy=110+(i/3)*30;g.fakeItem(icon(slots[i]),sx+5,sy+2);if(x>=sx&&x<sx+26&&y>=sy&&y<sy+20)g.setTooltipForNextFrame(font,Component.literal(ingredientName(slots[i])),x,y);}
   if(document.has("result")&&document.get("result").isJsonObject()){ItemStack output=icon(document.getAsJsonObject("result").get("id"));g.fakeItem(output,left+140,150);g.text(font,font.plainSubstrByWidth(output.getHoverName().getString(),size-144),left+132,136,0xFFFFFFFF,false);g.text(font,"Count",left+264,154,0xFFCCCCCC,false);}
   g.text(font,RecipeDocuments.type(document).contains("shapeless")?"Any order; each occupied slot is one ingredient.":RecipeDocuments.type(document).contains("shaped")?"Crafting grid — click slots to choose items or tags.":"Input → output. Time is in ticks (20 ticks = 1 second).",left,94,0xFFCCCCAA,false);
   if(!RecipeDocuments.type(document).contains("crafting_")){g.text(font,"Experience",left,182,0xFFCCCCCC,false);g.text(font,"Cooking time (ticks)",left+size/2,182,0xFFCCCCCC,false);}
  }
  if(!status.isEmpty())g.text(font,font.plainSubstrByWidth(status,size),left,height-52,0xFFFFDD88,false);
 }
 @Override public void onClose(){if(pending)return;if(document!=null)cancel();else minecraft.setScreen(parent);}
 private final class IngredientScreen extends CodexScreen {
  final int slot;IngredientScreen(int slot){super(Component.literal("Recipe Ingredient"));this.slot=slot;}
  void set(JsonElement ingredient){JsonElement[] slots=RecipeDocuments.slots(document);slots[slot]=ingredient;try{RecipeDocuments.writeSlots(document,slots);}catch(IllegalArgumentException empty){if(RecipeDocuments.type(document).contains("shaped")){JsonArray p=new JsonArray();p.add("   ");p.add("   ");p.add("   ");document.add("pattern",p);document.add("key",new JsonObject());}else if(RecipeDocuments.type(document).contains("shapeless"))document.add("ingredients",new JsonArray());else document.remove("ingredient");}minecraft.setScreen(RecipeEditorScreen.this);}
  @Override protected void init(){int w=Math.min(360,width-24),x=(width-w)/2;buttonHere("Choose Item…",x,60,w,()->minecraft.setScreen(new ItemPickerScreen(this,id->set(new JsonPrimitive(id.toString())))));buttonHere("Choose Item Tag…",x,90,w,()->minecraft.setScreen(new IdentifierSelectionScreen(this,"Ingredient Tag",BuiltInRegistries.ITEM.listTagIds().map(net.minecraft.tags.TagKey::location).toList(),id->Component.literal(ClientRewardPresentation.pretty(id)),id->set(new JsonPrimitive("#"+id)))));buttonHere("Clear Slot",x,120,w,()->set(null));buttonHere("Back",x,height-32,w,()->minecraft.setScreen(RecipeEditorScreen.this));}
  void buttonHere(String label,int x,int y,int w,Runnable action){addRenderableWidget(Button.builder(Component.literal(label),b->action.run()).bounds(x,y,w,20).build());}
  @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float dt){g.fill(0,0,width,height,0xED202020);g.text(font,title,(width-Math.min(360,width-24))/2,24,-1,false);super.extractRenderState(g,x,y,dt);}
  @Override public void onClose(){minecraft.setScreen(RecipeEditorScreen.this);}
 }
}
