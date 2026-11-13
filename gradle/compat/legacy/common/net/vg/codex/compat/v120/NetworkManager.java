//? if <1.21 {
/*package net.vg.codex.compat.v120;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.function.BiConsumer;
public final class NetworkManager {
 private NetworkManager(){}
 private static final Map<ResourceLocation,StreamCodec<FriendlyByteBuf,?>> CODECS=new HashMap<>();
 public static dev.architectury.networking.NetworkManager.Side s2c(){return dev.architectury.networking.NetworkManager.s2c();}
 public static dev.architectury.networking.NetworkManager.Side c2s(){return dev.architectury.networking.NetworkManager.c2s();}
 public record PacketContext(dev.architectury.networking.NetworkManager.PacketContext nativeContext){
  public net.minecraft.world.entity.player.Player getPlayer(){return nativeContext.getPlayer();}
  public void queue(Runnable action){nativeContext.queue(action);}
  public dev.architectury.utils.Env getEnvironment(){return nativeContext.getEnvironment();}
 }
 public static CustomPacketPayload decode(ResourceLocation id,FriendlyByteBuf buffer){var codec=CODECS.get(id);if(codec==null)throw new io.netty.handler.codec.DecoderException("Unknown Codex payload: "+id);var decoded=codec.decode(buffer);if(buffer.isReadable())throw new io.netty.handler.codec.DecoderException("Unexpected trailing Codex packet bytes");return (CustomPacketPayload)decoded;}
 public static <T extends CustomPacketPayload> void registerS2CPayloadType(CustomPacketPayload.Type<T> type,StreamCodec<FriendlyByteBuf,T> codec){CODECS.put(type.id(),codec);}
 public static <T extends CustomPacketPayload> void registerReceiver(dev.architectury.networking.NetworkManager.Side side,CustomPacketPayload.Type<T> type,StreamCodec<FriendlyByteBuf,T> codec,BiConsumer<T,PacketContext> receiver){
  CODECS.put(type.id(),codec);
  dev.architectury.networking.NetworkManager.registerReceiver(side,type.id(),(buffer,context)->{
   T packet=codec.decode(buffer);
   if(buffer.isReadable())throw new io.netty.handler.codec.DecoderException("Unexpected trailing Codex packet bytes");
   receiver.accept(packet,new PacketContext(context));
  });
 }
 @SuppressWarnings("unchecked") private static FriendlyByteBuf encode(CustomPacketPayload packet){
  var codec=(StreamCodec<FriendlyByteBuf,CustomPacketPayload>)CODECS.get(packet.type().id());if(codec==null)throw new IllegalStateException("Unregistered Codex packet "+packet.type().id());
  FriendlyByteBuf buffer=new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());try{codec.encode(buffer,packet);return buffer;}catch(RuntimeException error){buffer.release();throw error;}
 }
 public static void sendToPlayer(ServerPlayer player,CustomPacketPayload packet){var buffer=encode(packet);try{dev.architectury.networking.NetworkManager.sendToPlayer(player,packet.type().id(),buffer);}finally{buffer.release();}}
 public static void sendToPlayers(Iterable<ServerPlayer> players,CustomPacketPayload packet){for(var player:players)sendToPlayer(player,packet);}
 public static void sendToServer(CustomPacketPayload packet){var buffer=encode(packet);try{dev.architectury.networking.NetworkManager.sendToServer(packet.type().id(),buffer);}finally{buffer.release();}}
}

*///?}
