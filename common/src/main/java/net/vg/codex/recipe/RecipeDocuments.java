package net.vg.codex.recipe;

import com.google.gson.*;
import net.minecraft.resources.Identifier;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Lossless source documents; only explicitly edited visual fields are replaced. */
public final class RecipeDocuments {
    private RecipeDocuments() {}
    public static Path file(Path pack, Identifier id) {
        if (id.getPath().contains("..") || id.getNamespace().equals(".") || id.getNamespace().equals("..")) throw new IllegalArgumentException("Invalid recipe identifier");
        Path root=pack.toAbsolutePath().normalize();
        Path result=root.resolve("data/"+id.getNamespace()+"/recipe/"+id.getPath()+".json").normalize();
        if(!result.startsWith(root))throw new IllegalArgumentException("Invalid recipe path");
        return result;
    }
    public static String hash(JsonObject document) {
        if(document==null)return "-";
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(document.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static JsonObject blank(String type) {
        JsonObject d=new JsonObject();d.addProperty("type","minecraft:"+type);d.addProperty("category","misc");
        JsonObject out=new JsonObject();out.addProperty("id","minecraft:paper");out.addProperty("count",1);d.add("result",out);
        if(type.equals("crafting_shaped")){JsonArray p=new JsonArray();p.add("A");d.add("pattern",p);JsonObject k=new JsonObject();k.addProperty("A","minecraft:stick");d.add("key",k);}
        else if(type.equals("crafting_shapeless")){JsonArray a=new JsonArray();a.add("minecraft:stick");d.add("ingredients",a);}
        else {d.addProperty("ingredient","minecraft:stick");d.addProperty("experience",0);d.addProperty("cookingtime",type.equals("campfire_cooking")?600:type.equals("smelting")?200:100);}
        return d;
    }
    public static String type(JsonObject d){return d.has("type")&&d.get("type").isJsonPrimitive()?d.get("type").getAsString():"";}
    public static boolean supported(JsonObject d){boolean known=java.util.Set.of("minecraft:crafting_shaped","minecraft:crafting_shapeless","minecraft:smelting","minecraft:blasting","minecraft:smoking","minecraft:campfire_cooking").contains(type(d));if(!known)return false;try{slots(d);return d.has("result")&&d.get("result").isJsonObject()&&d.getAsJsonObject("result").has("id");}catch(RuntimeException invalid){return false;}}
    public static JsonElement[] slots(JsonObject d) {
        JsonElement[] slots=new JsonElement[9];
        if(type(d).equals("minecraft:crafting_shaped")){
            JsonArray pattern=d.getAsJsonArray("pattern");JsonObject key=d.getAsJsonObject("key");
            for(int y=0;y<Math.min(3,pattern.size());y++){String line=pattern.get(y).getAsString();for(int x=0;x<Math.min(3,line.length());x++){char c=line.charAt(x);if(c!=' ')slots[y*3+x]=key.get(String.valueOf(c)).deepCopy();}}
        }else if(type(d).equals("minecraft:crafting_shapeless")){JsonArray a=d.getAsJsonArray("ingredients");for(int i=0;i<Math.min(9,a.size());i++)slots[i]=a.get(i).deepCopy();}
        else if(d.has("ingredient"))slots[0]=d.get("ingredient").deepCopy();
        return slots;
    }
    public static void writeSlots(JsonObject d,JsonElement[] slots) {
        if(type(d).equals("minecraft:crafting_shaped")){
            JsonArray pattern=new JsonArray();JsonObject key=new JsonObject();
            for(int y=0;y<3;y++){StringBuilder line=new StringBuilder();for(int x=0;x<3;x++){int i=y*3+x;char c=(char)('A'+i);if(slots[i]==null)line.append(' ');else{line.append(c);key.add(String.valueOf(c),slots[i].deepCopy());}}pattern.add(line.toString());}
            if(key.isEmpty())throw new IllegalArgumentException("Add at least one ingredient");d.add("pattern",pattern);d.add("key",key);
        }else if(type(d).equals("minecraft:crafting_shapeless")){JsonArray a=new JsonArray();for(JsonElement e:slots)if(e!=null)a.add(e.deepCopy());if(a.isEmpty())throw new IllegalArgumentException("Add at least one ingredient");d.add("ingredients",a);}
        else{if(slots[0]==null)throw new IllegalArgumentException("Choose an ingredient");d.add("ingredient",slots[0].deepCopy());}
    }
}
