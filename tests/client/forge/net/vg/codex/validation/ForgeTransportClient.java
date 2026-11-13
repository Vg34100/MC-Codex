package net.vg.codex.validation;

@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid="codex_tests", value=net.minecraftforge.api.distmarker.Dist.CLIENT, bus=net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD)
public final class ForgeTransportClient {
    @net.minecraftforge.eventbus.api.SubscribeEvent
    static void setup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        if(Boolean.getBoolean("codex.test.client")) TransportClient.install();
    }
}
