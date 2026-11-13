package net.vg.codex.validation;

@net.neoforged.fml.common.Mod("codex_tests")
public final class NeoForgeScenarios {
    public NeoForgeScenarios() { if (!Boolean.getBoolean("codex.test.client")) ServerScenarios.install(net.neoforged.neoforge.network.registration.NetworkRegistry::configureMockConnection); }
}
