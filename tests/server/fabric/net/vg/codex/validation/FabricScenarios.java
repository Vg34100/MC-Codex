package net.vg.codex.validation;

public final class FabricScenarios implements net.fabricmc.api.ModInitializer {
    @Override public void onInitialize() { if (!Boolean.getBoolean("codex.test.client")) ServerScenarios.install(); }
}
