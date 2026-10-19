package net.vg.codex.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.vg.codex.network.AdvancementEditorNetworking;
import net.vg.codex.party.CodexPartyService;

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
                        .then(Commands.literal("party")
                                .then(Commands.literal("invite")
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> {
                                                    CodexPartyService.invite(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"));
                                                    return 1;
                                                })))
                                .then(Commands.literal("accept").executes(ctx -> {
                                    CodexPartyService.accept(ctx.getSource().getPlayerOrException()); return 1;
                                }))
                                .then(Commands.literal("leave").executes(ctx -> {
                                    CodexPartyService.leave(ctx.getSource().getPlayerOrException()); return 1;
                                })))
        );
    }

    private static int openEditor(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        AdvancementEditorNetworking.sendSnapshotIfPermitted(player);
        return 1;
    }
}
