//? if <1.21 {
/*package net.vg.codex.compat.v120;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import java.util.function.Function;
public final class ByteBufCodecs {
 private ByteBufCodecs(){}
 public static final StreamCodec<FriendlyByteBuf,Integer> VAR_INT=StreamCodec.of((b,v)->b.writeVarInt(v),FriendlyByteBuf::readVarInt);
 public static final StreamCodec<FriendlyByteBuf,Boolean> BOOL=StreamCodec.of((b,v)->b.writeBoolean(v),FriendlyByteBuf::readBoolean);
 public static final StreamCodec<FriendlyByteBuf,ResourceLocation> ID=StreamCodec.of(FriendlyByteBuf::writeResourceLocation,FriendlyByteBuf::readResourceLocation);
 public static StreamCodec<FriendlyByteBuf,String> stringUtf8(int max){return StreamCodec.of((b,v)->b.writeUtf(v,max),b->b.readUtf(max));}
 public static <B extends FriendlyByteBuf,V> Function<StreamCodec<B,V>,StreamCodec<B,List<V>>> list(int max){return codec->StreamCodec.of((b,values)->{if(values.size()>max)throw new io.netty.handler.codec.EncoderException("Too many entries");b.writeVarInt(values.size());for(V v:values)codec.encode(b,v);},b->{int size=b.readVarInt();if(size<0||size>max)throw new io.netty.handler.codec.DecoderException("Invalid list size");List<V> values=new ArrayList<>(size);for(int i=0;i<size;i++)values.add(codec.decode(b));return values;});}
 public static <B extends FriendlyByteBuf,V> StreamCodec<B,Optional<V>> optional(StreamCodec<B,V> codec){return StreamCodec.of((b,v)->{b.writeBoolean(v.isPresent());v.ifPresent(x->codec.encode(b,x));},b->b.readBoolean()?Optional.of(codec.decode(b)):Optional.empty());}
}

*///?}
