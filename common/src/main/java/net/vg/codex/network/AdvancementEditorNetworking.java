package net.vg.codex.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.vg.codex.advancement.AdvancementPackIO;
import net.vg.codex.advancement.AdvancementPackSync;

import java.nio.file.Path;

public final class AdvancementEditorNetworking {
    private AdvancementEditorNetworking() {
    }

    public static void registerCommon() {
        // On a dedicated server, client init (which registers this via registerReceiver) never
        // runs, so the payload type needs to be registered here instead. On a client JVM
        // (including the integrated server for singleplayer), skip it - architectury's
        // registerReceiver call in AdvancementEditorClient registers the type itself, and
        // registering it twice in the same JVM throws "already registered".
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(OpenAdvancementEditorPayload.TYPE, OpenAdvancementEditorPayload.STREAM_CODEC);
        }
        NetworkManager.registerReceiver(NetworkManager.c2s(), SaveAdvancementTreePayload.TYPE, SaveAdvancementTreePayload.STREAM_CODEC,
                AdvancementEditorNetworking::handleSave);
    }

    private static void handleSave(SaveAdvancementTreePayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player)) {
                return;
            }
            if (!Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack())) {
                return;
            }
            MinecraftServer server = player.level().getServer();
            HolderLookup.Provider registries = server.registryAccess();
            Path packDir = AdvancementPackSync.externalPackDir();
            for (var holder : payload.advancements()) {
                AdvancementPackIO.write(packDir, holder, registries);
            }
            AdvancementPackSync.onServerStarting(server);
        });
    }
}
