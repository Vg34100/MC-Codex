package net.vg.codex.advancement;

import com.google.gson.*;

/** Native item-component representation at the codec boundary; stored authoring data is untouched. */
public final class ItemFormats {
    private ItemFormats() { }
    public static JsonObject nativeComponents(JsonObject source) {
        JsonObject result=source.deepCopy();
        //? if <26.1 {
        /*for(String key:java.util.List.of("minecraft:custom_name","minecraft:item_name")) {
            if(result.has(key) && result.get(key).isJsonPrimitive()) {
                String name=result.get(key).getAsString();boolean serialized=false;
                try { JsonElement parsed=JsonParser.parseString(name);serialized=parsed.isJsonObject()||parsed.isJsonArray()||parsed.isJsonPrimitive()&&parsed.getAsJsonPrimitive().isString(); }
                catch(RuntimeException ignored) { }
                if(!serialized)result.addProperty(key,new JsonPrimitive(name).toString());
            }
        }
        *///?}
        return result;
    }
}
