package net.vg.codex.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;

/** Preflight the complete edited definition before changing files or sidecars. */
public final class AdvancementValidation {
    /** Reloadable recipe/advancement registries became native codec dependencies in 26.3. */
    public static HolderLookup.Provider registries(net.minecraft.server.MinecraftServer server) {
        //? if >=26.3 {
        /*return server.reloadableRegistries().lookup();
        *///?} else {
        return server.registryAccess();
        //?}
    }
    private AdvancementValidation() { }

    public static void validateParents(Identifier id, JsonObject definition, java.util.function.Function<Identifier, JsonObject> lookup) {
        java.util.Set<Identifier> visited = new java.util.HashSet<>(); visited.add(id);
        JsonElement parent = definition.get("parent");
        while (parent != null && !parent.isJsonNull()) {
            Identifier ancestor = Identifier.parse(parent.getAsString());
            if (!visited.add(ancestor)) throw new IllegalArgumentException("Advancement parent cycle: " + id);
            JsonObject document = lookup.apply(ancestor);
            if (document == null) throw new IllegalArgumentException("Missing advancement parent: " + ancestor);
            parent = document.get("parent");
        }
    }

    public static void validate(JsonObject definition, HolderLookup.Provider registries) {
        JsonObject vanilla = definition.deepCopy();
        vanilla.remove("codex_migration"); vanilla.remove("codex_quest"); vanilla.remove("codex_layout"); vanilla.remove("codex_claimable");
        if (!vanilla.has("criteria") || (vanilla.getAsJsonObject("criteria").size() == 0) || vanilla.getAsJsonObject("criteria").size() > 4096)
            throw new IllegalArgumentException("An advancement needs 1–4096 criteria");
        for (var entry : vanilla.getAsJsonObject("criteria").entrySet()) {
            if (entry.getKey().isBlank() || entry.getKey().length() > 256) throw new IllegalArgumentException("Invalid criterion name");
            JsonObject criterion = entry.getValue().getAsJsonObject();
            if (criterion.has("codex_submission")) {
                JsonObject task = new JsonObject(); task.add("items", criterion.remove("codex_submission"));
                SubmissionPlan.resolve(task, registries);
                if (!"minecraft:impossible".equals(criterion.get("trigger").getAsString())) throw new IllegalArgumentException("Delivery criteria must use the impossible trigger");
            }
            if (criterion.has("codex_statistic")) {
                JsonObject task = criterion.remove("codex_statistic").getAsJsonObject();
                String[] statistic = task.get("id").getAsString().split("\\|", 2);
                if (statistic.length != 2 || task.get("target").getAsBigDecimal().intValueExact() < 0) throw new IllegalArgumentException("Invalid statistic task");
                Identifier.parse(statistic[1]);
            }
        }
        if (vanilla.has("requirements")) AdvancementDocument.validateGroups(vanilla.getAsJsonArray("requirements"), vanilla.getAsJsonObject("criteria").keySet());
        if (vanilla.has("rewards")) {
            JsonObject rewards = vanilla.getAsJsonObject("rewards");
            rewards.remove("codex_ids");
            if (rewards.has("codex_items")) for (JsonElement value : rewards.remove("codex_items").getAsJsonArray()) {
                JsonObject item = value.getAsJsonObject();
                int count = item.has("count") ? item.get("count").getAsBigDecimal().intValueExact() : 1;
                if (!BuiltInRegistries.ITEM.containsKey(Identifier.parse(item.get("id").getAsString())) || count < 1 || count > 1_000_000)
                    throw new IllegalArgumentException("Invalid item reward");
            }
        }
        Advancement.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), vanilla).getOrThrow();
    }
}
