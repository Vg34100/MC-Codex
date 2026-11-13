package net.vg.codex.validation;
public final class FabricTransportClient implements net.fabricmc.api.ClientModInitializer {
    @Override public void onInitializeClient(){if(Boolean.getBoolean("codex.test.client"))TransportClient.install();}
}
