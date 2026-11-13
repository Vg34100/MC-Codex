//? if <1.21 {
/*package net.vg.codex.compat.v120;
import com.google.gson.*;
import com.mojang.serialization.*;
import com.mojang.datafixers.util.Pair;
import java.util.function.Function;
// The 1.20 Gson serializers legitimately emit explicit nulls. Converting through
// PASSTHROUGH into RegistryOps<JsonElement> loses those nulls in DFU 6.
public final class GsonCodec120<V> implements Codec<V> {
 private final Function<JsonElement,V> reader;private final Function<V,JsonElement> writer;
 public GsonCodec120(Function<JsonElement,V> reader,Function<V,JsonElement> writer){this.reader=reader;this.writer=writer;}
 public <T> DataResult<Pair<V,T>> decode(DynamicOps<T> ops,T input){try{
  JsonElement json=input instanceof JsonElement element?element:ops.convertTo(JsonOps.INSTANCE,input);
  return DataResult.success(Pair.of(reader.apply(json),ops.empty()));
 }catch(RuntimeException error){return DataResult.error(()->String.valueOf(error.getMessage()));}}
 @SuppressWarnings("unchecked") public <T> DataResult<T> encode(V input,DynamicOps<T> ops,T prefix){try{
  JsonElement json=writer.apply(input);
  if(prefix instanceof JsonElement existing){
   if(existing.isJsonNull())return DataResult.success((T)json);
   if(existing.isJsonObject()&&json.isJsonObject()){JsonObject merged=existing.getAsJsonObject().deepCopy();json.getAsJsonObject().entrySet().forEach(e->merged.add(e.getKey(),e.getValue()));return DataResult.success((T)merged);}
   return DataResult.error(()->"Cannot merge JSON value into this prefix");
  }
  T encoded=JsonOps.INSTANCE.convertTo(ops,json);return ops.mergeToMap(prefix,ops.getMap(encoded).getOrThrow(false,message->{}));
 }catch(RuntimeException error){return DataResult.error(()->String.valueOf(error.getMessage()));}}
}

*///?}
