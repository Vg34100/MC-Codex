//? if <1.21 {
/*package net.vg.codex.compat.v120;
import com.google.gson.*;
import com.mojang.serialization.Codec;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
public final class LegacyItems {
 private LegacyItems(){}
 public static final Codec<ItemPredicate> PREDICATE_CODEC=LegacyAdvancements.jsonCodec(source->{
  JsonObject json=source.deepCopy();
  if(json.has("items")&&json.get("items").isJsonPrimitive()){
   String id=json.get("items").getAsString();
   if(id.startsWith("#")){json.remove("items");json.addProperty("tag",id.substring(1));}
   else {JsonArray ids=new JsonArray();ids.add(id);json.add("items",ids);}
  }
  Component expectedName=null;
  if(json.has("components") && json.getAsJsonObject("components").has("minecraft:custom_name")){
   JsonElement value=json.getAsJsonObject("components").get("minecraft:custom_name");expectedName=Component.Serializer.fromJson(value.isJsonPrimitive()?value.getAsString():value.toString());
   if(expectedName==null)throw new IllegalArgumentException("Invalid item name");
  }
  if(json.has("components")){
   CompoundTag nbt=json.has("nbt")?parse(json.get("nbt")):new CompoundTag();nbt.merge(components(json.remove("components").getAsJsonObject()));json.addProperty("nbt",nbt.toString());
  }
  ItemPredicate base=ItemPredicate.fromJson(json);Component name=expectedName;
  if(name==null)return base;
  return new ItemPredicate(){@Override public boolean matches(ItemStack stack){return base.matches(stack)&&stack.hasCustomHoverName()&&stack.getHoverName().equals(name);}@Override public JsonElement serializeToJson(){return source.deepCopy();}};
 },ItemPredicate::serializeToJson);
 private static CompoundTag parse(JsonElement value){
  try{if(value.isJsonPrimitive())return TagParser.parseTag(value.getAsString());
   Tag tag=com.mojang.serialization.JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE,value);if(tag instanceof CompoundTag compound)return compound;
   throw new IllegalArgumentException("Expected item NBT compound");
  }catch(com.mojang.brigadier.exceptions.CommandSyntaxException error){throw new IllegalArgumentException("Invalid item NBT",error);}
 }
 private static CompoundTag components(JsonObject values){
  CompoundTag out=values.has("minecraft:custom_data")?parse(values.get("minecraft:custom_data")):new CompoundTag();
  for(var entry:values.entrySet())switch(entry.getKey()){
   case "minecraft:custom_data" -> {}
   case "minecraft:custom_name" -> {}
   case "minecraft:potion_contents" -> {
    JsonObject potion=entry.getValue().getAsJsonObject();if(!java.util.Set.of("potion").containsAll(potion.keySet()))throw new IllegalArgumentException("Use native item NBT for additional potion effects in Minecraft 1.20.1.");
    if(potion.has("potion")){String id=potion.get("potion").getAsString();if(!net.minecraft.core.registries.BuiltInRegistries.POTION.containsKey(new net.minecraft.resources.ResourceLocation(id)))throw new IllegalArgumentException("Unknown potion: "+id);out.putString("Potion",id);}
   }
   case "minecraft:damage" -> out.putInt("Damage",entry.getValue().getAsInt());
   case "minecraft:repair_cost" -> out.putInt("RepairCost",entry.getValue().getAsInt());
   case "minecraft:custom_model_data" -> out.putInt("CustomModelData",entry.getValue().getAsInt());
   case "minecraft:unbreakable" -> out.putBoolean("Unbreakable",entry.getValue().isJsonObject()||entry.getValue().getAsBoolean());
   default -> throw new IllegalArgumentException("This item property is unavailable in Minecraft 1.20.1: "+entry.getKey());
  }
  return out;
 }
 public static JsonObject heldProperties(ItemStack stack){
  JsonObject out=new JsonObject();if(!stack.hasTag())return out;CompoundTag tag=stack.getTag().copy();
  CompoundTag display=tag.getCompound("display");if(display.contains("Name")){out.addProperty("minecraft:custom_name",display.getString("Name"));display.remove("Name");if(display.isEmpty())tag.remove("display");else tag.put("display",display);}
  for(var field:java.util.Map.of("Damage","minecraft:damage","RepairCost","minecraft:repair_cost","CustomModelData","minecraft:custom_model_data").entrySet())if(tag.contains(field.getKey())){out.addProperty(field.getValue(),tag.getInt(field.getKey()));tag.remove(field.getKey());}
  if(tag.contains("Unbreakable")){out.addProperty("minecraft:unbreakable",tag.getBoolean("Unbreakable"));tag.remove("Unbreakable");}
  if(tag.contains("Potion")){JsonObject potion=new JsonObject();potion.addProperty("potion",tag.getString("Potion"));out.add("minecraft:potion_contents",potion);tag.remove("Potion");}
  if(!tag.isEmpty())out.addProperty("minecraft:custom_data",tag.toString());return out;
 }
}
*///?}
