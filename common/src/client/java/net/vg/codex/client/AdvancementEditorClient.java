package net.vg.codex.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.architectury.event.CompoundEventResult;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.vg.codex.client.advancements.ClientAdvancementRequiredItems;
import net.vg.codex.client.advancements.ClientAdvancementRewards;
import net.vg.codex.client.advancements.ClientAdvancementLayout;
import net.vg.codex.client.advancements.ClientClaimableRewards;
import net.vg.codex.client.advancements.ClientSubmissionTasks;
import net.vg.codex.client.advancements.ClientRecipeIds;
import net.vg.codex.client.advancements.ClientStatisticProgress;
import net.vg.codex.client.advancements.CodexAdvancementsScreen;
import net.vg.codex.network.AdvancementRequiredItemsPayload;
import net.vg.codex.network.AdvancementRewardsPayload;
import net.vg.codex.network.AdvancementLayoutPayload;
import net.vg.codex.network.ClaimableRewardsPayload;
import net.vg.codex.network.SubmissionTasksPayload;
import net.vg.codex.network.RecipeIdsPayload;
import net.vg.codex.network.StatisticProgressPayload;
import net.vg.codex.network.SaveAdvancementResultPayload;
import net.vg.codex.network.OpenAdvancementEditorPayload;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AdvancementEditorClient {
    private AdvancementEditorClient() {
    }

    public static void init() {
        NetworkManager.registerReceiver(NetworkManager.s2c(), SaveAdvancementResultPayload.TYPE, SaveAdvancementResultPayload.STREAM_CODEC,
                (payload, context) -> context.queue(() -> {
                    if (Minecraft.getInstance().screen instanceof CodexAdvancementsScreen screen) {
                        screen.onSaveConfirmed(payload.saved(), payload.failed());
                    }
                }));
        NetworkManager.registerReceiver(NetworkManager.s2c(), StatisticProgressPayload.TYPE, StatisticProgressPayload.STREAM_CODEC,
                (payload, context) -> context.queue(() -> ClientStatisticProgress.set(payload.entries())));
        NetworkManager.registerReceiver(NetworkManager.s2c(), RecipeIdsPayload.TYPE, RecipeIdsPayload.STREAM_CODEC,
                (payload, context) -> context.queue(() -> ClientRecipeIds.set(payload.recipeIds())));
        NetworkManager.registerReceiver(NetworkManager.s2c(), SubmissionTasksPayload.TYPE, SubmissionTasksPayload.STREAM_CODEC,
                (payload, context) -> context.queue(() -> ClientSubmissionTasks.set(payload.tasks())));
        NetworkManager.registerReceiver(NetworkManager.s2c(), ClaimableRewardsPayload.TYPE, ClaimableRewardsPayload.STREAM_CODEC,
                (payload, context) -> context.queue(() -> {
                    Map<Identifier, java.util.Set<Integer>> available = new HashMap<>();
                    for (ClaimableRewardsPayload.Claimable reward : payload.rewards()) {
                        available.put(reward.advancementId(), new java.util.HashSet<>(reward.rewardIndexes()));
                    }
                    ClientClaimableRewards.set(available);
                }));
        NetworkManager.registerReceiver(NetworkManager.s2c(), AdvancementLayoutPayload.TYPE, AdvancementLayoutPayload.STREAM_CODEC,
                (payload, context) -> context.queue(() -> {
                    Map<Identifier, int[]> positions = new HashMap<>();
                    for (AdvancementLayoutPayload.NodeLayout entry : payload.positions()) {
                        positions.put(entry.advancementId(), new int[]{entry.x(), entry.y()});
                    }
                    ClientAdvancementLayout.set(positions);
                    if (Minecraft.getInstance().screen instanceof CodexAdvancementsScreen screen) {
                        screen.applySharedLayout();
                    }
                }));
        NetworkManager.registerReceiver(NetworkManager.s2c(), AdvancementRewardsPayload.TYPE, AdvancementRewardsPayload.STREAM_CODEC,
                (payload, context) -> context.queue(() -> {
                    // Reassembled into the same raw JSON shape the editor itself already works
                    // with (see ClientAdvancementRewards's own doc comment), so view mode's
                    // reward chips are built through the exact same code path as edit mode's.
                    Map<Identifier, JsonObject> rewards = new HashMap<>();
                    for (AdvancementRewardsPayload.AdvancementReward reward : payload.rewards()) {
                        JsonObject json = new JsonObject();
                        if (reward.experience() > 0) {
                            json.addProperty("experience", reward.experience());
                        }
                        if (!reward.recipes().isEmpty()) {
                            JsonArray recipes = new JsonArray();
                            for (Identifier id : reward.recipes()) {
                                recipes.add(id.toString());
                            }
                            json.add("recipes", recipes);
                        }
                        if (!reward.loot().isEmpty()) {
                            JsonArray loot = new JsonArray();
                            for (Identifier id : reward.loot()) {
                                loot.add(id.toString());
                            }
                            json.add("loot", loot);
                        }
                        reward.function().ifPresent(id -> json.addProperty("function", id.toString()));
                        if (!reward.items().isEmpty()) {
                            JsonArray items = new JsonArray();
                            for (AdvancementRewardsPayload.ItemReward item : reward.items()) {
                                JsonObject itemJson = new JsonObject();
                                itemJson.addProperty("id", item.itemId().toString());
                                itemJson.addProperty("count", item.count());
                                items.add(itemJson);
                            }
                            json.add("codex_items", items);
                        }
                        rewards.put(reward.advancementId(), json);
                    }
                    ClientAdvancementRewards.set(rewards);
                }));

        NetworkManager.registerReceiver(NetworkManager.s2c(), AdvancementRequiredItemsPayload.TYPE, AdvancementRequiredItemsPayload.STREAM_CODEC,
                (payload, context) -> context.queue(() -> {
                    Map<Identifier, List<Identifier>> required = new HashMap<>();
                    Map<Identifier, List<ClientAdvancementRequiredItems.Hint>> hints = new HashMap<>();
                    for (AdvancementRequiredItemsPayload.AdvancementRequired entry : payload.required()) {
                        required.put(entry.advancementId(), entry.itemIds());
                        hints.put(entry.advancementId(), entry.hints().stream()
                                .map(hint -> new ClientAdvancementRequiredItems.Hint(hint.iconItemId(), Component.literal(hint.text())))
                                .toList());
                    }
                    ClientAdvancementRequiredItems.set(required);
                    ClientAdvancementRequiredItems.setHints(hints);
                }));

        // This registers the payload type itself (architectury does so internally on Fabric
        // regardless of overload used) - AdvancementEditorNetworking.registerCommon() only
        // registers it separately on a dedicated server, where this client init never runs.
        NetworkManager.registerReceiver(NetworkManager.s2c(), OpenAdvancementEditorPayload.TYPE, OpenAdvancementEditorPayload.STREAM_CODEC,
                (payload, context) -> context.queue(() -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.screen instanceof CodexAdvancementsScreen screen) {
                        screen.enterEditMode(payload.advancements(), payload.progress());
                    } else {
                        // The raw /codex advancements edit command reaches here without our
                        // screen already open - open one instead of falling back to anything else.
                        var connection = minecraft.getConnection();
                        if (connection == null) {
                            return;
                        }
                        CodexAdvancementsScreen screen = new CodexAdvancementsScreen(connection.getAdvancements(), minecraft.screen);
                        minecraft.setScreen(screen);
                        screen.enterEditMode(payload.advancements(), payload.progress());
                    }
                }));

        ClientGuiEvent.SET_SCREEN.register(screen -> {
            if (!(screen instanceof AdvancementsScreen)) {
                return CompoundEventResult.pass();
            }
            Minecraft minecraft = Minecraft.getInstance();
            var connection = minecraft.getConnection();
            if (connection == null) {
                return CompoundEventResult.pass();
            }
            return CompoundEventResult.interruptTrue(
                    new CodexAdvancementsScreen(connection.getAdvancements(), minecraft.screen));
        });
    }
}
