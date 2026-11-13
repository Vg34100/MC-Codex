package net.vg.codex.client.advancements;

import com.google.gson.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vg.codex.advancement.QuestDefinitions;
import java.util.*;

/** Quest presentation remains attached to the advancement; no NPC or world coordinates. */
public final class QuestDetailsScreen extends CodexScreen {
    public final Identifier id;
    private final Screen parent;
    private final Component description;
    private FittingMultiLineTextWidget body;
    private Button accept, claim, stored, retry, rewardPreview;
    private final List<Button> submissions = new ArrayList<>(), choices = new ArrayList<>();
    private boolean loaded;
    private int loadingTicks;
    private int left, panelWidth, rewardsTop;
    public QuestDetailsScreen(Screen parent, Identifier id, Component title, Component description) {
        super(title); this.parent = parent; this.id = id; this.description = description;
    }
    public static Screen open(Screen parent, Identifier id, Component title, Component description) {
        JsonObject info = ClientQuests.info(id), presentation = ClientQuests.presentation(id);
        boolean story = info.has("definition")
                ? info.getAsJsonObject("definition").has("dialogue") && info.getAsJsonObject("definition").getAsJsonObject("dialogue").size() != 0
                : presentation.has("dialogue") && presentation.get("dialogue").getAsBoolean();
        return story ? new StoryChoicesScreen(parent, id, title, description) : new QuestDetailsScreen(parent, id, title, description);
    }
    CodexAdvancementsScreen storyBackdrop() {
        return parent instanceof CodexAdvancementsScreen tree ? tree : parent instanceof StoryChoicesScreen story ? story.backdropTree() : null;
    }
    @Override protected void init() {
        ClientQuests.watch(id);
        panelWidth = Math.min(500, width - 24); left = (width - panelWidth) / 2; submissions.clear(); choices.clear();
        JsonObject info = ClientQuests.info(id); loaded = (info.size() != 0);
        int rightWidth = 150, right = left + panelWidth - rightWidth;
        body = addRenderableWidget(new FittingMultiLineTextWidget(left, 38, panelWidth - rightWidth - 10, height - 106, Component.literal(text(info)), font));
        retry = addRenderableWidget(Button.builder(Component.literal("Retry Loading"), b -> { loadingTicks = 0; ClientQuests.retry(id); update(); }).bounds(left, height - 58, panelWidth - rightWidth - 10, 20).build()); retry.visible = false;
        rewardPreview=addRenderableWidget(Button.builder(Component.literal("Reward Preview…"),b->{JsonObject current=ClientQuests.info(id);JsonObject rewards=current.has("rewards")?current.getAsJsonObject("rewards"):ClientAdvancementRewards.forAdvancement(id);if(rewards!=null)minecraft.setScreen(new RewardPreviewScreen(this,id,rewards,ClientClaimableRewards.contains(id)||current.has("claimable")&&current.get("claimable").getAsBoolean()));}).bounds(left,height-58,panelWidth-rightWidth-10,20).build());
        rewardPreview.visible=loaded;
        int y = 38;
        accept = addRenderableWidget(Button.builder(Component.literal("Accept / Next Cycle"), b -> ClientQuests.action(id, "accept", "")).bounds(right, y, rightWidth, 20).build()); y += 24;
        claim = addRenderableWidget(Button.builder(Component.literal("Claim Rewards"), b -> ClientQuests.action(id, "claim", "")).bounds(right, y, rightWidth, 20).build()); y += 24;
        addRenderableWidget(Button.builder(Component.literal(CodexClientSettings.pins.contains(id) ? "Unpin Tracker" : "Pin to Tracker"), b -> { CodexClientSettings.pin(id); rebuildWidgets(); }).bounds(right, y, rightWidth, 20).build()); y += 28;
        if (loaded) {
            List<JsonObject> deliveries=new ArrayList<>();for(JsonElement value:info.getAsJsonArray("tasks")){JsonObject task=value.getAsJsonObject();if(task.has("items"))deliveries.add(task);}
            if(!deliveries.isEmpty()){
                Button submit=addRenderableWidget(Button.builder(Component.literal(deliveries.size()==1?"Submit Items":"Choose Delivery…"),b->{
                    if(deliveries.size()==1)ClientQuests.action(id,"submit",deliveries.get(0).get("criterion").getAsString());
                    else{
                        Map<Identifier,JsonObject> options=new LinkedHashMap<>();for(int i=0;i<deliveries.size();i++)options.put(Identifier.fromNamespaceAndPath("codex","delivery/"+i),deliveries.get(i));
                        minecraft.setScreen(new IdentifierSelectionScreen(this,"Choose Delivery",options.keySet(),key->{JsonObject task=options.get(key);return Component.literal(task.get("criterion").getAsString().replace('_',' ')+" • "+task.getAsJsonArray("items").size()+" requirements");},key->ClientQuests.action(id,"submit",options.get(key).get("criterion").getAsString())));
                    }
                }).bounds(right,y,rightWidth,20).build());submit.setTooltip(Tooltip.create(Component.literal("Consumes matching inventory items. Partial deliveries are saved.")));submissions.add(submit);y+=24;
            }
            JsonObject q = info.getAsJsonObject("definition");
            if (q.has("dialogue")) {
                addRenderableWidget(Button.builder(Component.literal("Read Story…"), b -> minecraft.setScreen(parent instanceof StoryChoicesScreen story && story.id.equals(id) ? parent : new StoryChoicesScreen(this, id))).bounds(right, y, rightWidth, 20).build()); y += 24;
            }
        }
        rewardsTop = y + 8;
        stored = addRenderableWidget(Button.builder(Component.literal("Collect Stored Rewards"), b -> ClientQuests.action(id, "mailbox", "")).bounds(right, height - 60, rightWidth, 20).build());
        stored.visible = false;
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(left, height - 32, panelWidth, 20).build());
        update();
    }
    public void update() {
        JsonObject info = ClientQuests.info(id); if (body == null) return;
        if ((info.size() == 0)) {
            accept.active = false; claim.active = false; stored.visible = false; rewardPreview.visible = false;
            for (Button b : submissions) b.active = false;
            retry.visible = loadingTicks >= 60; body.setMessage(Component.literal(text(info))); return;
        }
        retry.visible = false; rewardPreview.visible = true;
        if (!loaded) { rebuildWidgets(); return; }
        body.setMessage(Component.literal(text(info)));
        JsonObject q = info.getAsJsonObject("definition"), s = info.getAsJsonObject("state");
        boolean accepted = QuestDefinitions.flag(s, "accepted"), done = QuestDefinitions.flag(s, "completed");
        long wait = info.has("available_in") ? info.get("available_in").getAsLong() : 0;
        accept.visible = QuestDefinitions.managedLocal(q);
        accept.active = info.get("unlocked").getAsBoolean() && (!accepted || QuestDefinitions.repeat(q) && done && info.get("all_claimed").getAsBoolean() && (!info.has("cycle_claimed") || info.get("cycle_claimed").getAsBoolean()) && wait == 0);
        accept.setMessage(Component.literal(!accepted ? "Accept Quest" : !done ? "Quest Accepted" : wait > 0 ? "Next Cycle: " + ClientQuests.waitText(wait) : info.has("cycle_claimed") && !info.get("cycle_claimed").getAsBoolean() ? "Waiting for Party Claims" : "Start Next Cycle"));
        claim.active = info.get("ready").getAsBoolean() && !info.get("all_claimed").getAsBoolean();
        stored.visible = info.has("stored_rewards") && info.get("stored_rewards").getAsInt() > 0;
        for (Button b : submissions) b.active = info.get("unlocked").getAsBoolean() && info.get("eligible").getAsBoolean() && (!QuestDefinitions.managedLocal(q) || accepted && !done);
    }
    private String text(JsonObject info) {
        if ((info.size() == 0)) {
            JsonObject cached = ClientQuests.presentation(id), result = ClientQuests.result(id);
            String lock = cached.has("reason") ? cached.get("reason").getAsString() + "\n\n" : "";
            String feedback = result.has("success") && !result.get("success").getAsBoolean() ? ClientQuests.resultText(result) + "\n\n" : "";
            return lock + description.getString() + "\n\n" + feedback + (loadingTicks < 60 ? "Loading quest details…" : "Quest data is taking longer to arrive. Retry Loading requests fresh data.");
        }
        JsonObject q = info.getAsJsonObject("definition"), s = info.getAsJsonObject("state");
        StringBuilder text = new StringBuilder();
        if (!info.get("unlocked").getAsBoolean()) {
            JsonObject cached = ClientQuests.presentation(id);
            text.append(cached.has("reason") ? cached.get("reason").getAsString() : "This quest requires a different story choice.").append("\n\n");
        }
        JsonObject result = ClientQuests.result(id);
        if ((result.size() != 0)) text.append(ClientQuests.resultText(result)).append("\n\n");
        String page = info.get("ready").getAsBoolean() ? "complete" : QuestDefinitions.flag(s, "accepted") ? "progress" : "intro";
        if (q.has("dialogue")) {
            JsonObject d = q.getAsJsonObject("dialogue");
            if (d.has(page)) text.append(d.get(page).getAsString()).append("\n\n");
            if (s.has("choice") && d.has("choices")) for (JsonElement e : d.getAsJsonArray("choices")) {
                JsonObject c = e.getAsJsonObject(); if (s.get("choice").equals(c.get("id")) && c.has("text")) text.append(c.get("text").getAsString()).append("\n\n");
            }
        }
        if (info.has("shared") && info.get("shared").getAsBoolean()) {
            text.append(info.has("individual_rewards")&&info.get("individual_rewards").getAsBoolean()?"PARTY CONTRACT\nShared objectives · each participant claims their own rewards.\n":"PARTY CONTRACT\nShared objectives · one reward set for the group.\n");
            if (!info.get("eligible").getAsBoolean()) text.append("You joined after this cycle began. Join the next cycle.\n");
            if (s.has("contributions")) { JsonObject c = s.getAsJsonObject("contributions"); String key = minecraft.player.getUUID().toString(); text.append("Your delivered items: ").append(c.has(key) ? c.get(key).getAsInt() : 0).append("\n"); }
            text.append("\n");
        }
        if (QuestDefinitions.repeat(q) && q.getAsJsonObject("repeat").has("schedule") && !q.getAsJsonObject("repeat").get("schedule").getAsString().equals("cooldown")) text.append(q.getAsJsonObject("repeat").get("schedule").getAsString().equals("daily_utc") ? "Next cycle unlocks at 00:00 UTC after completion.\n\n" : "Next cycle unlocks on the configured interval.\n\n");
        text.append(description.getString()).append("\n\n").append(ClientQuests.summary(info)).append("\n");
        if (QuestDefinitions.repeat(q)) text.append("Repeat cycle. Vanilla completion stays earned.\n");
        if (s.has("completed_day")) text.append("Completed on day ").append(s.get("completed_day").getAsLong()).append("\n");
        for (JsonElement e : info.getAsJsonArray("tasks")) {
            JsonObject t = e.getAsJsonObject();
            if (t.has("items")) for (JsonElement item : t.getAsJsonArray("items")) {
                JsonObject i = item.getAsJsonObject(); var type = BuiltInRegistries.ITEM.getValue(Identifier.parse(i.get("id").getAsString()));
                text.append("\nDeliver ").append(i.has("label") ? i.get("label").getAsString() : new net.minecraft.world.item.ItemStack(type).getHoverName().getString()).append(": ").append(i.get("submitted").getAsInt()).append(" / ").append(i.get("required").getAsInt());
            }
            else if (t.has("target")) text.append("\n").append(t.get("criterion").getAsString().replace('_', ' ')).append(": ").append(t.get("value").getAsInt()).append(" / ").append(t.get("target").getAsInt());
            else text.append("\n").append(t.get("done").getAsBoolean() ? "✓ " : "• ").append(t.get("criterion").getAsString().replace('_', ' '));
        }
        JsonObject rewards = info.has("rewards") ? info.getAsJsonObject("rewards") : ClientAdvancementRewards.forAdvancement(id);
        if (rewards != null && (rewards.size() != 0)) {
            text.append("\n\nRewards\n");
            for (JsonObject reward : net.vg.codex.advancement.QuestRewards.entries(rewards, id)) text.append(ClientRewardPresentation.label(reward)).append("\n");
        }
        return text.toString();
    }
    @Override public void tick() {
        if ((ClientQuests.info(id).size()==0)) { loadingTicks++; if (body != null) body.setMessage(Component.literal(text(ClientQuests.info(id)))); }
        update();
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0xED202020); g.text(font, font.plainSubstrByWidth(title.getString(), panelWidth), left, 16, 0xFFFFFFFF, false); super.extractRenderState(g, x, y, delta);
        JsonObject info = ClientQuests.info(id);
        JsonObject rewards = info.has("rewards") ? info.getAsJsonObject("rewards") : ClientAdvancementRewards.forAdvancement(id);
        if (rewards != null && rewardsTop + 26 <= height - 66) {
            int row = rewardsTop + 14, right = left + panelWidth - 150;
            g.text(font, "Rewards", right, rewardsTop, 0xFFCCCCAA, false);
            for (JsonObject reward : net.vg.codex.advancement.QuestRewards.entries(rewards, id)) {
                if (row + 18 > height - 66) break;
                g.fakeItem(ClientRewardPresentation.icon(reward), right, row);
                String label = ClientRewardPresentation.label(reward);
                g.text(font, font.plainSubstrByWidth(label, 129), right + 21, row + 4, 0xFFFFFFFF, false);
                if (x >= right && x < right + 150 && y >= row && y < row + 18) g.setTooltipForNextFrame(font, Component.literal(label), x, y);
                row += 20;
            }
        }
        QuestFeedback.render(g);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
