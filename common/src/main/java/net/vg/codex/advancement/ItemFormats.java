package net.vg.codex.advancement;

import com.google.gson.*;

/** Native item-component representation at the codec boundary; stored authoring data is untouched. */
public final class ItemFormats {
    private ItemFormats() { }
    public static JsonObject nativeStack(JsonObject source) {
        JsonObject result=source.deepCopy();if(result.has("components"))result.add("components",nativeComponents(result.getAsJsonObject("components")));
        //? if <1.21 {
        /*return net.vg.codex.compat.v120.LegacyItems.nativeStack(result);
        *///?} else {
        return result;
        //?}
    }
    public static JsonObject nativeComponents(JsonObject source) {
        JsonObject result=source.deepCopy();
        //? if <26.1 {
        /*for(String key:java.util.List.of("minecraft:custom_name","minecraft:item_name")) {
            if(result.has(key) && !result.get(key).isJsonPrimitive())result.addProperty(key,result.get(key).toString());
            else if(result.has(key) && result.get(key).isJsonPrimitive()) {
                String name=result.get(key).getAsString();boolean serialized=false;
                try { String trimmed=name.stripLeading();if(trimmed.startsWith("{")||trimmed.startsWith("[")||trimmed.startsWith("\"")){JsonElement parsed=JsonParser.parseString(name);serialized=parsed.isJsonObject()||parsed.isJsonArray()||parsed.isJsonPrimitive()&&parsed.getAsJsonPrimitive().isString();} }
                catch(RuntimeException ignored) { }
                if(!serialized)result.addProperty(key,new JsonPrimitive(name).toString());
            }
        }
        *///?}
        return result;
    }
    public static String displayName(JsonElement source) {
        JsonElement text=source;
        //? if <26.1 {
        /*if(source.isJsonPrimitive()) {
            String value=source.getAsString().stripLeading();
            if(value.startsWith("{")||value.startsWith("[")||value.startsWith("\""))try{text=JsonParser.parseString(value);}catch(RuntimeException ignored){}
        }
        *///?}
        return net.minecraft.network.chat.ComponentSerialization.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,text).result()
                .map(net.minecraft.network.chat.Component::getString).orElse(source.toString());
    }
}
