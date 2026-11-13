package net.vg.codex.client.advancements;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.*;
//? if <1.21 {
/*import net.vg.codex.compat.v120.LegacyComponents;
*///?}
import net.minecraft.resources.*;
import net.vg.codex.advancement.*;
import java.util.*;
import java.util.function.Consumer;

/** Creator tools operate on a snapshot; preview never sends gameplay actions to the server. */
public final class PackToolsScreen extends CodexScreen {
    private final Screen parent;
    private final Map<Identifier, JsonObject> definitions;
    private final Consumer<Map<Identifier, JsonObject>> apply;
    private final Consumer<Identifier> jump;
    private final Map<Identifier, String> choices = new HashMap<>();
    private Identifier selected;
    private String tab = "Problems", error = "";
    private String migrationKind="Quest identifier", migrationCriterion="", migrationTarget="", migrationStatus="";
    private String target = "", state = "Fresh player";
    private boolean descendants = true;
    private String acceptanceRule = "Keep current", claimRule = "Keep current";
    private int left, size, page;
    public PackToolsScreen(Screen parent, Map<Identifier, JsonObject> source, Identifier selected,
            Consumer<Map<Identifier, JsonObject>> apply, Consumer<Identifier> jump) {
        super(Component.literal("Pack Creator Tools")); this.parent = parent; this.selected = selected; this.apply = apply; this.jump = jump;
        definitions = new LinkedHashMap<>(); source.forEach((id, d) -> definitions.put(id, d.deepCopy()));
        if (selected != null) target = selected.getNamespace() + ":" + selected.getPath() + "_copy";
    }
    private String name(Identifier id) {
        JsonObject d = definitions.get(id);
        try { return ComponentSerialization.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess()), d.getAsJsonObject("display").get("title")).getOrThrow().getString(); }
        catch (RuntimeException ignored) { return id.toString(); }
    }
    @Override protected void init() {
        size = Math.min(560, width - 24); left = (width - size) / 2;
        String[] tabs = {"Problems", "Preview", "Duplicate", "Bulk Rules", "Migrate"};
        for (int i = 0; i < tabs.length; i++) { String t = tabs[i]; addRenderableWidget(Button.builder(Component.literal(t), b -> { tab = t; page = 0; error = ""; rebuildWidgets(); }).bounds(left + i * size / tabs.length, 34, size / tabs.length - 3, 20).build()).active = !tab.equals(t); }
        if (tab.equals("Problems")) { problems(); }
        else {
            addRenderableWidget(Button.builder(Component.literal(selected == null ? "Select a quest…" : name(selected)), b -> minecraft.setScreen(new IdentifierSelectionScreen(this, "Quest", definitions.keySet(), id -> Component.literal(name(id)), id -> { selected = id; target = id.getNamespace() + ":" + id.getPath() + "_copy"; migrationTarget="";migrationCriterion="";migrationStatus=""; }))).bounds(left, 62, size, 20).build());
            if (selected != null) switch (tab) { case "Preview" -> preview(); case "Duplicate" -> duplicate(); case "Migrate" -> migrate(); default -> bulk(); }
        }
        addRenderableWidget(Button.builder(Component.literal("Back to Editor"), b -> onClose()).bounds(left, height - 32, size, 20).build());
    }
    private void problems() {
        Set<Identifier> tables = ClientTreasureLists.lootTables().isEmpty() ? null : new HashSet<>(ClientTreasureLists.lootTables());
        List<PackAuthoring.Issue> issues = PackAuthoring.diagnose(definitions, tables);
        int rows = Math.max(1, (height - 150) / 32); page = Math.min(page, Math.max(0, (issues.size() - 1) / rows));
        for (int i = page * rows; i < Math.min(issues.size(), (page + 1) * rows); i++) {
            PackAuthoring.Issue issue = issues.get(i);
            Button b = addRenderableWidget(Button.builder(Component.literal((issue.error() ? "! " : "• ") + name(issue.quest()) + ": " + issue.message()), button -> { jump.accept(issue.quest()); minecraft.setScreen(parent); }).bounds(left, 86 + i % rows * 32, size, 28).build());
            b.setTooltip(Tooltip.create(Component.literal(issue.quest() + "\n" + issue.message() + "\nClick to edit this quest")));
        }
        addRenderableWidget(new MultiLineTextWidget(left, 62, Component.literal(issues.isEmpty() ? "No definition/link problems found. Gameplay checks are separate." : issues.size() + " issues • click an entry to edit"), font).setMaxWidth(size));
        addRenderableWidget(Button.builder(Component.literal("< Previous"), b -> { page--; rebuildWidgets(); }).bounds(left, height - 60, size / 2 - 3, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal("Next >"), b -> { page++; rebuildWidgets(); }).bounds(left + size / 2 + 3, height - 60, size / 2 - 3, 20).build()).active = (page + 1) * rows < issues.size();
    }
    private void preview() {
        addRenderableWidget(CycleButton.builder((String s) -> Component.literal(s), state).withValues("Fresh player", "Accepted", "Completed", "Rewards claimed").create(left, 88, size, 20, Component.literal("Preview state"), (b, s) -> { state = s; rebuildWidgets(); }));
        JsonObject d = definitions.get(selected), q = d.has("codex_quest") ? d.getAsJsonObject("codex_quest") : new JsonObject();
        List<String> keys = new ArrayList<>(); keys.add(""); for (JsonElement c : PackAuthoring.choices(d)) keys.add(c.getAsJsonObject().get("id").getAsString());
        if (keys.size() > 1) addRenderableWidget(CycleButton.builder((String key) -> {
            for (JsonElement c : PackAuthoring.choices(d)) if (c.getAsJsonObject().get("id").getAsString().equals(key)) return Component.literal(c.getAsJsonObject().get("label").getAsString());
            return Component.literal("None");
        }, choices.getOrDefault(selected, "")).withValues(keys).create(left, 114, size, 20, Component.literal("Simulate choice"), (b, key) -> { choices.put(selected, key); rebuildWidgets(); }));
        StringBuilder text = new StringBuilder("LOCAL PREVIEW • no progress or rewards are changed.\n\n");
        if (q.has("requires_choice")) {
            JsonObject gate = q.getAsJsonObject("requires_choice"); Identifier source = Identifier.parse(gate.get("quest").getAsString());
            boolean unlocked = gate.get("choice").getAsString().equals(choices.get(source));
            text.append(unlocked ? "UNLOCKED" : "LOCKED").append(" • requires a choice in ").append(name(source)).append("\n\n");
        }
        if (QuestDefinitions.flag(q, "acceptance")) text.append(state.equals("Fresh player") ? "Acceptance required\n" : "Quest accepted\n");
        if (QuestDefinitions.repeat(q)) text.append("Repeatable • ").append(q.getAsJsonObject("repeat").has("schedule") ? q.getAsJsonObject("repeat").get("schedule").getAsString() : "cooldown").append("\n");
        try { text.append(ComponentSerialization.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess()), d.getAsJsonObject("display").get("description")).getOrThrow().getString()).append("\n\n"); } catch (RuntimeException ignored) { }
        if (q.has("dialogue")) {
            JsonObject dialogue = q.getAsJsonObject("dialogue"); String phase = state.equals("Fresh player") ? "intro" : state.equals("Accepted") ? "progress" : "complete";
            if (dialogue.has(phase)) text.append(dialogue.get(phase).getAsString()).append("\n\n");
            for (JsonElement c : PackAuthoring.choices(d)) { JsonObject choice = c.getAsJsonObject(); if (choice.get("id").getAsString().equals(choices.get(selected)) && choice.has("text")) text.append("Chosen outcome:\n").append(choice.get("text").getAsString()).append("\n\n"); }
        }
        if (d.has("criteria")) for (var c : d.getAsJsonObject("criteria").entrySet()) text.append(state.equals("Completed") || state.equals("Rewards claimed") ? "✓ " : "○ ").append(c.getKey()).append("\n");
        if (d.has("rewards")) {
            text.append("\n").append(state.equals("Rewards claimed") ? "Rewards claimed" : QuestDefinitions.flag(d, "codex_claimable") ? "Manual claim rewards" : "Automatic rewards").append(":\n");
            for (JsonObject reward : QuestRewards.entries(d.getAsJsonObject("rewards"), selected)) text.append("• ").append(ClientRewardPresentation.label(reward)).append("\n");
        }
        addRenderableWidget(new FittingMultiLineTextWidget(left, 140, size, Math.max(30, height - 182), Component.literal(text.toString()), font));
    }
    private void duplicate() {
        EditBox id = addRenderableWidget(new EditBox(font, left, 102, size, 20, Component.literal("New root identifier"))); id.setMaxLength(256); id.setValue(target); id.setResponder(v -> target = v);
        addRenderableWidget(CycleButton.onOffBuilder(descendants).create(left, 130, size, 20, Component.literal("Include child quests"), (b, v) -> { descendants = v; rebuildWidgets(); }));
        addRenderableWidget(new FittingMultiLineTextWidget(left, 160, size, Math.max(12,height-226), Component.literal("Copies " + PackAuthoring.subtree(definitions, selected, descendants).size() + " definitions. Internal parents, story links and branch rules follow the copies. External links remain. No player progress is copied."), font));
        addRenderableWidget(Button.builder(Component.literal("Create Editable Copies"), b -> {
            try { Map<Identifier, JsonObject> copies = PackAuthoring.duplicate(definitions, selected, Identifier.parse(target), descendants); apply.accept(copies); jump.accept(Identifier.parse(target)); minecraft.setScreen(parent); }
            catch (RuntimeException e) { error = e.getMessage(); }
        }).bounds(left, height - 60, size, 20).build());
    }
    private void bulk() {
        addRenderableWidget(CycleButton.onOffBuilder(descendants).create(left, 90, size, 20, Component.literal("Include child quests"), (b, v) -> { descendants = v; rebuildWidgets(); }));
        addRenderableWidget(CycleButton.builder((String v) -> Component.literal(v), acceptanceRule).withValues("Keep current", "On", "Off").create(left, 116, size, 20, Component.literal("Require acceptance"), (b, v) -> acceptanceRule = v));
        addRenderableWidget(CycleButton.builder((String v) -> Component.literal(v), claimRule).withValues("Keep current", "On", "Off").create(left, 142, size, 20, Component.literal("Claim rewards manually"), (b, v) -> claimRule = v));
        if(height>=300)addRenderableWidget(new MultiLineTextWidget(left, 174, Component.literal("Apply these two rules to " + PackAuthoring.subtree(definitions, selected, descendants).size() + " quests. Other data stays intact. Changes are drafts until Save; Undo Apply works on each changed quest."), font).setMaxWidth(size));
        addRenderableWidget(Button.builder(Component.literal("Apply Rules to Drafts"), b -> {
            try {
                Map<Identifier, JsonObject> changes = new LinkedHashMap<>();
                for (Identifier id : PackAuthoring.subtree(definitions, selected, descendants)) {
                    JsonObject d = definitions.get(id).deepCopy(), q = d.has("codex_quest") ? d.getAsJsonObject("codex_quest") : new JsonObject();
                    if (!acceptanceRule.equals("Keep current")) { q.addProperty("acceptance", acceptanceRule.equals("On")); d.add("codex_quest", q); } if (!claimRule.equals("Keep current")) d.addProperty("codex_claimable", claimRule.equals("On")); QuestDefinitions.validateAdvancement(q, d); changes.put(id, d);
                }
                apply.accept(changes); minecraft.setScreen(parent);
            } catch (RuntimeException e) { error = e.getMessage(); }
        }).bounds(left, height - 60, size, 20).build());
    }
    private void migrate() {
        JsonObject source=definitions.get(selected), meta=source.has("codex_migration")?source.getAsJsonObject("codex_migration"):new JsonObject();
        List<String> criteria=new ArrayList<>(source.getAsJsonObject("criteria").keySet());
        if(migrationCriterion.isEmpty()||!criteria.contains(migrationCriterion))migrationCriterion=criteria.get(0);
        if(migrationTarget.isEmpty())migrationTarget=migrationKind.equals("Criterion key")?migrationCriterion+"_renamed":selected.getNamespace()+":"+selected.getPath()+"_renamed";
        addRenderableWidget(CycleButton.builder((String v)->Component.literal(v),migrationKind).withValues("Quest identifier","Criterion key").create(left,height<280?88:90,size,20,Component.literal("Rename"),(b,v)->{migrationKind=v;migrationTarget=v.equals("Criterion key")?migrationCriterion+"_renamed":selected.getNamespace()+":"+selected.getPath()+"_renamed";rebuildWidgets();}));
        if(migrationKind.equals("Criterion key"))addRenderableWidget(CycleButton.builder((String v)->Component.literal(v),migrationCriterion).withValues(criteria).create(left,height<280?112:116,size,20,Component.literal("Current key"),(b,v)->{migrationCriterion=v;migrationTarget=v+"_renamed";rebuildWidgets();}));
        EditBox target=addRenderableWidget(new EditBox(font,left,height<280?146:158,size,20,Component.literal("New identifier or key")));target.setMaxLength(256);target.setValue(migrationTarget);target.setResponder(v->migrationTarget=v);
        String help="Migrate the last saved definition and progress in closed worlds. Save editor changes before export. The included tool previews files, checks world locks and creates backups.";
        boolean available=meta.has("source_hash")&&!meta.get("source_hash").getAsString().equals("-")&&!meta.get("has_fallback").getAsBoolean();
        if(!available)help="Native/mod/datapack keys stay stable. Use Duplicate for a new quest. For an unsaved custom quest, Save it first and reopen these tools.";
        if(height>=310)addRenderableWidget(new FittingMultiLineTextWidget(left,190,size,height-276,Component.literal(help),font));
        addRenderableWidget(Button.builder(Component.literal(migrationStatus.isEmpty()?"Export Migration Plan":"Plan Exported (Export Again)"),b->{
            try {
                JsonObject plan=meta.deepCopy();plan.addProperty("id",java.util.UUID.randomUUID().toString());plan.addProperty("source",selected.toString());
                if(migrationKind.equals("Quest identifier")){Identifier id=Identifier.parse(migrationTarget);if(id.equals(selected)||definitions.containsKey(id))throw new IllegalArgumentException("Choose an unused destination identifier");plan.addProperty("target",id.toString());}
                else {if(migrationTarget.isBlank()||criteria.contains(migrationTarget))throw new IllegalArgumentException("Choose a new criterion key");plan.addProperty("target",selected.toString());plan.addProperty("criterion_from",migrationCriterion);plan.addProperty("criterion_to",migrationTarget);}
                java.nio.file.Path directory=minecraft.gameDirectory.toPath().resolve("codex/migration-plans");String file=selected.getNamespace()+"_"+selected.getPath().replace('/','_')+"_"+plan.get("id").getAsString()+".json";
                net.vg.codex.advancement.AtomicJson.write(directory.resolve(file),plan);
                try(var input=PackToolsScreen.class.getResourceAsStream("/tools/codex/migrate-advancement.py")){if(input!=null){java.nio.file.Path tool=directory.getParent().resolve("tools/migrate-advancement.py");java.nio.file.Files.createDirectories(tool.getParent());java.nio.file.Files.write(tool,input.readAllBytes());}}
                migrationStatus="Exported to codex/migration-plans/"+file;error="";rebuildWidgets();
            }catch(Exception invalid){error=invalid.getMessage();}
        }).bounds(left,height-60,size,20).build()).setTooltip(Tooltip.create(Component.literal(help+"\n"+migrationStatus)));
        for(var child:children())if(child instanceof Button b&&b.getMessage().getString().contains("Migration Plan"))b.active=available;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float dt) {
        g.fill(0, 0, width, height, 0xED202020); g.text(font, title, left, 14, -1, false); super.extractRenderState(g, x, y, dt);
        if(tab.equals("Migrate")){g.text(font,"New identifier / key",left,height<280?134:144,0xFFCCCCAA,false);if(height>=310&&!migrationStatus.isEmpty())g.text(font,font.plainSubstrByWidth(migrationStatus,size),left,height-92,0xFF8FE1B5,false);}
        if (!error.isEmpty()) g.text(font, font.plainSubstrByWidth(error, size), left, height - 74, 0xFFFF7777, false);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
