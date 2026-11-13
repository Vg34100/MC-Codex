package net.vg.codex.advancement;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import java.nio.file.*;

/** Recipe unlocks are idempotent. Arbitrary functions are reserved, never blindly replayed. */
public final class RewardEffects {
    private RewardEffects() { }
    private static Path file(ServerPlayer p) { return QuestState.directory(p.level().getServer()).resolve("codex/reward_effects/" + p.getUUID() + ".json"); }
    private static JsonArray read(ServerPlayer p) {
        try { return Files.exists(file(p)) ? JsonParser.parseString(Files.readString(file(p))).getAsJsonArray() : new JsonArray(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot read reward effects", e); }
    }
    public static void plan(ServerPlayer p, QuestJournal.Plan transaction, JsonObject entry) {
        JsonArray effects = read(p); JsonObject effect = entry.deepCopy(); effect.addProperty("status", "pending"); effects.add(effect);
        transaction.update(file(p), effects);
    }
    public static void deliver(ServerPlayer p) {
        QuestJournal.recover(p.level().getServer()); JsonArray effects = read(p);
        for (JsonElement e : effects) {
            JsonObject effect = e.getAsJsonObject(); String status = effect.get("status").getAsString();
            if (!status.equals("pending")) continue;
            boolean function = effect.get("type").getAsString().equals("FUNCTION");
            if (function) { effect.addProperty("status", "running"); AtomicJson.write(file(p), effects); }
            JsonObject vanilla = new JsonObject();
            if (function) vanilla.add("function", effect.get("id"));
            else { JsonArray recipes = new JsonArray(); recipes.add(effect.get("id")); vanilla.add("recipes", recipes); }
            try {
                AdvancementRewards.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, AdvancementValidation.registries(p.level().getServer())), vanilla).getOrThrow().grant(p);
                p.level().getServer().getPlayerList().saveAll();
                effect.addProperty("status", "done"); AtomicJson.write(file(p), effects);
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(RewardEffects.class).error("Reward effect needs operator review: {}", effect, failure);
                if (function) { effect.addProperty("status", "needs_review"); AtomicJson.write(file(p), effects); }
                break;
            }
        }
        // Keep only unresolved effects; successful deliveries do not accumulate history.
        JsonArray unresolved = new JsonArray(); for (JsonElement e : effects) if (!e.getAsJsonObject().get("status").getAsString().equals("done")) unresolved.add(e);
        if (!effects.equals(unresolved)) AtomicJson.write(file(p), unresolved);
    }
}
