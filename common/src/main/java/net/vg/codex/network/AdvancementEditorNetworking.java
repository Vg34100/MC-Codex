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
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, net.vg.codex.advancement.AdvancementValidation.registries(server));
        Path packDir = AdvancementPackSync.externalPackDir();
        Map<Identifier, AdvancementPackIO.NodePosition> layout = AdvancementPackIO.readLayout(packDir);
        Map<Identifier, JsonObject> claimableRewards = CodexQuestIO.readClaimableRewards(packDir);
        Map<String, JsonObject> submissionTasks = CodexQuestIO.readSubmissionTasks(packDir);
        Map<String, JsonObject> statisticTasks = CodexQuestIO.readStatisticTasks(packDir);
        TreasureNetworking.send(player, "");
        String migrationRulesHash = net.vg.codex.advancement.MigrationFingerprint.hash(packDir.resolve("codex/quests.json"));
        String migrationRewardsHash = net.vg.codex.advancement.MigrationFingerprint.hash(packDir.resolve("codex/claimable_rewards.json"));
        List<RawAdvancement> raw = new ArrayList<>();
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            try {
                JsonElement json = AdvancementPackIO.exists(packDir, server.getResourceManager(), holder.id())
                        ? AdvancementPackIO.readBaseJson(packDir, server.getResourceManager(), holder.id())
                        : Advancement.CODEC.encodeStart(ops, holder.value()).getOrThrow();
                JsonObject obj = json.getAsJsonObject();
                JsonObject quest = net.vg.codex.advancement.QuestDefinitions.get(holder.id());
                if ((quest.size() != 0)) obj.add("codex_quest", quest);
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
                if (obj.has("rewards")) obj.add("rewards", net.vg.codex.advancement.QuestRewardIdentity.attach(holder.id(), obj.getAsJsonObject("rewards")));
                obj.add("codex_migration", net.vg.codex.advancement.MigrationFingerprint.snapshot(packDir, server.getResourceManager(), holder.id(), migrationRulesHash, migrationRewardsHash, obj));
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
            HolderLookup.Provider registries = net.vg.codex.advancement.AdvancementValidation.registries(server);
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
            Map<Identifier, RawAdvancement> batch = new HashMap<>();
            for (RawAdvancement raw : payload.advancements()) batch.put(raw.id(), raw);
            java.util.function.Function<Identifier, JsonObject> lookup = id -> {
                JsonObject base = null;
                if (AdvancementPackIO.exists(packDir, resources, id)) base = AdvancementPackIO.readBaseJson(packDir, resources, id);
                else {
                    var loaded = server.getAdvancements().get(id);
                    if (loaded != null) base = Advancement.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), loaded.value()).getOrThrow().getAsJsonObject();
                }
                RawAdvancement pending = batch.get(id);
                if (pending == null) return base;
                JsonObject edits = JsonParser.parseString(pending.json()).getAsJsonObject();
                return base == null ? edits : net.vg.codex.advancement.AdvancementDocument.patch(base, edits);
            };
            for (RawAdvancement raw : payload.advancements()) {
                try {
                    AdvancementPackIO.safeResourceFile(packDir, raw.id(), "advancement");
                    var json = JsonParser.parseString(raw.json()).getAsJsonObject();
                    json.remove("codex_migration");
                    if (AdvancementPackIO.exists(packDir, resources, raw.id())) json = net.vg.codex.advancement.AdvancementDocument.patch(AdvancementPackIO.readBaseJson(packDir, resources, raw.id()), json);
                    net.vg.codex.advancement.AdvancementValidation.validate(json, registries);
                    if (json.has("codex_quest")) net.vg.codex.advancement.QuestDefinitions.validateAdvancement(json.getAsJsonObject("codex_quest"), json);
                    net.vg.codex.advancement.AdvancementValidation.validateParents(raw.id(), json, lookup);
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
                    if (json.has("rewards") && json.getAsJsonObject("rewards").has("codex_ids")) {
                        JsonObject editedRewards = json.getAsJsonObject("rewards");
                        net.vg.codex.advancement.QuestRewardIdentity.validate(editedRewards, raw.id());
                        JsonArray rewardIds = editedRewards.remove("codex_ids").getAsJsonArray();
                        if (!rewardIds.isEmpty()) {
                            JsonObject metadata = json.has("codex_quest") ? json.getAsJsonObject("codex_quest") : net.vg.codex.advancement.QuestDefinitions.get(raw.id());
                            if (!metadata.has("reward_ids_legacy")) {
                                JsonObject previous = claimableRewards.get(raw.id());
                                if (previous == null && AdvancementPackIO.exists(packDir, resources, raw.id())) {
                                    JsonObject original = AdvancementPackIO.readBaseJson(packDir, resources, raw.id());
                                    previous = original.has("rewards") ? original.getAsJsonObject("rewards") : new JsonObject();
                                }
                                if (previous == null) previous = new JsonObject();
                                previous = net.vg.codex.advancement.QuestTransactions.completeRewards(packDir, raw.id(), previous);
                                metadata.add("reward_ids_legacy", net.vg.codex.advancement.QuestRewardIdentity.attach(raw.id(), previous).getAsJsonArray("codex_ids"));
                            }
                            metadata.add("reward_ids", rewardIds); json.add("codex_quest", metadata);
                        }
                    }
                    if (json.has("codex_quest")) {
                        net.vg.codex.advancement.QuestDefinitions.save(raw.id(), json.getAsJsonObject("codex_quest"));
                        json.remove("codex_quest");
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
            try {
                for (Identifier id : payload.ids()) AdvancementPackIO.safeResourceFile(packDir, id, "advancement");
                var deleted = AdvancementPackIO.deleteWithDescendants(packDir, payload.ids());
                for (Identifier id : deleted) net.vg.codex.advancement.QuestDefinitions.save(id, new JsonObject());
                Map<Identifier, AdvancementPackIO.NodePosition> layout = AdvancementPackIO.readLayout(packDir);
                if (layout.keySet().removeAll(deleted)) {
                    AdvancementPackIO.writeLayout(packDir, layout);
                }
                Map<Identifier, JsonObject> claimableRewards = CodexQuestIO.readClaimableRewards(packDir);
                if (claimableRewards.keySet().removeAll(deleted)) {
                    CodexQuestIO.writeClaimableRewards(packDir, claimableRewards);
                }
                var submissions = CodexQuestIO.readSubmissionTasks(packDir);
                var statistics = CodexQuestIO.readStatisticTasks(packDir);
                submissions.keySet().removeIf(key -> deleted.stream().anyMatch(id -> key.startsWith(id + "#")));
                statistics.keySet().removeIf(key -> deleted.stream().anyMatch(id -> key.startsWith(id + "#")));
                CodexQuestIO.writeSubmissionTasks(packDir, submissions);
                CodexQuestIO.writeStatisticTasks(packDir, statistics);
                AdvancementPackSync.onServerStarting(server);
                player.sendSystemMessage(Component.literal("Codex: deleted " + deleted.size() + " advancement(s)."));
            } catch (RuntimeException invalid) {
                LOGGER.warn("Rejected advancement deletion from {}: {}", player.getScoreboardName(), invalid.getMessage());
                player.sendSystemMessage(Component.literal("Codex: delete rejected; see server log."));
            }
        });
    }

    private static void handleClaim(ClaimAdvancementRewardPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player)) return;
            QuestNetworking.act(player, payload.advancementId(), "claim", "", java.util.Optional.empty(), payload.rewardIndex());
        });
    }

    private static void handleClaimAll(ClaimAllAdvancementRewardsPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player)) return;
            try (net.vg.codex.advancement.QuestReadScope scope = net.vg.codex.advancement.QuestReadScope.open()) {
            int claimed = 0;
            int quests = 0;
            for (Identifier id : CodexQuestIO.readClaimableRewards(AdvancementPackSync.externalPackDir()).keySet()) {
                int count = net.vg.codex.advancement.QuestTransactions.claim(player, id, -1);
                claimed += count;
                if (count > 0) quests++;
            }
            AdvancementPackSync.sendClaimableRewardsTo(player);
            JsonObject result = new JsonObject(); result.addProperty("action", "claim_all");
            result.addProperty("success", claimed > 0); result.addProperty("title", "Rewards");
            result.addProperty("message", claimed > 0 ? "Claimed " + claimed + (claimed == 1 ? " reward from " : " rewards from ") + quests + (quests == 1 ? " quest" : " quests") : "No rewards available to claim");
            JsonObject response = new JsonObject(); response.add("result", result);
            NetworkManager.sendToPlayer(player, new QuestInfoPayload(Identifier.parse("codex:reward_claims"), response.toString()));
            QuestNetworking.refresh(player);
            }
        });
    }

    public static int rewardCount(Path packDir, Identifier id, JsonObject rewards) {
        return net.vg.codex.advancement.QuestTransactions.rewardCount(packDir, id, rewards);
    }

    private static void handleSubmit(SubmitAdvancementItemsPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player)) return;
            QuestNetworking.act(player, payload.advancementId(), "submit", payload.criterion(), payload.itemId(), -1);
        });
    }
}
