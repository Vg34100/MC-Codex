package net.vg.codex.compat;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
public final class LegacyPermissions {
 private LegacyPermissions(){}
 public static Predicate<CommandSourceStack> required(int level){return source -> source.hasPermission(level);}
}
