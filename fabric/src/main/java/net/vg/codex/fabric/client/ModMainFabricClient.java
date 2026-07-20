package net.vg.codex.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.vg.codex.client.AdvancementEditorClient;

public final class ModMainFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AdvancementEditorClient.init();
    }
}
