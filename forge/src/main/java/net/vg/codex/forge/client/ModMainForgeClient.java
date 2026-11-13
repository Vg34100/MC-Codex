package net.vg.codex.forge.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.vg.codex.ModMain;
import net.vg.codex.client.AdvancementEditorClient;

@Mod.EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModMainForgeClient {
    private ModMainForgeClient() { }
    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(AdvancementEditorClient::init);
    }
}
