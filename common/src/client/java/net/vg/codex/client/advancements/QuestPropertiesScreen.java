package net.vg.codex.client.advancements;

import com.google.gson.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vg.codex.advancement.QuestDefinitions;
import java.util.*;
import java.util.function.Consumer;

/** A private, lossless sidecar draft. Advanced properties stay out of everyday editing. */
public final class QuestPropertiesScreen extends CodexScreen {
    private final Screen parent;
    private final Identifier currentQuest;
    private final Consumer<JsonObject> apply;
    private final Map<Identifier, JsonObject> definitions;
    private final JsonObject draft;
    private String page = "intro", tab = "Story", error = "";
    private int left, size, choicePage, branchPage;
    private boolean invalidDuration; private String durationDraft, durationField;
    public QuestPropertiesScreen(Screen parent, Identifier currentQuest, JsonObject metadata, Map<Identifier, JsonObject> definitions, Consumer<JsonObject> apply) {
        super(Component.literal("Quest Story & Rules")); this.parent = parent; this.currentQuest = currentQuest; this.apply = apply; this.definitions = definitions; draft = metadata.deepCopy();
    }
    private JsonObject dialogue() { if (!draft.has("dialogue")) draft.add("dialogue", new JsonObject()); return draft.getAsJsonObject("dialogue"); }
    private JsonArray choices() { JsonObject d = dialogue(); if (!d.has("choices")) d.add("choices", new JsonArray()); return d.getAsJsonArray("choices"); }
    @Override protected void init() {
        size = Math.min(500, width - 24); left = (width - size) / 2;
        String[] tabs = {"Story", "Choices", "Rules", "Branch", "Effects", "Tab"};
        for (int i = 0; i < tabs.length; i++) { String value = tabs[i]; addRenderableWidget(Button.builder(Component.literal(value), b -> { tab = value; rebuildWidgets(); }).bounds(left + i * (size / tabs.length), 34, size / tabs.length - 3, 20).build()).active = !tab.equals(value); }
        switch (tab) {
            case "Tab" -> {
                JsonObject definition = definitions.get(currentQuest);
                boolean root = definition != null && !definition.has("parent");
                addRenderableWidget(CycleButton.onOffBuilder(QuestDefinitions.flag(draft, "quest_tab")).create(left, 64, size, 20,
                    Component.literal("Codex quest tab"), (b, value) -> draft.addProperty("quest_tab", value))).active = root;
            }
            case "Story" -> {
                addRenderableWidget(CycleButton.builder((String v) -> Component.literal(switch (v) { case "intro" -> "Introduction"; case "progress" -> "In Progress"; default -> "Completion"; }), page).withValues("intro", "progress", "complete").create(left, 64, size, 20, Component.literal("Text"), (b, value) -> { page = value; rebuildWidgets(); }));
                MultiLineEditBox editor = addRenderableWidget(MultiLineEditBox.builder().setX(left).setY(90).build(font, size, height - 160, Component.literal("Dialogue text")));
                editor.setCharacterLimit(6000); editor.setValue(dialogue().has(page) ? dialogue().get(page).getAsString() : ""); editor.setValueListener(value -> dialogue().addProperty(page, value));
            }
            case "Rules" -> {
                addRenderableWidget(CycleButton.onOffBuilder(QuestDefinitions.flag(draft, "acceptance")).create(left, rulesY(0), size, 20, Component.literal("Require Acceptance"), (b, value) -> draft.addProperty("acceptance", value)));
                JsonObject repeat = draft.has("repeat") ? draft.getAsJsonObject("repeat") : new JsonObject(); draft.add("repeat", repeat);
                addRenderableWidget(CycleButton.onOffBuilder(QuestDefinitions.flag(repeat, "enabled")).create(left, rulesY(1), size, 20, Component.literal("Repeatable"), (b, value) -> { repeat.addProperty("enabled", value); if(!value){invalidDuration=false;durationDraft=null;error="";} rebuildWidgets(); }));
                if (QuestDefinitions.flag(repeat, "enabled")) {
                    String schedule = repeat.has("schedule") ? repeat.get("schedule").getAsString() : "cooldown";
                    addRenderableWidget(CycleButton.builder((String v) -> Component.literal(switch(v) { case "daily_utc" -> "Daily (00:00 UTC)"; case "periodic" -> "Fixed interval"; default -> "After cooldown"; }), schedule).withValues("cooldown", "daily_utc", "periodic").create(left, rulesY(2), size, 20, Component.literal("Next cycle"), (b, value) -> { repeat.addProperty("schedule", value); invalidDuration=false;durationDraft=null;error=""; if (value.equals("periodic") && !repeat.has("period_seconds")) repeat.addProperty("period_seconds", 3600); rebuildWidgets(); }));
                    if (!schedule.equals("daily_utc")) {
                        String field = schedule.equals("periodic") ? "period_seconds" : "cooldown_seconds";
                        EditBox duration = addRenderableWidget(new EditBox(font, left + 140, rulesY(3), size - 140, 20, Component.literal("Duration in seconds")));
                        if(!field.equals(durationField)){durationDraft=null;invalidDuration=false;durationField=field;}
                        duration.setValue(durationDraft!=null?durationDraft:repeat.has(field)?repeat.get(field).getAsString():"0");
                        duration.setResponder(value -> { durationDraft=value; try { repeat.addProperty(field, Integer.parseInt(value)); invalidDuration=false;error = ""; } catch (NumberFormatException invalid) { invalidDuration=true;error = "Enter whole seconds"; } });
                }
                    addRenderableWidget(CycleButton.builder((String v) -> Component.literal(v.equals("party") ? "Party: shared progress & loot" : "Personal"), repeat.has("scope") ? repeat.get("scope").getAsString() : "personal").withValues("personal", "party").create(left, rulesY(4), size, 20, Component.literal("Cycle"), (b, value) -> { repeat.addProperty("scope", value); rebuildWidgets(); })).setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Personal: your own progress and rewards. Party: members at acceptance share progress and one reward set. New members join the next cycle.")));
                    if (net.vg.codex.advancement.QuestCycles.shared(draft)) addRenderableWidget(CycleButton.builder((String v)->Component.literal(v.equals("each")?"Each participant":"One group set"),repeat.has("rewards")?repeat.get("rewards").getAsString():"group").withValues("group","each").create(left,rulesY(5),size,20,Component.literal("Rewards"),(b,v)->{repeat.addProperty("rewards",v);rebuildWidgets();})).setTooltip(Tooltip.create(Component.literal("Applies at next acceptance. Each participant claims once. Offline members keep their share; leaving forfeits an unclaimed share. All remaining participants must claim before the next cycle.")));
                }
            }
            case "Effects" -> {
                JsonObject c=draft.has("celebration")?draft.getAsJsonObject("celebration"):new JsonObject();draft.add("celebration",c);
                addRenderableWidget(CycleButton.onOffBuilder(QuestDefinitions.flag(c,"enabled")).create(left,64,size,20,Component.literal("Chapter celebration"),(b,v)->c.addProperty("enabled",v)));
                EditBox heading=addRenderableWidget(new EditBox(font,left,108,size,20,Component.literal("Celebration heading")));heading.setMaxLength(80);heading.setValue(c.has("title")?c.get("title").getAsString():"");heading.setHint(Component.literal("Chapter complete"));heading.setResponder(v->c.addProperty("title",v));
                addRenderableWidget(Button.builder(Component.literal(c.has("sound")?"Sound: "+ClientRewardPresentation.pretty(Identifier.parse(c.get("sound").getAsString())):"Choose Sound…"),b->minecraft.setScreen(new IdentifierSelectionScreen(this,"Milestone Sound",net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.keySet(),id->Component.literal(ClientRewardPresentation.pretty(id)),id->c.addProperty("sound",id.toString())))).bounds(left,138,size-64,20).build());
                addRenderableWidget(Button.builder(Component.literal("Quiet"),b->{c.remove("sound");rebuildWidgets();}).bounds(left+size-60,138,60,20).build());
                addRenderableWidget(Button.builder(Component.literal("Preview Celebration"),b->ChapterFeedback.show(currentQuest,questName(currentQuest),c)).bounds(left,168,size,20).build());
            }
            case "Choices" -> {
                JsonArray list = choices();
                int rows = Math.max(1, (height - 176) / 24); choicePage = Math.min(choicePage, Math.max(0, (list.size() - 1) / rows));
                for (int i = choicePage * rows; i < Math.min((choicePage + 1) * rows, list.size()); i++) { int index = i; JsonObject c = list.get(i).getAsJsonObject();
                    addRenderableWidget(Button.builder(Component.literal(c.get("label").getAsString()), b -> editChoice(index)).bounds(left, 64 + (i % rows) * 24, size, 20).build());
                }
                if (list.size() > rows) {
                    addRenderableWidget(Button.builder(Component.literal("< Previous"), b -> { choicePage--; rebuildWidgets(); }).bounds(left, height - 102, size / 2 - 3, 20).build()).active = choicePage > 0;
                    addRenderableWidget(Button.builder(Component.literal("Next >"), b -> { choicePage++; rebuildWidgets(); }).bounds(left + size / 2 + 3, height - 102, size / 2 - 3, 20).build()).active = (choicePage + 1) * rows < list.size();
                }
                addRenderableWidget(Button.builder(Component.literal("Add Choice"), b -> editChoice(-1)).bounds(left, height - 76, size, 20).build()).active = list.size() < 8;
            }
            case "Branch" -> {
                JsonObject gate = draft.has("requires_choice") ? draft.getAsJsonObject("requires_choice") : new JsonObject();
                addRenderableWidget(Button.builder(Component.literal(gate.has("quest") ? "Story: " + questName(Identifier.parse(gate.get("quest").getAsString())) : "Select Story Quest…"), b -> {
                    List<Identifier> ids = definitions.entrySet().stream().filter(e -> e.getValue().has("codex_quest") && e.getValue().getAsJsonObject("codex_quest").has("dialogue") && e.getValue().getAsJsonObject("codex_quest").getAsJsonObject("dialogue").has("choices")).map(Map.Entry::getKey).toList();
                    minecraft.setScreen(new IdentifierSelectionScreen(this, "Story Quest", ids, i -> Component.literal(questName(i)), id -> { JsonObject g = new JsonObject(); g.addProperty("quest", id.toString()); draft.add("requires_choice", g); branchPage=0; }));
                }).bounds(left, 64, size, 20).build());
                if (gate.has("quest")) {
                    JsonObject source = definitions.get(Identifier.parse(gate.get("quest").getAsString()));
                    if (source != null && source.has("codex_quest")) {
                        JsonObject d = source.getAsJsonObject("codex_quest").getAsJsonObject("dialogue");
                        if(d!=null&&d.has("choices")){
                            JsonArray choices=d.getAsJsonArray("choices");int rows=Math.max(1,(height-202)/24);branchPage=Math.min(branchPage,Math.max(0,(choices.size()-1)/rows));
                            for(int i=branchPage*rows;i<Math.min((branchPage+1)*rows,choices.size());i++){
                                JsonObject c=choices.get(i).getAsJsonObject();addRenderableWidget(Button.builder(Component.literal((gate.has("choice")&&gate.get("choice").equals(c.get("id"))?"✓ ":"")+c.get("label").getAsString()),b->{gate.add("choice",c.get("id"));error="";rebuildWidgets();}).bounds(left,94+(i%rows)*24,size,20).build());
                            }
                            if(choices.size()>rows){addRenderableWidget(Button.builder(Component.literal("< Previous"),b->{branchPage--;rebuildWidgets();}).bounds(left,height-102,size/2-3,20).build()).active=branchPage>0;addRenderableWidget(Button.builder(Component.literal("Next >"),b->{branchPage++;rebuildWidgets();}).bounds(left+size/2+3,height-102,size/2-3,20).build()).active=(branchPage+1)*rows<choices.size();}
                        }
                    }
                }
                addRenderableWidget(Button.builder(Component.literal("Clear Branch Requirement"), b -> { draft.remove("requires_choice"); rebuildWidgets(); }).bounds(left, height - 76, size, 20).build());
            }
        }
        addRenderableWidget(Button.builder(Component.literal("Apply to Draft"), b -> {
            try { if (invalidDuration) throw new IllegalArgumentException("Enter whole seconds"); QuestDefinitions.validate(draft); apply.accept(draft.deepCopy()); minecraft.setScreen(parent); }
            catch (RuntimeException invalid) { error = invalid.getMessage(); }
        }).bounds(left, height - 32, size / 2 - 3, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(left + size / 2 + 3, height - 32, size / 2 - 3, 20).build());
    }
    private int rulesY(int row){return (height<280?60:64)+row*(height<280?24:26);}
    private String questName(Identifier id) {
        JsonObject q = definitions.get(id); if (q == null || !q.has("display")) return id.getPath();
        JsonElement title = q.getAsJsonObject("display").get("title");
        if (title == null) return id.getPath();
        var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, minecraft.level.registryAccess());
        return net.minecraft.network.chat.ComponentSerialization.CODEC.parse(ops, title).result().map(Component::getString).orElse(id.getPath());
    }
    private void editChoice(int index) {
        JsonArray list = choices(); JsonObject c;
        if (index < 0) { c = new JsonObject(); c.addProperty("id", UUID.randomUUID().toString()); c.addProperty("label", "New Choice"); c.addProperty("text", ""); }
        else c = list.get(index).getAsJsonObject().deepCopy();
        minecraft.setScreen(new ChoiceEditor(this, c, updated -> { if (index < 0) list.add(updated); else list.set(index, updated); }, index < 0 ? null : () -> list.remove(index)));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0xED202020); g.text(font, height<280&&!error.isEmpty()?Component.literal(font.plainSubstrByWidth(error,size)):title, left, 14, height<280&&!error.isEmpty()?0xFFFF8888:0xFFFFFFFF, false);
        if(tab.equals("Effects")) g.text(font,"Banner heading (optional)",left,94,0xFFCCCCAA,false);
        if (tab.equals("Tab")) {
            String[] help = {"Select the root quest to change this tab's appearance.", "Darker gray marks a quest tab. Gold means finished.", "Icons pulse for unclaimed rewards or required quest actions."};
            for (int i=0; i<help.length; i++) g.text(font, font.plainSubstrByWidth(help[i], size), left, 98+i*14, 0xFFCCCCAA, false);
        }
        if (tab.equals("Rules")) {
            JsonObject r = draft.getAsJsonObject("repeat");
            if (QuestDefinitions.flag(r, "enabled") && (!r.has("schedule") || !r.get("schedule").getAsString().equals("daily_utc"))) g.text(font, "Duration (seconds)", left, rulesY(3)+6, 0xFFCCCCCC, false);
            String[] help = !QuestDefinitions.flag(r, "enabled") ? new String[]{"Enable Repeatable to configure schedules and party cycles.", "Acceptance also works with ordinary one-time advancements."} : net.vg.codex.advancement.QuestCycles.shared(draft)
                ? new String[]{"Party members at acceptance share this cycle.", r.has("rewards") && r.get("rewards").getAsString().equals("each") ? "Each participant has their own once-per-cycle claim." : "One reward set for the group; any participant may claim.", "New members join the next cycle. Offline progress is retained."}
                : new String[]{"Each player has their own cycle and rewards.", "Delivery and statistic tasks; use claimable rewards.", "Schedules unlock the next cycle; unfinished rewards remain."};
            int helpY = QuestDefinitions.flag(r, "enabled") ? (net.vg.codex.advancement.QuestCycles.shared(draft)?rulesY(5)+28:rulesY(4)+32) : 124;
            for (int i = 0; i < help.length && helpY + i * 13 + font.lineHeight <= height - 38; i++) g.text(font, font.plainSubstrByWidth(help[i], size), left, helpY + i * 13, 0xFFCCCCAA, false);
        }
        super.extractRenderState(g, x, y, delta);
        QuestFeedback.render(g);
        if (height>=280&&!error.isEmpty()) g.text(font, font.plainSubstrByWidth(error, size), left, height - 48, 0xFFFF8888, false);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    private final class ChoiceEditor extends CodexScreen {
        private final Screen parent;
        private final JsonObject choice; private final Consumer<JsonObject> apply; private final Runnable remove;
        private int left, size;
        ChoiceEditor(Screen parent, JsonObject c, Consumer<JsonObject> apply, Runnable remove) { super(Component.literal("Edit Story Choice")); this.parent = parent; this.choice = c; this.apply = apply; this.remove = remove; }
        @Override protected void init() {
            size = Math.min(500, width - 24); left = (width - size) / 2;
            EditBox label = addRenderableWidget(new EditBox(font, left, 46, size, 20, Component.literal("Button label"))); label.setMaxLength(80); label.setValue(choice.get("label").getAsString()); label.setResponder(v -> choice.addProperty("label", v));
            addRenderableWidget(CycleButton.builder((String v) -> Component.literal(switch(v) { case "intro" -> "Before acceptance"; case "progress" -> "While in progress"; case "complete" -> "After completion"; default -> "Any time"; }), choice.has("stage") ? choice.get("stage").getAsString() : "any").withValues("any", "intro", "progress", "complete").create(left, 72, size, 20, Component.literal("Offer choice"), (b, v) -> choice.addProperty("stage", v)));
            addRenderableWidget(Button.builder(Component.literal(choice.has("next_quest") ? "Next chapter: " + questName(Identifier.parse(choice.get("next_quest").getAsString())) : "Link Next Chapter…"), b -> minecraft.setScreen(new IdentifierSelectionScreen(this, "Next Chapter", definitions.entrySet().stream().filter(e -> e.getValue().has("display") && e.getValue().get("display").isJsonObject()).map(Map.Entry::getKey).toList(), i -> Component.literal(questName(i)), id -> { choice.addProperty("next_quest", id.toString()); choice.addProperty("gate_chapter", true); }))).bounds(left, 98, size - 66, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Clear"), b -> { choice.remove("next_quest"); choice.remove("gate_chapter"); rebuildWidgets(); }).bounds(left + size - 60, 98, 60, 20).build());
            boolean gated = QuestDefinitions.flag(choice, "gate_chapter");
            if (!choice.has("gate_chapter") && choice.has("next_quest")) {
                JsonObject destination = definitions.get(Identifier.parse(choice.get("next_quest").getAsString()));
                if (destination != null && destination.has("codex_quest") && destination.getAsJsonObject("codex_quest").has("requires_choice")) {
                    JsonObject gate = destination.getAsJsonObject("codex_quest").getAsJsonObject("requires_choice");
                    gated = currentQuest.toString().equals(gate.get("quest").getAsString()) && choice.get("id").equals(gate.get("choice"));
                }
            }
            addRenderableWidget(CycleButton.onOffBuilder(gated).create(left, 124, size, 20, Component.literal("Chapter requires this choice"), (b, value) -> choice.addProperty("gate_chapter", value)))
                .setTooltip(Tooltip.create(Component.literal("On: the linked chapter is locked until this choice is saved. Apply to Draft updates both definitions. Off: chapter link only.")));
            addRenderableWidget(Button.builder(Component.literal("Show Choice When…"), b -> minecraft.setScreen(new DialogueConditionsScreen(this, definitions, choice.has("when") ? choice.getAsJsonArray("when") : new JsonArray(), conditions -> { if (conditions.isEmpty()) choice.remove("when"); else choice.add("when", conditions); }))).bounds(left, 150, size, 20).build());
            if(height<290) addRenderableWidget(Button.builder(Component.literal("Edit Outcome Text…"),b->minecraft.setScreen(new OutcomeEditor(this))).bounds(left,178,size,20).build());
            else {
                MultiLineEditBox text = addRenderableWidget(MultiLineEditBox.builder().setX(left).setY(194).build(font, size, height-244, Component.literal("Outcome text"))); text.setCharacterLimit(6000); text.setValue(choice.has("text") ? choice.get("text").getAsString() : ""); text.setValueListener(v -> choice.addProperty("text", v));
            }
            int columns = remove == null ? 2 : 3, w = (size - (columns - 1) * 6) / columns;
            addRenderableWidget(Button.builder(Component.literal("Save Choice"), b -> { if (choice.get("label").getAsString().isBlank()) return; apply.accept(choice.deepCopy()); minecraft.setScreen(parent); }).bounds(left, height - 32, w, 20).build());
            if (remove != null) addRenderableWidget(Button.builder(Component.literal("Remove"), b -> { remove.run(); minecraft.setScreen(parent); }).bounds(left + w + 6, height - 32, w, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(left + (w + 6) * (columns - 1), height - 32, w, 20).build());
        }
        @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
            g.fill(0, 0, width, height, 0xED202020); g.text(font, title, left, 14, 0xFFFFFFFF, false); g.text(font, "Choice label", left, 33, 0xFFCCCCAA, false); if(height>=290)g.text(font, "Outcome text (shown after choosing)", left, 180, 0xFFCCCCAA, false); super.extractRenderState(g, x, y, delta);
        }
        private final class OutcomeEditor extends CodexScreen {
            private final Screen parent;private String text=choice.has("text")?choice.get("text").getAsString():"";
            OutcomeEditor(Screen parent){super(Component.literal("Choice Outcome Text"));this.parent=parent;}
            @Override protected void init(){int size=Math.min(500,width-24),left=(width-size)/2;MultiLineEditBox editor=addRenderableWidget(MultiLineEditBox.builder().setX(left).setY(44).build(font,size,height-92,Component.literal("Outcome text")));editor.setCharacterLimit(6000);editor.setValue(text);editor.setValueListener(v->text=v);addRenderableWidget(Button.builder(Component.literal("Apply Text"),b->{choice.addProperty("text",text);minecraft.setScreen(parent);}).bounds(left,height-32,size/2-3,20).build());addRenderableWidget(Button.builder(Component.literal("Cancel"),b->minecraft.setScreen(parent)).bounds(left+size/2+3,height-32,size/2-3,20).build());}
            @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float dt){g.fill(0,0,width,height,0xED202020);g.text(font,title,(width-Math.min(500,width-24))/2,14,-1,false);super.extractRenderState(g,x,y,dt);}
            @Override public void onClose(){minecraft.setScreen(parent);}
        }
        @Override public void onClose() { minecraft.setScreen(parent); }
    }
}
