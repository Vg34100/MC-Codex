package net.vg.codex.client;

import dev.architectury.networking.NetworkManager;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.vg.codex.network.SaveAdvancementTreePayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal v1 editor: search/select an existing advancement (including vanilla ones) that has
 * display info, edit its title/description, and save. This exists to prove out the
 * command -> network -> screen -> network -> write/reload pipeline end to end before building the
 * full drag-and-connect node graph on top of it.
 */
public final class AdvancementEditorScreen extends Screen {
    private static final int ROW_HEIGHT = 14;
    private static final int MAX_RESULTS = 12;

    private final List<AdvancementHolder> current;
    private final List<AdvancementHolder> matches = new ArrayList<>();
    private final List<Button> resultButtons = new ArrayList<>();

    private EditBox searchBox;
    private EditBox titleBox;
    private EditBox descriptionBox;
    private AdvancementHolder selected;

    public AdvancementEditorScreen(List<AdvancementHolder> advancements) {
        super(Component.literal("Codex Advancement Editor"));
        this.current = new ArrayList<>(advancements);
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 150;
        int top = 32;

        searchBox = addRenderableWidget(new EditBox(this.font, left, top, 300, 18, Component.literal("Search")));
        searchBox.setResponder(text -> refreshResults());

        resultButtons.clear();
        for (int i = 0; i < MAX_RESULTS; i++) {
            int index = i;
            int y = top + 24 + i * ROW_HEIGHT;
            Button button = addRenderableWidget(Button.builder(Component.empty(), b -> selectMatch(index))
                    .bounds(left, y, 300, ROW_HEIGHT - 2)
                    .build());
            button.visible = false;
            resultButtons.add(button);
        }

        int detailTop = top + 24 + MAX_RESULTS * ROW_HEIGHT + 10;
        titleBox = addRenderableWidget(new EditBox(this.font, left, detailTop, 300, 18, Component.literal("Title")));
        descriptionBox = addRenderableWidget(new EditBox(this.font, left, detailTop + 22, 300, 18, Component.literal("Description")));

        addRenderableWidget(Button.builder(Component.literal("Apply Edit"), b -> applyEdit())
                .bounds(left, detailTop + 46, 145, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(left + 155, detailTop + 46, 145, 20)
                .build());

        refreshResults();
    }

    private void refreshResults() {
        String query = searchBox.getValue().toLowerCase(Locale.ROOT);
        matches.clear();
        for (AdvancementHolder holder : current) {
            if (holder.value().display().isEmpty()) {
                continue;
            }
            DisplayInfo display = holder.value().display().get();
            String title = display.getTitle().getString().toLowerCase(Locale.ROOT);
            String id = holder.id().toString();
            if (!query.isEmpty() && !title.contains(query) && !id.contains(query)) {
                continue;
            }
            matches.add(holder);
            if (matches.size() >= MAX_RESULTS) {
                break;
            }
        }
        for (int i = 0; i < resultButtons.size(); i++) {
            Button button = resultButtons.get(i);
            if (i < matches.size()) {
                AdvancementHolder holder = matches.get(i);
                DisplayInfo display = holder.value().display().get();
                button.setMessage(Component.literal(holder.id() + " - " + display.getTitle().getString()));
                button.visible = true;
                button.active = true;
            } else {
                button.visible = false;
                button.active = false;
            }
        }
    }

    private void selectMatch(int index) {
        if (index < 0 || index >= matches.size()) {
            return;
        }
        selected = matches.get(index);
        DisplayInfo display = selected.value().display().orElseThrow();
        titleBox.setValue(display.getTitle().getString());
        descriptionBox.setValue(display.getDescription().getString());
    }

    private void applyEdit() {
        if (selected == null) {
            return;
        }
        DisplayInfo old = selected.value().display().orElseThrow();
        DisplayInfo updated = new DisplayInfo(
                old.getIcon(),
                Component.literal(titleBox.getValue()),
                Component.literal(descriptionBox.getValue()),
                old.getBackground(),
                old.getType(),
                old.shouldShowToast(),
                old.shouldAnnounceChat(),
                old.isHidden()
        );
        Advancement oldAdvancement = selected.value();
        Advancement updatedAdvancement = new Advancement(
                oldAdvancement.parent(),
                java.util.Optional.of(updated),
                oldAdvancement.rewards(),
                oldAdvancement.criteria(),
                oldAdvancement.requirements(),
                oldAdvancement.sendsTelemetryEvent(),
                oldAdvancement.name()
        );
        AdvancementHolder updatedHolder = new AdvancementHolder(selected.id(), updatedAdvancement);
        int index = current.indexOf(selected);
        if (index >= 0) {
            current.set(index, updatedHolder);
        }
        selected = updatedHolder;
        refreshResults();
    }

    private void save() {
        NetworkManager.sendToServer(new SaveAdvancementTreePayload(List.copyOf(current)));
    }
}
