package net.vg.codex.validation;
import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.vg.codex.recipe.RecipeDocuments;
import net.vg.codex.network.RecipeEditorNetworking;
import java.util.*;
import java.util.function.BiConsumer;

final class RecipeScenarios {
 @SuppressWarnings("unchecked") static void run(MinecraftServer server,BiConsumer<String,Boolean> check) throws Exception {
  var ops=RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,server.registryAccess());
  JsonObject shaped=RecipeDocuments.blank("crafting_shaped");JsonElement[] slots=new JsonElement[9];slots[0]=new JsonPrimitive("#minecraft:logs");slots[1]=new JsonPrimitive("minecraft:diamond");slots[4]=new JsonPrimitive("minecraft:stick");RecipeDocuments.writeSlots(shaped,slots);
  shaped.addProperty("unknown_mod_field","preserve me");shaped.getAsJsonObject("result").addProperty("id","minecraft:ender_eye");shaped.getAsJsonObject("result").addProperty("count",3);
  RecipeEditorNetworking.validate(server,shaped);
  Recipe<CraftingInput> recipe=(Recipe<CraftingInput>)(Recipe<?>)Recipe.CODEC.parse(ops,net.vg.codex.recipe.RecipeFormats.nativeDefinition(shaped)).getOrThrow();
  var input=CraftingInput.of(2,2,List.of(new ItemStack(Items.OAK_LOG),new ItemStack(Items.DIAMOND),ItemStack.EMPTY,new ItemStack(Items.STICK)));
  check.accept("Shaped recipe matches exact grid with a tag ingredient",recipe.matches(input,server.overworld()));
  check.accept("Crafting yields configured item and count",recipe.assemble(input).is(Items.ENDER_EYE)&&recipe.assemble(input).getCount()==3);
  check.accept("Wrong arrangement does not match shaped recipe",!recipe.matches(CraftingInput.of(2,2,List.of(new ItemStack(Items.DIAMOND),new ItemStack(Items.OAK_LOG),ItemStack.EMPTY,new ItemStack(Items.STICK))),server.overworld()));
  RecipeDocuments.writeSlots(shaped,RecipeDocuments.slots(shaped));check.accept("Visual ingredient edits preserve unknown fields",shaped.get("unknown_mod_field").getAsString().equals("preserve me"));
  JsonObject shapeless=RecipeDocuments.blank("crafting_shapeless");JsonElement[] unordered=new JsonElement[9];unordered[0]=new JsonPrimitive("minecraft:diamond");unordered[1]=new JsonPrimitive("minecraft:stick");RecipeDocuments.writeSlots(shapeless,unordered);RecipeEditorNetworking.validate(server,shapeless);
  Recipe<CraftingInput> free=(Recipe<CraftingInput>)(Recipe<?>)Recipe.CODEC.parse(ops,net.vg.codex.recipe.RecipeFormats.nativeDefinition(shapeless)).getOrThrow();check.accept("Shapeless ingredients match in either order",free.matches(CraftingInput.of(2,1,List.of(new ItemStack(Items.STICK),new ItemStack(Items.DIAMOND))),server.overworld()));
  for(String type:List.of("smelting","blasting","smoking","campfire_cooking")){
   JsonObject cooking=RecipeDocuments.blank(type);cooking.addProperty("ingredient","minecraft:cobblestone");cooking.getAsJsonObject("result").addProperty("id","minecraft:diamond");cooking.getAsJsonObject("result").addProperty("count",2);RecipeEditorNetworking.validate(server,cooking);
   Recipe<SingleRecipeInput> r=(Recipe<SingleRecipeInput>)(Recipe<?>)Recipe.CODEC.parse(ops,net.vg.codex.recipe.RecipeFormats.nativeDefinition(cooking)).getOrThrow();var in=new SingleRecipeInput(new ItemStack(Items.COBBLESTONE));check.accept(type+" matches input and produces configured output",r.matches(in,server.overworld())&&r.assemble(in).is(Items.DIAMOND)&&r.assemble(in).getCount()==2);
  }
  JsonObject invalid=shaped.deepCopy();invalid.getAsJsonObject("result").addProperty("id","missing:invalid_item");boolean rejected=false;try{RecipeEditorNetworking.validate(server,invalid);}catch(RuntimeException e){rejected=true;}check.accept("Unknown output item rejected before write",rejected);
  invalid=RecipeDocuments.blank("smelting");invalid.addProperty("cookingtime",-1);rejected=false;try{RecipeEditorNetworking.validate(server,invalid);}catch(RuntimeException e){rejected=true;}check.accept("Negative cooking time rejected",rejected);
  invalid=shaped.deepCopy();invalid.remove("pattern");check.accept("Malformed advanced JSON falls back without visual crash",!RecipeDocuments.supported(invalid));
  invalid=shaped.deepCopy();invalid.add("type",new JsonObject());check.accept("Malformed recipe type falls back to advanced JSON",!RecipeDocuments.supported(invalid));
  var packet=new net.vg.codex.network.RecipeEditorRequest(shaped.toString());
  var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),server.registryAccess());
  try {net.vg.codex.network.RecipeEditorRequest.STREAM_CODEC.encode(buffer,packet);check.accept("Recipe request codec preserves the full document",net.vg.codex.network.RecipeEditorRequest.STREAM_CODEC.decode(buffer).equals(packet));}finally{buffer.release();}
  buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),server.registryAccess());rejected=false;
  try{net.vg.codex.network.RecipeEditorRequest.STREAM_CODEC.encode(buffer,new net.vg.codex.network.RecipeEditorRequest("x".repeat(65537)));}catch(RuntimeException e){rejected=true;}finally{buffer.release();}
  check.accept("Oversized recipe packets are rejected",rejected);
  rejected=false;try{RecipeDocuments.file(java.nio.file.Path.of("test"),Identifier.parse("codex:../../escape"));}catch(RuntimeException e){rejected=true;}check.accept("Unsafe recipe path rejected",rejected);
  var nativeEye=RecipeEditorNetworking.read(server,Identifier.parse("minecraft:ender_eye"),true);check.accept("Original vanilla recipe available for restore",nativeEye!=null&&nativeEye.get("type").getAsString().equals("minecraft:crafting_shapeless"));
  check.accept("Recipe version detects changed output",!RecipeDocuments.hash(shaped).equals(RecipeDocuments.hash(shapeless)));
 }
}
