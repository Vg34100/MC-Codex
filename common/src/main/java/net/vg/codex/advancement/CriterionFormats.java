package net.vg.codex.advancement;

import com.google.gson.*;
import java.util.List;

/** Version adapters for Codex-generated criteria and read-only visual-editor inspection. */
public final class CriterionFormats {
    private CriterionFormats() { }
    private static final boolean OBJECT_CONDITIONS =
        //? if >=26.3 {
        /*true;
        *///?} else {
        false;
        //?}

    public static JsonObject generated(JsonObject criterion) {
        //? if >=26.2 {
        /*return convert(criterion,true);
        *///?} else {
        return criterion;
        //?}
    }
    public static JsonObject authoringView(JsonObject criterion) {
        //? if >=26.2 {
        /*return convert(criterion,false);
        *///?} else {
        return criterion;
        //?}
    }
    private static JsonObject convert(JsonObject criterion,boolean modern) {
        JsonObject out=criterion.deepCopy();if(!out.has("conditions") || !out.get("conditions").isJsonObject())return out;
        JsonObject conditions=out.getAsJsonObject("conditions");
        for(String field:List.of("player","entity","location")) {
            JsonElement value=conditions.get(field);if(value==null)continue;
            if(modern && value.isJsonArray() && !value.getAsJsonArray().isEmpty() && value.getAsJsonArray().get(0).isJsonObject() && value.getAsJsonArray().get(0).getAsJsonObject().has("condition")) {
                JsonArray terms=new JsonArray();for(JsonElement term:value.getAsJsonArray())terms.add(condition(term.getAsJsonObject(),true));
                if(!OBJECT_CONDITIONS)conditions.add(field,terms);
                else if(terms.size()==1)conditions.add(field,terms.get(0));
                else { JsonObject all=new JsonObject();all.addProperty("type","minecraft:all_of");all.add("terms",terms);conditions.add(field,all); }
            } else if(!modern && !OBJECT_CONDITIONS && value.isJsonArray()) {
                JsonArray terms=new JsonArray();for(JsonElement term:value.getAsJsonArray())terms.add(condition(term.getAsJsonObject(),false));conditions.add(field,terms);
            } else if(!modern && value.isJsonObject() && value.getAsJsonObject().has("type")) {
                JsonObject predicate=value.getAsJsonObject();JsonArray terms=new JsonArray();
                if(predicate.get("type").getAsString().equals("minecraft:all_of") && predicate.has("terms"))
                    for(JsonElement term:predicate.getAsJsonArray("terms"))terms.add(condition(term.getAsJsonObject(),false));
                else terms.add(condition(predicate,false));
                conditions.add(field,terms);
            }
        }
        if(OBJECT_CONDITIONS && out.has("trigger") && List.of("minecraft:recipe_unlocked","minecraft:recipe_crafted").contains(out.get("trigger").getAsString())) {
            String classic=out.get("trigger").getAsString().equals("minecraft:recipe_crafted")?"recipe_id":"recipe";
            String from=modern?classic:"recipes",to=modern?"recipes":classic;
            if(conditions.has(from) && conditions.get(from).isJsonPrimitive())conditions.add(to,conditions.remove(from));
        }
        return out;
    }
    private static JsonObject condition(JsonObject input,boolean modern) {
        JsonObject out=input.deepCopy();String from=modern || !OBJECT_CONDITIONS?"condition":"type",to=modern && OBJECT_CONDITIONS?"type":"condition";
        if(!out.has(from))return out;
        String kind=out.get(from).getAsString();out.add(to,out.remove(from));
        if(kind.equals("minecraft:entity_properties") && out.has("predicate") && out.get("predicate").isJsonObject()) {
            JsonObject entity=out.getAsJsonObject("predicate");
            for(String field:List.of("type","location")) {
                String mapped="minecraft:"+(field.equals("type")?"entity_type":field);
                String a=modern?field:mapped,b=modern?mapped:field;
                if(entity.has(a))entity.add(b,entity.remove(a));
            }
        }
        return out;
    }
}
