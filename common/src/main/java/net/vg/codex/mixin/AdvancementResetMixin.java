package net.vg.codex.mixin;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.AdvancementCommands;
import net.minecraft.server.level.ServerPlayer;
import net.vg.codex.advancement.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Collection;

@Mixin(AdvancementCommands.class)
abstract class AdvancementResetMixin {
    //? if <26.1 {
    /*@Inject(method = "perform(Lnet/minecraft/commands/CommandSourceStack;Ljava/util/Collection;Lnet/minecraft/server/commands/AdvancementCommands$Action;Ljava/util/Collection;)I", at = @At("HEAD"), cancellable = true)
    private static void codex$everything(CommandSourceStack source, Collection<ServerPlayer> players, @Coerce Object action,
                                        Collection<AdvancementHolder> advancements, CallbackInfoReturnable<Integer> ci) {
    *///?} else {
    @Inject(method = "perform(Lnet/minecraft/commands/CommandSourceStack;Ljava/util/Collection;Lnet/minecraft/server/commands/AdvancementCommands$Action;Ljava/util/Collection;Z)I", at = @At("HEAD"), cancellable = true)
    private static void codex$everything(CommandSourceStack source, Collection<ServerPlayer> players, @Coerce Object action,
                                        Collection<AdvancementHolder> advancements, boolean show, CallbackInfoReturnable<Integer> ci) {
    //?}
        var all = source.getServer().getAdvancements().getAllAdvancements();
        if (!(action instanceof Enum<?> operation) || !operation.name().equals("REVOKE") || advancements.size() != all.size() || !advancements.containsAll(all)) return;
        int count = 0;
        for (ServerPlayer player : players) {
            QuestReset.everything(player);
            for (AdvancementHolder holder : advancements) {
                var progress = player.getAdvancements().getOrStartProgress(holder);
                java.util.List<String> criteria = new java.util.ArrayList<>(); progress.getCompletedCriteria().forEach(criteria::add);
                for (String criterion : criteria) if (player.getAdvancements().revoke(holder, criterion)) count++;
            }
            AdvancementPackSync.sendClaimableRewardsTo(player); AdvancementPackSync.sendSubmissionTasksTo(player);
        }
        source.sendSuccess(() -> Component.literal("Revoked advancements and reset Codex objectives, story choices and reward claims for " + players.size() + " player(s). Shared party cycles were also reset; inventories were retained."), true);
        ci.setReturnValue(Math.max(1, count));
    }
}
