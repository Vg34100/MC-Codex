package net.vg.codex.client.advancements;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Optional speaker settings, independent of the text and the server's branch rules. */
final class DialoguePresentationScreen extends QuestEditorDialog {
    private final JsonObject draft;
    private final Consumer<JsonObject> apply;
    DialoguePresentationScreen(Screen parent, JsonObject dialogue, Consumer<JsonObject> apply) {
        super(parent, "Dialogue Presentation");
        draft = dialogue.deepCopy();
        this.apply = apply;
    }
    @Override protected void init() {
        super.init();
        EditBox speaker = addRenderableWidget(new EditBox(font, left + 12, top + 52, panelWidth - 24, 20, Component.literal("Speaker name")));
        speaker.setMaxLength(80);
        speaker.setValue(draft.has("speaker") ? draft.get("speaker").getAsString() : "");
        speaker.setHint(Component.literal("Use quest title"));
        speaker.setResponder(value -> { if (value.isBlank()) draft.remove("speaker"); else draft.addProperty("speaker", value); });
        addRenderableWidget(Button.builder(Component.literal("Choose Speaker Icon…"), b -> minecraft.setScreen(new ItemPickerScreen(this,
                selected -> draft.addProperty("speaker_icon", selected.toString())))).bounds(left + 12, top + 82, panelWidth - 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> { draft.remove("speaker_icon"); rebuildWidgets(); }).bounds(left + panelWidth - 80, top + 82, 68, 20).build());
        addRenderableWidget(CycleButton.onOffBuilder(!draft.has("animate") || draft.get("animate").getAsBoolean())
                .create(left + 12, top + 112, panelWidth - 24, 20, Component.literal("Text reveal"), (b, value) -> draft.addProperty("animate", value)));
        footer("Apply", () -> apply.accept(draft.deepCopy()), null);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        super.extractRenderState(g, x, y, delta);
        text(g, "Speaker name (optional)", 38, 0xFFCCCCAA);
        text(g, "Show Text reveals a whole passage immediately.", 144, 0xFFB6B9AD);
        if (draft.has("speaker_icon")) g.fakeItem(BuiltInRegistries.ITEM.getValue(Identifier.parse(draft.get("speaker_icon").getAsString())).getDefaultInstance(), left + panelWidth - 110, top + 84);
    }
}
