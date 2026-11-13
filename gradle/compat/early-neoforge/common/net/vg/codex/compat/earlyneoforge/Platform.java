package net.vg.codex.compat.earlyneoforge;

import java.nio.file.Path;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

/** Loader bindings for the two early 26.1 NeoForge targets without a matching Architectury build. */
public final class Platform {
    private Platform() { }
    public enum Env { CLIENT, SERVER }
    public static Env getEnvironment() {return FMLEnvironment.getDist()==Dist.CLIENT?Env.CLIENT:Env.SERVER;}
    public static Path getConfigFolder() {return FMLPaths.CONFIGDIR.get();}
    public static Path getGameFolder() {return FMLPaths.GAMEDIR.get();}
    public static boolean isModLoaded(String id) {return ModList.get().isLoaded(id);}
    public static boolean isDevelopmentEnvironment() {return !FMLEnvironment.isProduction();}
}
