package net.vg.codex.validation;

@net.minecraftforge.fml.common.Mod("codex_tests")
public final class ForgeScenarios {
    public ForgeScenarios() { if(!Boolean.getBoolean("codex.test.client"))ServerScenarios.install(); }
}
