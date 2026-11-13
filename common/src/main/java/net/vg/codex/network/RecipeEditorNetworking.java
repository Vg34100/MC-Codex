package net.vg.codex.network;

import com.google.gson.*;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.commands.Commands;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.vg.codex.advancement.*;
import net.vg.codex.recipe.RecipeDocuments;
import java.nio.file.*;
import java.util.*;

/** Operator-only document editing. Vanilla codecs and the server own validation/reloading. */
public final class RecipeEditorNetworking {
 private RecipeEditorNetworking(){}
 private static final Set<MinecraftServer> BUSY=Collections.newSetFromMap(new WeakHashMap<>());
 public static void register(){
  if(Platform.getEnvironment()==Env.SERVER)NetworkManager.registerS2CPayloadType(RecipeEditorResponse.TYPE,RecipeEditorResponse.STREAM_CODEC);
  NetworkManager.registerReceiver(NetworkManager.c2s(),RecipeEditorRequest.TYPE,RecipeEditorRequest.STREAM_CODEC,(payload,context)->context.queue(()->{
   if(!(context.getPlayer() instanceof ServerPlayer p)||!Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(p.createCommandSourceStack()))return;
   JsonObject req;
   try{req=JsonParser.parseString(payload.json()).getAsJsonObject();handle(p,req);}
   catch(Exception e){JsonObject out=new JsonObject();out.addProperty("error",e.getMessage()==null?"Invalid recipe request":e.getMessage());send(p,out);}
  }));
 }
 private static void send(ServerPlayer p,JsonObject out){String json=out.toString();if(json.length()>200000){out=new JsonObject();out.addProperty("error","Recipe is too large for this editor");json=out.toString();}NetworkManager.sendToPlayer(p,new RecipeEditorResponse(json));}
 private static Identifier resource(Identifier id){return Identifier.fromNamespaceAndPath(id.getNamespace(),RecipeDocuments.DIRECTORY+"/"+id.getPath()+".json");}
 public static JsonObject read(MinecraftServer server,Identifier id,boolean original) throws java.io.IOException{
  Path file=RecipeDocuments.file(AdvancementPackSync.externalPackDir(),id);
  if(!original&&Files.isRegularFile(file))return net.vg.codex.recipe.RecipeFormats.authoringView(JsonParser.parseString(Files.readString(file)).getAsJsonObject());
  var stack=server.getResourceManager().getResourceStack(resource(id));
  for(int i=stack.size()-1;i>=0;i--){var r=stack.get(i);if(r.sourcePackId().contains(AdvancementPackSync.PACK_ID))continue;try(var reader=r.openAsReader()){return net.vg.codex.recipe.RecipeFormats.authoringView(JsonParser.parseReader(reader).getAsJsonObject());}}
  return null;
 }
 public static void validate(MinecraftServer s,JsonObject doc){if(doc.has("cookingtime")&&(doc.get("cookingtime").getAsInt()<1||doc.get("cookingtime").getAsInt()>72000))throw new IllegalArgumentException("Cooking time must be 1–72000 ticks");if(doc.has("experience")){float xp=doc.get("experience").getAsFloat();if(!Float.isFinite(xp)||xp<0||xp>1000)throw new IllegalArgumentException("Experience must be 0–1000");}
  JsonObject nativeDocument=net.vg.codex.recipe.RecipeFormats.nativeDefinition(doc);
  //? if <1.21 {
  /*net.vg.codex.compat.v120.LegacyRecipes.parse(nativeDocument).getOrThrow();
  *///?} else {
  Recipe.CODEC.parse(RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,s.registryAccess()),nativeDocument).getOrThrow();
  //?}
 }

 private static void open(ServerPlayer p,Identifier id,String status) throws java.io.IOException{
  JsonObject out=new JsonObject();JsonObject doc=read(p.level().getServer(),id,false);out.addProperty("id",id.toString());if(doc!=null)out.add("document",doc);out.addProperty("hash",RecipeDocuments.hash(doc));out.addProperty("override",Files.isRegularFile(RecipeDocuments.file(AdvancementPackSync.externalPackDir(),id)));out.addProperty("has_original",read(p.level().getServer(),id,true)!=null);out.addProperty("status",status);send(p,out);
 }
 private static void handle(ServerPlayer p,JsonObject req) throws Exception{
  String action=req.get("action").getAsString();MinecraftServer server=p.level().getServer();
  if(action.equals("list")){
   String query=req.has("query")?req.get("query").getAsString().toLowerCase(Locale.ROOT).replace(' ','_'):"";if(query.length()>128)throw new IllegalArgumentException("Search is too long");
   int limit=req.has("limit")?req.get("limit").getAsInt():12;if(limit<1||limit>12)throw new IllegalArgumentException("Invalid page size");
   int offset=req.has("offset")?req.get("offset").getAsInt():0;if(offset<0||offset>100000)throw new IllegalArgumentException("Invalid page");
   var resources=server.getResourceManager().listResources(RecipeDocuments.DIRECTORY,id->id.getPath().endsWith(".json"));
   var ids=resources.keySet().stream().map(id->Identifier.fromNamespaceAndPath(id.getNamespace(),id.getPath().substring(RecipeDocuments.DIRECTORY.length()+1,id.getPath().length()-5))).filter(id->id.toString().contains(query)).sorted().toList();
   JsonObject out=new JsonObject();JsonArray entries=new JsonArray();for(int i=offset;i<Math.min(ids.size(),offset+limit);i++){Identifier id=ids.get(i);JsonObject e=new JsonObject();e.addProperty("id",id.toString());e.addProperty("override",Files.isRegularFile(RecipeDocuments.file(AdvancementPackSync.externalPackDir(),id)));try{JsonObject d=read(server,id,false);if(d.has("result")&&d.get("result").isJsonObject()&&d.getAsJsonObject("result").has("id"))e.addProperty("icon",d.getAsJsonObject("result").get("id").getAsString());}catch(RuntimeException ignored){}entries.add(e);}out.add("entries",entries);out.addProperty("total",ids.size());out.addProperty("query",query);out.addProperty("offset",offset);send(p,out);return;
  }
  Identifier id=Identifier.parse(req.get("id").getAsString());Path file=RecipeDocuments.file(AdvancementPackSync.externalPackDir(),id);
  if(action.equals("open")){open(p,id,"");return;}
  if(!action.equals("save")&&!action.equals("restore"))throw new IllegalArgumentException("Unknown recipe action");
  if(BUSY.contains(server))throw new IllegalArgumentException("A recipe reload is still running. Try again after it finishes.");
  JsonObject current=read(server,id,false);if(!req.has("hash")||!RecipeDocuments.hash(current).equals(req.get("hash").getAsString()))throw new IllegalArgumentException("Recipe changed since opening. Reopen it before saving.");
  if(action.equals("save")&&(!req.has("document")||!req.get("document").isJsonObject()))throw new IllegalArgumentException("Save requires a recipe document");
  JsonObject next=action.equals("save")?net.vg.codex.recipe.RecipeFormats.nativeDefinition(req.getAsJsonObject("document")):null;
  if(next!=null)validate(server,next);
  if(next==null&&!Files.isRegularFile(file))throw new IllegalArgumentException("There is no Codex override to restore");
  byte[] before=Files.isRegularFile(file)?Files.readAllBytes(file):null;
  if(next!=null)AtomicJson.write(file,next);else{Files.copy(file,file.resolveSibling(file.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING);Files.delete(file);}
  BUSY.add(server);
  AdvancementPackSync.reloadAfterEdit(server,()->{BUSY.remove(server);AdvancementPackSync.sendRecipeIdsTo(p);try{open(p,id,next==null?"Override removed; original recipe restored when available.":"Recipe saved and reloaded.");}catch(Exception e){JsonObject out=new JsonObject();out.addProperty("error",e.getMessage());send(p,out);}},()->{
   try{if(before==null)Files.deleteIfExists(file);else{Files.createDirectories(file.getParent());Files.write(file,before);}AdvancementPackSync.reloadAfterEdit(server,()->BUSY.remove(server),()->BUSY.remove(server));}catch(Exception ignored){BUSY.remove(server);}
   JsonObject out=new JsonObject();out.addProperty("error","Recipe reload failed. Previous file restored; check the server log.");send(p,out);
  });
 }
}
