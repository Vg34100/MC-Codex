package net.vg.codex.fabric;

import net.vg.codex.ModMain;
import net.fabricmc.api.ModInitializer;

public final class ModMainFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ModMain.init();
    }
}
