package net.vg.codex.client.advancements;

import net.minecraft.resources.Identifier;

import java.util.Map;

/** Latest shared layout received from the server, used by the normal advancement view. */
public final class ClientAdvancementLayout {
    private static Map<Identifier, int[]> positions = Map.of();

    private ClientAdvancementLayout() {
    }

    public static void set(Map<Identifier, int[]> newPositions) {
        positions = Map.copyOf(newPositions);
    }

    public static Map<Identifier, int[]> positions() {
        return positions;
    }
}
