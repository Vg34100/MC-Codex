package net.vg.codex.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.vg.codex.advancement.AdvancementPackIO;
import net.vg.codex.advancement.AdvancementPackSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

public final class AdvancementEditorNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdvancementEditorNetworking.class);

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
        NetworkManager.registerReceiver(NetworkManager.c2s(), RequestAdvancementSnapshotPayload.TYPE, RequestAdvancementSnapshotPayload.STREAM_CODEC,
                (payload, context) -> context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        sendSnapshotIfPermitted(player);
                    }
                }));
    }

    /** Shared by the /codex advancements edit command and the in-screen edit-mode toggle. */
    public static void sendSnapshotIfPermitted(ServerPlayer player) {
        if (!Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack())) {
            return;
        }
        List<AdvancementHolder> advancements = List.copyOf(player.level().getServer().getAdvancements().getAllAdvancements());
        NetworkManager.sendToPlayer(player, new OpenAdvancementEditorPayload(advancements));
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
            ResourceProvider resources = server.getResourceManager();
            Path packDir = AdvancementPackSync.externalPackDir();
            int written = 0;
            int failed = 0;
            for (AdvancementHolder holder : payload.advancements()) {
                try {
                    if (AdvancementPackIO.exists(packDir, resources, holder.id()) && holder.value().display().isPresent()) {
                        // Existing advancement (vanilla or otherwise): patch just the display
                        // block as raw JSON rather than round-tripping the whole thing through
                        // Advancement.CODEC, which some vanilla advancements fail to re-encode
                        // even though they loaded fine (decode is more lenient than encode).
                        AdvancementPackIO.patchDisplay(packDir, resources, holder.id(), holder.value().display().get(), registries);
                    } else {
                        AdvancementPackIO.write(packDir, holder, registries);
                    }
                    written++;
                } catch (RuntimeException e) {
                    failed++;
                    LOGGER.error("Failed to write advancement {}", holder.id(), e);
                }
            }
            if (written > 0) {
                AdvancementPackSync.onServerStarting(server);
            }
            if (failed == 0) {
                player.sendSystemMessage(Component.literal("Codex: saved " + written + " advancement(s)."));
            } else {
                player.sendSystemMessage(Component.literal(
                        "Codex: saved " + written + ", failed " + failed + " (see server log)."));
            }
        });
    }
}
