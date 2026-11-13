package net.vg.codex.validation;

import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;
import net.vg.codex.advancement.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

final class BundledExampleScenarios {
    private static JsonObject read(Path p) throws Exception { return JsonParser.parseString(Files.readString(p)).getAsJsonObject(); }
    static void run(MinecraftServer server, BiConsumer<String,Boolean> check) throws Exception {
        Path root = Files.createTempDirectory(Path.of("."), "example-install-"), pack=root.resolve("pack"), history=root.resolve("history.json");
        for(var pair:Map.of(QuestCriteria.CriterionType.PLAYER_KILLED_ENTITY,"minecraft:zombie",QuestCriteria.CriterionType.LOCATION_BIOME,"minecraft:forest",QuestCriteria.CriterionType.RECIPE_CRAFTED,"minecraft:crafting_table",QuestCriteria.CriterionType.LOCATION_STRUCTURE,"minecraft:stronghold",QuestCriteria.CriterionType.ITEM_USED_ON_BLOCK,"minecraft:stone;minecraft:stick").entrySet()) {
            JsonObject document=new JsonObject(),criteria=new JsonObject();criteria.add("task",QuestCriteria.buildCriterionJson(pair.getKey(),pair.getValue()));document.add("criteria",criteria);
            var ops=net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,AdvancementValidation.registries(server));
            var nativeAdvancement=net.minecraft.advancements.Advancement.CODEC.parse(ops,document).getOrThrow();
            String serialized=net.minecraft.advancements.Advancement.CODEC.encodeStart(ops,nativeAdvancement).getOrThrow().toString();
            check.accept("Generated "+pair.getKey()+" retains its target through native codecs",java.util.Arrays.stream(pair.getValue().split(";")).allMatch(serialized::contains));
            check.accept("Generated "+pair.getKey()+" remains visually editable",QuestCriteria.CriterionType.fromJson(criteria.getAsJsonObject("task"))==pair.getKey());
        }
        Set<String> all=Set.copyOf(BundledExamples.GROUPS);
        BundledExamples.apply(pack,history,Set.of());
        check.accept("Disabled first install creates no example content",!Files.exists(pack.resolve("data")));
        BundledExamples.apply(pack,history,all);
        Path story=pack.resolve(ResourceFormats.relative("data/codex_demo/advancement/camp_chronicle/root.json")), quests=pack.resolve("codex/quests.json");
        check.accept("Bundled story installs ordinary native advancement JSON",read(story).getAsJsonObject("criteria").getAsJsonObject("arrive").get("trigger").getAsString().equals("minecraft:tick"));
        check.accept("Bundled vanilla and story metadata merge without loss",read(quests).has("minecraft:story/root")&&read(quests).has("codex_demo:camp_chronicle/root"));
        check.accept("Companion sidecars are included only when requested",read(quests).has("sagittary:arsenal"));
        check.accept("Examples do not change recipes",!Files.exists(pack.resolve(ResourceFormats.relative("data/minecraft/recipe"))));
        String rootKey="codex_demo:camp_chronicle/root";
        JsonObject installed=read(quests).getAsJsonObject(rootKey).deepCopy();
        QuestDefinitions.validate(installed);
        check.accept("Bundled RPG dialogue has editable speaker/icon and paged text",installed.getAsJsonObject("dialogue").get("speaker_icon").getAsString().equals("minecraft:book")&&installed.getAsJsonObject("dialogue").get("intro").getAsString().contains("\n\n"));
        JsonObject old=installed.deepCopy();old.getAsJsonObject("dialogue").remove("speaker");old.getAsJsonObject("dialogue").remove("speaker_icon");
        JsonObject oldDocument=read(quests);oldDocument.add(rootKey,old);AtomicJson.write(quests,oldDocument);
        JsonObject oldLedger=read(history);oldLedger.getAsJsonObject("showcase").getAsJsonObject("codex/quests.json#"+rootKey).add("baseline",old.deepCopy());AtomicJson.write(history,oldLedger);
        BundledExamples.apply(pack,history,all);
        check.accept("Untouched existing story gains RPG presentation on upgrade",read(quests).getAsJsonObject(rootKey).equals(installed));
        JsonObject authored=read(quests);authored.getAsJsonObject(rootKey).getAsJsonObject("dialogue").addProperty("speaker","My storyteller");AtomicJson.write(quests,authored);
        BundledExamples.apply(pack,history,all);
        check.accept("Example upgrade preserves an author's speaker edits",read(quests).getAsJsonObject(rootKey).getAsJsonObject("dialogue").get("speaker").getAsString().equals("My storyteller"));
        authored.add(rootKey,installed);AtomicJson.write(quests,authored);
        boolean speakerRejected=false;JsonObject invalidSpeaker=installed.deepCopy();invalidSpeaker.getAsJsonObject("dialogue").addProperty("speaker_icon","codex:not_an_item");
        try{QuestDefinitions.validate(invalidSpeaker);}catch(IllegalArgumentException expected){speakerRejected=true;}
        check.accept("Dialogue rejects unknown speaker item icons",speakerRejected);
        String snapshot=Files.readString(quests);BundledExamples.apply(pack,history,all);
        check.accept("Restart does not change installed metadata",Files.readString(quests).equals(snapshot));
        Path removed=pack.resolve(ResourceFormats.relative("data/codex_demo/advancement/camp_chronicle/heading.json"));Files.delete(removed);BundledExamples.apply(pack,history,all);
        check.accept("Restart does not recreate author-deleted quests",!Files.exists(removed));
        JsonObject q=read(quests);JsonObject custom=new JsonObject();custom.addProperty("acceptance",true);q.add("custom:unrelated",custom);AtomicJson.write(quests,q);
        BundledExamples.apply(pack,history,Set.of());
        check.accept("Disable removes untouched example trees",!Files.exists(story));
        check.accept("Disable preserves unrelated authored entries",read(quests).has("custom:unrelated"));
        check.accept("Removing bundled files retains backups",Files.exists(story.resolveSibling("root.json.bak")));
        BundledExamples.apply(pack,history,all);
        check.accept("Re-enable restores entries removed by the switch",Files.exists(story));
        check.accept("Re-enable still respects author deletion",!Files.exists(removed));
        JsonObject edited=read(story);edited.getAsJsonObject("display").addProperty("title","My edited camp");AtomicJson.write(story,edited);
        BundledExamples.apply(pack,history,Set.of());
        check.accept("Disable preserves customized story",read(story).getAsJsonObject("display").get("title").getAsString().equals("My edited camp"));
        check.accept("Customized story keeps its dependencies",read(quests).has("codex_demo:camp_chronicle/root")&&Files.exists(pack.resolve(ResourceFormats.relative("data/codex_demo/loot_table/codex_treasure/field_provisions.json"))));
        Path existing=root.resolve("existing"),existingHistory=root.resolve("existing-history.json");Path existingStory=existing.resolve(ResourceFormats.relative("data/codex_demo/advancement/camp_chronicle/root.json"));AtomicJson.write(existingStory,edited);BundledExamples.apply(existing,existingHistory,all);BundledExamples.apply(existing,existingHistory,Set.of());
        check.accept("Pre-existing author definitions never become installer-owned",Files.exists(existingStory)&&read(existingStory).equals(edited));
        Path malformed=root.resolve("malformed"), malformedHistory=root.resolve("malformed-history.json");BundledExamples.apply(malformed,malformedHistory,all);Files.writeString(malformed.resolve("codex/quests.json"),"{");boolean rejected=false;
        try{BundledExamples.apply(malformed,malformedHistory,Set.of());}catch(RuntimeException e){rejected=true;}
        check.accept("Malformed authored metadata aborts before removing files",rejected&&Files.exists(malformed.resolve(ResourceFormats.relative("data/codex_demo/advancement/camp_chronicle/root.json"))));
        check.accept("Fresh server loads bundled showcase root",server.getAdvancements().get(Identifier.parse("codex_demo:camp_chronicle/root"))!=null);
        check.accept("Fresh server loads all twelve showcase quests",server.getAdvancements().getAllAdvancements().stream().filter(entry->entry.id().toString().startsWith("codex_demo:camp_chronicle/")).count()==12);
        for(String id:List.of("story/root","story/mine_stone","story/smelt_iron","story/upgrade_tools","story/enter_the_nether","husbandry/plant_seed")) {
            Identifier resource=Identifier.fromNamespaceAndPath("minecraft",ResourceFormats.directory("advancement")+"/"+id+".json");JsonObject original=null;
            for(var r:server.getResourceManager().getResourceStack(resource))if(!r.sourcePackId().contains(AdvancementPackSync.PACK_ID))try(var reader=r.openAsReader()){original=JsonParser.parseReader(reader).getAsJsonObject();}
            JsonObject example=read(AdvancementPackSync.externalPackDir().resolve(ResourceFormats.relative("data/minecraft/advancement/"+id+".json")));original.remove("display");example.remove("display");
            check.accept("Native criteria requirements and rewards unchanged: "+id,original.equals(example));
        }
        check.accept("No legacy journal tab bundled",server.getAdvancements().get(Identifier.parse("codex_demo:field_journal/root"))==null);
    }
}
