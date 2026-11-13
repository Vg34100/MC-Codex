package net.vg.codex.client.advancements;

import com.google.gson.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Advanced escape hatch with a private draft and explicit Apply/Cancel. */
public final class JsonDocumentScreen extends CodexScreen {
    private final Screen parent;
    private final Consumer<JsonObject> apply;
    private String draft;
    private String error = "";

    public JsonDocumentScreen(Screen parent, JsonObject document, Consumer<JsonObject> apply) {
        super(Component.literal("Advanced Advancement JSON")); this.parent = parent; this.apply = apply;
        draft = new GsonBuilder().setPrettyPrinting().create().toJson(document);
    }

    @Override protected void init() {
        MultiLineEditBox editor = addRenderableWidget(MultiLineEditBox.builder().setX(16).setY(34)
                .build(font, width - 32, height - 100, Component.literal("Advancement JSON")));
        editor.setValue(draft); editor.setValueListener(value -> draft = value);
        addRenderableWidget(Button.builder(Component.literal("Apply JSON"), b -> {
            try { apply.accept(JsonParser.parseString(draft).getAsJsonObject()); minecraft.setScreen(parent); }
            catch (RuntimeException invalid) { error = invalid.getMessage(); }
        }).bounds(16, height - 56, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(132, height - 56, 90, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xED202020);
        graphics.text(font, title, 16, 14, 0xFFFFFFFF, false);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.text(font, error.isEmpty() ? "Advanced fields are preserved. Save Changes sends the applied draft to the server." : error, 16, height - 28, error.isEmpty() ? 0xFFBBBBBB : 0xFFFF7777, false);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
