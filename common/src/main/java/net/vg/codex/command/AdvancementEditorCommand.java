package net.vg.codex.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.vg.codex.network.AdvancementEditorNetworking;

public final class AdvancementEditorCommand {
    private AdvancementEditorCommand() {
    }

    public static void register() {
        CommandRegistrationEvent.EVENT.register(AdvancementEditorCommand::register);
    }

    private static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            net.minecraft.commands.CommandBuildContext buildContext,
            Commands.CommandSelection selection
    ) {
        dispatcher.register(
                Commands.literal("codex")
                        .then(Commands.literal("advancements")
                                .then(Commands.literal("edit")
                                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                        .executes(AdvancementEditorCommand::openEditor)))
        );
    }

    private static int openEditor(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        AdvancementEditorNetworking.sendSnapshotIfPermitted(player);
        return 1;
    }
}
