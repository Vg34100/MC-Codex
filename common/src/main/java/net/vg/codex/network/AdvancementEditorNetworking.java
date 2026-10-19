package net.vg.codex.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.item.ItemStack;
import net.vg.codex.advancement.AdvancementPackIO;
import net.vg.codex.advancement.AdvancementPackSync;
import net.vg.codex.advancement.CodexQuestIO;
import net.vg.codex.party.CodexPartyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AdvancementEditorNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdvancementEditorNetworking.class);
    private static final Gson GSON = new GsonBuilder().create();

    private AdvancementEditorNetworking() {
    }

    public static void registerCommon() {
        // On a dedicated server, client init (which registers this via registerReceiver) never
        // runs, so the payload type needs to be registered here instead. On a client JVM
        // (including the integrated server for singleplayer), skip it - architectury's
        // registerReceiver call in AdvancementEditorClient registers the type itself, and
        // registering it twice in the same JVM throws "already registered".
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(OpenAdvancementEditorPayload.TYPE, OpenAdvancementEditorPayload.STREAM_CODEC);
            NetworkManager.registerS2CPayloadType(AdvancementLayoutPayload.TYPE, AdvancementLayoutPayload.STREAM_CODEC);
            NetworkManager.registerS2CPayloadType(ClaimableRewardsPayload.TYPE, ClaimableRewardsPayload.STREAM_CODEC);
            NetworkManager.registerS2CPayloadType(SubmissionTasksPayload.TYPE, SubmissionTasksPayload.STREAM_CODEC);
            NetworkManager.registerS2CPayloadType(RecipeIdsPayload.TYPE, RecipeIdsPayload.STREAM_CODEC);
            NetworkManager.registerS2CPayloadType(StatisticProgressPayload.TYPE, StatisticProgressPayload.STREAM_CODEC);
            NetworkManager.registerS2CPayloadType(SaveAdvancementResultPayload.TYPE, SaveAdvancementResultPayload.STREAM_CODEC);
            NetworkManager.registerS2CPayloadType(AdvancementRewardsPayload.TYPE, AdvancementRewardsPayload.STREAM_CODEC);
            NetworkManager.registerS2CPayloadType(AdvancementRequiredItemsPayload.TYPE, AdvancementRequiredItemsPayload.STREAM_CODEC);
        }
        NetworkManager.registerReceiver(NetworkManager.c2s(), SaveAdvancementTreePayload.TYPE, SaveAdvancementTreePayload.STREAM_CODEC,
                AdvancementEditorNetworking::handleSave);
        NetworkManager.registerReceiver(NetworkManager.c2s(), DeleteAdvancementsPayload.TYPE, DeleteAdvancementsPayload.STREAM_CODEC,
                AdvancementEditorNetworking::handleDelete);
        NetworkManager.registerReceiver(NetworkManager.c2s(), ClaimAdvancementRewardPayload.TYPE, ClaimAdvancementRewardPayload.STREAM_CODEC,
                AdvancementEditorNetworking::handleClaim);
        NetworkManager.registerReceiver(NetworkManager.c2s(), ClaimAllAdvancementRewardsPayload.TYPE, ClaimAllAdvancementRewardsPayload.STREAM_CODEC,
                AdvancementEditorNetworking::handleClaimAll);
        NetworkManager.registerReceiver(NetworkManager.c2s(), SubmitAdvancementItemsPayload.TYPE, SubmitAdvancementItemsPayload.STREAM_CODEC,
                AdvancementEditorNetworking::handleSubmit);
        NetworkManager.registerReceiver(NetworkManager.c2s(), RequestAdvancementSnapshotPayload.TYPE, RequestAdvancementSnapshotPayload.STREAM_CODEC,
                (payload, context) -> context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        sendSnapshotIfPermitted(player);
                    }
                }));
    }

    /** Shared by the /codex advancements edit command and the in-screen edit-mode toggle. */
    public static void sendSnapshotIfPermitted(ServerPlayer player) {
        if (!Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack())) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        Path packDir = AdvancementPackSync.externalPackDir();
        Map<Identifier, AdvancementPackIO.NodePosition> layout = AdvancementPackIO.readLayout(packDir);
        Map<Identifier, JsonObject> claimableRewards = CodexQuestIO.readClaimableRewards(packDir);
        Map<String, JsonObject> submissionTasks = CodexQuestIO.readSubmissionTasks(packDir);
        Map<String, JsonObject> statisticTasks = CodexQuestIO.readStatisticTasks(packDir);
        List<RawAdvancement> raw = new ArrayList<>();
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            try {
                JsonElement json = Advancement.CODEC.encodeStart(ops, holder.value()).getOrThrow();
                JsonObject obj = json.getAsJsonObject();
                // Advancement.CODEC only round-trips real vanilla fields, so item rewards (not a
                // vanilla concept) can't survive this encode - reattached here, wire-only, from the
                // generated loot table the editor itself wrote last time (see AdvancementPackIO).
                // "codex_items" never touches the actual advancement file on disk.
                var itemRewards = AdvancementPackIO.readGeneratedItemRewards(packDir, holder.id());
                if (!itemRewards.isEmpty()) {
                    JsonObject rewards = obj.has("rewards") && obj.get("rewards").isJsonObject()
                            ? obj.getAsJsonObject("rewards") : new JsonObject();
                    rewards.add("codex_items", itemRewards);
                    obj.add("rewards", rewards);
                }
                JsonObject claimable = claimableRewards.get(holder.id());
                if (claimable != null) {
                    // Claimable rewards live in a sidecar because the real advancement must keep
                    // its vanilla rewards empty.  The sidecar references our generated loot table
                    // for item rewards, whereas the editor works with the wire-only codex_items
                    // list.  Sending the reference back unchanged made the editor think the item
                    // list was empty after a save; its next Apply then deleted that loot table.
                    JsonObject editorRewards = claimable.deepCopy();
                    JsonArray claimableItems = AdvancementPackIO.readGeneratedItemRewards(packDir, holder.id());
                    if (!claimableItems.isEmpty()) {
                        Identifier generatedLootId = AdvancementPackIO.generatedLootTableId(holder.id());
                        JsonArray retainedLoot = new JsonArray();
                        if (editorRewards.has("loot") && editorRewards.get("loot").isJsonArray()) {
                            for (JsonElement loot : editorRewards.getAsJsonArray("loot")) {
                                if (!generatedLootId.toString().equals(loot.getAsString())) {
                                    retainedLoot.add(loot);
                                }
                            }
                        }
                        if (retainedLoot.isEmpty()) {
                            editorRewards.remove("loot");
                        } else {
                            editorRewards.add("loot", retainedLoot);
                        }
                        editorRewards.add("codex_items", claimableItems);
                    }
                    obj.add("rewards", editorRewards);
                    obj.addProperty("codex_claimable", true);
                }
                if (obj.has("criteria") && obj.get("criteria").isJsonObject()) {
                    for (var criterion : obj.getAsJsonObject("criteria").entrySet()) {
                        JsonObject task = submissionTasks.get(CodexQuestIO.taskKey(holder.id(), criterion.getKey()));
                        if (task != null && criterion.getValue().isJsonObject()) {
                            criterion.getValue().getAsJsonObject().add("codex_submission", task.getAsJsonArray("items").deepCopy());
                        }
                        JsonObject statistic = statisticTasks.get(CodexQuestIO.taskKey(holder.id(), criterion.getKey()));
                        if (statistic != null && criterion.getValue().isJsonObject()) {
                            criterion.getValue().getAsJsonObject().add("codex_statistic", statistic.deepCopy());
                        }
                    }
                }
                AdvancementPackIO.NodePosition position = layout.get(holder.id());
                if (position != null) {
                    JsonObject layoutJson = new JsonObject();
                    layoutJson.addProperty("x", position.x());
                    layoutJson.addProperty("y", position.y());
                    // Wire-only metadata; never written into the vanilla advancement document.
                    obj.add("codex_layout", layoutJson);
                }
                raw.add(new RawAdvancement(holder.id(), GSON.toJson(obj)));
            } catch (RuntimeException e) {
                LOGGER.error("Failed to encode advancement {} for the editor snapshot", holder.id(), e);
            }
        }
        // getOrStartProgress always returns real progress for every advancement, regardless of
        // whether vanilla's own visibility rules have ever revealed it to this player's client -
        // this is what lets edit mode show a progress counter even for a tab the player has never
        // actually discovered in survival (see OpenAdvancementEditorPayload's own doc comment).
        List<OpenAdvancementEditorPayload.ProgressEntry> progress = new ArrayList<>();
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            progress.add(new OpenAdvancementEditorPayload.ProgressEntry(
                    holder.id(), player.getAdvancements().getOrStartProgress(holder), holder.value().requirements()));
        }
        NetworkManager.sendToPlayer(player, new OpenAdvancementEditorPayload(raw, progress));
    }

    private static void handleSave(SaveAdvancementTreePayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player)) {
                return;
            }
            if (!Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack())) {
                return;
            }
            MinecraftServer server = player.level().getServer();
            HolderLookup.Provider registries = server.registryAccess();
            ResourceProvider resources = server.getResourceManager();
            Path packDir = AdvancementPackSync.externalPackDir();
            Map<Identifier, AdvancementPackIO.NodePosition> layout = new HashMap<>(AdvancementPackIO.readLayout(packDir));
            Map<Identifier, JsonObject> claimableRewards = new HashMap<>(CodexQuestIO.readClaimableRewards(packDir));
            Map<String, JsonObject> submissionTasks = new HashMap<>(CodexQuestIO.readSubmissionTasks(packDir));
            Map<String, JsonObject> statisticTasks = new HashMap<>(CodexQuestIO.readStatisticTasks(packDir));
            boolean layoutChanged = false;
            boolean claimableChanged = false;
            boolean submissionsChanged = false;
            boolean statisticsChanged = false;
            int written = 0;
            int failed = 0;
            for (RawAdvancement raw : payload.advancements()) {
                try {
                    var json = JsonParser.parseString(raw.json()).getAsJsonObject();
                    if (json.has("codex_layout")) {
                        if (json.get("codex_layout").isJsonObject()) {
                            JsonObject point = json.getAsJsonObject("codex_layout");
                            layout.put(raw.id(), new AdvancementPackIO.NodePosition(point.get("x").getAsInt(), point.get("y").getAsInt()));
                        } else {
                            layout.remove(raw.id());
                        }
                        layoutChanged = true;
                    }
                    json.remove("codex_layout");
                    if (json.has("criteria") && json.get("criteria").isJsonObject()) {
                        String prefix = raw.id() + "#";
                        submissionsChanged |= submissionTasks.keySet().removeIf(key -> key.startsWith(prefix));
                        statisticsChanged |= statisticTasks.keySet().removeIf(key -> key.startsWith(prefix));
                        for (var criterion : json.getAsJsonObject("criteria").entrySet()) {
                            if (!criterion.getValue().isJsonObject()) continue;
                            JsonObject criterionJson = criterion.getValue().getAsJsonObject();
                            if (criterionJson.has("codex_submission") && criterionJson.get("codex_submission").isJsonArray()) {
                                JsonObject task = new JsonObject();
                                task.add("items", criterionJson.getAsJsonArray("codex_submission").deepCopy());
                                submissionTasks.put(CodexQuestIO.taskKey(raw.id(), criterion.getKey()), task);
                                criterionJson.remove("codex_submission");
                                submissionsChanged = true;
                            }
                            if (criterionJson.has("codex_statistic") && criterionJson.get("codex_statistic").isJsonObject()) {
                                statisticTasks.put(CodexQuestIO.taskKey(raw.id(), criterion.getKey()), criterionJson.getAsJsonObject("codex_statistic").deepCopy());
                                criterionJson.remove("codex_statistic");
                                statisticsChanged = true;
                            }
                        }
                    }
                    applyItemRewards(packDir, raw.id(), json);
                    if (json.has("codex_claimable")) {
                        boolean claimable = json.get("codex_claimable").getAsBoolean();
                        json.remove("codex_claimable");
                        if (claimable) {
                            JsonObject rewards = json.has("rewards") && json.get("rewards").isJsonObject()
                                    ? json.getAsJsonObject("rewards").deepCopy() : new JsonObject();
                            claimableRewards.put(raw.id(), rewards);
                            // Empty rewards preserve a valid vanilla advancement but prevent its
                            // normal immediate grant; Codex grants the stored reward on Claim.
                            json.add("rewards", new JsonObject());
                        } else {
                            claimableRewards.remove(raw.id());
                        }
                        claimableChanged = true;
                    }
                    if (AdvancementPackIO.exists(packDir, resources, raw.id())) {
                        // Existing advancement (vanilla or otherwise): patch just the JSON keys the
                        // client actually sent, leaving the rest (including rewards) untouched.
                        AdvancementPackIO.patchRawFields(packDir, resources, raw.id(), json);
                    } else {
                        AdvancementPackIO.writeRaw(packDir, raw.id(), json, registries);
                    }
                    written++;
                } catch (RuntimeException e) {
                    failed++;
                    LOGGER.error("Failed to write advancement {}", raw.id(), e);
                }
            }
            if (layoutChanged) {
                AdvancementPackIO.writeLayout(packDir, layout);
            }
            if (claimableChanged) {
                CodexQuestIO.writeClaimableRewards(packDir, claimableRewards);
            }
            if (submissionsChanged) {
                CodexQuestIO.writeSubmissionTasks(packDir, submissionTasks);
            }
            if (statisticsChanged) {
                CodexQuestIO.writeStatisticTasks(packDir, statisticTasks);
            }
            int saved = written;
            int failures = failed;
            if (written > 0) {
                AdvancementPackSync.reloadAfterEdit(server, () -> {
                    NetworkManager.sendToPlayer(player, new SaveAdvancementResultPayload(saved, failures));
                    player.sendSystemMessage(Component.literal(failures == 0
                            ? "Codex: saved " + saved + " advancement(s)."
                            : "Codex: saved " + saved + ", failed " + failures + " (see server log)."));
                }, () -> NetworkManager.sendToPlayer(player, new SaveAdvancementResultPayload(0, Math.max(1, failures))));
            } else {
                NetworkManager.sendToPlayer(player, new SaveAdvancementResultPayload(0, failures));
            }
        });
    }

    /**
     * Pulls the editor's wire-only "codex_items" out of {@code json.rewards} (see
     * {@link #sendSnapshotIfPermitted}), regenerates the generated loot table from it (or deletes
     * it if the list is now empty), and points/unpoints "rewards.loot" at it - so the advancement
     * file that actually gets written to disk stays 100% real vanilla fields, with the generated
     * loot table doing the actual granting.
     */
    private static void applyItemRewards(Path packDir, Identifier id, JsonObject json) {
        if (!json.has("rewards") || !json.get("rewards").isJsonObject()) {
            AdvancementPackIO.deleteGeneratedLootTable(packDir, id);
            return;
        }
        JsonObject rewards = json.getAsJsonObject("rewards");
        JsonArray items = rewards.has("codex_items") && rewards.get("codex_items").isJsonArray()
                ? rewards.getAsJsonArray("codex_items") : new JsonArray();
        rewards.remove("codex_items");

        Identifier lootId = AdvancementPackIO.generatedLootTableId(id);
        JsonArray existingLoot = rewards.has("loot") && rewards.get("loot").isJsonArray()
                ? rewards.getAsJsonArray("loot") : new JsonArray();
        JsonArray filteredLoot = new JsonArray();
        for (JsonElement e : existingLoot) {
            if (!e.getAsString().equals(lootId.toString())) {
                filteredLoot.add(e);
            }
        }
        if (items.isEmpty()) {
            AdvancementPackIO.deleteGeneratedLootTable(packDir, id);
        } else {
            AdvancementPackIO.writeGeneratedLootTable(packDir, id, items);
            filteredLoot.add(lootId.toString());
        }
        if (filteredLoot.isEmpty()) {
            rewards.remove("loot");
        } else {
            rewards.add("loot", filteredLoot);
        }
    }

    private static void handleDelete(DeleteAdvancementsPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player)) {
                return;
            }
            if (!Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack())) {
                return;
            }
            MinecraftServer server = player.level().getServer();
            Path packDir = AdvancementPackSync.externalPackDir();
            // Cascades to descendants scanned directly off disk, independent of whatever tree the
            // client had loaded - a stale client-side tree previously left orphaned children behind
            // (parent gone, child file still present), which only ever surfaced as a silent
            // "Couldn't load advancements" warning rather than actually being cleaned up.
            var deleted = AdvancementPackIO.deleteWithDescendants(packDir, payload.ids());
            Map<Identifier, AdvancementPackIO.NodePosition> layout = AdvancementPackIO.readLayout(packDir);
            if (layout.keySet().removeAll(deleted)) {
                AdvancementPackIO.writeLayout(packDir, layout);
            }
            Map<Identifier, JsonObject> claimableRewards = CodexQuestIO.readClaimableRewards(packDir);
            if (claimableRewards.keySet().removeAll(deleted)) {
                CodexQuestIO.writeClaimableRewards(packDir, claimableRewards);
            }
            AdvancementPackSync.onServerStarting(server);
            player.sendSystemMessage(Component.literal("Codex: deleted " + deleted.size() + " advancement(s)."));
        });
    }

    private static void handleClaim(ClaimAdvancementRewardPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player)) return;
            if (claim(player, payload.advancementId(), payload.rewardIndex()) == 0) {
                player.sendSystemMessage(Component.literal("Codex: that reward was already claimed."));
            }
        });
    }

    private static void handleClaimAll(ClaimAllAdvancementRewardsPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player)) return;
            int claimed = 0;
            for (Identifier id : CodexQuestIO.readClaimableRewards(AdvancementPackSync.externalPackDir()).keySet()) {
                claimed += claim(player, id, -1);
            }
            AdvancementPackSync.sendClaimableRewardsTo(player);
            player.sendSystemMessage(Component.literal(claimed > 0
                    ? "Codex: claimed " + claimed + " reward(s)."
                    : "Codex: no rewards are available to claim."));
        });
    }

    /** Returns false for every intentionally non-claimable state; never grants twice. */
    private static int claim(ServerPlayer player, Identifier id, int requestedIndex) {
        MinecraftServer server = player.level().getServer();
        AdvancementHolder holder = server.getAdvancements().get(id);
        if (holder == null || !player.getAdvancements().getOrStartProgress(holder).isDone()) return 0;
        Path packDir = AdvancementPackSync.externalPackDir();
        Map<Identifier, JsonObject> rewards = CodexQuestIO.readClaimableRewards(packDir);
        JsonObject rewardJson = rewards.get(id);
        var claims = CodexQuestIO.readClaimIndexes(packDir, player.getUUID());
        if (rewardJson == null) return 0;
        Set<Integer> claimed = new HashSet<>(claims.getOrDefault(id, Set.of()));
        if (claimed.contains(-1)) return 0;
        try {
            int awarded = 0;
            int count = rewardCount(packDir, id, rewardJson);
            int first = requestedIndex < 0 ? 0 : requestedIndex;
            int last = requestedIndex < 0 ? count : requestedIndex + 1;
            for (int index = first; index < last; index++) {
                if (index < 0 || index >= count || claimed.contains(index)) continue;
                grantRewardEntry(player, packDir, id, rewardJson, index);
                claimed.add(index);
                awarded++;
            }
            if (awarded == 0) return 0;
            claims.put(id, claimed);
            CodexQuestIO.writeClaimIndexes(packDir, player.getUUID(), claims);
            AdvancementPackSync.sendClaimableRewardsTo(player);
            return awarded;
        } catch (RuntimeException e) {
            LOGGER.error("Failed to claim {} for {}", id, player.getScoreboardName(), e);
            player.sendSystemMessage(Component.literal("Codex: reward could not be claimed; see server log."));
            return 0;
        }
    }

    /** The order exactly matches CodexAdvancementWidget's reward-chip order. */
    public static int rewardCount(Path packDir, Identifier id, JsonObject rewards) {
        int count = rewards.has("experience") && rewards.get("experience").getAsInt() > 0 ? 1 : 0;
        JsonArray items = rewardItems(packDir, id, rewards);
        count += items.size();
        if (rewards.has("recipes") && rewards.get("recipes").isJsonArray()) count += rewards.getAsJsonArray("recipes").size();
        if (rewards.has("loot") && rewards.get("loot").isJsonArray()) {
            String generated = AdvancementPackIO.generatedLootTableId(id).toString();
            for (JsonElement loot : rewards.getAsJsonArray("loot")) if (!generated.equals(loot.getAsString())) count++;
        }
        return count + (rewards.has("function") ? 1 : 0);
    }

    private static void grantRewardEntry(ServerPlayer player, Path packDir, Identifier id, JsonObject rewards, int index) {
        int cursor = 0;
        if (rewards.has("experience") && rewards.get("experience").getAsInt() > 0) {
            if (index == cursor) { player.giveExperiencePoints(rewards.get("experience").getAsInt()); return; }
            cursor++;
        }
        JsonArray items = rewardItems(packDir, id, rewards);
        for (JsonElement element : items) {
            if (index == cursor) {
                JsonObject item = element.getAsJsonObject();
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(item.get("id").getAsString())),
                        item.has("count") ? item.get("count").getAsInt() : 1);
                if (!player.getInventory().add(stack)) player.drop(stack, false);
                return;
            }
            cursor++;
        }
        JsonArray recipes = rewards.has("recipes") && rewards.get("recipes").isJsonArray() ? rewards.getAsJsonArray("recipes") : new JsonArray();
        for (JsonElement recipe : recipes) {
            if (index == cursor) { grantVanillaSlice(player, "recipes", recipe); return; }
            cursor++;
        }
        JsonArray loot = rewards.has("loot") && rewards.get("loot").isJsonArray() ? rewards.getAsJsonArray("loot") : new JsonArray();
        String generated = AdvancementPackIO.generatedLootTableId(id).toString();
        for (JsonElement entry : loot) {
            if (generated.equals(entry.getAsString())) continue;
            if (index == cursor) { grantVanillaSlice(player, "loot", entry); return; }
            cursor++;
        }
        if (rewards.has("function") && index == cursor) grantVanillaFunction(player, rewards.get("function").getAsString());
    }

    private static JsonArray rewardItems(Path packDir, Identifier id, JsonObject rewards) {
        return rewards.has("codex_items") && rewards.get("codex_items").isJsonArray()
                ? rewards.getAsJsonArray("codex_items") : AdvancementPackIO.readGeneratedItemRewards(packDir, id);
    }

    private static void grantVanillaSlice(ServerPlayer player, String key, JsonElement value) {
        JsonObject json = new JsonObject(); JsonArray values = new JsonArray(); values.add(value); json.add(key, values);
        AdvancementRewards.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, player.level().getServer().registryAccess()), json).getOrThrow().grant(player);
    }

    private static void grantVanillaFunction(ServerPlayer player, String function) {
        JsonObject json = new JsonObject(); json.addProperty("function", function);
        AdvancementRewards.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, player.level().getServer().registryAccess()), json).getOrThrow().grant(player);
    }

    private static void handleSubmit(SubmitAdvancementItemsPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player)) return;
            Path packDir = AdvancementPackSync.externalPackDir();
            String key = CodexQuestIO.taskKey(payload.advancementId(), payload.criterion());
            JsonObject task = CodexQuestIO.readSubmissionTasks(packDir).get(key);
            AdvancementHolder holder = player.level().getServer().getAdvancements().get(payload.advancementId());
            if (task == null || holder == null) {
                player.sendSystemMessage(Component.literal("Codex: that submission task no longer exists."));
                return;
            }
            UUID progressOwner = CodexPartyService.progressOwner(player.level().getServer(), player.getUUID());
            Map<String, Map<Identifier, Integer>> allProgress = CodexQuestIO.readSubmissionProgress(packDir, progressOwner);
            Map<Identifier, Integer> progress = allProgress.computeIfAbsent(key, ignored -> new HashMap<>());
            int removed = 0;
            try {
                for (JsonElement element : task.getAsJsonArray("items")) {
                    JsonObject requirement = element.getAsJsonObject();
                    Identifier itemId = Identifier.parse(requirement.get("id").getAsString());
                    if (payload.itemId().isPresent() && !payload.itemId().get().equals(itemId)) continue;
                    int needed = requirement.get("count").getAsInt() - progress.getOrDefault(itemId, 0);
                    if (needed <= 0) continue;
                    for (int slot = 0; slot < player.getInventory().getContainerSize() && needed > 0; slot++) {
                        ItemStack stack = player.getInventory().getItem(slot);
                        if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId)) continue;
                        int take = Math.min(needed, stack.getCount());
                        stack.shrink(take);
                        progress.merge(itemId, take, Integer::sum);
                        needed -= take;
                        removed += take;
                    }
                }
                CodexQuestIO.writeSubmissionProgress(packDir, progressOwner, allProgress);
                boolean complete = true;
                for (JsonElement element : task.getAsJsonArray("items")) {
                    JsonObject requirement = element.getAsJsonObject();
                    Identifier itemId = Identifier.parse(requirement.get("id").getAsString());
                    if (progress.getOrDefault(itemId, 0) < requirement.get("count").getAsInt()) complete = false;
                }
                if (complete) player.getAdvancements().award(holder, payload.criterion());
                UUID party = CodexPartyService.progressOwner(player.level().getServer(), player.getUUID());
                for (ServerPlayer online : player.level().getServer().getPlayerList().getPlayers()) {
                    if (CodexPartyService.progressOwner(player.level().getServer(), online.getUUID()).equals(party)) {
                        AdvancementPackSync.sendSubmissionTasksTo(online);
                    }
                }
                player.sendSystemMessage(Component.literal(complete
                        ? "Codex: submission complete."
                        : "Codex: submitted " + removed + " item(s)."));
            } catch (RuntimeException e) {
                LOGGER.error("Failed submission {} for {}", key, player.getScoreboardName(), e);
                player.sendSystemMessage(Component.literal("Codex: submission failed; no further items were removed."));
            }
        });
    }
}
