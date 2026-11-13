package net.vg.codex.fabric;

import net.vg.codex.ModMain;
import net.fabricmc.api.ModInitializer;

public final class ModMainFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        net.vg.codex.advancement.TreasureBagItem.ITEM = net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.vg.codex.advancement.TreasureBagItem.KEY, new net.vg.codex.advancement.TreasureBagItem());
        ModMain.init();
    }
}
