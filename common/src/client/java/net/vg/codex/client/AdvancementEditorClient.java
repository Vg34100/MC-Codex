package net.vg.codex.client;

import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
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
    }
}
