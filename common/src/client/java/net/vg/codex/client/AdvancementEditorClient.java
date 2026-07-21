package net.vg.codex.client;

import dev.architectury.event.CompoundEventResult;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.vg.codex.client.advancements.CodexAdvancementsScreen;
import net.vg.codex.network.OpenAdvancementEditorPayload;

public final class AdvancementEditorClient {
    private AdvancementEditorClient() {
    }

    public static void init() {
        // This registers the payload type itself (architectury does so internally on Fabric
        // regardless of overload used) - AdvancementEditorNetworking.registerCommon() only
        // registers it separately on a dedicated server, where this client init never runs.
        NetworkManager.registerReceiver(NetworkManager.s2c(), OpenAdvancementEditorPayload.TYPE, OpenAdvancementEditorPayload.STREAM_CODEC,
                (payload, context) -> context.queue(() ->
                        Minecraft.getInstance().setScreen(new AdvancementEditorScreen(payload.advancements()))));

        ClientGuiEvent.SET_SCREEN.register(screen -> {
            if (!(screen instanceof AdvancementsScreen)) {
                return CompoundEventResult.pass();
            }
            Minecraft minecraft = Minecraft.getInstance();
            var connection = minecraft.getConnection();
            if (connection == null) {
                return CompoundEventResult.pass();
            }
            return CompoundEventResult.interruptTrue(
                    new CodexAdvancementsScreen(connection.getAdvancements(), minecraft.screen));
        });
    }
}
