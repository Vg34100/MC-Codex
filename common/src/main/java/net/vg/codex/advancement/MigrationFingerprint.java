package net.vg.codex.advancement;

import com.google.gson.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.nio.file.*;
import java.util.HexFormat;

/** Operator editor metadata only. Never stored in the vanilla advancement resource. */
public final class MigrationFingerprint {
    private MigrationFingerprint(){}
    public static String hash(Path file){
        try{return Files.exists(file)?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))):"-";}
        catch(java.io.IOException|java.security.NoSuchAlgorithmException error){throw new IllegalStateException("Cannot fingerprint migration source",error);}
    }
    public static JsonObject snapshot(Path pack,ResourceManager resources,Identifier id,String rulesHash,String rewardsHash,JsonObject wire){
        JsonObject meta=new JsonObject();meta.addProperty("source_hash",hash(AdvancementPackIO.safeResourceFile(pack,id,"advancement")));meta.addProperty("rules_hash",rulesHash);meta.addProperty("rewards_hash",rewardsHash);
        meta.addProperty("generated_hash",hash(AdvancementPackIO.safeResourceFile(pack,AdvancementPackIO.generatedLootTableId(id),"loot_table")));
        meta.addProperty("has_fallback",resources.getResourceStack(Identifier.fromNamespaceAndPath(id.getNamespace(),"advancement/"+id.getPath()+".json")).size()>1);
        JsonArray ids=wire.has("rewards")&&wire.getAsJsonObject("rewards").has("codex_ids")?wire.getAsJsonObject("rewards").getAsJsonArray("codex_ids").deepCopy():new JsonArray();meta.add("reward_ids",ids);meta.addProperty("reward_count",ids.size());return meta;
    }
}
