package net.vg.codex.client;

import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.vg.codex.network.OpenAdvancementEditorPayload;

public final class AdvancementEditorClient {
    private AdvancementEditorClient() {
    }

    public static void init() {
        // The payload type is already registered by AdvancementEditorNetworking.registerCommon()
        // (needed so a dedicated server, which never runs this client init, can still send it).
        // Registering it again here via the type+codec receiver overload would throw
        // "already registered", so this decodes manually against the plain identifier instead.
        NetworkManager.registerReceiver(NetworkManager.s2c(), OpenAdvancementEditorPayload.TYPE.id(), (buf, context) -> {
            OpenAdvancementEditorPayload payload = OpenAdvancementEditorPayload.STREAM_CODEC.decode(buf);
            context.queue(() -> Minecraft.getInstance().setScreen(new AdvancementEditorScreen(payload.advancements())));
        });
    }
}
