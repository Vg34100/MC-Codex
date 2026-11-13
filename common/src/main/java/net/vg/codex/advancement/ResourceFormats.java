package net.vg.codex.advancement;

import com.google.gson.*;

/** Native datapack names and supported display fields; no player-state or metadata migration. */
public final class ResourceFormats {
    private ResourceFormats() { }
    public static String directory(String kind) {
        //? if <1.21 {
        /*return switch(kind) {case "advancement"->"advancements";case "loot_table"->"loot_tables";case "recipe"->"recipes";case "function"->"functions";default->kind;};
        *///?} else {
        return kind;
        //?}
    }
    public static String relative(String path) {
        //? if <1.21 {
        /*if(path.startsWith("data/"))return path.replace("/advancement/","/advancements/").replace("/loot_table/","/loot_tables/").replace("/recipe/","/recipes/").replace("/function/","/functions/");
        *///?}
        return path;
    }
    public static JsonObject display(JsonObject source) {
        JsonObject result=source.deepCopy();
        //? if <1.21 {
        /*if(result.has("icon") && result.get("icon").isJsonObject()) {
            JsonObject icon=result.getAsJsonObject("icon");if(icon.has("id"))icon.add("item",icon.get("id").deepCopy());
        }
        *///?}
        return result;
    }
    public static JsonObject advancement(JsonObject source) {
        JsonObject result=source.deepCopy();
        if(result.has("display") && result.get("display").isJsonObject())result.add("display",display(result.getAsJsonObject("display")));
        return result;
    }
    public static JsonObject legacyCriterion(JsonObject source,boolean forward) {
        JsonObject result=source.deepCopy();
        if(result.has("conditions"))legacyPredicates(result.get("conditions"),forward);
        return result;
    }
    private static void legacyPredicates(JsonElement value,boolean forward) {
        if(value.isJsonArray()) {for(JsonElement entry:value.getAsJsonArray())legacyPredicates(entry,forward);return;}
        if(!value.isJsonObject())return;
        JsonObject object=value.getAsJsonObject();
        if(forward && object.has("items") && object.get("items").isJsonPrimitive()) {
            String id=object.get("items").getAsString();
            if(id.startsWith("#")){object.remove("items");object.addProperty("tag",id.substring(1));}
            else {JsonArray items=new JsonArray();items.add(id);object.add("items",items);}
        } else if(!forward) {
            if(object.has("tag") && object.get("tag").isJsonPrimitive() && !object.has("items"))object.addProperty("items","#"+object.remove("tag").getAsString());
            else if(object.has("items") && object.get("items").isJsonArray()) {
                JsonArray items=object.getAsJsonArray("items");if(items.size()==1 && items.get(0).isJsonPrimitive())object.add("items",items.get(0).deepCopy());
            }
        }
        for(var entry:object.entrySet())legacyPredicates(entry.getValue(),forward);
    }
}
