package net.vg.codex.mixin;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.vg.codex.advancement.QuestDefinitions;
import net.vg.codex.advancement.QuestRuntime;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only explicitly choice-gated quests opt in. Ordinary advancements keep vanilla behavior. */
@Mixin(PlayerAdvancements.class)
abstract class AdvancementBranchMixin {
    @Shadow private ServerPlayer player;
    @Inject(method = "award", at = @At("HEAD"), cancellable = true)
    private void codex$branch(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> ci) {
        var q = QuestDefinitions.get(holder.id());
        if (q.has("requires_choice") && !QuestRuntime.unlocked(player, q)) ci.setReturnValue(false);
    }
}
