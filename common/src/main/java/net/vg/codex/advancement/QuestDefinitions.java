package net.vg.codex.advancement;

import com.google.gson.*;
import net.minecraft.resources.Identifier;
import java.nio.file.*;

/** Optional presentation/lifecycle metadata. The advancement itself remains ordinary JSON. */
public final class QuestDefinitions {
    private QuestDefinitions() { }
    private static Path cachedFile;
    private static java.nio.file.attribute.FileTime cachedTime;
    private static long cachedSize = -1, checkedAt;
    private static JsonObject cached = new JsonObject();
    private static JsonObject loaded() {
        Path file = AdvancementPackSync.externalPackDir().resolve("codex/quests.json");
        long now = System.nanoTime();
        if (file.equals(cachedFile) && now - checkedAt < 100_000_000L) return cached;
        checkedAt = now;
        try {
            var time = Files.exists(file) ? Files.getLastModifiedTime(file) : null;
            long size = time == null ? 0 : Files.size(file);
            if (!file.equals(cachedFile) || !java.util.Objects.equals(time, cachedTime) || size != cachedSize) {
                cached = time == null ? new JsonObject() : JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                cachedFile = file; cachedTime = time; cachedSize = size;
            }
            return cached;
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot read quest definitions", error); }
    }
    public static JsonObject all() { return loaded().deepCopy(); }
    public static JsonObject get(Identifier id) {
        JsonElement value = loaded().get(id.toString());
        return value != null && value.isJsonObject() ? value.getAsJsonObject().deepCopy() : new JsonObject();
    }
    public static void save(Identifier id, JsonObject value) {
        validate(value);
        JsonObject all = all();
        if ((value.size() == 0)) all.remove(id.toString()); else all.add(id.toString(), value.deepCopy());
        AtomicJson.write(AdvancementPackSync.externalPackDir().resolve("codex/quests.json"), all);
        cachedFile = null; checkedAt = 0;
    }
    public static boolean flag(JsonObject object, String key) { return object.has(key) && object.get(key).getAsBoolean(); }
    public static boolean repeat(JsonObject object) { return object.has("repeat") && flag(object.getAsJsonObject("repeat"), "enabled"); }
    public static boolean managedLocal(JsonObject q) { return flag(q, "acceptance") || repeat(q); }
    public static boolean managed(Identifier id) { return managedLocal(get(id)); }
    public static boolean choiceAvailable(JsonObject choice, boolean completed, boolean accepted) {
        String stage = choice.has("stage") ? choice.get("stage").getAsString() : "any";
        return switch (stage) { case "intro" -> !accepted && !completed; case "progress" -> accepted && !completed; case "complete" -> completed; default -> true; };
    }
    public static void validate(JsonObject object) {
        if (object.has("quest_tab")) flag(object, "quest_tab");
        if (object.toString().length() > 32_000) throw new IllegalArgumentException("Quest presentation is too large");
        if (object.has("celebration")) {
            JsonObject c=object.getAsJsonObject("celebration"); flag(c,"enabled");
            if(c.has("title")&&c.get("title").getAsString().length()>80)throw new IllegalArgumentException("Celebration title exceeds 80 characters");
            if(c.has("sound")&&!net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.containsKey(Identifier.parse(c.get("sound").getAsString())))throw new IllegalArgumentException("Unknown celebration sound");
        }
        if (object.has("acceptance")) flag(object, "acceptance");
        if (object.has("repeat")) {
            JsonObject repeat = object.getAsJsonObject("repeat");
            flag(repeat, "enabled");
            int seconds = repeat.has("cooldown_seconds") ? repeat.get("cooldown_seconds").getAsBigDecimal().intValueExact() : 0;
            if (repeat.has("rewards") && !java.util.Set.of("group","each").contains(repeat.get("rewards").getAsString())) throw new IllegalArgumentException("Unknown shared reward policy");
            if (repeat.has("scope") && !java.util.Set.of("personal", "party").contains(repeat.get("scope").getAsString())) throw new IllegalArgumentException("Unknown repeat scope");
            if (repeat.has("schedule") && !java.util.Set.of("cooldown", "daily_utc", "periodic").contains(repeat.get("schedule").getAsString())) throw new IllegalArgumentException("Unknown repeat schedule");
            if (repeat.has("schedule") && repeat.get("schedule").getAsString().equals("periodic")) {
                int period = repeat.has("period_seconds") ? repeat.get("period_seconds").getAsBigDecimal().intValueExact() : 0;
                if (period < 60 || period > 31_536_000) throw new IllegalArgumentException("Period must be 60–31536000 seconds");
            }
            if (seconds < 0 || seconds > 31_536_000) throw new IllegalArgumentException("Cooldown must be 0–31536000 seconds");
        }
        if (object.has("dialogue")) {
            JsonObject dialogue = object.getAsJsonObject("dialogue");
            if (dialogue.has("speaker") && dialogue.get("speaker").getAsString().length() > 80) throw new IllegalArgumentException("Speaker name exceeds 80 characters");
            if (dialogue.has("speaker_icon") && !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(Identifier.parse(dialogue.get("speaker_icon").getAsString()))) throw new IllegalArgumentException("Unknown speaker icon");
            if (dialogue.has("animate")) flag(dialogue, "animate");
            for (String page : java.util.List.of("intro", "progress", "complete")) if (dialogue.has(page) && dialogue.get(page).getAsString().length() > 6000) throw new IllegalArgumentException("Dialogue page exceeds 6000 characters");
            if (dialogue.has("choices")) {
                JsonArray choices = dialogue.getAsJsonArray("choices");
                if (choices.size() > 8) throw new IllegalArgumentException("At most eight dialogue choices");
                java.util.Set<String> keys = new java.util.HashSet<>();
                for (JsonElement element : choices) {
                    JsonObject c = element.getAsJsonObject();
                    if (c.get("id").getAsString().isBlank() || c.get("id").getAsString().length() > 80 || !keys.add(c.get("id").getAsString()) || c.get("label").getAsString().isBlank() || c.get("label").getAsString().length() > 80) throw new IllegalArgumentException("Choices need unique IDs and short labels");
                    if (c.has("when")) QuestConditions.validate(c.getAsJsonArray("when"));
                    if (c.has("next_quest")) Identifier.parse(c.get("next_quest").getAsString());
                    if (c.has("stage") && !java.util.Set.of("any", "intro", "progress", "complete").contains(c.get("stage").getAsString())) throw new IllegalArgumentException("Unknown choice stage");
                    if (c.has("text") && c.get("text").getAsString().length() > 6000) throw new IllegalArgumentException("Choice text exceeds 6000 characters");
                }
            }
        }
        if (object.has("requires_choice")) {
            JsonObject requirement = object.getAsJsonObject("requires_choice");
            if(!requirement.has("quest")||!requirement.has("choice"))throw new IllegalArgumentException("Select a story and choice for this branch");
            Identifier.parse(requirement.get("quest").getAsString());
            if (requirement.get("choice").getAsString().isBlank()) throw new IllegalArgumentException("Select a required choice");
        }
    }
    /** A repeat cycle must be representable without revoking native trigger listeners/progress. */
    public static void validateAdvancement(JsonObject metadata, JsonObject advancement) {
        validate(metadata);
        if (!repeat(metadata)) return;
        for (var entry : advancement.getAsJsonObject("criteria").entrySet()) {
            JsonObject criterion = entry.getValue().getAsJsonObject();
            if (!criterion.has("codex_submission") && !criterion.has("codex_statistic")) throw new IllegalArgumentException("Repeat cycles currently support delivery and statistic criteria only");
        }
        if (!QuestDefinitions.flag(advancement, "codex_claimable")) throw new IllegalArgumentException("Repeatable rewards must be claimable");
    }
}
