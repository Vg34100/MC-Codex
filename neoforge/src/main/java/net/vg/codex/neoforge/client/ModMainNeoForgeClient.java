package net.vg.codex.neoforge.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.vg.codex.ModMain;
import net.vg.codex.client.AdvancementEditorClient;

@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT)
public final class ModMainNeoForgeClient {
    private ModMainNeoForgeClient() {
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        AdvancementEditorClient.init();
    }
}
