package net.vg.codex.recipe;

import com.google.gson.*;

/** Version-specific native recipe syntax at the file boundary, retaining unrelated fields. */
public final class RecipeFormats {
    private RecipeFormats() { }
    public static JsonElement simpleIngredient(JsonElement value) {
        if(value!=null && value.isJsonObject() && value.getAsJsonObject().size()==1) {
            JsonObject object=value.getAsJsonObject();
            if(object.has("item"))return object.get("item");
            if(object.has("tag"))return new JsonPrimitive("#"+object.get("tag").getAsString());
        }
        return value;
    }
    private static boolean known(JsonObject document) {
        return java.util.Set.of("minecraft:crafting_shaped","minecraft:crafting_shapeless","minecraft:smelting","minecraft:blasting","minecraft:smoking","minecraft:campfire_cooking").contains(RecipeDocuments.type(document));
    }
    public static JsonObject authoringView(JsonObject source) {
        JsonObject result=source.deepCopy();
        //? if <1.21 {
        /*if(known(result) && result.has("result")) {
            JsonElement output=result.get("result");
            if(output.isJsonPrimitive()) { JsonObject value=new JsonObject();value.add("id",output);value.addProperty("count",1);result.add("result",value); }
            else if(output.isJsonObject() && output.getAsJsonObject().has("item") && !output.getAsJsonObject().has("id")) { JsonObject value=output.getAsJsonObject();value.add("id",value.remove("item")); }
        }
        *///?}
        return result;
    }
    private static JsonElement nativeIngredient(JsonElement value) {
        if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String id=value.getAsString();JsonObject ingredient=new JsonObject();
            ingredient.addProperty(id.startsWith("#")?"tag":"item",id.startsWith("#")?id.substring(1):id);return ingredient;
        }
        return value.deepCopy();
    }
    public static JsonObject nativeDefinition(JsonObject source) {
        JsonObject result=source.deepCopy();
        //? if <26.1 {
        /*if(!known(result))return result;
        if(!RecipeDocuments.type(result).startsWith("minecraft:crafting_") && result.has("result") && result.get("result").isJsonObject()) {
            JsonObject output=result.getAsJsonObject("result");
            if(output.has("count") && output.get("count").getAsInt()!=1)throw new IllegalArgumentException("This Minecraft version produces one item per cooking operation.");
        }
        if(result.has("key") && result.get("key").isJsonObject()) {
            for(var entry:result.getAsJsonObject("key").entrySet())entry.setValue(nativeIngredient(entry.getValue()));
        }
        if(result.has("ingredients") && result.get("ingredients").isJsonArray()) {
            JsonArray ingredients=new JsonArray();for(JsonElement entry:result.getAsJsonArray("ingredients"))ingredients.add(nativeIngredient(entry));result.add("ingredients",ingredients);
        }
        if(result.has("ingredient"))result.add("ingredient",nativeIngredient(result.get("ingredient")));
        *///?}
        //? if <1.21 {
        /*if(known(result) && result.has("result") && result.get("result").isJsonObject()) {
            JsonObject output=result.getAsJsonObject("result");
            if(RecipeDocuments.type(result).startsWith("minecraft:crafting_")) { if(output.has("id") && !output.has("item"))output.add("item",output.remove("id")); }
            else {
                if(!java.util.Set.of("id","count").containsAll(output.keySet()))throw new IllegalArgumentException("Minecraft 1.20.1 cooking outputs cannot represent these item properties.");
                if(output.has("count") && output.get("count").getAsInt()!=1)throw new IllegalArgumentException("Minecraft 1.20.1 cooking recipes produce one item per operation.");
                if(output.has("id"))result.add("result",output.get("id"));
            }
        }
        *///?}
        return result;
    }
}
