package net.vg.codex.client.advancements;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vg.codex.network.StatisticProgressPayload;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-advancement formatted statistic counters, supplied by the authoritative server. */
public final class ClientStatisticProgress {
    private static Map<Identifier, Component> byAdvancement = Map.of();
    private ClientStatisticProgress() { }
    public static void set(java.util.List<StatisticProgressPayload.Entry> entries) {
        Map<Identifier, StringBuilder> text = new LinkedHashMap<>();
        for (var entry : entries) {
            StringBuilder line = text.computeIfAbsent(entry.advancementId(), ignored -> new StringBuilder());
            if (!line.isEmpty()) line.append("  •  ");
            line.append(entry.current()).append(" / ").append(entry.target());
        }
        Map<Identifier, Component> result = new LinkedHashMap<>();
        text.forEach((id, value) -> result.put(id, Component.literal(value.toString())));
        byAdvancement = Map.copyOf(result);
    }
    public static Component forAdvancement(Identifier id) { return byAdvancement.get(id); }
}
