package net.vg.codex.validation;
@net.neoforged.fml.common.EventBusSubscriber(modid="codex_tests",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class NeoForgeTransportClient {
    @net.neoforged.bus.api.SubscribeEvent
    static void setup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event){if(Boolean.getBoolean("codex.test.client"))TransportClient.install();}
}
