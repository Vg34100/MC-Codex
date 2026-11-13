package net.vg.codex.advancement;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.vg.codex.party.CodexPartyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-authoritative quest actions, independent of the packet dispatch layer. */
public final class QuestTransactions {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuestTransactions.class);
    private QuestTransactions() { }
    /** Returns the number of newly granted entries; reservations prevent replay on retry. */
    public static int claim(ServerPlayer player, Identifier id, int requestedIndex) {
        MinecraftServer server = player.level().getServer();
        QuestJournal.recover(server);
        AdvancementHolder holder = server.getAdvancements().get(id);
        if (holder == null || !QuestRuntime.ready(player, id)) return 0;
        Path packDir = AdvancementPackSync.externalPackDir();
        Map<Identifier, JsonObject> rewards = CodexQuestIO.readClaimableRewards(packDir);
        JsonObject rewardJson = rewards.get(id);

        if (rewardJson == null) return 0;
        try {
            var claims = CodexQuestIO.readClaimIndexes(QuestRuntime.claimsDirectory(player, id), QuestCycles.rewardOwner(player, id));
            Set<Integer> claimed = new HashSet<>(QuestRewardIdentity.indexes(player, id, completeRewards(packDir, id, rewardJson)));
            if (claimed.contains(-1)) return 0;
            int awarded = 0;
            int count = rewardCount(packDir, id, rewardJson);
            if (requestedIndex < -1) return 0;
            int first = requestedIndex == -1 ? 0 : requestedIndex;
            int last = requestedIndex == -1 ? count : Math.min(count, requestedIndex + 1);
            for (int index = first; index < last; index++) {
                if (index >= 0 && index < count && !claimed.contains(index)) validateRewardEntry(player, packDir, id, rewardJson, index);
            }
            for (int index = first; index < last; index++) {
                if (index < 0 || index >= count || claimed.contains(index)) continue;
                // Reserve each entry durably before running arbitrary reward functions. If a
                // function throws after side effects, retries must never replay those effects.
                validateRewardEntry(player, packDir, id, rewardJson, index);
                QuestJournal.Plan transaction = new QuestJournal.Plan(player);
                JsonObject entry = entries(packDir, id, rewardJson).get(index);
                planReward(player, transaction, entry);
                claimed.add(index); claims.put(id, claimed);
                JsonObject ledger = new JsonObject();
                claims.forEach((quest, indexes) -> { JsonArray values = new JsonArray(); indexes.stream().sorted().forEach(values::add); ledger.add(quest.toString(), values); });
                transaction.update(QuestRuntime.claimsDirectory(player, id).resolve("codex/claims/" + QuestCycles.rewardOwner(player, id) + ".json"), ledger);
                JsonObject keysLedger = QuestRewardIdentity.ledger(player, id);
                Set<String> keys = QuestRewardIdentity.claimed(player, id, completeRewards(packDir, id, rewardJson)); keys.add(entry.get("codex_id").getAsString());
                JsonArray keyArray = new JsonArray(); keys.stream().sorted().forEach(keyArray::add); keysLedger.add(id.toString(), keyArray);
                transaction.update(QuestRewardIdentity.file(player, id), keysLedger);
                transaction.commit();
                RewardEffects.deliver(player);
                awarded++;
            }
            if (awarded == 0) return 0;
            // Flush vanilla inventory/XP/advancement data before acknowledging a durable claim.
            server.getPlayerList().saveAll();
            AdvancementPackSync.sendClaimableRewardsTo(player);
            return awarded;
        } catch (RuntimeException e) {
            LOGGER.error("Failed to claim {} for {}", id, player.getScoreboardName(), e);
            player.sendSystemMessage(Component.literal("Codex: reward could not be claimed; see server log."));
            return 0;
        }
    }

    /** A restart between the inventory commit and native criterion award loses no task progress. */
    public static void reconcile(ServerPlayer player) {
        QuestJournal.recover(player.level().getServer());
        var tasks = CodexQuestIO.readSubmissionTasks(AdvancementPackSync.externalPackDir());
        UUID owner = CodexPartyService.progressOwner(player.level().getServer(), player.getUUID());
        var counts = CodexQuestIO.readSubmissionProgress(QuestState.directory(player.level().getServer()), owner);
        for (var entry : tasks.entrySet()) {
            int split = entry.getKey().lastIndexOf('#'); if (split < 1) continue;
            Identifier id = Identifier.parse(entry.getKey().substring(0, split));
            if (QuestDefinitions.repeat(QuestDefinitions.get(id))) { QuestRuntime.refresh(player, id); continue; }
            var progress = counts.getOrDefault(entry.getKey(), Map.of());
            var holder = player.level().getServer().getAdvancements().get(id);
            if (holder != null && SubmissionPlan.requirements(entry.getValue()).entrySet().stream().allMatch(e -> progress.getOrDefault(e.getKey(), 0) >= e.getValue())) player.getAdvancements().award(holder, entry.getKey().substring(split + 1));
        }
    }

    /** The order exactly matches the shared reward model and widget chips. */
    public static int rewardCount(Path packDir, Identifier id, JsonObject rewards) { return entries(packDir, id, rewards).size(); }

    public static JsonObject completeRewards(Path pack, Identifier id, JsonObject rewards) {
        JsonObject complete = rewards.deepCopy();
        if (!complete.has("codex_items")) complete.add("codex_items", AdvancementPackIO.readGeneratedItemRewards(pack, id));
        return complete;
    }
    private static java.util.List<JsonObject> entries(Path pack, Identifier id, JsonObject rewards) {
        return QuestRewards.entries(QuestRewardIdentity.attach(id, completeRewards(pack, id, rewards)), id);
    }

    public static void planReward(ServerPlayer player, QuestJournal.Plan transaction, JsonObject entry) {
        switch (QuestRewards.RewardType.valueOf(entry.get("type").getAsString())) {
            case EXPERIENCE -> transaction.experience(entry.get("amount").getAsInt());
            case ITEM -> {
                JsonObject item = entry.deepCopy(); item.remove("type"); item.remove("codex_id");
                int count = item.has("count") ? item.get("count").getAsInt() : 1; item.addProperty("count", 1);
                transaction.add(ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, player.registryAccess()), ItemFormats.nativeStack(item)).getOrThrow().copyWithCount(count));
            }
            case TREASURE_BAG -> transaction.add(TreasureBagItem.create(Identifier.parse(entry.get("id").getAsString()), entry.get("count").getAsInt(),
                entry.has("bag_name") ? entry.get("bag_name").getAsString() : "Treasure Bag", entry.has("bag_model") ? Identifier.parse(entry.get("bag_model").getAsString()) : null));
            case LOOT_TABLE, RANDOM_TREASURE -> {
                var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(player.level())
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY, player)
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, player.position())
                    .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.ADVANCEMENT_REWARD);
                //? if <1.21 {
                /*player.level().getServer().getLootData().getLootTable(Identifier.parse(entry.get("id").getAsString())).getRandomItems(params).forEach(transaction::add);
                *///?} else {
                var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, Identifier.parse(entry.get("id").getAsString()));
                player.level().getServer().reloadableRegistries().getLootTable(key).getRandomItems(params).forEach(transaction::add);
                //?}
            }
            case RECIPE, FUNCTION -> RewardEffects.plan(player, transaction, entry);
        }
    }

    private static void grantVanillaSlice(ServerPlayer player, String key, JsonElement value) {
        JsonObject json = new JsonObject(); JsonArray values = new JsonArray(); values.add(value); json.add(key, values);
        AdvancementRewards.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, AdvancementValidation.registries(player.level().getServer())), json).getOrThrow().grant(player);
    }

    private static void grantVanillaFunction(ServerPlayer player, String function) {
        JsonObject json = new JsonObject(); json.addProperty("function", function);
        AdvancementRewards.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, AdvancementValidation.registries(player.level().getServer())), json).getOrThrow().grant(player);
    }

    private static void validateRewardEntry(ServerPlayer player, Path pack, Identifier id, JsonObject rewards, int index) {
        JsonObject entry = entries(pack, id, rewards).get(index);
        switch (QuestRewards.RewardType.valueOf(entry.get("type").getAsString())) {
            case EXPERIENCE -> { if (entry.get("amount").getAsInt() < 0 || entry.get("amount").getAsInt() > 1_000_000) throw new IllegalArgumentException("XP reward too large"); }
            case ITEM -> {
                Identifier item = Identifier.parse(entry.get("id").getAsString()); int count = entry.get("count").getAsBigDecimal().intValueExact();
                if (!BuiltInRegistries.ITEM.containsKey(item) || item.equals(Identifier.withDefaultNamespace("air")) || count < 1 || count > 1_000_000) throw new IllegalArgumentException("Invalid item reward");
            }
            case RECIPE -> validateVanillaSlice(player, "recipes", entry.get("id"));
            case LOOT_TABLE, RANDOM_TREASURE -> validateVanillaSlice(player, "loot", entry.get("id"));
            case TREASURE_BAG -> {
                validateVanillaSlice(player, "loot", entry.get("id"));
                int count = entry.get("count").getAsBigDecimal().intValueExact();
                if (count < 1 || count > 4096 || entry.has("bag_name") && entry.get("bag_name").getAsString().length() > 80) throw new IllegalArgumentException("Invalid treasure bag reward");
                if (entry.has("bag_model")) Identifier.parse(entry.get("bag_model").getAsString());
            }
            case FUNCTION -> validateVanillaSlice(player, "function", entry.get("id"));
        }
    }

    private static void validateVanillaSlice(ServerPlayer player, String key, JsonElement value) {
        Identifier reference = Identifier.parse(value.getAsString());
        MinecraftServer server = player.level().getServer();
        QuestJournal.recover(server);
        boolean exists = switch (key) {
            //? if <1.21 {
            /*case "loot" -> server.getLootData().getKeys(net.minecraft.world.level.storage.loot.LootDataType.TABLE).contains(reference);
            *///?} else {
            case "loot" -> server.reloadableRegistries().lookup().lookupOrThrow(net.minecraft.core.registries.Registries.LOOT_TABLE)
                    .get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, reference)).isPresent();
            //?}
            case "recipes" -> server.getRecipeManager().getRecipes().stream().anyMatch(recipe -> recipe.id().identifier().equals(reference));
            case "function" -> server.getFunctions().get(reference).isPresent();
            default -> false;
        };
        if (!exists) throw new IllegalArgumentException("Missing reward " + key + ": " + reference);
        JsonObject json = new JsonObject();
        if (key.equals("function")) json.add(key, value);
        else { JsonArray values = new JsonArray(); values.add(value); json.add(key, values); }
        AdvancementRewards.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, AdvancementValidation.registries(player.level().getServer())), json).getOrThrow();
    }

    public static int submit(ServerPlayer player, Identifier advancementId, String criterion, java.util.Optional<Identifier> selectedItem) {
        return submit(player, advancementId, criterion, selectedItem, new java.util.LinkedHashMap<>());
    }
    public static int submit(ServerPlayer player, Identifier advancementId, String criterion, java.util.Optional<Identifier> selectedItem, Map<Identifier, Integer> consumed) {
        if (!QuestRuntime.unlocked(player, QuestDefinitions.get(advancementId))) return 0;
        if (QuestDefinitions.managed(advancementId)) return QuestRuntime.submit(player, advancementId, criterion, selectedItem, consumed);
        return submitOrdinary(player, advancementId, criterion, selectedItem, consumed);
    }

    static int submitOrdinary(ServerPlayer player, Identifier advancementId, String criterion, java.util.Optional<Identifier> selectedItem) {
        return submitOrdinary(player, advancementId, criterion, selectedItem, new java.util.LinkedHashMap<>());
    }
    static int submitOrdinary(ServerPlayer player, Identifier advancementId, String criterion, java.util.Optional<Identifier> selectedItem, Map<Identifier, Integer> consumed) {
        MinecraftServer server = player.level().getServer();
        QuestJournal.recover(server);
        Path packDir = AdvancementPackSync.externalPackDir();
        String key = CodexQuestIO.taskKey(advancementId, criterion);
        JsonObject task = CodexQuestIO.readSubmissionTasks(packDir).get(key);
        AdvancementHolder holder = server.getAdvancements().get(advancementId);
        if (task == null || holder == null || !holder.value().criteria().containsKey(criterion)) return 0;
        var criterionProgress = player.getAdvancements().getOrStartProgress(holder).getCriterion(criterion);
        if (criterionProgress != null && criterionProgress.isDone()) return 0;
        // All validation, including later rows, precedes every mutation.
        Map<Identifier, Integer> requirements = SubmissionPlan.requirements(task);
        UUID owner = CodexPartyService.progressOwner(server, player.getUUID());
        var allProgress = CodexQuestIO.readSubmissionProgress(QuestState.directory(server), owner);
        var progress = allProgress.computeIfAbsent(key, ignored -> new HashMap<>());
        SubmissionPlan.Allocation allocation = SubmissionPlan.allocate(player.getInventory(), SubmissionPlan.resolve(task, player.registryAccess()), progress, selectedItem);
        allocation.added().forEach((item, count) -> progress.merge(item, count, Integer::sum));
        allocation.consumed().forEach((item, count) -> consumed.merge(item, count, Integer::sum));
        Map<Integer, Integer> removals = allocation.removals();
        int removed = allocation.total();
        // A failed state write leaves inventory untouched. No parsing or fallible I/O between
        // this write and the bounded, precomputed slot mutations on the server thread.
        if (removed > 0) {
            QuestJournal.Plan transaction = new QuestJournal.Plan(player);
            transaction.remove(removals);
            JsonObject ledger = new JsonObject();
            allProgress.forEach((taskName, counts) -> { JsonObject values = new JsonObject(); counts.forEach((item, count) -> values.addProperty(item.toString(), count)); ledger.add(taskName, values); });
            transaction.update(QuestState.directory(server).resolve("codex/submissions/" + owner + ".json"), ledger);
            transaction.commit();
        }
        boolean complete = requirements.entrySet().stream().allMatch(e -> progress.getOrDefault(e.getKey(), 0) >= e.getValue());
        if (complete) player.getAdvancements().award(holder, criterion);
        if (removed > 0 || complete) server.getPlayerList().saveAll();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (CodexPartyService.progressOwner(server, online.getUUID()).equals(owner)) AdvancementPackSync.sendSubmissionTasksTo(online);
        }
        return removed;
    }
}
