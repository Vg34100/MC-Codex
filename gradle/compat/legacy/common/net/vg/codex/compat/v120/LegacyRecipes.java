//? if <1.21 {
/*package net.vg.codex.compat.v120;
import com.google.gson.JsonObject;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.resources.ResourceLocation;
import com.mojang.serialization.DataResult;
public final class LegacyRecipes {
 private LegacyRecipes(){}
 public static DataResult<Recipe<?>> parse(JsonObject document){try{return DataResult.success(net.minecraft.world.item.crafting.RecipeManager.fromJson(new ResourceLocation("codex","validation"),document));}catch(RuntimeException error){return DataResult.error(()->String.valueOf(error.getMessage()));}}
}

*///?}
