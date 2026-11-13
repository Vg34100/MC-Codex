package net.vg.codex.compat.earlyneoforge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Native payload registration/dispatch; packet definitions and permission checks stay shared. */
public final class NetworkManager {
    private NetworkManager() { }
    public enum Side { C2S, S2C }
    public static Side c2s(){return Side.C2S;}
    public static Side s2c(){return Side.S2C;}
    private record Key(Side side,Identifier id) { }
    private record Type<T extends CustomPacketPayload>(Key key,CustomPacketPayload.Type<T> type,StreamCodec<? super RegistryFriendlyByteBuf,T> codec) { }
    private static final Map<Key,Type<?>> TYPES=new LinkedHashMap<>();
    private static final Map<Key,BiConsumer<CustomPacketPayload,PacketContext>> RECEIVERS=new ConcurrentHashMap<>();
    public record PacketContext(IPayloadContext context) {
        public net.minecraft.world.entity.player.Player getPlayer(){return context.player();}
        public void queue(Runnable action){context.enqueueWork(action);}
        public Platform.Env getEnvironment(){return context.flow()==PacketFlow.CLIENTBOUND?Platform.Env.CLIENT:Platform.Env.SERVER;}
    }
    public static void install(IEventBus bus){bus.addListener((RegisterPayloadHandlersEvent event)->{
        PayloadRegistrar registrar=event.registrar("1");
        for(var type:List.copyOf(TYPES.values()))register(registrar,type);
    });}
    private static <T extends CustomPacketPayload> void register(PayloadRegistrar registrar,Type<T> entry){
        net.neoforged.neoforge.network.handling.IPayloadHandler<T> handler=(packet,context)->{
            var callback=RECEIVERS.get(entry.key());
            if(callback==null)throw new IllegalStateException("Missing Codex receiver: "+entry.key());
            callback.accept(packet,new PacketContext(context));
        };
        if(entry.key().side()==Side.S2C)registrar.playToClient(entry.type(),entry.codec(),handler);
        else registrar.playToServer(entry.type(),entry.codec(),handler);
    }
    public static <T extends CustomPacketPayload> void registerS2CPayloadType(CustomPacketPayload.Type<T> type,StreamCodec<? super RegistryFriendlyByteBuf,T> codec){
        Key key=new Key(Side.S2C,type.id());TYPES.putIfAbsent(key,new Type<>(key,type,codec));
    }
    public static <T extends CustomPacketPayload> void registerReceiver(Side side,CustomPacketPayload.Type<T> type,StreamCodec<? super RegistryFriendlyByteBuf,T> codec,BiConsumer<T,PacketContext> receiver){
        Key key=new Key(side,type.id());TYPES.putIfAbsent(key,new Type<>(key,type,codec));
        RECEIVERS.put(key,(packet,context)->receiver.accept(cast(packet),context));
    }
    @SuppressWarnings("unchecked") private static <T extends CustomPacketPayload> T cast(CustomPacketPayload payload){return (T)payload;}
    public static void sendToPlayer(ServerPlayer player,CustomPacketPayload packet){PacketDistributor.sendToPlayer(player,packet);}
    public static void sendToPlayers(Iterable<ServerPlayer> players,CustomPacketPayload packet){for(var player:players)sendToPlayer(player,packet);}
    public static void sendToServer(CustomPacketPayload packet){NativeClientHooks.send(packet);}
}
