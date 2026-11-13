package net.vg.codex.neoforge;

import net.vg.codex.ModMain;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(ModMain.MOD_ID)
public final class ModMainNeoForge {
    public ModMainNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        var items = net.neoforged.neoforge.registries.DeferredRegister.create(net.minecraft.core.registries.Registries.ITEM, ModMain.MOD_ID);
        items.register("treasure_bag", () -> {
            var item = new net.vg.codex.advancement.TreasureBagItem(); net.vg.codex.advancement.TreasureBagItem.ITEM = item; return item;
        });
        items.register(modEventBus);
        ModMain.init();
    }
}
