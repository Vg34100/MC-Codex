package net.vg.codex.client.advancements;

import com.google.gson.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

final class StoryChoicesScreen extends CodexScreen {
    final Identifier id;
    private final QuestDetailsScreen parent;
    private int left, size;
    private String pending = "", shown = "";
    private int page;
    private long pendingAt;
    private String confirmation = "", status = "";
    private boolean recovering;
    StoryChoicesScreen(QuestDetailsScreen parent, Identifier id) { super(Component.literal("Story Choices")); this.parent = parent; this.id = id; }
    private String layoutKey(JsonObject info) {
        if (info.isEmpty()) return "";
        JsonObject state = info.getAsJsonObject("state");
        return "" + state.get("choice") + state.get("accepted") + info.get("dialogue_started") + info.get("ready") + info.get("unlocked") + info.get("outcomes");
    }
    void refresh(boolean acknowledged) {
        JsonObject info = ClientQuests.info(id); String key = layoutKey(info);
        boolean chosen = !info.isEmpty() && info.getAsJsonObject("state").has("choice");
        boolean changed = !key.equals(shown), redraw = changed;
        JsonObject result = ClientQuests.result(id);
        if (chosen) {
            redraw |= !pending.isEmpty() || recovering;
            pending = ""; confirmation = ""; status = ""; recovering = false;
        } else if (acknowledged && result.has("action") && result.get("action").getAsString().equals("choice")) {
            pending = ""; confirmation = ""; recovering = true;
            status = result.get("message").getAsString(); redraw = true;
        } else if (!confirmation.isEmpty() && info.has("request") && info.get("request").getAsString().equals(confirmation)) {
            boolean wasPending = !pending.isEmpty();
            pending = ""; confirmation = ""; recovering = false;
            status = wasPending ? "Your choice was not saved. Select a chapter to try again." : ""; redraw = true;
        }
        if (changed) page = 0;
        if (redraw) rebuildWidgets();
    }
    private boolean needsRetry(JsonObject info) { return info.isEmpty() || recovering; }
    @Override public void tick() {
        if (!pending.isEmpty() && System.nanoTime() - pendingAt >= 4_000_000_000L && !recovering) {
            recovering = true; status = "No confirmation yet. Reload quest status to check.";
            confirmation = ClientQuests.retry(id); rebuildWidgets();
        }
    }
    @Override protected void init() {
        ClientQuests.watch(id); size = Math.min(500, width - 24); left = (width - size) / 2;
        JsonObject info = ClientQuests.info(id); shown = layoutKey(info);
        int reserved = needsRetry(info) ? 26 : 0;
        if (!info.isEmpty()) {
            JsonObject state = info.getAsJsonObject("state"), d = info.getAsJsonObject("definition").getAsJsonObject("dialogue");
            boolean chosen = state.has("choice");
            int y = 62;
            if (chosen) {
                StringBuilder outcome = new StringBuilder("Your choice is saved.\n\n");
                for (JsonElement e : d.getAsJsonArray("choices")) {
                    JsonObject c = e.getAsJsonObject(); if (!c.get("id").equals(state.get("choice"))) continue;
                    outcome.append(c.get("label").getAsString()).append("\n\n").append(c.has("text") ? c.get("text").getAsString() : "");
                }
                addRenderableWidget(new FittingMultiLineTextWidget(left, y, size, Math.max(42, (height - 140 - reserved) / 2), Component.literal(outcome.toString()), font));
                y += Math.max(42, (height - 140 - reserved) / 2) + 8;
                JsonArray outcomes = info.has("outcomes") ? info.getAsJsonArray("outcomes") : new JsonArray();
                int rows = Math.max(1, (height - 78 - reserved - y) / 24); page = Math.min(page, Math.max(0, (outcomes.size() - 1) / rows));
                for (int i = page * rows; i < Math.min((page + 1) * rows, outcomes.size()); i++) {
                    JsonObject quest = outcomes.get(i).getAsJsonObject(); Identifier next = Identifier.parse(quest.get("id").getAsString());
                    addRenderableWidget(Button.builder(Component.literal("Open chapter: " + quest.get("title").getAsString()), b -> minecraft.setScreen(new QuestDetailsScreen(parent, next, Component.literal(quest.get("title").getAsString()), Component.literal(quest.get("description").getAsString())))).bounds(left, y, size, 20).build()); y += 24;
                }
                if (outcomes.size() > rows) {
                    addRenderableWidget(Button.builder(Component.literal("< Previous"), b -> { page--; rebuildWidgets(); }).bounds(left, height - 58 - reserved, size / 2 - 3, 20).build()).active = page > 0;
                    addRenderableWidget(Button.builder(Component.literal("Next >"), b -> { page++; rebuildWidgets(); }).bounds(left + size / 2 + 3, height - 58 - reserved, size / 2 - 3, 20).build()).active = (page + 1) * rows < outcomes.size();
                }
            } else {
                JsonArray choices = d.getAsJsonArray("choices"); int rows = Math.max(1, (height - 122 - reserved) / 26), from = page * rows;
                for (int i = from; i < Math.min(from + rows, choices.size()); i++) {
                    JsonObject c = choices.get(i).getAsJsonObject(); String key = c.get("id").getAsString();
                    Button button = addRenderableWidget(Button.builder(Component.literal(c.get("label").getAsString()), b -> { pending = key; pendingAt = System.nanoTime(); confirmation = ""; status = ""; recovering = false; ClientQuests.action(id, "choice", key); rebuildWidgets(); }).bounds(left, y, size, 20).build());
                    button.active = (!info.has("choice_availability") || !info.getAsJsonObject("choice_availability").has(key) || info.getAsJsonObject("choice_availability").get(key).getAsBoolean()) && pending.isEmpty() && info.get("unlocked").getAsBoolean() && info.get("eligible").getAsBoolean() && net.vg.codex.advancement.QuestDefinitions.choiceAvailable(c, info.get("ready").getAsBoolean(), info.get("dialogue_started").getAsBoolean());
                    button.setTooltip(Tooltip.create(Component.literal(info.has("choice_reasons") && info.getAsJsonObject("choice_reasons").has(key) ? info.getAsJsonObject("choice_reasons").get(key).getAsString() : c.has("text") ? c.get("text").getAsString() : "Choose this chapter"))); y += 26;
                }
                if (choices.size() > rows) {
                    addRenderableWidget(Button.builder(Component.literal("< Previous"), b -> { page--; rebuildWidgets(); }).bounds(left, height - 58 - reserved, size / 2 - 3, 20).build()).active = page > 0;
                    addRenderableWidget(Button.builder(Component.literal("Next >"), b -> { page++; rebuildWidgets(); }).bounds(left + size / 2 + 3, height - 58 - reserved, size / 2 - 3, 20).build()).active = from + rows < choices.size();
                }
            }
        }
        if (needsRetry(info)) addRenderableWidget(Button.builder(Component.literal("Reload Quest Status"), b -> {
            confirmation = ClientQuests.retry(id); status = "Requesting current quest status…";
        }).bounds(left, height - 58, size, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back to Quest"), b -> onClose()).bounds(left, height - 32, size, 20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0xED202020); g.text(font, title, left, 14, 0xFFFFFFFF, false);
        String message = !status.isEmpty() ? status : !pending.isEmpty() ? "Saving your choice…" : ClientQuests.info(id).isEmpty() ? "Loading story choices…" : "Choose a chapter once. The outcome stays in your journal.";
        g.text(font, font.plainSubstrByWidth(message, size), left, 38, 0xFFCCCCAA, false);
        super.extractRenderState(g, x, y, delta);
        QuestFeedback.render(g);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
