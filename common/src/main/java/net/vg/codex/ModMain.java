package net.vg.codex;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.vg.codex.advancement.AdvancementPackSync;
import net.vg.codex.advancement.CodexStatisticTasks;
import net.vg.codex.command.AdvancementEditorCommand;
import net.vg.codex.network.AdvancementEditorNetworking;
import net.vg.codex.party.CodexPartyService;

public final class ModMain {
    public static final String MOD_ID = "codex";

    private ModMain() {
    }

    public static void init() {
        LifecycleEvent.SERVER_STARTING.register(AdvancementPackSync::onServerStarting);
        // A join happens independent of any reload - without this, a player who joins between
        // reloads would see generic rewards until the next edit/save, instead of the current state.
        PlayerEvent.PLAYER_JOIN.register(AdvancementPackSync::sendRewardsTo);
        PlayerEvent.PLAYER_JOIN.register(AdvancementPackSync::sendRequiredItemsTo);
        PlayerEvent.PLAYER_JOIN.register(AdvancementPackSync::sendLayoutTo);
        PlayerEvent.PLAYER_JOIN.register(AdvancementPackSync::sendClaimableRewardsTo);
        PlayerEvent.PLAYER_JOIN.register(AdvancementPackSync::sendSubmissionTasksTo);
        PlayerEvent.PLAYER_JOIN.register(AdvancementPackSync::sendRecipeIdsTo);
        PlayerEvent.PLAYER_JOIN.register(CodexPartyService::onJoin);
        PlayerEvent.PLAYER_JOIN.register(CodexStatisticTasks::evaluateAndSend);
        PlayerEvent.PLAYER_ADVANCEMENT.register((player, advancement) -> AdvancementPackSync.sendClaimableRewardsTo(player));
        PlayerEvent.PLAYER_ADVANCEMENT.register(CodexPartyService::onAdvancementAward);
        TickEvent.SERVER_POST.register(CodexStatisticTasks::tick);
        TickEvent.PLAYER_POST.register(player -> {
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                CodexStatisticTasks.tickPlayer(serverPlayer);
            }
        });
        AdvancementEditorNetworking.registerCommon();
        AdvancementEditorCommand.register();
    }
}
