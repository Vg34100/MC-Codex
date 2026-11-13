package net.vg.codex.forge;

import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.vg.codex.ModMain;
import net.vg.codex.advancement.TreasureBagItem;

@Mod(ModMain.MOD_ID)
public final class ModMainForge {
    public ModMainForge() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        EventBuses.registerModEventBus(ModMain.MOD_ID, bus);
        var items = net.minecraftforge.registries.DeferredRegister.create(net.minecraft.core.registries.Registries.ITEM, ModMain.MOD_ID);
        items.register("treasure_bag", () -> {
            var item = new TreasureBagItem(); TreasureBagItem.ITEM = item; return item;
        });
        items.register(bus);
        ModMain.init();
    }
}
