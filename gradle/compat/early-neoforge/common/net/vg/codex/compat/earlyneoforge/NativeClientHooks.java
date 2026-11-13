package net.vg.codex.compat.earlyneoforge;

import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.common.NeoForge;

/** Loaded exclusively by client setup or a clientbound UI action. */
public final class NativeClientHooks {
    private NativeClientHooks() { }
    public record CompoundEventResult<T>(boolean interrupted,T object) {
        public static <T> CompoundEventResult<T> pass(){return new CompoundEventResult<>(false,null);}
        public static <T> CompoundEventResult<T> interruptTrue(T value){return new CompoundEventResult<>(true,value);}
    }
    public static final class ClientGuiEvent {
        public static final NativeHooks.Hook<Function<Screen,CompoundEventResult<Screen>>> SET_SCREEN = callback -> NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ScreenEvent.Opening event)->{
            var result=callback.apply(event.getNewScreen());if(result.interrupted())event.setNewScreen(result.object());
        });
    }
    public static final class ClientTickEvent {
        public static final NativeHooks.Hook<Consumer<Minecraft>> CLIENT_POST = callback -> NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post event)->callback.accept(Minecraft.getInstance()));
    }
    public static void send(CustomPacketPayload packet){net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(packet);}
}
