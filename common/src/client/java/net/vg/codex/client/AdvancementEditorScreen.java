package net.vg.codex.client;

import dev.architectury.networking.NetworkManager;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vg.codex.network.SaveAdvancementTreePayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal v1 editor: search/select an existing advancement (including vanilla ones) that has
 * display info, edit its title/description, and save. This exists to prove out the
 * command -> network -> screen -> network -> write/reload pipeline end to end before building the
 * full drag-and-connect node graph on top of it.
 *
 * <p>Only edited entries are ever sent back to the server - the full snapshot can include
 * thousands of advancements (including synthetic, non-file-backed ones like per-recipe unlock
 * advancements), and re-encoding every one of them on every save is both wasteful and unsafe.
 */
public final class AdvancementEditorScreen extends Screen {
    private static final int ROW_HEIGHT = 14;
    private static final int PANEL_WIDTH = 320;

    private final List<AdvancementHolder> current;
    private final List<AdvancementHolder> matches = new ArrayList<>();
    private final List<Button> resultButtons = new ArrayList<>();
    private final Map<Identifier, AdvancementHolder> dirty = new LinkedHashMap<>();

    private int maxResults;
    private EditBox searchBox;
    private EditBox titleBox;
    private EditBox descriptionBox;
    private StringWidget statusWidget;
    private AdvancementHolder selected;

    public AdvancementEditorScreen(List<AdvancementHolder> advancements) {
        super(Component.literal("Codex Advancement Editor"));
        this.current = new ArrayList<>(advancements);
    }

    @Override
    protected void init() {
        int left = Math.max(10, (this.width - PANEL_WIDTH) / 2);
        int panelWidth = Math.min(PANEL_WIDTH, this.width - 20);
        int top = 16;
        int searchHeight = 16;

        // Detail panel (title/description/buttons/status) is anchored to the bottom of the
        // screen so it's always visible regardless of screen height or GUI scale, instead of
        // being pushed off-screen by however many result rows happen to fit above it.
        int detailHeight = 16 + 4 + 16 + 4 + 20 + 4 + 10;
        int detailTop = this.height - detailHeight - 10;

        searchBox = addRenderableWidget(new EditBox(this.font, left, top, panelWidth, searchHeight, Component.literal("Search")));
        searchBox.setResponder(text -> refreshResults());

        int resultsTop = top + searchHeight + 6;
        int resultsBottom = detailTop - 6;
        maxResults = Math.max(1, (resultsBottom - resultsTop) / ROW_HEIGHT);

        resultButtons.clear();
        for (int i = 0; i < maxResults; i++) {
            int index = i;
            int y = resultsTop + i * ROW_HEIGHT;
            Button button = addRenderableWidget(Button.builder(Component.empty(), b -> selectMatch(index))
                    .bounds(left, y, panelWidth, ROW_HEIGHT - 2)
                    .build());
            button.visible = false;
            resultButtons.add(button);
        }

        titleBox = addRenderableWidget(new EditBox(this.font, left, detailTop, panelWidth, 16, Component.literal("Title")));
        descriptionBox = addRenderableWidget(new EditBox(this.font, left, detailTop + 20, panelWidth, 16, Component.literal("Description")));

        int buttonWidth = (panelWidth - 10) / 2;
        addRenderableWidget(Button.builder(Component.literal("Apply Edit"), b -> applyEdit())
                .bounds(left, detailTop + 40, buttonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(left + buttonWidth + 10, detailTop + 40, buttonWidth, 20)
                .build());

        statusWidget = addRenderableWidget(new StringWidget(left, detailTop + 64, panelWidth, 10, Component.empty(), this.font));

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
            if (matches.size() >= maxResults) {
                break;
            }
        }
        for (int i = 0; i < resultButtons.size(); i++) {
            Button button = resultButtons.get(i);
            if (i < matches.size()) {
                AdvancementHolder holder = matches.get(i);
                DisplayInfo display = holder.value().display().get();
                String prefix = dirty.containsKey(holder.id()) ? "* " : "";
                button.setMessage(Component.literal(prefix + holder.id() + " - " + display.getTitle().getString()));
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
        setStatus("");
    }

    private void applyEdit() {
        if (selected == null) {
            setStatus("Select an advancement first.");
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
                Optional.of(updated),
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
        dirty.put(updatedHolder.id(), updatedHolder);
        selected = updatedHolder;
        setStatus("Edit applied - not saved yet.");
        refreshResults();
    }

    private void save() {
        if (dirty.isEmpty()) {
            setStatus("Nothing to save - apply an edit first.");
            return;
        }
        NetworkManager.sendToServer(new SaveAdvancementTreePayload(List.copyOf(dirty.values())));
        setStatus("Sent " + dirty.size() + " edit(s) to the server.");
        dirty.clear();
        refreshResults();
    }

    private void setStatus(String message) {
        if (statusWidget != null) {
            statusWidget.setMessage(Component.literal(message));
        }
    }
}
