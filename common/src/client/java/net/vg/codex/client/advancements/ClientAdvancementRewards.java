package net.vg.codex.client.advancements;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * Client-side cache of the last {@link net.vg.codex.network.AdvancementRewardsPayload} received -
 * view mode's only source of real reward data, since vanilla's own client-side {@code Advancement}
 * object never carries any (confirmed via bytecode - see that payload's own doc comment). Cached
 * in the same raw JSON shape {@link CodexAdvancementWidget#buildRewardChipsFromJson} already
 * expects (the same one the editor itself works with), so view and edit mode share one chip-
 * building code path instead of needing a second one specifically for real {@code AdvancementRewards}.
 */
public final class ClientAdvancementRewards {
    private static Map<Identifier, JsonObject> rewards = Map.of();

    private ClientAdvancementRewards() {
    }

    public static void set(Map<Identifier, JsonObject> newRewards) {
        rewards = newRewards;
    }

    /** Null if this advancement has no rewards at all (never had an entry in the broadcast). */
    public static JsonObject forAdvancement(Identifier advancementId) {
        return rewards.get(advancementId);
    }
}
