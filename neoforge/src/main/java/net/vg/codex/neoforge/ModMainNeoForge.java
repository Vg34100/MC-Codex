package net.vg.codex.neoforge;

import net.vg.codex.ModMain;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(ModMain.MOD_ID)
public final class ModMainNeoForge {
    public ModMainNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        ModMain.init();
    }
}
