package net.vg.codex.client.advancements;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vg.codex.advancement.QuestDefinitions;

/** Advancement-backed dialogue; only confirmed server state can select a branch. */
final class StoryChoicesScreen extends CodexScreen {
    final Identifier id;
    private final Screen parent;
    private final CodexAdvancementsScreen backdrop;
    private final Component description;
    private String pending = "", confirmation = "", status = "", shown = "", passage = "";
    private boolean recovering, revealAll, listening;
    private long pendingAt, passageAt;
    private int page, choicePage, left, top, size, panelHeight, textY, rows, actionY;
    private List<String> lines = List.of();
    private Button advance;

    StoryChoicesScreen(QuestDetailsScreen parent, Identifier id) {
        this(parent, id, parent.getTitle(), Component.empty());
    }

    StoryChoicesScreen(Screen parent, Identifier id, Component title, Component description) {
        super(title);
        this.parent = parent;
        this.id = id;
        this.description = description;
        backdrop = parent instanceof CodexAdvancementsScreen tree ? tree
                : parent instanceof QuestDetailsScreen details ? details.storyBackdrop() : null;
    }

    CodexAdvancementsScreen backdropTree() { return backdrop; }

    private String layoutKey(JsonObject info) {
        if ((info.size() == 0)) return "loading";
        // Progress counters do not restart reading or reset text animation.
        JsonObject state = info.getAsJsonObject("state");
        return "" + info.get("definition") + state.get("choice") + state.get("accepted")
                + info.get("ready") + info.get("unlocked") + info.get("eligible")
                + info.get("all_claimed") + info.get("available_in") + info.get("choice_availability") + info.get("outcomes");
    }

    void refresh(boolean acknowledged) {
        JsonObject info = ClientQuests.info(id);
        JsonObject result = ClientQuests.result(id);
        if (info.has("state") && (pending.startsWith("choice:") && info.getAsJsonObject("state").has("choice")
                || pending.equals("accept") && QuestDefinitions.flag(info.getAsJsonObject("state"), "accepted")
                || pending.equals("claim") && info.get("all_claimed").getAsBoolean())) {
            // An authoritative snapshot confirms success even if a separate result arrived late.
            String expected = pending.startsWith("choice:") ? "choice" : pending;
            status = acknowledged && result.has("action") && result.get("action").getAsString().equals(expected)
                    ? result.get("message").getAsString() : "";
            pending = ""; confirmation = ""; recovering = false;
        }
        if (!pending.isEmpty() && acknowledged && result.has("action")
                && result.get("action").getAsString().equals(pending.startsWith("choice:") ? "choice" : pending)) {
            pending = "";
            recovering = false;
            confirmation = "";
            status = result.get("message").getAsString();
        }
        if (!confirmation.isEmpty() && info.has("request") && info.get("request").getAsString().equals(confirmation)) {
            pending = "";
            recovering = false;
            confirmation = "";
            status = "Quest status refreshed.";
        }
        if (!layoutKey(info).equals(shown) || acknowledged) rebuildWidgets();
    }

    private void send(String action, String value) {
        if (!pending.isEmpty()) return;
        pending = action.equals("choice") ? "choice:" + value : action;
        pendingAt = System.nanoTime();
        confirmation = "";
        recovering = false;
        status = "";
        ClientQuests.action(id, action, value);
        rebuildWidgets();
    }

    private JsonObject dialogue(JsonObject info) {
        if (!info.has("definition")) return new JsonObject();
        JsonObject q = info.getAsJsonObject("definition");
        return q.has("dialogue") ? q.getAsJsonObject("dialogue") : new JsonObject();
    }

    private String passage(JsonObject info) {
        if ((info.size() == 0)) return "Loading the story…";
        JsonObject state = info.getAsJsonObject("state"), d = dialogue(info);
        if (state.has("choice") && d.has("choices")) {
            for (JsonElement e : d.getAsJsonArray("choices")) {
                JsonObject c = e.getAsJsonObject();
                if (c.get("id").equals(state.get("choice"))) return c.has("text") ? c.get("text").getAsString() : "Your choice has been saved.";
            }
        }
        String stage = info.get("ready").getAsBoolean() ? "complete" : QuestDefinitions.flag(state, "accepted") ? "progress" : "intro";
        if (d.has(stage) && !d.get(stage).getAsString().isBlank()) return d.get(stage).getAsString();
        if (d.has("intro") && !d.get("intro").getAsString().isBlank()) return d.get("intro").getAsString();
        return description.getString().isBlank() ? "Choose how you want to continue." : description.getString();
    }

    @Override protected void init() {
        ClientQuests.watch(id);
        size = Math.min(548, width - 24);
        int maxPanelHeight = Math.min(226, height - 32);
        left = (width - size) / 2;
        rows = Math.max(2, Math.min(6, (maxPanelHeight - 132) / 12));
        if (backdrop != null && (backdrop.width != width || backdrop.height != height)) backdrop.init(width, height);
        if (backdrop != null && !listening) { backdrop.attachDialogueBackdrop(); listening = true; }
        JsonObject info = ClientQuests.info(id);
        shown = layoutKey(info);
        String next = passage(info);
        if (!next.equals(passage)) {
            passage = next; page = 0; choicePage = 0; revealAll = false; passageAt = System.nanoTime();
        }
        List<String> wrapped = new ArrayList<>();
        for (String paragraph : passage.split("\n", -1)) {
            if (paragraph.isEmpty()) { wrapped.add(""); continue; }
            for (var line : font.split(Component.literal(paragraph), size - 32)) {
                StringBuilder plain = new StringBuilder();
                line.accept((index, style, codepoint) -> { plain.appendCodePoint(codepoint); return true; });
                wrapped.add(plain.toString());
            }
        }
        lines = wrapped;
        page = Math.min(page, Math.max(0, (lines.size() - 1) / rows));
        int visibleRows = Math.max(2, Math.min(rows, lines.size() - page * rows));
        int textAndHeading = 53 + visibleRows * 12;
        int options = 0;
        if (info.size() == 0 || recovering) options = 1;
        else if (lastPage()) {
            JsonObject state = info.getAsJsonObject("state"), d = dialogue(info);
            options = state.has("choice") ? info.has("outcomes") ? info.getAsJsonArray("outcomes").size() : 0
                    : d.has("choices") ? d.getAsJsonArray("choices").size() : 0;
        }
        int optionRows = Math.min(options, Math.max(1, Math.min(3, (maxPanelHeight - textAndHeading - 52) / 22)));
        panelHeight = Math.min(maxPanelHeight, textAndHeading + optionRows * 22 + 52);
        top = height - panelHeight - 16;
        textY = top + 43;
        actionY = top + textAndHeading;
        int footer = top + panelHeight - 30, buttonWidth = (size - 40) / 3;
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(left + 12, footer, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Quest Details"), b -> minecraft.setScreen(new QuestDetailsScreen(this, id, title, description)))
                .bounds(left + 20 + buttonWidth, footer, buttonWidth, 20).build());
        advance = addRenderableWidget(Button.builder(Component.literal("Continue"), b -> advance()).bounds(left + size - 12 - buttonWidth, footer, buttonWidth, 20).build());
        if ((info.size() == 0) || recovering) {
            addRenderableWidget(Button.builder(Component.literal("Reload Quest Status"), b -> {
                confirmation = ClientQuests.retry(id); status = "Requesting current quest status…";
            }).bounds(left + 16, actionY, size - 32, 20).build());
        } else if (lastPage()) {
            JsonObject state = info.getAsJsonObject("state"), d = dialogue(info);
            if (state.has("choice")) buildOutcomes(info);
            else if (d.has("choices")) buildChoices(info, d.getAsJsonArray("choices"));
        }
        updateAdvance(info);
    }

    private void buildChoices(JsonObject info, JsonArray choices) {
        int count = Math.max(1, Math.min(3, (top + panelHeight - 52 - actionY) / 22));
        choicePage = Math.min(choicePage, Math.max(0, (choices.size() - 1) / count));
        boolean pages = choices.size() > count;
        int x = left + 16 + (pages ? 26 : 0), w = size - 32 - (pages ? 52 : 0);
        if (pages) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> { choicePage--; rebuildWidgets(); }).bounds(left + 12, actionY, 22, 20).build()).active = choicePage > 0;
            addRenderableWidget(Button.builder(Component.literal(">"), b -> { choicePage++; rebuildWidgets(); }).bounds(left + size - 34, actionY, 22, 20).build()).active = (choicePage + 1) * count < choices.size();
        }
        for (int i = choicePage * count; i < Math.min(choices.size(), (choicePage + 1) * count); i++) {
            JsonObject c = choices.get(i).getAsJsonObject();
            String key = c.get("id").getAsString();
            Button button = addRenderableWidget(Button.builder(Component.literal("› " + c.get("label").getAsString()), b -> send("choice", key))
                    .bounds(x, actionY + (i % count) * 22, w, 20).build());
            button.active = pending.isEmpty() && info.get("unlocked").getAsBoolean() && info.get("eligible").getAsBoolean()
                    && (!info.has("choice_availability") || !info.getAsJsonObject("choice_availability").has(key) || info.getAsJsonObject("choice_availability").get(key).getAsBoolean())
                    && QuestDefinitions.choiceAvailable(c, info.get("ready").getAsBoolean(), info.get("dialogue_started").getAsBoolean());
            if (info.has("choice_reasons") && info.getAsJsonObject("choice_reasons").has(key)) button.setTooltip(Tooltip.create(Component.literal(info.getAsJsonObject("choice_reasons").get(key).getAsString())));
        }
    }

    private void buildOutcomes(JsonObject info) {
        JsonArray outcomes = info.has("outcomes") ? info.getAsJsonArray("outcomes") : new JsonArray();
        if ((outcomes.size() == 0)) return;
        // The server lists only chapters unlocked by this decision.
        int count = Math.max(1, Math.min(3, (top + panelHeight - 52 - actionY) / 22));
        choicePage = Math.min(choicePage, Math.max(0, (outcomes.size() - 1) / count));
        int w = size - 84;
        for (int i = choicePage * count; i < Math.min(outcomes.size(), (choicePage + 1) * count); i++) {
            JsonObject quest = outcomes.get(i).getAsJsonObject();
            addRenderableWidget(Button.builder(Component.literal("Open chapter: " + quest.get("title").getAsString()), b -> minecraft.setScreen(QuestDetailsScreen.open(parent,
                    Identifier.parse(quest.get("id").getAsString()), Component.literal(quest.get("title").getAsString()), Component.literal(quest.get("description").getAsString()))))
                    .bounds(left + 42, actionY + (i % count) * 22, w, 20).build());
        }
        if (outcomes.size() > count) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> { choicePage--; rebuildWidgets(); }).bounds(left + 12, actionY, 22, 20).build()).active = choicePage > 0;
            addRenderableWidget(Button.builder(Component.literal(">"), b -> { choicePage++; rebuildWidgets(); }).bounds(left + size - 34, actionY, 22, 20).build()).active = (choicePage + 1) * count < outcomes.size();
        }
    }

    private boolean lastPage() { return (page + 1) * rows >= lines.size(); }
    private int characters() { int count = 0; for (int i = page * rows; i < Math.min(lines.size(), (page + 1) * rows); i++) count += lines.get(i).codePointCount(0, lines.get(i).length()); return count; }
    private int revealed() {
        JsonObject d = dialogue(ClientQuests.info(id));
        return revealAll || !CodexClientSettings.animations || d.has("animate") && !d.get("animate").getAsBoolean()
                ? characters() : (int)Math.min(characters(), (System.nanoTime() - passageAt) / 12_000_000L);
    }
    private boolean canClaim(JsonObject info) {
        return info.size() != 0 && info.get("unlocked").getAsBoolean() && info.get("eligible").getAsBoolean()
                && info.get("ready").getAsBoolean() && !info.get("all_claimed").getAsBoolean();
    }
    private boolean awaitingChoice(JsonObject info) {
        JsonObject d = dialogue(info);
        if (info.size() == 0 || info.getAsJsonObject("state").has("choice") || !d.has("choices")
                || !info.get("unlocked").getAsBoolean() || !info.get("eligible").getAsBoolean()) return false;
        for (JsonElement e : d.getAsJsonArray("choices")) {
            JsonObject c = e.getAsJsonObject(); String key = c.get("id").getAsString();
            if (QuestDefinitions.choiceAvailable(c, info.get("ready").getAsBoolean(), info.get("dialogue_started").getAsBoolean())
                    && (!info.has("choice_availability") || !info.getAsJsonObject("choice_availability").has(key) || info.getAsJsonObject("choice_availability").get(key).getAsBoolean())) return true;
        }
        return false;
    }
    private void advance() {
        if (revealed() < characters()) { revealAll = true; return; }
        if (!lastPage()) { page++; revealAll = false; passageAt = System.nanoTime(); rebuildWidgets(); return; }
        JsonObject info = ClientQuests.info(id);
        if (ClientQuests.canAccept(info)) send("accept", "");
        else if (canClaim(info)) send("claim", "");
        else if (!awaitingChoice(info)) onClose();
    }
    private void updateAdvance(JsonObject info) {
        advance.active = pending.isEmpty() && info.size() != 0 && (!lastPage() || revealed() < characters() || !awaitingChoice(info) || ClientQuests.canAccept(info) || canClaim(info));
        advance.setMessage(Component.literal(revealed() < characters() ? "Show Text" : !lastPage() ? "Continue"
                : ClientQuests.canAccept(info) ? "Accept Quest"
                : canClaim(info) ? "Claim Rewards" : awaitingChoice(info) ? "Choose a Reply" : "Done"));
    }
    @Override public void tick() {
        if (!pending.isEmpty() && System.nanoTime() - pendingAt >= 4_000_000_000L && !recovering) {
            recovering = true; status = "No confirmation yet. Reload quest status to check.";
            confirmation = ClientQuests.retry(id); rebuildWidgets();
        }
        if (!layoutKey(ClientQuests.info(id)).equals(shown)) rebuildWidgets();
        updateAdvance(ClientQuests.info(id));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        if (backdrop != null) backdrop.extractRenderState(g, -10000, -10000, delta);
        g.fill(0, 0, width, height, 0x990B100C);
        g.fill(left - 2, top - 2, left + size + 2, top + panelHeight + 2, 0xFF151711);
        g.fill(left, top, left + size, top + panelHeight, 0xFFB8B9AF);
        g.fill(left + 2, top + 2, left + size - 2, top + panelHeight - 2, 0xFF3E4439);
        g.fill(left + 4, top + 4, left + size - 4, top + panelHeight - 4, 0xFA242722);
        JsonObject info = ClientQuests.info(id), d = dialogue(info);
        int headingX = left + 16;
        if (d.has("speaker_icon")) {
            var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(d.get("speaker_icon").getAsString()));
            g.fakeItem(item.getDefaultInstance(), headingX, top + 13); headingX += 24;
        }
        String speaker = d.has("speaker") && !d.get("speaker").getAsString().isBlank() ? d.get("speaker").getAsString() : title.getString();
        g.text(font, font.plainSubstrByWidth(speaker, size - (headingX - left) - 20), headingX, top + 14, 0xFFE4C76A, false);
        g.fill(left + 16, top + 34, left + size - 16, top + 35, 0xFF444B3D);
        int remaining = revealed();
        for (int i = page * rows; i < Math.min(lines.size(), (page + 1) * rows); i++) {
            String line = lines.get(i); int count = Math.min(remaining, line.codePointCount(0, line.length()));
            g.text(font, line.substring(0, line.offsetByCodePoints(0, count)), left + 16, textY + (i % rows) * 12, 0xFFF1F1E7, false);
            remaining -= count;
        }
        String message = !status.isEmpty() ? status : !pending.isEmpty() ? "Waiting for server confirmation…"
                : (info.size() == 0) ? "Loading…" : !info.get("unlocked").getAsBoolean() ? "This chapter requires a different story choice."
                : info.getAsJsonObject("state").has("choice") ? "Decision saved · linked chapters are available below"
                : "Page " + (page + 1) + " / " + Math.max(1, (lines.size() + rows - 1) / rows);
        g.text(font, font.plainSubstrByWidth(message, size - 32), left + 16, top + panelHeight - 45, 0xFFB6B9AD, false);
        updateAdvance(info);
        super.extractRenderState(g, x, y, delta);
        QuestFeedback.render(g, top - 8);
    }
    @Override public void removed() {
        if (backdrop != null && listening) { backdrop.detachDialogueBackdrop(); listening = false; }
        super.removed();
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
