package net.vg.codex.client.advancements;

import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;

/**
 * Client-side cache of the last {@link net.vg.codex.network.AdvancementRequiredItemsPayload}
 * received - view mode's only source for the hover box's "Required" hint row (edit mode instead
 * reads the currently-edited criteria JSON directly, see
 * {@link CodexAdvancementWidget#applyRequiredItemsOverride}).
 */
public final class ClientAdvancementRequiredItems {
    private static Map<Identifier, List<Identifier>> required = Map.of();
    private static Map<Identifier, List<Hint>> hints = Map.of();

    private ClientAdvancementRequiredItems() {
    }

    public static void set(Map<Identifier, List<Identifier>> newRequired) {
        required = newRequired;
    }

    public static void setHints(Map<Identifier, List<Hint>> newHints) {
        hints = newHints;
    }

    public static List<Identifier> forAdvancement(Identifier advancementId) {
        return required.getOrDefault(advancementId, List.of());
    }

    public static List<Hint> hintsForAdvancement(Identifier advancementId) {
        return hints.getOrDefault(advancementId, List.of());
    }

    public record Hint(Identifier iconItemId, Component tooltip) { }
}
