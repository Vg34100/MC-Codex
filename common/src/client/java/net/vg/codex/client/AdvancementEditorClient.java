package net.vg.codex.client;

import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.vg.codex.network.OpenAdvancementEditorPayload;

public final class AdvancementEditorClient {
    private AdvancementEditorClient() {
    }

    public static void init() {
        NetworkManager.registerReceiver(NetworkManager.s2c(), OpenAdvancementEditorPayload.TYPE, OpenAdvancementEditorPayload.STREAM_CODEC,
                (payload, context) -> context.queue(() ->
                        Minecraft.getInstance().setScreen(new AdvancementEditorScreen(payload.advancements()))));
    }
}
