package net.vg.codex.client.advancements;

import net.minecraft.resources.Identifier;
import java.util.List;

/** Recipes only exist authoritatively on the server in this Minecraft version. */
public final class ClientRecipeIds {
    private static List<Identifier> ids = List.of();
    private ClientRecipeIds() { }
    public static void set(List<Identifier> recipeIds) { ids = recipeIds.stream().sorted().toList(); }
    public static List<Identifier> all() { return ids; }
}
