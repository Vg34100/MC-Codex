package net.vg.codex.client.advancements;

import net.minecraft.resources.Identifier;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClientClaimableRewards {
    private static Map<Identifier, Set<Integer>> available = Map.of();
    private ClientClaimableRewards() {}
    public static void set(Map<Identifier, Set<Integer>> rewards) { available = Map.copyOf(rewards); }
    public static boolean contains(Identifier id) { return available.containsKey(id) && !available.get(id).isEmpty(); }
    public static boolean contains(Identifier id, int rewardIndex) { return available.getOrDefault(id, Set.of()).contains(rewardIndex); }
    /** Stable, searchable snapshot for the rewards drawer. */
    public static List<Identifier> available() { return available.keySet().stream().sorted().toList(); }
}
