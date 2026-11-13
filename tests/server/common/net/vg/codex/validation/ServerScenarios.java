package net.vg.codex.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.architectury.event.events.common.TickEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.21 {
import net.minecraft.server.network.CommonListenerCookie;
//?}
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.vg.codex.advancement.*;
import net.vg.codex.party.CodexPartyService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Real server/registries/inventories, embedded simulated connections; no graphical clients. */
public final class ServerScenarios {
    private static final UUID ALICE = UUID.fromString("7ee01a01-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("7ee01a01-0000-4000-8000-000000000002");
    private static final Identifier DELIVERY = Identifier.parse("codex_test:delivery");
    private static final Identifier REWARD = Identifier.parse("codex_test:reward");
    private static final List<String> PASSED = new ArrayList<>();
    private static final List<String> FAILURES = new ArrayList<>();
    private static final Map<UUID, List<net.minecraft.network.protocol.common.custom.CustomPacketPayload>> QUEST_PACKETS = new HashMap<>();
    private static int ticks;
    private static boolean ran;

    private static java.util.function.Consumer<Connection> configureConnection = connection -> { };
    public static void install() { install(connection -> { }); }
    public static void install(java.util.function.Consumer<Connection> configure) {
        configureConnection = configure;
        if (!Boolean.getBoolean("codex.test.migration_probe") && !Boolean.getBoolean("codex.test.recipe_probe") && !Boolean.getBoolean("codex.test.bundled_probe")) fixtures();
        if (Boolean.getBoolean("codex.test.transport")) { TransportServer.install(); return; }
        // Architectury's development transformer owns non-daemon worker pools. End only this
        // opt-in test JVM after the server's normal save/close sequence has completed.
        TickEvent.SERVER_POST.register(server -> {
            if (ran || ++ticks < 50) return;
            ran = true;
            try { if (Boolean.getBoolean("codex.test.bundled_probe")) BundledExampleScenarios.run(server, ServerScenarios::check); else if (Boolean.getBoolean("codex.test.recipe_probe")) RecipeScenarios.run(server, ServerScenarios::check); else if (Boolean.getBoolean("codex.test.migration_probe")) migrationProbe(server); else run(server); } catch (Throwable error) { FAILURES.add("Unexpected scenario failure: " + error); error.printStackTrace(); }
            JsonObject report = new JsonObject();
            JsonArray passed = new JsonArray(); PASSED.forEach(passed::add);
            JsonArray failures = new JsonArray(); FAILURES.forEach(failures::add);
            report.add("passed", passed); report.add("failures", failures);
            report.addProperty("players", Boolean.getBoolean("codex.test.bundled_probe") ? "native server example loading; no player clients" : Boolean.getBoolean("codex.test.recipe_probe") ? "native server recipe matching; no player clients" : "two simulated players using embedded connections; no real clients");
            AtomicJson.write(Path.of("results.json"), report);
            Thread shutdown=new Thread(() -> { server.halt(true); System.exit(FAILURES.isEmpty() ? 0 : 1); },"codex-test-shutdown");shutdown.setDaemon(true);shutdown.start();
        });
    }

    private static void migrationProbe(MinecraftServer server) {
        String nameA="CodexTransportA",nameB="CodexTransportB";
        UUID uuidA=UUID.nameUUIDFromBytes(("OfflinePlayer:"+nameA).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID uuidB=UUID.nameUUIDFromBytes(("OfflinePlayer:"+nameB).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ServerPlayer a=player(server,uuidA,nameA),b=player(server,uuidB,nameB);
        Identifier renamed=Identifier.parse(System.getProperty("codex.test.migration_target","codex_test:reward_renamed"));
        var holder=server.getAdvancements().get(renamed);
        check("Migrated advancement loads on a fresh dedicated server",holder!=null);
        check("Old custom advancement resource is absent",server.getAdvancements().get(Identifier.parse("codex_test:reward"))==null);
        check("Migrated native criterion and requirement group load",holder!=null&&holder.value().criteria().containsKey("done_migrated")&&holder.value().requirements().names().contains("done_migrated"));
        if(System.getProperty("codex.test.world","world-a").equals("world-b")){
            check("Independent world did not inherit migrated native completion",!a.getAdvancements().getOrStartProgress(holder).isDone()&&!b.getAdvancements().getOrStartProgress(holder).isDone());
            check("Independent world cannot claim another world's migrated reward",QuestTransactions.claim(a,renamed,-1)==0&&count(a,Items.DIAMOND)==0);
            return;
        }
        check("Completed criterion survives ID/key migration and dedicated restart",a.getAdvancements().getOrStartProgress(holder).isDone()&&b.getAdvancements().getOrStartProgress(holder).isDone());
        check("Native reload does not regrant completed reward items",count(a,Items.DIAMOND)==2);
        check("Sealed migrated claims prevent reward replay",QuestRuntime.allClaimed(a,renamed)&&QuestTransactions.claim(a,renamed,-1)==0&&count(a,Items.DIAMOND)==2);
        int before=count(b,Items.DIAMOND);
        check("Unclaimed participant retains legitimate migrated entitlement",QuestTransactions.claim(b,renamed,-1)==5&&count(b,Items.DIAMOND)==before+2);
        check("New migrated claim also rejects a duplicate",QuestTransactions.claim(b,renamed,-1)==0&&count(b,Items.DIAMOND)==before+2);
    }

    private static void fixtures() {
        Path pack = AdvancementPackSync.externalPackDir();
        for (String name : List.of("delivery", "reward", "normal", "random_auto", "partial", "failed_reward", "identity", "feedback_delivery", "predicate_delivery", "each_delivery", "conditional_story", "gui_story", "gui_chapter")) {
            JsonObject definition = json("""
                {"criteria":{"done":{"trigger":"minecraft:impossible"}},
                 "display":{"icon":{"id":"minecraft:book"},"title":"Server scenario","description":"test","background":"minecraft:block/stone","show_toast":false,"announce_to_chat":false}}
                """);
            if(name.equals("gui_story"))definition.getAsJsonObject("display").addProperty("title","GUI Story Test");
            if(name.equals("gui_chapter"))definition.getAsJsonObject("display").addProperty("title","GUI Chapter Test");
            if(name.equals("reward"))definition.getAsJsonObject("display").addProperty("title","Reward Scenario");
            if (name.equals("partial")) definition.add("criteria", json("{\"one\":{\"trigger\":\"minecraft:impossible\"},\"two\":{\"trigger\":\"minecraft:impossible\"}}"));
            if (name.equals("normal")) definition.add("criteria", json("{\"stone\":{\"trigger\":\"minecraft:inventory_changed\",\"conditions\":{\"items\":[{\"items\":\"minecraft:stone\"}]}}}"));
            if (name.equals("feedback_delivery")) definition.add("rewards", json("{\"function\":\"codex_test:refund\"}"));
            if (name.equals("random_auto")) definition.add("rewards", json("{\"loot\":[\"codex_test:codex_treasure/trial\"]}"));
            AtomicJson.write(pack.resolve("data/codex_test/advancement/" + name + ".json"), definition);
        }
        JsonObject scaleRules=QuestDefinitions.all();
        for (String key : List.of("root", "child", "hidden")) {
            JsonObject definition = json("{\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}},\"display\":{\"icon\":{\"id\":\"minecraft:book\"},\"title\":\"Tab summary test\",\"description\":\"test\",\"background\":\"minecraft:block/stone\"}}");
            if (!key.equals("root")) { definition.addProperty("parent", "codex_test:tab/root"); definition.getAsJsonObject("display").remove("background"); }
            if (key.equals("hidden")) definition.remove("display");
            AtomicJson.write(pack.resolve("data/codex_test/advancement/tab/"+key+".json"), definition);
        }
        scaleRules.add("codex_test:tab/root", json("{\"quest_tab\":true}"));
        for(int i=0;i<=500;i++) {
            String path=i==0?"scale/root":"scale/node_"+i;
            JsonObject d=json("{\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}},\"display\":{\"icon\":{\"id\":\"minecraft:book\"},\"title\":\"Scale scenario\",\"description\":\"Test-only overview node\",\"show_toast\":false,\"announce_to_chat\":false}}");
            if(i>0)d.addProperty("parent","codex_test:scale/root");else d.getAsJsonObject("display").addProperty("background","minecraft:block/stone");
            AtomicJson.write(pack.resolve("data/codex_test/advancement/"+path+".json"),d);scaleRules.add("codex_test:"+path,json("{\"acceptance\":true}"));
        }
        AtomicJson.write(pack.resolve("codex/quests.json"),scaleRules);
        JsonObject rewards = json("{\"experience\":7,\"codex_items\":[{\"id\":\"minecraft:diamond\",\"count\":2}],\"recipes\":[\"minecraft:crafting_table\"],\"function\":\"codex_test:reward\"}");
        rewards.add("loot", JsonParser.parseString("[\"codex_test:codex_treasure/trial\"]"));
        TreasureLists.save(pack, Identifier.parse("codex_test:trial"), json("{\"name\":\"Trial Supplies\",\"rolls\":1,\"entries\":[{\"id\":\"minecraft:apple\",\"min\":2,\"max\":4,\"weight\":1},{\"id\":\"minecraft:bread\",\"min\":2,\"max\":4,\"weight\":3}]}"), Set.of());
        var rewardDefinitions = CodexQuestIO.readClaimableRewards(pack); rewardDefinitions.put(REWARD, rewards);
        CodexQuestIO.writeClaimableRewards(pack, rewardDefinitions);
        var submissionDefinitions = CodexQuestIO.readSubmissionTasks(pack); submissionDefinitions.put(CodexQuestIO.taskKey(DELIVERY, "done"), json("{\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":5},{\"id\":\"minecraft:gold_ingot\",\"count\":2}]}"));
        submissionDefinitions.put(CodexQuestIO.taskKey(Identifier.parse("codex_test:feedback_delivery"), "done"), json("{\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":1}]}"));
        CodexQuestIO.writeSubmissionTasks(pack, submissionDefinitions);
        try {
            Path function = pack.resolve("data/codex_test/function/reward.mcfunction");
            Files.createDirectories(function.getParent());
            Files.writeString(function, "give @s minecraft:emerald 1\n");
            Files.writeString(function.resolveSibling("refund.mcfunction"), "give @s minecraft:iron_ingot 1\n");
        } catch (Exception error) { throw new IllegalStateException(error); }
        //? if <1.21 {
        /*LegacyFixtures.adapt(pack);
        *///?}
        //? if >=26.2 {
        /*modernFixtureSchemas(pack);
        *///?}
    }

    /** Disposable fixture imports follow the target game's schema; user packs are never migrated here. */
    private static void modernFixtureSchemas(Path pack) {
        try(var files=Files.walk(pack.resolve("data"))) {
            for(Path file:files.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".json")).toList()) {
                String relative=pack.relativize(file).toString().replace('\\','/');
                if(!relative.contains("/advancement/"))continue;
                JsonObject before=json(Files.readString(file)),doc=before.deepCopy();
                if(relative.startsWith("data/minecraft/advancement/")) {
                    try(var input=net.minecraft.advancements.Advancement.class.getResourceAsStream("/"+relative)) {
                        if(input==null)throw new IllegalStateException("Missing native fixture base "+relative);
                        doc=json(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                        if(before.has("display"))doc.add("display",before.get("display").deepCopy());
                    }
                } else if(doc.has("criteria")) {
                    for(var entry:doc.getAsJsonObject("criteria").entrySet())entry.setValue(CriterionFormats.generated(entry.getValue().getAsJsonObject()));
                }
                if(!doc.equals(before))AtomicJson.write(file,doc);
            }
        }catch(Exception error){throw new IllegalStateException("Cannot adapt isolated test fixtures",error);}
    }

    private static void run(MinecraftServer server) throws Exception {
        String phase = System.getProperty("codex.test.phase", "initial");
        Path pack = AdvancementPackSync.externalPackDir();
        Path state = QuestState.directory(server);
        ServerPlayer a = player(server, ALICE, "CodexAlice");
        ServerPlayer b = player(server, BOB, "CodexBob");
        check("Dedicated initialization; fixtures/datapack synchronized", server.getAdvancements().get(DELIVERY) != null);
        check("Ordinary vanilla advancements load", server.getAdvancements().get(Identifier.parse("minecraft:story/mine_stone")) != null);
        compatibilityScenarios(server, phase);
        if (phase.equals("independent")) {
            check("Second world has independent vanilla progress", !a.getAdvancements().getOrStartProgress(server.getAdvancements().get(REWARD)).isDone());
            check("Second world has independent quest cycles and dialogue", (QuestRuntime.states(a).size() == 0));
            check("Second world has independent claims", CodexQuestIO.readClaimIndexes(state, ALICE).isEmpty());
            check("Second world has independent submissions", CodexQuestIO.readSubmissionProgress(state, ALICE).isEmpty());
            check("Second world has independent parties", CodexPartyService.progressOwner(server, ALICE).equals(ALICE));
            return;
        }
        if (phase.equals("restart")) {
            check("Advancement completion survives dedicated restart", a.getAdvancements().getOrStartProgress(server.getAdvancements().get(REWARD)).isDone());
            Identifier supply = Identifier.parse("codex_demo:camp_chronicle/supply");
            check("Personal repeat cycle and claim survive restart", QuestRuntime.cycle(QuestRuntime.state(a, supply)) == 1 && QuestRuntime.ready(a, supply) && QuestTransactions.claim(a, supply, -1) == 0);
            Identifier communal = Identifier.parse("codex_demo:camp_chronicle/communal"), daily = Identifier.parse("codex_demo:camp_chronicle/daily");
            check("Shared cycle and group claim survive restart", QuestRuntime.ready(a, communal) && QuestRuntime.ready(b, communal) && QuestTransactions.claim(b, communal, -1) == 0);
            check("Daily schedule and claim survive restart", QuestRuntime.ready(a, daily) && !QuestRuntime.accept(a, daily) && QuestTransactions.claim(a, daily, -1) == 0);
            check("Dialogue choice survives restart", QuestRuntime.state(a, Identifier.parse("codex_demo:camp_chronicle/root")).get("choice").getAsString().equals("campkeeper"));
            check("Claims survive dedicated restart", !CodexQuestIO.readClaimIndexes(state, ALICE).isEmpty());
            check("No duplicate rewards after restart", QuestTransactions.claim(a, REWARD, -1) == 0);
            check("Player submission progress survives restart", CodexQuestIO.readSubmissionProgress(state, ALICE).get(CodexQuestIO.taskKey(DELIVERY, "done")).get(Identifier.parse("minecraft:iron_ingot")) == 5);
            check("Party membership survives restart", CodexPartyService.progressOwner(server, ALICE).equals(CodexPartyService.progressOwner(server, BOB)));
            return;
        }
        //? if <1.21 {
        /*PacketFragmentScenarios.run(ServerScenarios::check);
        *///?}
        check("Incomplete advancement cannot claim", QuestTransactions.claim(a, REWARD, -1) == 0);
        check("Separate story-demo tree loads as ordinary advancements", server.getAdvancements().get(Identifier.parse("codex_demo:field_journal/root")) != null && server.getAdvancements().get(Identifier.parse("codex_demo:field_journal/workshop")) != null);
        Identifier enhancedVanillaId=Identifier.parse("minecraft:story/mine_stone");
        var enhancedVanilla=server.getAdvancements().get(enhancedVanillaId);
        check("Vanilla enhancement loads without replacing vanilla objectives", enhancedVanilla.value().criteria().containsKey("get_stone"));
        documentScenarios(server);
        migrationScenarios();
        resetAndBranchScenarios(server, a, b);
        creatorAndPredicateScenarios(server, a);
        navigationScenarios(server, a);
        tabSummaryScenarios(server, a);
        refreshPerformanceScenarios(server, a);
        lifecycleScenarios(server, a, b);
        var normal = server.getAdvancements().get(Identifier.parse("codex_test:normal"));
        a.getInventory().add(new ItemStack(Items.STONE));
        net.minecraft.advancements.CriteriaTriggers.INVENTORY_CHANGED.trigger(a, a.getInventory(), new ItemStack(Items.STONE));
        check("Native inventory trigger completes without a Codex task", a.getAdvancements().getOrStartProgress(normal).isDone());
        a.getInventory().add(new ItemStack(Items.IRON_INGOT, 3));
        check("Partial submission consumes exactly available items", QuestTransactions.submit(a, DELIVERY, "done", Optional.empty()) == 3 && a.getInventory().countItem(Items.IRON_INGOT) == 0);
        check("Partial progress stays incomplete", !a.getAdvancements().getOrStartProgress(server.getAdvancements().get(DELIVERY)).isDone());
        a.getInventory().add(new ItemStack(Items.IRON_INGOT, 4));
        a.getInventory().add(new ItemStack(Items.GOLD_INGOT, 2));
        check("Submission caps requirements and completes criterion", QuestTransactions.submit(a, DELIVERY, "done", Optional.empty()) == 4 && a.getInventory().countItem(Items.IRON_INGOT) == 2 && a.getAdvancements().getOrStartProgress(server.getAdvancements().get(DELIVERY)).isDone());
        check("Repeated submission consumes nothing", QuestTransactions.submit(a, DELIVERY, "done", Optional.empty()) == 0);
        Identifier feedback = Identifier.parse("codex_test:feedback_delivery"); int ironBefore = a.getInventory().countItem(Items.IRON_INGOT);
        JsonObject receipt = QuestActions.perform(a, feedback, "submit", "done", Optional.empty(), -1);
        check("Delivery receipt reports consumed items even when a native reward refunds them", receipt.get("success").getAsBoolean() && receipt.getAsJsonObject("items").get("minecraft:iron_ingot").getAsInt() == 1 && a.getInventory().countItem(Items.IRON_INGOT) == ironBefore);
        check("Invalid criterion is rejected", QuestTransactions.submit(a, DELIVERY, "does_not_exist", Optional.empty()) == 0);
        var tasks = CodexQuestIO.readSubmissionTasks(pack);
        tasks.put(CodexQuestIO.taskKey(DELIVERY, "done"), json("{\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":5},{\"id\":\"missing:no_item\",\"count\":2}]}"));
        CodexQuestIO.writeSubmissionTasks(pack, tasks);
        b.getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
        boolean rejected = false;
        try { QuestTransactions.submit(b, DELIVERY, "done", Optional.empty()); } catch (RuntimeException expected) { rejected = true; }
        check("Malformed later row rejects entire task without consuming inventory", rejected && b.getInventory().countItem(Items.IRON_INGOT) == 2);
        fixtures();
        // Simulate denied storage writes with a directory at the expected JSON file.
        Path blockedSubmission = state.resolve("codex/submissions/" + BOB + ".json");
        Files.createDirectories(blockedSubmission); rejected = false;
        try { QuestTransactions.submit(b, DELIVERY, "done", Optional.empty()); } catch (RuntimeException expected) { rejected = true; }
        check("Unwritable submission state never consumes inventory", rejected && b.getInventory().countItem(Items.IRON_INGOT) == 2);
        Files.delete(blockedSubmission);
        b.getAdvancements().award(server.getAdvancements().get(REWARD), "done");
        Path blockedClaims = state.resolve("codex/claims/" + BOB + ".json"); Files.createDirectories(blockedClaims);
        int bobXp = b.totalExperience;
        check("Unwritable claim state never grants rewards", QuestTransactions.claim(b, REWARD, -1) == 0 && b.totalExperience == bobXp && b.getInventory().countItem(Items.DIAMOND) == 0);
        Files.delete(blockedClaims);
        a.getAdvancements().award(server.getAdvancements().get(REWARD), "done");
        int xp = a.totalExperience;
        check("Claim item/XP/recipe/function slices", QuestTransactions.claim(a, REWARD, -1) == 5);
        check("Claim grants expected XP and items", a.totalExperience == xp + 7 && a.getInventory().countItem(Items.DIAMOND) == 2);
        check("Loot treasure uses the registered server table and bounded quantity", a.getInventory().countItem(Items.APPLE) + a.getInventory().countItem(Items.BREAD) >= 2 && a.getInventory().countItem(Items.APPLE) + a.getInventory().countItem(Items.BREAD) <= 4);
        boolean recipeKnown;
        //? if <26.1 {
        /*recipeKnown = a.getRecipeBook().contains(Identifier.parse("minecraft:crafting_table"));
        *///?} else {
        recipeKnown = a.getRecipeBook().contains(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, Identifier.parse("minecraft:crafting_table")));
        //?}
        check("Function and recipe rewards execute", a.getInventory().countItem(Items.EMERALD) == 1 && recipeKnown);
        check("Double claim is rejected", QuestTransactions.claim(a, REWARD, -1) == 0);
        check("Negative and oversized claim indexes are rejected", QuestTransactions.claim(a, REWARD, -2) == 0 && QuestTransactions.claim(a, REWARD, Integer.MAX_VALUE) == 0);
        check("Other player has separate claim ledger", CodexQuestIO.readClaimIndexes(state, BOB).isEmpty());
        var automatic = server.getAdvancements().get(Identifier.parse("codex_test:random_auto"));
        int beforeTreasure = a.getInventory().countItem(Items.APPLE) + a.getInventory().countItem(Items.BREAD);
        a.getAdvancements().award(automatic, "done");
        int generatedTreasure = a.getInventory().countItem(Items.APPLE) + a.getInventory().countItem(Items.BREAD) - beforeTreasure;
        check("Automatic random treasure uses vanilla advancement rewards", generatedTreasure >= 2 && generatedTreasure <= 4);
        Identifier failedReward = Identifier.parse("codex_test:failed_reward");
        var invalidRewards = CodexQuestIO.readClaimableRewards(pack);
        invalidRewards.put(failedReward, json("{\"experience\":3,\"loot\":[\"missing:unknown_table\"]}"));
        CodexQuestIO.writeClaimableRewards(pack, invalidRewards);
        b.getAdvancements().award(server.getAdvancements().get(failedReward), "done");
        int beforeInvalid = b.totalExperience;
        check("Invalid later reward is rejected before any XP or claim reservation", QuestTransactions.claim(b, failedReward, -1) == 0 && b.totalExperience == beforeInvalid && !CodexQuestIO.readClaimIndexes(state, BOB).containsKey(failedReward));
        invalidRewards.put(failedReward, json("{\"experience\":3,\"loot\":[\"codex_test:codex_treasure/trial\"]}"));
        CodexQuestIO.writeClaimableRewards(pack, invalidRewards);
        check("Corrected reward can be claimed once after validation failure", QuestTransactions.claim(b, failedReward, -1) == 2 && b.totalExperience == beforeInvalid + 3 && QuestTransactions.claim(b, failedReward, -1) == 0);
        CodexPartyService.invite(a, b); CodexPartyService.accept(b);
        check("Party invite/accept and shared owner", !CodexPartyService.progressOwner(server, ALICE).equals(ALICE) && CodexPartyService.progressOwner(server, ALICE).equals(CodexPartyService.progressOwner(server, BOB)));
        var vanilla = server.getAdvancements().get(Identifier.parse("minecraft:story/mine_stone"));
        for (String criterion : vanilla.value().criteria().keySet()) a.getAdvancements().award(vanilla, criterion);
        check("Ordinary vanilla advancement shares to party", b.getAdvancements().getOrStartProgress(vanilla).isDone());
        check("Party completion does not share personal reward claims", QuestTransactions.claim(b, REWARD, -1) == 5);
        UUID partyOwner = CodexPartyService.progressOwner(server, ALICE);
        var importedBefore = CodexQuestIO.readSubmissionProgress(state, partyOwner);
        CodexPartyService.leave(b);
        check("Leaving party restores personal owner", CodexPartyService.progressOwner(server, BOB).equals(BOB));
        CodexPartyService.invite(a, b); CodexPartyService.accept(b);
        CodexPartyService.leave(a); CodexPartyService.invite(b, a); CodexPartyService.accept(a);
        check("Rejoining the same party does not re-import deliveries", CodexQuestIO.readSubmissionProgress(state, partyOwner).equals(importedBefore));
        server.getPlayerList().remove(b);
        var offlineMilestone = server.getAdvancements().get(Identifier.parse("codex_test:partial"));
        a.getAdvancements().award(offlineMilestone, "one");
        for (int i = 0; i < 20; i++) CodexPartyService.tick(server);
        server.getPlayerList().remove(a);
        b = player(server, BOB, "CodexBob");
        check("Partial party criteria reach a reconnecting member while source is offline", b.getAdvancements().getOrStartProgress(offlineMilestone).getCriterion("one").isDone() && !b.getAdvancements().getOrStartProgress(offlineMilestone).isDone());
        check("Native partial progress enables in-progress dialogue without acceptance", QuestRuntime.info(b, offlineMilestone.id()).get("dialogue_started").getAsBoolean());
        a = player(server, ALICE, "CodexAlice");
        check("Disconnect/reconnect retains advancement and prevents duplicate claims", a.getAdvancements().getOrStartProgress(server.getAdvancements().get(REWARD)).isDone() && QuestTransactions.claim(a, REWARD, -1) == 0);
        sharedCycleScenarios(server, a, b);
        extensionScenarios(server,a,b);
        exampleUpgradeScenarios();
        // Legacy files remain readable, and malformed ledgers never become empty unclaimed state.
        Path isolated = Files.createTempDirectory("codex-state-test");
        Path legacy = isolated.resolve("codex/claims/" + ALICE + ".json"); Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "[\"codex_test:reward\"]");
        check("Legacy whole-quest claim migration is read-only", CodexQuestIO.readClaimIndexes(isolated, ALICE).get(REWARD).contains(-1));
        Files.writeString(legacy, "{broken"); rejected = false;
        try { CodexQuestIO.readClaimIndexes(isolated, ALICE); } catch (RuntimeException expected) { rejected = true; }
        check("Corrupt claims fail closed", rejected);
    }

    private static void compatibilityScenarios(MinecraftServer server, String phase) throws Exception {
        String requested = System.getProperty("codex.test.compat", "");
        if (requested.isBlank()) return;
        Path pack = AdvancementPackSync.externalPackDir();
        for (String namespace : requested.split(",")) {
            var holders = server.getAdvancements().getAllAdvancements().stream().filter(holder -> holder.id().getNamespace().equals(namespace)).toList();
            long sourceCount = server.getResourceManager().listResources("advancement", resource -> resource.getNamespace().equals(namespace) && resource.getPath().endsWith(".json")).size();
            check(namespace + " native advancements load without conversion (" + holders.size() + ")", !holders.isEmpty() && holders.size() == sourceCount);
            var root = holders.stream().filter(holder -> holder.value().display().isPresent() && holder.value().parent().isEmpty()).findFirst().orElseThrow();
            String description = "Codex compatibility: a richer journal entry, with the mod's original objectives preserved.";
            if (phase.equals("restart")) {
                check(namespace + " edited presentation survives reload with native criteria", root.value().display().orElseThrow().getDescription().getString().equals(description));
            } else if (phase.equals("initial")) {
                JsonObject original = AdvancementPackIO.readBaseJson(pack, server.getResourceManager(), root.id());
                JsonObject edited = original.deepCopy(); edited.getAsJsonObject("display").addProperty("description", description);
                AdvancementValidation.validate(edited, AdvancementValidation.registries(server));
                AdvancementPackIO.patchRawFields(pack, server.getResourceManager(), root.id(), edited);
                JsonObject saved = AdvancementPackIO.readBaseJson(pack, server.getResourceManager(), root.id());
                check(namespace + " native definition can be enhanced without losing criteria/requirements", saved.get("criteria").equals(original.get("criteria")) && Objects.equals(saved.get("requirements"), original.get("requirements")));
            }
        }
    }

    private static void documentScenarios(MinecraftServer server) throws Exception {
        Path pack = Files.createTempDirectory("codex-doc-test");
        Identifier id = Identifier.parse("codex_test:mixed");
        JsonObject source = json("{\"criteria\":{\"a\":{\"trigger\":\"minecraft:impossible\"},\"b\":{\"trigger\":\"minecraft:impossible\"},\"c\":{\"trigger\":\"minecraft:impossible\"}},\"requirements\":[[\"a\",\"b\"],[\"c\"]],\"mod_custom\":{\"opaque\":[1,2,3]}}");
        AdvancementPackIO.writeRaw(pack, id, source, AdvancementValidation.registries(server));
        JsonObject edited = new JsonObject(); edited.addProperty("sends_telemetry_event", false);
        AdvancementPackIO.patchRawFields(pack, server.getResourceManager(), id, edited);
        JsonObject saved = AdvancementPackIO.readBaseJson(pack, server.getResourceManager(), id);
        check("Editing preserves unknown advancement fields and criterion expressions", saved.get("mod_custom").equals(source.get("mod_custom")) && saved.get("criteria").equals(source.get("criteria")));
        check("Mixed AND/OR groups survive Apply unchanged", AdvancementDocument.requirements(source, source.getAsJsonObject("criteria").keySet(), AdvancementDocument.Requirements.CUSTOM).equals(source.get("requirements")));
        for (var template : QuestTemplates.Type.values()) {
            JsonObject templateDefinition = new JsonObject(); templateDefinition.add("criteria", QuestTemplates.criteria(template));
            AdvancementValidation.validate(templateDefinition, AdvancementValidation.registries(server));
        }
        check("All authoring templates validate against the real advancement codec", true);
        JsonArray renamed = AdvancementDocument.renameReference(source.getAsJsonArray("requirements"), "a", "renamed_a");
        check("Criterion rename preserves mixed requirement alternatives", renamed.equals(JsonParser.parseString("[[\"renamed_a\",\"b\"],[\"c\"]]")));
        JsonObject opaqueRewards = json("{\"experience\":0,\"recipes\":[],\"unknown_mod_field\":{\"keep\":true}}");
        check("Unchanged rewards preserve unknown fields and explicit empty values", QuestRewards.serialize(QuestRewards.entries(opaqueRewards, id), opaqueRewards, id).equals(opaqueRewards));
        AdvancementValidation.validate(saved, AdvancementValidation.registries(server));
        check("Patched advancement remains valid", true);
        boolean rejected = false;
        try { AdvancementPackIO.safeResourceFile(pack, Identifier.parse("codex_test:../escape"), "advancement"); } catch (RuntimeException expected) { rejected = true; }
        check("Resource path traversal is rejected", rejected);
        JsonObject cyclic = source.deepCopy(); cyclic.addProperty("parent", id.toString()); rejected = false;
        try { AdvancementValidation.validateParents(id, cyclic, ignored -> source); } catch (RuntimeException expected) { rejected = true; }
        check("Cyclic advancement parents are rejected before writing", rejected);
        JsonObject invalid = source.deepCopy(); invalid.getAsJsonObject("criteria").getAsJsonObject("a").addProperty("trigger", "missing:unknown");
        rejected = false; try { AdvancementValidation.validate(invalid, AdvancementValidation.registries(server)); } catch (RuntimeException expected) { rejected = true; }
        check("Invalid trigger fails preflight without changing stored data", rejected && AdvancementPackIO.readBaseJson(pack, server.getResourceManager(), id).equals(saved));
        Identifier sibling = Identifier.parse("codex_test:unrelated"); AdvancementPackIO.writeRaw(pack, sibling, source, AdvancementValidation.registries(server));
        AdvancementPackIO.deleteWithDescendants(pack, List.of(id));
        check("Deleting an advancement preserves unrelated definitions", AdvancementPackIO.readBaseJson(pack, server.getResourceManager(), sibling).equals(source));
        var buffer = ScenarioBuffers.create(io.netty.buffer.Unpooled.buffer(), server.registryAccess());
        var packet = new net.vg.codex.network.SaveAdvancementTreePayload(List.of(new net.vg.codex.network.RawAdvancement(id, source.toString())));
        net.vg.codex.network.SaveAdvancementTreePayload.STREAM_CODEC.encode(buffer, packet);
        check("Save payload preserves arbitrary source JSON on a codec round trip", net.vg.codex.network.SaveAdvancementTreePayload.STREAM_CODEC.decode(buffer).equals(packet));
        buffer.clear(); buffer.writeVarInt(513); rejected = false;
        try { net.vg.codex.network.SaveAdvancementTreePayload.STREAM_CODEC.decode(buffer); } catch (RuntimeException expected) { rejected = true; }
        check("Oversized client save lists are rejected by the decoder", rejected); buffer.release();
        JsonObject invalidTreasure = json("{\"rolls\":1,\"entries\":[{\"id\":\"missing:table\",\"table\":true,\"weight\":1}]}");
        rejected = false; try { TreasureLists.compile(invalidTreasure, Set.of()); } catch (RuntimeException expected) { rejected = true; }
        check("Missing loot-table references are rejected", rejected);
        check("Duplicate delivery rows combine into one unambiguous requirement", SubmissionPlan.requirements(json("{\"items\":[{\"id\":\"minecraft:iron_ingot\",\"count\":2},{\"id\":\"minecraft:iron_ingot\",\"count\":3}]}" )).get(Identifier.parse("minecraft:iron_ingot")) == 5);
    }

    private static List<net.minecraft.network.protocol.common.custom.CustomPacketPayload> questPackets(ServerPlayer player) {
        var packets = QUEST_PACKETS.computeIfAbsent(player.getUUID(), ignored -> new ArrayList<>());
        var copy = List.copyOf(packets); packets.clear(); return copy;
    }
    private static void request(ServerPlayer p, Identifier id, String action, String value) {
        net.vg.codex.network.QuestNetworking.handle(p, new net.vg.codex.network.QuestActionPayload(id, action, value));
    }
    private static void extensionScenarios(MinecraftServer server,ServerPlayer a,ServerPlayer b) {
        Path pack=AdvancementPackSync.externalPackDir(),world=QuestState.directory(server);
        java.util.List<ItemStack> beforeA=new java.util.ArrayList<>(),beforeB=new java.util.ArrayList<>();
        for(int i=0;i<a.getInventory().getContainerSize();i++){beforeA.add(a.getInventory().getItem(i).copy());beforeB.add(b.getInventory().getItem(i).copy());a.getInventory().setItem(i,ItemStack.EMPTY);b.getInventory().setItem(i,ItemStack.EMPTY);}
        try {
            Identifier each=Identifier.parse("codex_test:each_delivery");
            QuestDefinitions.save(each,json("{\"acceptance\":true,\"repeat\":{\"enabled\":true,\"scope\":\"party\",\"rewards\":\"each\",\"cooldown_seconds\":0}}"));
            var tasks=CodexQuestIO.readSubmissionTasks(pack);tasks.put(CodexQuestIO.taskKey(each,"done"),json("{\"items\":[{\"id\":\"minecraft:stick\",\"count\":2}]}"));CodexQuestIO.writeSubmissionTasks(pack,tasks);
            var rewards=CodexQuestIO.readClaimableRewards(pack);rewards.put(each,json("{\"codex_items\":[{\"id\":\"minecraft:apple\",\"count\":1}]}"));CodexQuestIO.writeClaimableRewards(pack,rewards);
            check("Individual party reward policy snapshots at acceptance",QuestRuntime.accept(a,each)&&QuestCycles.individual(a,each)&&QuestCycles.individual(b,each));
            a.getInventory().add(new ItemStack(Items.STICK,2));check("Individual rewards reuse one shared objective tally",QuestTransactions.submit(a,each,"done",Optional.empty())==2&&QuestRuntime.ready(b,each));
            check("First participant claims once without consuming second entitlement",QuestTransactions.claim(a,each,-1)==1&&QuestTransactions.claim(a,each,-1)==0&&count(a,Items.APPLE)==1&&!QuestRuntime.cycleClaimed(a,each));
            check("Next cycle waits for remaining participant claims",!QuestRuntime.accept(a,each));
            JsonObject edited=QuestDefinitions.get(each);edited.getAsJsonObject("repeat").addProperty("rewards","group");QuestDefinitions.save(each,edited);
            check("Policy edit cannot change already accepted reward ownership",QuestCycles.individual(a,each)&&QuestCycles.individual(b,each)&&QuestTransactions.claim(b,each,-1)==1&&QuestTransactions.claim(b,each,-1)==0&&count(b,Items.APPLE)==1);
            check("Next cycle adopts edited reward policy after both claims",QuestRuntime.cycleClaimed(a,each)&&QuestRuntime.accept(a,each)&&!QuestCycles.individual(a,each));
            a.getInventory().add(new ItemStack(Items.STICK,2));QuestTransactions.submit(a,each,"done",Optional.empty());
            check("New group policy still grants only one shared reward set",QuestTransactions.claim(a,each,-1)==1&&QuestTransactions.claim(b,each,-1)==0);
            Identifier story=Identifier.parse("codex_test:conditional_story");
            QuestDefinitions.save(story,json("{\"dialogue\":{\"choices\":[{\"id\":\"after_group\",\"label\":\"After the contract\",\"when\":[[{\"quest\":\"codex_test:each_delivery\",\"state\":\"claimed\"}],[{\"quest\":\"codex_test:normal\",\"state\":\"completed\"},{\"quest\":\"codex_test:each_delivery\",\"state\":\"completed\"}]]}]}}"));
            check("Conditional dialogue evaluates AND groups and OR alternatives",QuestRuntime.choose(a,story,"after_group"));
            JsonObject impossible=json("{\"when\":[[{\"quest\":\"codex_test:each_delivery\",\"state\":\"completed\",\"negate\":true}]]}");
            check("Negated dialogue conditions reject a completed quest",!QuestConditions.available(a,impossible));
            check("Story info contains server authoritative availability and reasons",QuestRuntime.info(a,story).getAsJsonObject("choice_availability").has("after_group")&&QuestRuntime.info(a,story).getAsJsonObject("choice_reasons").has("after_group"));
        } finally {for(int i=0;i<beforeA.size();i++){a.getInventory().setItem(i,beforeA.get(i));b.getInventory().setItem(i,beforeB.get(i));}}
        JsonObject states=QuestRuntime.states(a);Path file=world.resolve("codex/player_quests/"+a.getUUID()+".json");
        try {
            JsonObject many=states.deepCopy();for(int i=0;i<=500;i++)many.add("codex_test:"+(i==0?"scale/root":"scale/node_"+i),json("{\"accepted\":true}"));AtomicJson.write(file,many);
            long started=System.nanoTime();int reads;
            try(QuestReadScope scope=QuestReadScope.open()){net.vg.codex.network.QuestNetworking.view(a,Identifier.parse("codex_test:scale/root"));reads=scope.reads();}
            long elapsed=(System.nanoTime()-started)/1_000_000;JsonObject perf=new JsonObject();perf.addProperty("nodes",501);perf.addProperty("overview_ms",elapsed);perf.addProperty("documents_read",reads);AtomicJson.write(Path.of("performance.json"),perf);
            check("501-node overview reads a bounded number of state documents",reads<=12);
            try(QuestReadScope scope=QuestReadScope.open()){JsonObject first=QuestReadScope.read(file);JsonObject changed=first.deepCopy();changed.addProperty("cache_invalidation_probe",true);AtomicJson.write(file,changed);check("Read scope observes atomic writes immediately",QuestReadScope.read(file).has("cache_invalidation_probe"));}
        } catch(Exception error){throw new IllegalStateException(error);} finally {AtomicJson.write(file,states);net.vg.codex.network.QuestNetworking.reset();}
    }

    private static int count(ServerPlayer player, net.minecraft.world.item.Item item) {
        int count=0; for(int slot=0;slot<player.getInventory().getContainerSize();slot++) if(player.getInventory().getItem(slot).is(item)) count+=player.getInventory().getItem(slot).getCount(); return count;
    }
    private static void creatorAndPredicateScenarios(MinecraftServer server, ServerPlayer a) {
        Identifier story=Identifier.parse("codex_test:editor_story"), child=Identifier.parse("codex_test:editor_child"), copy=Identifier.parse("codex_test:editor_copy");
        JsonObject root=json("{\"criteria\":{\"done\":{\"trigger\":\"minecraft:tick\"}},\"codex_quest\":{\"dialogue\":{\"choices\":[{\"id\":\"build\",\"label\":\"Build\",\"next_quest\":\"codex_test:editor_child\",\"gate_chapter\":true}]}},\"unknown\":{\"keep\":[1,2]}}");
        JsonObject branch=json("{\"parent\":\"codex_test:editor_story\",\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}},\"codex_quest\":{\"requires_choice\":{\"quest\":\"codex_test:editor_story\",\"choice\":\"build\"}}}");
        Map<Identifier,JsonObject> definitions=new LinkedHashMap<>(); definitions.put(story,root);definitions.put(child,branch);
        check("Pack diagnostics accept coherent story and branch", PackAuthoring.diagnose(definitions,null).isEmpty());
        var copies=PackAuthoring.duplicate(definitions,story,copy,true); Identifier copiedChild=Identifier.parse("codex_test:editor_copy/copy_1");
        check("Subtree copy preserves unknown data without changing source", copies.size()==2&&copies.get(copy).get("unknown").equals(root.get("unknown"))&&root.getAsJsonObject("codex_quest").getAsJsonObject("dialogue").getAsJsonArray("choices").get(0).getAsJsonObject().get("next_quest").getAsString().equals(child.toString()));
        check("Subtree copy rewrites parent, choice link and gate together", copies.get(copiedChild).get("parent").getAsString().equals(copy.toString())&&copies.get(copiedChild).getAsJsonObject("codex_quest").getAsJsonObject("requires_choice").get("quest").getAsString().equals(copy.toString())&&PackAuthoring.choices(copies.get(copy)).get(0).getAsJsonObject().get("next_quest").getAsString().equals(copiedChild.toString()));
        boolean collision=false;try{PackAuthoring.duplicate(definitions,story,story,true);}catch(IllegalArgumentException expected){collision=true;}check("Duplicate rejects ID collisions before changing drafts",collision&&definitions.size()==2);
        JsonObject metadata=root.getAsJsonObject("codex_quest").deepCopy();metadata.getAsJsonObject("dialogue").getAsJsonArray("choices").get(0).getAsJsonObject().addProperty("gate_chapter",false);
        var ungated=PackAuthoring.linkBranches(definitions,story,metadata);
        check("Visual link toggle clears only its matching chapter gate", !ungated.get(child).getAsJsonObject("codex_quest").has("requires_choice")&&branch.getAsJsonObject("codex_quest").has("requires_choice"));
        Map<Identifier,JsonObject> broken=new LinkedHashMap<>(definitions);JsonObject bad=branch.deepCopy();bad.getAsJsonObject("codex_quest").getAsJsonObject("requires_choice").addProperty("choice","missing");broken.put(child,bad);
        check("Pack diagnostics identify dangling story choice",PackAuthoring.diagnose(broken,null).stream().anyMatch(i->i.error()&&i.quest().equals(child)));
        java.util.List<ItemStack> inventory=new java.util.ArrayList<>();for(int i=0;i<a.getInventory().getContainerSize();i++){inventory.add(a.getInventory().getItem(i).copy());a.getInventory().setItem(i,ItemStack.EMPTY);}
        try {
            JsonObject task=json("{\"items\":[{\"tag\":\"minecraft:logs\",\"count\":2},{\"id\":\"minecraft:oak_log\",\"count\":2}]}");
            a.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,2));a.getInventory().setItem(1,new ItemStack(Items.BIRCH_LOG,2));
            var allocation=SubmissionPlan.allocate(a.getInventory(),SubmissionPlan.resolve(task,a.registryAccess()),Map.of(),Optional.empty());
            check("Overlapping tag and exact item allocate the maximum valid delivery",allocation.total()==4&&allocation.removals().get(0)==2&&allocation.removals().get(1)==2);
            check("Planning overlapping requirements leaves inventory untouched",count(a,Items.OAK_LOG)==2&&count(a,Items.BIRCH_LOG)==2);
            JsonObject properties=json("{\"items\":[{\"id\":\"minecraft:oak_log\",\"count\":1},{\"id\":\"minecraft:oak_log\",\"components\":{\"minecraft:custom_name\":\"Delivery Sample\"},\"count\":2}]}");
            ItemStack named=new ItemStack(Items.OAK_LOG,2);
            //? if <1.21 {
            /*named.setHoverName(net.minecraft.network.chat.Component.literal("Delivery Sample"));
            *///?} else {
            named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Delivery Sample"));
            //?}
            a.getInventory().setItem(0,named);a.getInventory().setItem(1,new ItemStack(Items.OAK_LOG));
            var exact=SubmissionPlan.allocate(a.getInventory(),SubmissionPlan.resolve(properties,a.registryAccess()),Map.of(),Optional.empty());
            check("Component delivery and ordinary delivery have distinct stable ledgers",SubmissionPlan.requirements(properties).size()==2&&exact.total()==3&&exact.consumed().get(Identifier.parse("minecraft:oak_log"))==3);
            Identifier predicate=Identifier.parse("codex_test:predicate_delivery");var tasks=CodexQuestIO.readSubmissionTasks(AdvancementPackSync.externalPackDir());tasks.put(CodexQuestIO.taskKey(predicate,"done"),properties);CodexQuestIO.writeSubmissionTasks(AdvancementPackSync.externalPackDir(),tasks);
            check("Predicate submission commits exact inventory consumption and native completion",QuestTransactions.submit(a,predicate,"done",Optional.empty())==3&&count(a,Items.OAK_LOG)==0&&a.getAdvancements().getOrStartProgress(server.getAdvancements().get(predicate)).isDone());
            check("Predicate delivery cannot consume a second time",QuestTransactions.submit(a,predicate,"done",Optional.empty())==0);
            boolean invalid=false;try{SubmissionPlan.resolve(json("{\"items\":[{\"tag\":\"codex_test:missing_tag\",\"count\":1}]}"),a.registryAccess());}catch(RuntimeException expected){invalid=true;}check("Unknown item tag is rejected before inventory mutation",invalid);
        } finally {for(int i=0;i<inventory.size();i++)a.getInventory().setItem(i,inventory.get(i));}
    }

    private static void refreshPerformanceScenarios(MinecraftServer server, ServerPlayer a) {
        var hidden=server.getAdvancements().getAllAdvancements().stream().filter(h->h.value().display().isEmpty()).findFirst().orElseThrow();
        for(String criterion:hidden.value().criteria().keySet())a.getAdvancements().award(hidden,criterion);
        check("Hidden recipe unlocks do not write quest completion history",!QuestRuntime.state(a,hidden.id()).has("completed_day"));
        AdvancementPackSync.clearQueuedRewards();questPackets(a);
        long start=System.nanoTime();
        for(int i=0;i<500;i++)AdvancementPackSync.queueClaimableRewardsTo(a,hidden);
        long queued=System.nanoTime();AdvancementPackSync.flushQueuedRewards(server);long finished=System.nanoTime();
        check("A burst of500 advancement events sends one reward refresh",questPackets(a).stream().filter(p->p instanceof net.vg.codex.network.ClaimableRewardsPayload).count()==1);
        AdvancementPackSync.flushQueuedRewards(server);
        check("An empty reward refresh queue sends no redundant packet",questPackets(a).isEmpty());
        for(int i=0;i<500;i++)AdvancementPackSync.queueClaimableRewardsTo(a,hidden);
        net.vg.codex.network.QuestNetworking.view(a,Identifier.parse("codex_test:tab/root"));
        check("Opening a paused advancement screen flushes queued claims once",questPackets(a).stream().filter(p->p instanceof net.vg.codex.network.ClaimableRewardsPayload).count()==1);
        AdvancementPackSync.flushQueuedRewards(server);check("Screen flush leaves no later duplicate refresh",questPackets(a).isEmpty());
        JsonObject perf=new JsonObject();perf.addProperty("events",500);perf.addProperty("queue_ms",(queued-start)/1_000_000.0);perf.addProperty("single_refresh_ms",(finished-queued)/1_000_000.0);AtomicJson.write(Path.of("performance-bulk-refresh.json"),perf);
        int[] calls={0};Path probe=Path.of("codex/cache-probe.json");
        try(QuestReadScope scope=QuestReadScope.open()) {
            QuestReadScope.memo("probe",()->++calls[0]);QuestReadScope.memo("probe",()->++calls[0]);
            check("One overview reuses repeated derived state",calls[0]==1);
            AtomicJson.write(probe,new JsonObject());QuestReadScope.memo("probe",()->++calls[0]);
            check("Atomic changes invalidate derived state in the same operation",calls[0]==2);
        }
    }

    private static void tabSummaryScenarios(MinecraftServer server, ServerPlayer a) {
        Identifier root=Identifier.parse("codex_test:tab/root"), child=Identifier.parse("codex_test:tab/child");
        JsonObject first=QuestTabSummary.snapshot(a).getAsJsonObject(root.toString());
        check("Quest tab style is explicit and hidden recipe nodes do not count", first.get("quest_tab").getAsBoolean() && first.get("total").getAsInt()==2 && first.get("completed").getAsInt()==0);
        check("Ordinary vanilla tabs keep their own appearance", !QuestTabSummary.snapshot(a).getAsJsonObject("minecraft:story/root").get("quest_tab").getAsBoolean());
        a.getAdvancements().award(server.getAdvancements().get(root), "done");
        check("Unfinished descendants prevent a completed tab edge", QuestTabSummary.snapshot(a).getAsJsonObject(root.toString()).get("completed").getAsInt()==1);
        var rewards=CodexQuestIO.readClaimableRewards(AdvancementPackSync.externalPackDir());rewards.put(child,json("{\"experience\":1}"));CodexQuestIO.writeClaimableRewards(AdvancementPackSync.externalPackDir(),rewards);
        a.getAdvancements().award(server.getAdvancements().get(child), "done");
        JsonObject ready=QuestTabSummary.snapshot(a).getAsJsonObject(root.toString());
        check("Unclaimed rewards keep a chapter unfinished and explain the action", ready.get("completed").getAsInt()==1 && ready.get("attention").getAsBoolean() && ready.get("rewards").getAsInt()==1);
        check("Claim finishes the chapter and clears all action hints", QuestTransactions.claim(a,child,-1)==1 && !QuestTabSummary.snapshot(a).getAsJsonObject(root.toString()).get("attention").getAsBoolean() && QuestTabSummary.snapshot(a).getAsJsonObject(root.toString()).get("completed").getAsInt()==2);
        QuestDefinitions.save(child,json("{\"acceptance\":true}"));
        check("Acceptance actions also light the tab dot", QuestTabSummary.snapshot(a).getAsJsonObject(root.toString()).get("attention").getAsBoolean());
        QuestDefinitions.save(child,new JsonObject());
    }

    private static void navigationScenarios(MinecraftServer server, ServerPlayer a) throws Exception {
        var budget = new net.vg.codex.network.QuestRequestBudget(); boolean burst = true;
        for (int i = 0; i < 12; i++) burst &= budget.acquire(1_000_000L);
        check("Quest request limiter bounds bursts", burst && !budget.acquire(1_000_000L));
        check("Quest request limiter replenishes without a player tick", budget.acquire(51_000_000L));
        net.vg.codex.network.QuestNetworking.reset(); questPackets(a);
        Identifier root = Identifier.parse("codex_demo:camp_chronicle/root"), vanilla = Identifier.parse("minecraft:story/root"),
            builder = Identifier.parse("codex_demo:camp_chronicle/builder"), wanderer = Identifier.parse("codex_demo:camp_chronicle/wanderer");
        request(a, root, "view", ""); request(a, root, "view", "");
        var initialPackets = questPackets(a);
        long snapshots = initialPackets.stream().filter(p -> p instanceof net.vg.codex.network.QuestPresentationPayload).count();
        check("Explicit same-tree refresh resends unchanged decorations", snapshots == 2);
        check("Explicit refresh also resends all tab summaries", initialPackets.stream().filter(p -> p instanceof net.vg.codex.network.TabSummaryPayload).count()==2);
        int pausedTick = a.tickCount;
        // This entire scenario executes inside one server tick. Only elapsed time advances,
        // matching requests handled while IntegratedServer is paused in a screen.
        Thread.sleep(60);
        for (int i = 0; i < 18; i++) {
            Identifier quest = i % 2 == 0 ? builder : wanderer;
            request(a, i % 2 == 0 ? root : vanilla, "view", "");
            request(a, quest, "watch", "navigation-" + i);
            request(a, quest, "unwatch", "");
            if (i % 2 == 1) Thread.sleep(60);
        }
        var packets = questPackets(a);
        var reads = packets.stream().filter(p -> p instanceof net.vg.codex.network.QuestInfoPayload).map(p -> (net.vg.codex.network.QuestInfoPayload)p).map(p -> json(p.json())).toList();
        check("More than twelve paused navigation requests all receive data", a.tickCount == pausedTick && reads.size() == 18 && reads.stream().allMatch(i -> i.has("definition")));
        check("Repeated paused tab switches retain server presentation responses", packets.stream().filter(p -> p instanceof net.vg.codex.network.QuestPresentationPayload).count() == 18);
        check("Unchosen story branches load real details instead of a loading failure", reads.stream().allMatch(i -> !i.get("unlocked").getAsBoolean() && i.getAsJsonObject("definition").has("requires_choice")));
        check("Read responses identify the exact fresh-status request", reads.size() == 18 && reads.get(17).get("request").getAsString().equals("navigation-17"));
        request(a, root, "choice", "campkeeper");
        var choice = questPackets(a).stream().filter(p -> p instanceof net.vg.codex.network.QuestInfoPayload).map(p -> json(((net.vg.codex.network.QuestInfoPayload)p).json())).filter(i -> i.has("result")).findFirst();
        check("Story choice after repeated paused navigation saves and acknowledges", choice.isPresent() && choice.get().getAsJsonObject("result").get("success").getAsBoolean() && QuestRuntime.state(a, root).get("choice").getAsString().equals("campkeeper"));
        request(a, root, "watch", "confirm-choice");
        var confirmed = questPackets(a).stream().filter(p -> p instanceof net.vg.codex.network.QuestInfoPayload).map(p -> json(((net.vg.codex.network.QuestInfoPayload)p).json())).filter(i -> i.has("request")).findFirst();
        check("Read-only choice recovery observes saved state without replay", confirmed.isPresent() && confirmed.get().getAsJsonObject("state").get("choice").getAsString().equals("campkeeper") && confirmed.get().get("request").getAsString().equals("confirm-choice"));
        request(a, Identifier.parse("codex_test:no_longer_exists"), "watch", "missing");
        var missing = questPackets(a).stream().filter(p -> p instanceof net.vg.codex.network.QuestInfoPayload).map(p -> json(((net.vg.codex.network.QuestInfoPayload)p).json())).findFirst();
        check("Unavailable advancement reads return an explicit failure", missing.isPresent() && !missing.get().getAsJsonObject("result").get("success").getAsBoolean());
        QuestReset.everything(a); net.vg.codex.network.QuestNetworking.reset(); questPackets(a);
    }

    private static void resetAndBranchScenarios(MinecraftServer server, ServerPlayer a, ServerPlayer b) throws Exception {
        Identifier root = Identifier.parse("codex_demo:camp_chronicle/root"), supply = Identifier.parse("codex_demo:camp_chronicle/supply"),
            builder = Identifier.parse("codex_demo:camp_chronicle/builder"), wanderer = Identifier.parse("codex_demo:camp_chronicle/wanderer");
        var builderHolder = server.getAdvancements().get(builder); var wandererHolder = server.getAdvancements().get(wanderer);
        String builderCriterion = builderHolder.value().criteria().keySet().iterator().next();
        String wandererCriterion = wandererHolder.value().criteria().keySet().iterator().next();
        check("Explicit branch metadata blocks native awards before choosing", !a.getAdvancements().award(builderHolder, builderCriterion));
        QuestRuntime.choose(a, root, "campkeeper");
        check("Chosen branch allows native criteria while alternative stays blocked", a.getAdvancements().award(builderHolder, builderCriterion) && !a.getAdvancements().award(wandererHolder, wandererCriterion));
        check("Presentation distinguishes excluded branches and acceptance dots", QuestRuntime.presentation(a, wanderer).get("excluded").getAsBoolean() && !QuestRuntime.presentation(a, wanderer).get("can_accept").getAsBoolean() && QuestRuntime.presentation(a, supply).get("can_accept").getAsBoolean());
        JsonObject original = QuestDefinitions.get(supply), rule = original.deepCopy(); rule.getAsJsonObject("repeat").addProperty("cooldown_seconds", 0); QuestDefinitions.save(supply, rule);
        for (int i = 0; i < 2; i++) {
            QuestRuntime.accept(a, supply); a.getInventory().add(new ItemStack(Items.OAK_LOG, 4)); a.getInventory().add(new ItemStack(Items.COBBLESTONE, 8));
            QuestTransactions.submit(a, supply, "deliver", Optional.empty()); QuestTransactions.claim(a, supply, -1);
        }
        check("Repeat presentation distinguishes claimed completion from next-cycle action", QuestRuntime.presentation(a, supply).get("ready").getAsBoolean() && !QuestRuntime.presentation(a, supply).get("claim_pending").getAsBoolean() && QuestRuntime.presentation(a, supply).get("can_accept").getAsBoolean());
        a.getInventory().add(new ItemStack(Items.IRON_INGOT, 3)); QuestTransactions.submit(a, DELIVERY, "done", Optional.empty());
        a.getAdvancements().award(server.getAdvancements().get(REWARD), "done"); QuestTransactions.claim(a, REWARD, -1);
        b.getAdvancements().award(server.getAdvancements().get(REWARD), "done");
        int inventory = a.getInventory().countItem(Items.APPLE);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "advancement revoke CodexAlice everything");
        check("Vanilla revoke-everything clears native and sidecar completion", !a.getAdvancements().getOrStartProgress(server.getAdvancements().get(supply)).isDone() && (QuestRuntime.state(a, supply).size() == 0) && !QuestRuntime.ready(a, supply));
        check("Vanilla reset clears story choices and ordinary submissions", !QuestRuntime.state(a, root).has("choice") && CodexQuestIO.readSubmissionProgress(QuestState.directory(server), ALICE).isEmpty());
        check("Vanilla reset clears stable and legacy reward claims", CodexQuestIO.readClaimIndexes(QuestState.directory(server), ALICE).isEmpty() && (QuestRewardIdentity.ledger(a, REWARD).size() == 0));
        check("Reset retains inventory and unrelated player's native progress", a.getInventory().countItem(Items.APPLE) == inventory && b.getAdvancements().getOrStartProgress(server.getAdvancements().get(REWARD)).isDone());
        check("Reset writes a recoverable backup", Files.isDirectory(QuestState.directory(server).resolve("codex/reset_backups")));
        QuestRuntime.accept(a, supply); a.getInventory().add(new ItemStack(Items.OAK_LOG, 4)); a.getInventory().add(new ItemStack(Items.COBBLESTONE, 8)); QuestTransactions.submit(a, supply, "deliver", Optional.empty());
        check("Reset cycle zero can legitimately claim again exactly once", QuestTransactions.claim(a, supply, -1) == 3 && QuestTransactions.claim(a, supply, -1) == 0);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "advancement revoke CodexAlice everything");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "advancement revoke CodexAlice everything");
        check("Repeated reset cannot resurrect completed sidecar criteria", !QuestRuntime.refresh(a, supply).has("accepted") && !a.getAdvancements().getOrStartProgress(server.getAdvancements().get(supply)).isDone());
        CodexPartyService.invite(a, b); CodexPartyService.accept(b);
        Identifier communal = Identifier.parse("codex_demo:camp_chronicle/communal");
        QuestRuntime.choose(b, root, "wanderer"); QuestRuntime.accept(a, communal);
        a.getInventory().add(new ItemStack(Items.COBBLESTONE, 8)); QuestTransactions.submit(a, communal, "deliver", Optional.empty()); QuestTransactions.claim(a, communal, -1);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "advancement revoke CodexAlice everything");
        check("Administrator reset clears the shared cycle for both members without dissolving the party", (QuestRuntime.state(b, communal).size() == 0) && !QuestRuntime.ready(b, communal) && QuestTransactions.claim(b, communal, -1) == 0 && CodexPartyService.members(server, ALICE).size() == 2);
        check("Reset does not clear another member's personal story choice", QuestRuntime.state(b, root).get("choice").getAsString().equals("wanderer"));
        QuestRuntime.accept(b, communal); b.getInventory().add(new ItemStack(Items.COBBLESTONE, 8)); QuestTransactions.submit(b, communal, "deliver", Optional.empty());
        check("Reset shared cycle grants one fresh group reward set", QuestTransactions.claim(b, communal, -1) == 2 && QuestTransactions.claim(a, communal, -1) == 0);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "advancement revoke @a everything");
        CodexPartyService.onAdvancementAward(a, server.getAdvancements().get(root));
        check("Resetting all party members also clears the persistent native sharing ledger", !a.getAdvancements().getOrStartProgress(server.getAdvancements().get(REWARD)).isDone() && !b.getAdvancements().getOrStartProgress(server.getAdvancements().get(REWARD)).isDone() && !a.getAdvancements().getOrStartProgress(server.getAdvancements().get(communal)).isDone());
        CodexPartyService.leave(b); CodexPartyService.leave(a);
        QuestDefinitions.save(supply, original); a.getInventory().clearContent();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "advancement revoke CodexBob everything");
    }

    private static void lifecycleScenarios(MinecraftServer server, ServerPlayer a, ServerPlayer b) throws Exception {
        Identifier root = Identifier.parse("codex_demo:camp_chronicle/root"), supply = Identifier.parse("codex_demo:camp_chronicle/supply"), trail = Identifier.parse("codex_demo:camp_chronicle/trail"), builder = Identifier.parse("codex_demo:camp_chronicle/builder");
        check("Camp Chronicle story and native branch advancements load", server.getAdvancements().get(root) != null && server.getAdvancements().get(builder) != null);
        check("Branch cannot accept before its required choice", !QuestRuntime.accept(a, builder));
        check("Dialogue choice persists and cannot be changed by replay", QuestRuntime.choose(a, root, "campkeeper") && !QuestRuntime.choose(a, root, "wanderer") && QuestRuntime.state(a, root).get("choice").getAsString().equals("campkeeper"));
        check("Chosen branch opens only for the choosing player", QuestRuntime.accept(a, builder) && !QuestRuntime.accept(b, builder));
        JsonObject beforeAcceptance = QuestActions.perform(a, supply, "submit", "deliver", Optional.empty(), -1);
        check("Rejected delivery returns an accept-first GUI result without consumption", !beforeAcceptance.get("success").getAsBoolean() && beforeAcceptance.get("message").getAsString().contains("Accept this quest first") && (beforeAcceptance.getAsJsonObject("items").size() == 0));
        check("Saved story outcome links only the chosen chapter", java.util.stream.StreamSupport.stream(QuestRuntime.info(a, root).getAsJsonArray("outcomes").spliterator(),false).anyMatch(e -> e.getAsJsonObject().get("id").getAsString().equals(builder.toString())) && java.util.stream.StreamSupport.stream(QuestRuntime.info(a, root).getAsJsonArray("outcomes").spliterator(),false).noneMatch(e -> e.getAsJsonObject().get("id").getAsString().endsWith("/wanderer")));
        check("Unaccepted repeat quest cannot consume items or grant rewards", QuestTransactions.submit(a, supply, "deliver", Optional.empty()) == 0 && QuestTransactions.claim(a, supply, -1) == 0);
        JsonObject rule = QuestDefinitions.get(supply); rule.getAsJsonObject("repeat").addProperty("cooldown_seconds", 0); QuestDefinitions.save(supply, rule);
        check("Repeatable delivery can be accepted exactly once per cycle", QuestRuntime.accept(a, supply) && !QuestRuntime.accept(a, supply));
        a.getInventory().add(new ItemStack(Items.OAK_LOG, 2));
        JsonObject partial = QuestActions.perform(a, supply, "submit", "deliver", Optional.empty(), -1);
        check("Repeatable delivery returns exact consumed item receipt and partial progress", partial.get("success").getAsBoolean() && partial.getAsJsonObject("items").get("minecraft:oak_log").getAsInt() == 2 && !QuestRuntime.ready(a, supply));
        check("Tree tooltip snapshot agrees with the active partial cycle", AdvancementPackSync.submissionTasksFor(a).stream().filter(t -> t.advancementId().equals(supply)).flatMap(t -> t.items().stream()).anyMatch(i -> i.itemId().equals(Identifier.parse("minecraft:oak_log")) && i.submitted() == 2));
        a.getInventory().add(new ItemStack(Items.OAK_LOG, 2)); a.getInventory().add(new ItemStack(Items.COBBLESTONE, 8));
        check("Cycle completion awards the ordinary advancement once", QuestTransactions.submit(a, supply, "deliver", Optional.empty()) == 10 && QuestRuntime.ready(a, supply) && a.getAdvancements().getOrStartProgress(server.getAdvancements().get(supply)).isDone());
        check("Cannot restart a cycle with unclaimed rewards", !QuestRuntime.accept(a, supply));
        check("Direct random treasure and component-driven bag rewards claim once", QuestTransactions.claim(a, supply, -1) == 3 && QuestTransactions.claim(a, supply, -1) == 0 && a.getInventory().countItem(TreasureBagItem.ITEM) == 1);
        check("Next cycle retains native completion and resets personal delivery", QuestRuntime.accept(a, supply) && !QuestRuntime.ready(a, supply) && a.getAdvancements().getOrStartProgress(server.getAdvancements().get(supply)).isDone());
        check("Tree tooltip resets repeat totals while native completion remains", AdvancementPackSync.submissionTasksFor(a).stream().filter(t -> t.advancementId().equals(supply)).flatMap(t -> t.items().stream()).allMatch(i -> i.submitted() == 0));
        a.getInventory().add(new ItemStack(Items.OAK_LOG, 4)); a.getInventory().add(new ItemStack(Items.COBBLESTONE, 8));
        check("Second cycle earns a separate once-only claim", QuestTransactions.submit(a, supply, "deliver", Optional.empty()) == 12 && QuestTransactions.claim(a, supply, -1) == 3 && QuestTransactions.claim(a, supply, -1) == 0);
        check("Repeat cycles do not leak to a second player", !QuestRuntime.ready(b, supply) && QuestRuntime.cycle(QuestRuntime.state(b, supply)) == 0);
        a.awardStat(net.minecraft.stats.Stats.JUMP, 8);
        check("Statistic cycle accepts a baseline instead of lifetime progress", QuestRuntime.accept(a, trail) && !QuestRuntime.ready(a, trail));
        a.awardStat(net.minecraft.stats.Stats.JUMP, 5); QuestRuntime.refresh(a, trail);
        check("Statistic delta completes the cycle and claim once", QuestRuntime.ready(a, trail) && QuestTransactions.claim(a, trail, -1) == 1 && QuestTransactions.claim(a, trail, -1) == 0);
        a.getInventory().setItem(0, TreasureBagItem.create(Identifier.parse("codex_test:codex_treasure/trial"), 1, "Test Satchel", null)); a.getInventory().setSelectedSlot(0);
        check("A physical treasure bag consumes one server-held bag and generates loot", TreasureBagItem.open(a, net.minecraft.world.InteractionHand.MAIN_HAND) && !a.getMainHandItem().is(TreasureBagItem.ITEM));
        a.getInventory().setItem(0, TreasureBagItem.create(Identifier.parse("codex_test:missing_pool"), 1, "Invalid Satchel", null));
        check("Invalid bag pool never consumes its item", !TreasureBagItem.open(a, net.minecraft.world.InteractionHand.MAIN_HAND) && a.getMainHandItem().getCount() == 1);
        a.getInventory().clearContent(); b.getInventory().clearContent();
        // Persisted receipt + outstanding sidecar: emulate a process death after the player save.
        QuestJournal.Plan plan = new QuestJournal.Plan(a); plan.add(new ItemStack(Items.APPLE, 2));
        Path proof = QuestState.directory(server).resolve("codex/recovery-proof.json"); plan.update(proof, json("{\"applied\":true}")); plan.commit();
        String receipt = ((TransactionReceipt)a).codex$receipt();
        JsonObject pending = new JsonObject(); pending.addProperty("player", a.getUUID().toString()); pending.addProperty("before", "older"); pending.addProperty("receipt", receipt);
        JsonArray updates = new JsonArray(); JsonObject update = new JsonObject(); update.addProperty("path", "codex/recovery-proof.json"); update.add("value", json("{\"applied\":true}")); updates.add(update); pending.add("updates", updates);
        Files.delete(proof); AtomicJson.write(QuestState.directory(server).resolve("codex/transaction.json"), pending);
        QuestJournal.recover(server); QuestJournal.recover(server);
        check("Recovery finalizes a saved transaction without granting items again", Files.exists(proof) && a.getInventory().countItem(Items.APPLE) == 2);
        pending.addProperty("before", receipt); pending.addProperty("receipt", "never-saved"); update.add("value", json("{\"applied\":false}"));
        AtomicJson.write(QuestState.directory(server).resolve("codex/transaction.json"), pending); QuestJournal.recover(server);
        check("Recovery aborts an unsaved transaction without changing committed state", JsonParser.parseString(Files.readString(proof)).getAsJsonObject().get("applied").getAsBoolean());
        // Overflow is kept in a recoverable mailbox, never dropped as an unsaved world entity.
        for (int i = 0; i < 36; i++) a.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        plan = new QuestJournal.Plan(a); plan.add(new ItemStack(Items.DIAMOND, 3)); plan.commit();
        check("Full inventory stores rewards durably", QuestJournal.mailbox(a).getAsJsonArray("items").size() == 1 && a.getInventory().countItem(Items.DIAMOND) == 0);
        a.getInventory().setItem(0, ItemStack.EMPTY);
        check("Stored rewards collect once when space is available", QuestJournal.collect(a) && !QuestJournal.collect(a) && a.getInventory().countItem(Items.DIAMOND) == 3);
        a.getInventory().clearContent(); b.getInventory().clearContent();
        Identifier identity = Identifier.parse("codex_test:identity");
        JsonObject reward = json("{\"codex_items\":[{\"id\":\"minecraft:diamond\",\"count\":1},{\"id\":\"minecraft:emerald\",\"count\":2}]}");
        var definitions = CodexQuestIO.readClaimableRewards(AdvancementPackSync.externalPackDir()); definitions.put(identity, reward); CodexQuestIO.writeClaimableRewards(AdvancementPackSync.externalPackDir(), definitions);
        a.getAdvancements().award(server.getAdvancements().get(identity), "done"); b.getAdvancements().award(server.getAdvancements().get(identity), "done");
        check("Individual reward claim records a persistent identity", QuestTransactions.claim(a, identity, 0) == 1 && a.getInventory().countItem(Items.DIAMOND) == 1);
        JsonObject attached = QuestRewardIdentity.attach(identity, reward); var rewardEntries = QuestRewards.entries(attached, identity); java.util.Collections.reverse(rewardEntries);
        JsonObject reordered = QuestRewards.serialize(rewardEntries, attached, identity), metadata = new JsonObject();
        metadata.add("reward_ids_legacy", attached.getAsJsonArray("codex_ids")); metadata.add("reward_ids", reordered.remove("codex_ids")); QuestDefinitions.save(identity, metadata);
        definitions.put(identity, reordered); CodexQuestIO.writeClaimableRewards(AdvancementPackSync.externalPackDir(), definitions);
        check("Reordering rewards cannot regrant a previously claimed item", QuestTransactions.claim(a, identity, -1) == 1 && a.getInventory().countItem(Items.DIAMOND) == 1 && a.getInventory().countItem(Items.EMERALD) == 2);
        var legacyClaims = CodexQuestIO.readClaimIndexes(QuestState.directory(server), b.getUUID()); legacyClaims.put(identity, Set.of(0)); CodexQuestIO.writeClaimIndexes(QuestState.directory(server), b.getUUID(), legacyClaims);
        check("An unmigrated legacy index keeps its original meaning after reordering", QuestTransactions.claim(b, identity, -1) == 1 && b.getInventory().countItem(Items.DIAMOND) == 0 && b.getInventory().countItem(Items.EMERALD) == 2);
        a.getInventory().clearContent(); b.getInventory().clearContent();
        // The subsequent unwritable-ledger case deliberately creates this path as a directory.
        Files.deleteIfExists(QuestState.directory(server).resolve("codex/claims/" + b.getUUID() + ".json"));
        server.getPlayerList().saveAll();
    }

    private static void exampleUpgradeScenarios() throws Exception {
        Path root = Files.createTempDirectory("codex-example-upgrade"), source = root.resolve("source"), target = root.resolve("target"), backup = root.resolve("backup");
        Path node = Path.of("data/codex_demo/advancement/field_journal/root.json");
        JsonObject definition = json("{\"display\":{\"title\":\"Original\"}}");
        AtomicJson.write(source.resolve(node), definition); AtomicJson.write(target.resolve(node), definition);
        JsonObject edited = definition.deepCopy(); edited.getAsJsonObject("display").addProperty("title", "Authored change"); AtomicJson.write(target.resolve(node), edited);
        check("Demo consolidation preserves an edited old tree", !DevelopmentExampleUpgrade.retireJournal(source, target, backup) && JsonParser.parseString(Files.readString(target.resolve(node))).equals(edited));
        DevelopmentExampleUpgrade.labelPreservedJournal(source, target, backup);
        check("Legacy label preserves custom authored titles", JsonParser.parseString(Files.readString(target.resolve(node))).equals(edited));
        AtomicJson.write(target.resolve(node), definition); Path child = target.resolve("data/other/advancement/custom.json"); AtomicJson.write(child, json("{\"parent\":\"codex_demo:field_journal/root\"}"));
        check("Demo consolidation preserves custom children in other namespaces", !DevelopmentExampleUpgrade.retireJournal(source, target, backup) && Files.exists(target.resolve(node)));
        Files.delete(child); Path sidecar = Path.of("codex/claimable_rewards.json"); JsonObject oldRewards = json("{\"codex_demo:field_journal/root\":{\"experience\":1}}"); AtomicJson.write(source.resolve(sidecar), oldRewards);
        JsonObject mixed = oldRewards.deepCopy(); mixed.add("codex_demo:camp_chronicle/daily", json("{\"experience\":2}")); AtomicJson.write(target.resolve(sidecar), mixed);
        check("Untouched legacy demo is backed up and retired without losing new entries", DevelopmentExampleUpgrade.retireJournal(source, target, backup) && Files.exists(backup.resolve(node)) && !Files.exists(target.resolve(node)) && JsonParser.parseString(Files.readString(target.resolve(sidecar))).getAsJsonObject().size() == 1);
    }

    private static void sharedCycleScenarios(MinecraftServer server, ServerPlayer a, ServerPlayer b) throws Exception {
        Identifier group = Identifier.parse("codex_demo:camp_chronicle/communal"), daily = Identifier.parse("codex_demo:camp_chronicle/daily"), trail = Identifier.parse("codex_demo:camp_chronicle/trail");
        JsonObject groupRule = QuestDefinitions.get(group); groupRule.getAsJsonObject("repeat").addProperty("cooldown_seconds", 0); QuestDefinitions.save(group, groupRule);
        check("One acceptance starts the party's shared cycle", QuestRuntime.accept(a, group) && QuestRuntime.state(b, group).get("accepted").getAsBoolean() && QuestRuntime.state(b, group).getAsJsonArray("participants").size() == 2);
        a.getInventory().add(new ItemStack(Items.COBBLESTONE, 3)); b.getInventory().add(new ItemStack(Items.COBBLESTONE, 5));
        check("Two members contribute to one exact shared tally", QuestTransactions.submit(a, group, "deliver", Optional.empty()) == 3 && QuestTransactions.submit(b, group, "deliver", Optional.empty()) == 5 && QuestRuntime.ready(a, group) && QuestRuntime.ready(b, group));
        check("Party contributions use the inventories actually consumed", QuestRuntime.state(a, group).getAsJsonObject("contributions").get(ALICE.toString()).getAsInt() == 3 && QuestRuntime.state(a, group).getAsJsonObject("contributions").get(BOB.toString()).getAsInt() == 5);
        check("Party reward set is granted once across both members", QuestTransactions.claim(a, group, -1) == 2 && QuestTransactions.claim(b, group, -1) == 0 && QuestRuntime.allClaimed(b, group));
        CodexPartyService.leave(b);
        check("Remaining member can start the next cycle without revoking vanilla", QuestRuntime.accept(a, group) && QuestRuntime.cycle(QuestRuntime.state(a, group)) == 1 && !QuestRuntime.ready(a, group) && a.getAdvancements().getOrStartProgress(server.getAdvancements().get(group)).isDone());
        CodexPartyService.invite(a, b); CodexPartyService.accept(b);
        b.getInventory().add(new ItemStack(Items.COBBLESTONE, 1));
        check("Late party join cannot submit to or claim an existing cycle", !QuestCycles.eligible(b, group, QuestRuntime.state(b, group)) && QuestTransactions.submit(b, group, "deliver", Optional.empty()) == 0 && QuestTransactions.claim(b, group, -1) == 0);
        a.getInventory().add(new ItemStack(Items.COBBLESTONE, 8));
        check("Shared second cycle keeps the original participant entitlement", QuestTransactions.submit(a, group, "deliver", Optional.empty()) == 8 && QuestTransactions.claim(b, group, -1) == 0 && QuestTransactions.claim(a, group, -1) == 2);
        check("Late member participates in the following cycle", QuestRuntime.accept(b, group) && QuestCycles.eligible(b, group, QuestRuntime.state(b, group)));
        a.getInventory().add(new ItemStack(Items.COBBLESTONE, 8));
        check("Shared third cycle can be claimed by the other participant", QuestTransactions.submit(a, group, "deliver", Optional.empty()) == 8 && QuestTransactions.claim(b, group, -1) == 2 && QuestTransactions.claim(a, group, -1) == 0);
        CodexPartyService.leave(b);
        check("Leaving a party cannot inherit its completed extension cycle", !QuestRuntime.ready(b, group) && QuestTransactions.claim(b, group, -1) == 0);
        CodexPartyService.invite(a, b); CodexPartyService.accept(b);
        check("Rejoining cannot replay a shared reward", QuestRuntime.ready(b, group) && QuestTransactions.claim(b, group, -1) == 0);
        // Shared statistic deltas use both online baselines, never lifetime totals.
        JsonObject personal = QuestDefinitions.get(trail), shared = personal.deepCopy(); shared.getAsJsonObject("repeat").addProperty("scope", "party"); shared.getAsJsonObject("repeat").addProperty("cooldown_seconds", 0); QuestDefinitions.save(trail, shared);
        check("Shared statistics start from each member's own baseline", QuestRuntime.accept(a, trail) && !QuestRuntime.ready(b, trail));
        a.awardStat(net.minecraft.stats.Stats.JUMP, 2); b.awardStat(net.minecraft.stats.Stats.JUMP, 3);
        check("Shared statistic contributions complete together and claim once", QuestRuntime.ready(a, trail) && QuestRuntime.ready(b, trail) && QuestTransactions.claim(a, trail, -1) == 1 && QuestTransactions.claim(b, trail, -1) == 0);
        QuestDefinitions.save(trail, personal);
        check("Changing shared scope preserves the earlier personal cycle data", QuestRuntime.ready(a, trail) && QuestTransactions.claim(a, trail, -1) == 0);
        check("Daily contract starts with an ordinary editable delivery", QuestRuntime.accept(a, daily));
        a.getInventory().add(new ItemStack(Items.STICK, 2));
        check("Daily completion claims once and remains readable before reset", QuestTransactions.submit(a, daily, "deliver", Optional.empty()) == 2 && QuestTransactions.claim(a, daily, -1) == 2 && !QuestRuntime.accept(a, daily) && QuestRuntime.ready(a, daily));
        long now = 1_700_012_345_678L;
        check("Daily reset uses the next UTC midnight", QuestCycles.nextAvailable(QuestDefinitions.get(daily), now) == (Math.floorDiv(now, 86_400_000L) + 1) * 86_400_000L);
        JsonObject periodic = QuestDefinitions.get(daily); periodic.getAsJsonObject("repeat").addProperty("schedule", "periodic"); periodic.getAsJsonObject("repeat").addProperty("period_seconds", 3600);
        check("Periodic reset uses aligned server time boundaries", QuestCycles.nextAvailable(periodic, now) == (Math.floorDiv(now, 3_600_000L) + 1) * 3_600_000L);
        JsonObject expired = QuestRuntime.state(a, daily); expired.addProperty("next_available", 0); QuestRuntime.put(a, daily, expired);
        check("Expired daily cycle preserves vanilla completion and starts empty", QuestRuntime.accept(a, daily) && !QuestRuntime.ready(a, daily) && a.getAdvancements().getOrStartProgress(server.getAdvancements().get(daily)).isDone());
        a.getInventory().add(new ItemStack(Items.STICK, 2)); QuestTransactions.submit(a, daily, "deliver", Optional.empty()); QuestTransactions.claim(a, daily, -1);
        JsonObject choice = json("{\"stage\":\"complete\"}");
        check("Completion-only dialogue cannot be offered early", !QuestDefinitions.choiceAvailable(choice, false, true) && QuestDefinitions.choiceAvailable(choice, true, true));
    }

    private static void migrationScenarios() throws Exception {
        Path legacy = Files.createTempDirectory("codex-legacy"); Path oldWorld = Files.createTempDirectory("codex-old-world"); Path newWorld = Files.createTempDirectory("codex-new-world");
        CodexQuestIO.writeClaimIndexes(legacy, ALICE, Map.of(REWARD, Set.of(-1)));
        Files.createDirectories(oldWorld.resolve("playerdata")); Files.writeString(oldWorld.resolve("playerdata/" + ALICE + ".dat"), "test identity marker");
        QuestState.migrate(legacy, oldWorld); QuestState.migrate(legacy, newWorld);
        Path currentWorld = Files.createTempDirectory("codex-current-world");
        Files.createDirectories(currentWorld.resolve("players/data")); Files.writeString(currentWorld.resolve("players/data/" + ALICE + ".dat"), "test identity marker");
        QuestState.migrate(legacy, currentWorld);
        check("Migration recognizes the 26.1.2 players/data path", CodexQuestIO.readClaimIndexes(currentWorld, ALICE).get(REWARD).contains(-1));
        Files.createDirectories(newWorld.resolve("players/data")); Files.writeString(newWorld.resolve("players/data/" + ALICE + ".dat"), "later player");
        AtomicJson.write(newWorld.resolve("codex/state_version.json"), json("{\"version\":1,\"legacy_players_imported\":0}"));
        QuestState.migrate(legacy, newWorld);
        check("An old marker never reimports global data into an independent world", CodexQuestIO.readClaimIndexes(newWorld, ALICE).isEmpty());
        check("Migration imports existing-world claims with legacy backup", CodexQuestIO.readClaimIndexes(oldWorld, ALICE).get(REWARD).contains(-1) && Files.exists(oldWorld.resolve("codex/claims/" + ALICE + ".json.legacy.bak")));
        check("Migration leaves legacy source intact and a new world clean", CodexQuestIO.readClaimIndexes(legacy, ALICE).get(REWARD).contains(-1) && CodexQuestIO.readClaimIndexes(newWorld, ALICE).isEmpty());
        CodexQuestIO.writeClaimIndexes(oldWorld, ALICE, Map.of(REWARD, Set.of(0)));
        QuestState.migrate(legacy, oldWorld);
        check("Migration does not repeat or overwrite newer state", CodexQuestIO.readClaimIndexes(oldWorld, ALICE).get(REWARD).equals(Set.of(0)));
    }

    private static ServerPlayer player(MinecraftServer server, UUID id, String name) {
        //? if <1.21 {
        /*ServerPlayer player = new ServerPlayer(server, server.overworld(), new GameProfile(id,name)) { };
        *///?} else {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(id, name), false);
        ServerPlayer player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation()) { };
        //?}
        // Normal 26.1 login restores NBT during PrepareSpawnTask, before placeNewPlayer.
        // Embedded connections bypass configuration, so perform that step explicitly.
        // Legacy placeNewPlayer performs its own native NBT load.
        //? if >=26.1 {
        server.getPlayerList().loadPlayerData(player.nameAndId()).ifPresent(data -> player.load(
                net.minecraft.world.level.storage.TagValueInput.create(
                        net.minecraft.util.ProblemReporter.DISCARDING, server.registryAccess(), data)));
        //?}
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        configureConnection.accept(connection);
        //? if <1.21 {
        /*server.getPlayerList().placeNewPlayer(connection, player);
        *///?} else {
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        //?}
        channel.pipeline().addLast("codex-scenario-payloads", new io.netty.channel.ChannelOutboundHandlerAdapter() {
            @Override public void write(io.netty.channel.ChannelHandlerContext ctx, Object message, io.netty.channel.ChannelPromise promise) throws Exception {
                //? if <1.21 {
                /*if(message instanceof net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket packet) {
                    var type=packet.getIdentifier();
                    var data=packet.getData();
                    try {
                        // Architectury Forge wraps its logical channel inside a shared native channel.
                        if(type.toString().equals("architectury:network"))type=data.readResourceLocation();
                        if(type.equals(net.vg.codex.network.QuestInfoPayload.TYPE.id())||type.equals(net.vg.codex.network.QuestPresentationPayload.TYPE.id())||type.equals(net.vg.codex.network.TabSummaryPayload.TYPE.id())||type.equals(net.vg.codex.network.ClaimableRewardsPayload.TYPE.id())) {
                            var decoded=net.vg.codex.compat.v120.NetworkManager.receive(net.vg.codex.compat.v120.NetworkManager.s2c(),id,type,data);
                            if(decoded!=null)QUEST_PACKETS.computeIfAbsent(id,ignored->new ArrayList<>()).add(decoded);
                        }
                    } finally {data.release();}
                }
                *///?} else {
                if (message instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket packet) {
                    var raw = packet.payload();
                    var type = raw.type().id();
                    if (type.equals(net.vg.codex.network.QuestInfoPayload.TYPE.id()) || type.equals(net.vg.codex.network.QuestPresentationPayload.TYPE.id()) || type.equals(net.vg.codex.network.TabSummaryPayload.TYPE.id()) || type.equals(net.vg.codex.network.ClaimableRewardsPayload.TYPE.id())) {
                        if (raw instanceof net.vg.codex.network.QuestInfoPayload || raw instanceof net.vg.codex.network.QuestPresentationPayload || raw instanceof net.vg.codex.network.TabSummaryPayload || raw instanceof net.vg.codex.network.ClaimableRewardsPayload) {
                            QUEST_PACKETS.computeIfAbsent(id, ignored -> new ArrayList<>()).add(raw);
                            ctx.write(message, promise);return;
                        }
                        byte[] bytes=(byte[])raw.getClass().getMethod("payload").invoke(raw);
                        var buf = ScenarioBuffers.create(io.netty.buffer.Unpooled.wrappedBuffer(bytes), player.registryAccess());
                        try {
                            net.minecraft.network.protocol.common.custom.CustomPacketPayload decoded;
                            if (type.equals(net.vg.codex.network.QuestInfoPayload.TYPE.id())) decoded = net.vg.codex.network.QuestInfoPayload.STREAM_CODEC.decode(buf);
                            else if (type.equals(net.vg.codex.network.ClaimableRewardsPayload.TYPE.id())) decoded = net.vg.codex.network.ClaimableRewardsPayload.STREAM_CODEC.decode(buf);
                            else if (type.equals(net.vg.codex.network.TabSummaryPayload.TYPE.id())) decoded = net.vg.codex.network.TabSummaryPayload.STREAM_CODEC.decode(buf);
                            else decoded = net.vg.codex.network.QuestPresentationPayload.STREAM_CODEC.decode(buf);
                            QUEST_PACKETS.computeIfAbsent(id, ignored -> new ArrayList<>()).add(decoded);
                        } finally { buf.release(); }
                    }
                }
                //?}
                ctx.write(message, promise);
            }
        });
        return player;
    }

    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static void check(String name, boolean success) {
        if (success) PASSED.add(name); else FAILURES.add(name);
        System.out.println("CODEX TEST " + (success ? "PASS " : "FAIL ") + name);
    }
}
