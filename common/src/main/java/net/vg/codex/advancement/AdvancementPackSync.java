package net.vg.codex.advancement;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.storage.LevelResource;
import net.vg.codex.network.AdvancementRequiredItemsPayload;
import net.vg.codex.network.AdvancementRewardsPayload;
import net.vg.codex.network.AdvancementLayoutPayload;
import net.vg.codex.network.ClaimableRewardsPayload;
import net.vg.codex.network.SubmissionTasksPayload;
import net.vg.codex.network.RecipeIdsPayload;
import net.vg.codex.party.CodexPartyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Keeps the externally-editable "codex/advancements" folder (outside the mod jar, edited by the
 * in-game editor) in sync with every world's own datapacks folder, so edits apply to any world
 * or server without the user manually copying files around.
 */
public final class AdvancementPackSync {
    public static final String PACK_ID = "codex_advancements";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LoggerFactory.getLogger(AdvancementPackSync.class);

    private AdvancementPackSync() {
    }

    public static Path externalPackDir() {
        return Platform.getGameFolder().resolve("codex").resolve("advancements");
    }

    public static void onServerStarting(MinecraftServer server) {
        reload(server, () -> { });
    }

    /** Reloads the editable pack and invokes {@code afterReload} only after client advancement
     * data and Codex sidecar payloads have been refreshed. */
    public static void reloadAfterEdit(MinecraftServer server, Runnable afterReload) {
        reload(server, afterReload, () -> { });
    }

    private static void reload(MinecraftServer server, Runnable afterReload) {
        reload(server, afterReload, () -> { });
    }

    public static void reloadAfterEdit(MinecraftServer server, Runnable afterReload, Runnable onFailure) {
        reload(server, afterReload, onFailure);
    }

    private static void reload(MinecraftServer server, Runnable afterReload, Runnable onFailure) {
        try {
            ensurePackSkeleton();
            Path worldPackDir = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_ID);
            copyTree(externalPackDir(), worldPackDir);

            // ReloadCommand.reloadPacks() alone does NOT rescan disk for newly-appeared packs -
            // it only reloads content for whatever's already selected. Rediscovery only happens
            // inside vanilla's private ReloadCommand.discoverNewPacks(), which real /reload calls
            // first. On a brand new world, this pack's folder didn't exist yet when the world's
            // initial pack list was built, so without replicating that discovery step ourselves,
            // codex_advancements would never get selected for that server's entire lifetime -
            // reloadPacks would just keep re-selecting the same stale (pack-less) set forever.
            PackRepository repository = server.getPackRepository();
            repository.reload();
            List<String> disabled = server.getWorldData().getDataConfiguration().dataPacks().getDisabled();
            List<String> selected = new ArrayList<>(repository.getSelectedIds());
            for (String id : repository.getAvailableIds()) {
                if (!disabled.contains(id) && !selected.contains(id)) {
                    selected.add(id);
                }
            }
            // Not going through ReloadCommand.reloadPacks() here (which does exactly this
            // internally, then discards the future) - reloadResources() is asynchronous, and we
            // need to run our own follow-up only once it actually completes: an already-connected
            // player's PlayerAdvancements otherwise kept running against the pre-reload data for a
            // moment, so toggling edit mode back to view mode (or Delete) too soon still showed the
            // old tree until the player fully left and rejoined.
            server.reloadResources(selected).thenRun(() -> {
                // Same thing PlayerList's own reloadResources() does after a real /reload -
                // refreshes every already-connected player's advancement progress against the new
                // ServerAdvancementManager, so new/edited/deleted advancements show up immediately.
                server.getPlayerList().reloadResources();
                // PlayerAdvancements.reload() marks its first advancement packet dirty, but does
                // not itself send it.  Waiting for the next regular player tick left the client
                // showing the old title/description (and sometimes the old tree) while the editor
                // snapshot already contained the new data.  Flush it now, before acknowledging a
                // save, so view and edit mode describe the same advancement definition.
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    player.getAdvancements().flushDirty(player, true);
                    CodexStatisticTasks.sendProgress(player);
                }
                broadcastRewards(server);
                broadcastRequiredItems(server);
                broadcastLayout(server);
                afterReload.run();
            }).exceptionally(throwable -> {
                LOGGER.error("Failed to reload the codex advancement datapack", throwable);
                onFailure.run();
                return null;
            });
        } catch (IOException e) {
            LOGGER.error("Failed to sync the codex advancement datapack", e);
            onFailure.run();
        }
    }

    /**
     * Sends every advancement's actual reward contents to every connected player - not gated
     * behind op/editor permissions, since this is what lets a normal player's hover show rewards
     * at all (see {@link AdvancementRewardsPayload}'s own doc comment for why: vanilla's real
     * client-side {@code Advancement} object never carries real reward data, confirmed via
     * bytecode, so {@code node.advancement().rewards()} is always empty in view mode regardless of
     * what's actually saved).
     */
    public static void broadcastRewards(MinecraftServer server) {
        AdvancementRewardsPayload payload = buildRewardsPayload(server);
        NetworkManager.sendToPlayers(server.getPlayerList().getPlayers(), payload);
    }

    /** Same as {@link #broadcastRewards}, but for a single just-joined player who wouldn't
     * otherwise see the current state until the next reload. */
    public static void sendRewardsTo(ServerPlayer player) {
        NetworkManager.sendToPlayer(player, buildRewardsPayload(player.level().getServer()));
    }

    public static void broadcastLayout(MinecraftServer server) {
        NetworkManager.sendToPlayers(server.getPlayerList().getPlayers(), buildLayoutPayload());
    }

    public static void sendLayoutTo(ServerPlayer player) {
        NetworkManager.sendToPlayer(player, buildLayoutPayload());
    }

    /** Refreshes after login, advancement completion, and a successful claim. */
    public static void sendClaimableRewardsTo(ServerPlayer player) {
        Path packDir = externalPackDir();
        var rewardDefinitions = CodexQuestIO.readClaimableRewards(packDir);
        var claimed = CodexQuestIO.readClaimIndexes(packDir, player.getUUID());
        List<ClaimableRewardsPayload.Claimable> available = new ArrayList<>();
        for (var definition : rewardDefinitions.entrySet()) {
            Identifier id = definition.getKey();
            AdvancementHolder holder = player.level().getServer().getAdvancements().get(id);
            if (holder != null && player.getAdvancements().getOrStartProgress(holder).isDone()) {
                java.util.Set<Integer> alreadyClaimed = claimed.getOrDefault(id, java.util.Set.of());
                if (alreadyClaimed.contains(-1)) continue;
                int count = net.vg.codex.network.AdvancementEditorNetworking.rewardCount(packDir, id, definition.getValue());
                List<Integer> indexes = new ArrayList<>();
                for (int index = 0; index < count; index++) if (!alreadyClaimed.contains(index)) indexes.add(index);
                if (!indexes.isEmpty()) available.add(new ClaimableRewardsPayload.Claimable(id, indexes));
            }
        }
        NetworkManager.sendToPlayer(player, new ClaimableRewardsPayload(available));
    }

    public static void sendSubmissionTasksTo(ServerPlayer player) {
        Path packDir = externalPackDir();
        Map<String, JsonObject> definitions = CodexQuestIO.readSubmissionTasks(packDir);
        Map<String, Map<Identifier, Integer>> progress = CodexQuestIO.readSubmissionProgress(packDir,
                CodexPartyService.progressOwner(player.level().getServer(), player.getUUID()));
        List<SubmissionTasksPayload.Task> tasks = new ArrayList<>();
        for (var entry : definitions.entrySet()) {
            int split = entry.getKey().lastIndexOf('#');
            if (split < 1) continue;
            try {
                Identifier id = Identifier.parse(entry.getKey().substring(0, split));
                List<SubmissionTasksPayload.ItemProgress> items = new ArrayList<>();
                for (JsonElement element : entry.getValue().getAsJsonArray("items")) {
                    JsonObject item = element.getAsJsonObject();
                    Identifier itemId = Identifier.parse(item.get("id").getAsString());
                    items.add(new SubmissionTasksPayload.ItemProgress(itemId, item.get("count").getAsInt(),
                            progress.getOrDefault(entry.getKey(), Map.of()).getOrDefault(itemId, 0)));
                }
                tasks.add(new SubmissionTasksPayload.Task(id, entry.getKey().substring(split + 1), items));
            } catch (RuntimeException ignored) { }
        }
        NetworkManager.sendToPlayer(player, new SubmissionTasksPayload(tasks));
    }

    /** Recipes are server data, so the client editor receives ids explicitly instead of guessing
     * from its limited {@code RecipeAccess} view. */
    public static void sendRecipeIdsTo(ServerPlayer player) {
        List<Identifier> ids = player.level().getServer().getRecipeManager().getRecipes().stream()
                .map(recipe -> recipe.id().identifier()).sorted().toList();
        NetworkManager.sendToPlayer(player, new RecipeIdsPayload(ids));
    }

    private static AdvancementLayoutPayload buildLayoutPayload() {
        List<AdvancementLayoutPayload.NodeLayout> entries = new ArrayList<>();
        for (var entry : AdvancementPackIO.readLayout(externalPackDir()).entrySet()) {
            entries.add(new AdvancementLayoutPayload.NodeLayout(entry.getKey(), entry.getValue().x(), entry.getValue().y()));
        }
        return new AdvancementLayoutPayload(entries);
    }

    private static AdvancementRewardsPayload buildRewardsPayload(MinecraftServer server) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        Path packDir = externalPackDir();
        Map<Identifier, JsonObject> claimableDefinitions = CodexQuestIO.readClaimableRewards(packDir);
        List<AdvancementRewardsPayload.AdvancementReward> rewards = new ArrayList<>();
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            try {
                JsonElement json = Advancement.CODEC.encodeStart(ops, holder.value()).getOrThrow();
                JsonObject obj = json.getAsJsonObject();
                // Claimable rewards are deliberately removed from vanilla's advancement payload
                // to prevent auto-granting. For the client hover, however, expose the stored
                // definition exactly like ordinary rewards so players can see what awaits them.
                JsonObject rewardsJson = claimableDefinitions.get(holder.id());
                if (rewardsJson == null) {
                    if (!obj.has("rewards") || !obj.get("rewards").isJsonObject()) {
                        continue;
                    }
                    rewardsJson = obj.getAsJsonObject("rewards");
                }
                int experience = rewardsJson.has("experience") ? rewardsJson.get("experience").getAsInt() : 0;

                List<Identifier> recipes = new ArrayList<>();
                if (rewardsJson.has("recipes") && rewardsJson.get("recipes").isJsonArray()) {
                    for (JsonElement e : rewardsJson.getAsJsonArray("recipes")) {
                        recipes.add(Identifier.parse(e.getAsString()));
                    }
                }

                // The generated loot table's own id is excluded here - it's not a real reward the
                // player should see named as a loot table, it's the mechanism item rewards (below)
                // are actually granted through; showing both would just be the same reward twice.
                Identifier generatedLootId = AdvancementPackIO.generatedLootTableId(holder.id());
                List<Identifier> loot = new ArrayList<>();
                if (rewardsJson.has("loot") && rewardsJson.get("loot").isJsonArray()) {
                    for (JsonElement e : rewardsJson.getAsJsonArray("loot")) {
                        Identifier lootId = Identifier.parse(e.getAsString());
                        if (!lootId.equals(generatedLootId)) {
                            loot.add(lootId);
                        }
                    }
                }

                Optional<Identifier> function = rewardsJson.has("function")
                        ? Optional.of(Identifier.parse(rewardsJson.get("function").getAsString()))
                        : Optional.empty();

                List<AdvancementRewardsPayload.ItemReward> items = new ArrayList<>();
                JsonArray directItems = rewardsJson.has("codex_items") && rewardsJson.get("codex_items").isJsonArray()
                        ? rewardsJson.getAsJsonArray("codex_items") : null;
                Iterable<JsonElement> itemDefinitions = directItems != null
                        ? directItems : AdvancementPackIO.readGeneratedItemRewards(packDir, holder.id());
                for (JsonElement e : itemDefinitions) {
                    JsonObject item = e.getAsJsonObject();
                    Identifier itemId = Identifier.parse(item.get("id").getAsString());
                    int count = item.has("count") ? item.get("count").getAsInt() : 1;
                    items.add(new AdvancementRewardsPayload.ItemReward(itemId, count));
                }

                if (experience > 0 || !recipes.isEmpty() || !loot.isEmpty() || function.isPresent() || !items.isEmpty()) {
                    rewards.add(new AdvancementRewardsPayload.AdvancementReward(holder.id(), experience, recipes, loot, function, items));
                }
            } catch (RuntimeException e) {
                LOGGER.error("Failed to extract rewards for {}", holder.id(), e);
            }
        }
        return new AdvancementRewardsPayload(rewards);
    }

    /**
     * Sends every advancement's required-item hints (see
     * {@link AdvancementPackIO#extractCriterionItemId}) to every connected player - same
     * not-op-gated reasoning as {@link #broadcastRewards}.
     */
    public static void broadcastRequiredItems(MinecraftServer server) {
        AdvancementRequiredItemsPayload payload = buildRequiredItemsPayload(server);
        NetworkManager.sendToPlayers(server.getPlayerList().getPlayers(), payload);
    }

    /** Same as {@link #broadcastRequiredItems}, but for a single just-joined player. */
    public static void sendRequiredItemsTo(ServerPlayer player) {
        NetworkManager.sendToPlayer(player, buildRequiredItemsPayload(player.level().getServer()));
    }

    private static AdvancementRequiredItemsPayload buildRequiredItemsPayload(MinecraftServer server) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        List<AdvancementRequiredItemsPayload.AdvancementRequired> required = new ArrayList<>();
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            try {
                JsonElement json = Advancement.CODEC.encodeStart(ops, holder.value()).getOrThrow();
                JsonObject obj = json.getAsJsonObject();
                if (!obj.has("criteria") || !obj.get("criteria").isJsonObject()) {
                    continue;
                }
                List<Identifier> itemIds = new ArrayList<>();
                List<AdvancementRequiredItemsPayload.CriterionHint> hints = new ArrayList<>();
                for (var entry : obj.getAsJsonObject("criteria").entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject criterion = entry.getValue().getAsJsonObject();
                    AdvancementPackIO.extractCriterionItemId(criterion).ifPresent(itemIds::add);
                    if (criterion.has("trigger")
                            && "minecraft:recipe_unlocked".equals(criterion.get("trigger").getAsString())
                            && criterion.has("conditions") && criterion.get("conditions").isJsonObject()) {
                        JsonObject conditions = criterion.getAsJsonObject("conditions");
                        if (conditions.has("recipe")) {
                            hints.add(new AdvancementRequiredItemsPayload.CriterionHint(
                                    Identifier.withDefaultNamespace("knowledge_book"),
                                    "Unlock recipe: " + conditions.get("recipe").getAsString()));
                        }
                    }
                }
                if (!itemIds.isEmpty() || !hints.isEmpty()) {
                    required.add(new AdvancementRequiredItemsPayload.AdvancementRequired(holder.id(), itemIds, hints));
                }
            } catch (RuntimeException e) {
                LOGGER.error("Failed to extract required-item hints for {}", holder.id(), e);
            }
        }
        return new AdvancementRequiredItemsPayload(required);
    }

    private static void ensurePackSkeleton() throws IOException {
        Path dir = externalPackDir();
        Files.createDirectories(dir);
        // Regenerated on every sync (not just when missing) via the game's own metadata codec,
        // rather than a hand-written pack_format integer - this MC version requires min_format/
        // max_format once the format number is past a threshold, and hand-writing that schema
        // risks going stale again on a future MC update the same way pack_format alone did.
        var sectionType = PackMetadataSection.forPackType(PackType.SERVER_DATA);
        PackFormat format = PackFormat.of(SharedConstants.DATA_PACK_FORMAT_MAJOR, SharedConstants.DATA_PACK_FORMAT_MINOR);
        PackMetadataSection section = new PackMetadataSection(
                Component.literal("Codex custom advancements"),
                new InclusiveRange<>(format)
        );
        JsonElement sectionJson = sectionType.codec().encodeStart(JsonOps.INSTANCE, section).getOrThrow();
        JsonObject root = new JsonObject();
        root.add(sectionType.name(), sectionJson);
        Files.writeString(dir.resolve("pack.mcmeta"), GSON.toJson(root), StandardCharsets.UTF_8);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path src : (Iterable<Path>) stream::iterator) {
                Path dest = target.resolve(source.relativize(src));
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        // Mirror deletions too - the loop above is copy-only, so a file removed from the editable
        // source (via the in-game Delete button) would otherwise linger forever in a world's own
        // datapack copy, silently un-deleting it on every sync. Deepest paths first so a directory
        // is only removed after everything inside it has already been handled.
        try (Stream<Path> stream = Files.walk(target)) {
            for (Path dest : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (dest.equals(target)) {
                    continue;
                }
                Path src = source.resolve(target.relativize(dest));
                if (Files.exists(src)) {
                    continue;
                }
                if (Files.isDirectory(dest)) {
                    try (Stream<Path> children = Files.list(dest)) {
                        if (children.findAny().isPresent()) {
                            continue;
                        }
                    }
                }
                Files.delete(dest);
            }
        }
    }
}
