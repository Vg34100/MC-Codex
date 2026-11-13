package net.vg.codex.compat.earlyneoforge;

import java.util.function.*;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import com.mojang.brigadier.CommandDispatcher;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.*;

/** The native events consumed by the shared Codex implementation. No quest logic lives here. */
public final class NativeHooks {
    private NativeHooks() { }
    @FunctionalInterface public interface Hook<T> {void register(T callback);}
    public static final class LifecycleEvent {
        public static final Hook<Consumer<MinecraftServer>> SERVER_STARTING = callback -> NeoForge.EVENT_BUS.addListener((ServerStartingEvent event)->callback.accept(event.getServer()));
        public static final Hook<Consumer<MinecraftServer>> SERVER_STOPPED = callback -> NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event)->callback.accept(event.getServer()));
    }
    public static final class PlayerEvent {
        public static final Hook<Consumer<ServerPlayer>> PLAYER_JOIN = callback -> NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event)->{if(event.getEntity() instanceof ServerPlayer player)callback.accept(player);});
        public static final Hook<BiConsumer<ServerPlayer,AdvancementHolder>> PLAYER_ADVANCEMENT = callback -> NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.AdvancementEvent.AdvancementEarnEvent event)->{if(event.getEntity() instanceof ServerPlayer player)callback.accept(player,event.getAdvancement());});
    }
    public static final class TickEvent {
        public static final Hook<Consumer<MinecraftServer>> SERVER_POST = callback -> NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post event)->callback.accept(event.getServer()));
        public static final Hook<Consumer<Player>> PLAYER_POST = callback -> NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event)->callback.accept(event.getEntity()));
    }
    public static final class CommandRegistrationEvent {
        @FunctionalInterface public interface Registration {void register(CommandDispatcher<CommandSourceStack> dispatcher,CommandBuildContext registries,Commands.CommandSelection selection);}
        public static final Hook<Registration> EVENT = callback -> NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent event)->callback.register(event.getDispatcher(),event.getBuildContext(),event.getCommandSelection()));
    }
}
