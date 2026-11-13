package net.vg.codex.validation;
//? if <26.1 {
/*@net.neoforged.fml.common.EventBusSubscriber(modid="codex_tests",value=net.neoforged.api.distmarker.Dist.CLIENT,bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
*///?} else {
@net.neoforged.fml.common.EventBusSubscriber(modid="codex_tests",value=net.neoforged.api.distmarker.Dist.CLIENT)
//?}
public final class NeoForgeTransportClient {
    @net.neoforged.bus.api.SubscribeEvent
    static void setup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event){if(Boolean.getBoolean("codex.test.client"))TransportClient.install();}
}
