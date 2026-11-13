//? if <1.21 {
/*package net.vg.codex.compat.v120;
import com.google.gson.*;
import com.mojang.serialization.*;
import net.minecraft.advancements.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Function;
public final class LegacyAdvancements {
 private LegacyAdvancements(){}
 private static MinecraftServer server;
 public static void start(MinecraftServer value){server=value;}
 public static void stop(){server=null;}
 public static <T> Codec<T> jsonCodec(Function<JsonObject,T> decode,Function<T,JsonElement> encode){return new GsonCodec120<>(value->decode.apply(value.getAsJsonObject()),encode);}

 public static final Codec<Advancement> CODEC=jsonCodec(json->{
  if(server==null)throw new IllegalStateException("Advancement decoding requires the running server");
  var context=new net.minecraft.advancements.critereon.DeserializationContext(new ResourceLocation("codex","validation"),server.getLootData());
  var builder=Advancement.Builder.fromJson(net.vg.codex.advancement.ResourceFormats.advancement(json),context);
  builder.canBuild(LegacyAdvancements::empty);
  return builder.build(new ResourceLocation("codex","validation"));
 },value->value.deconstruct().serializeToJson());
 public static final Codec<DisplayInfo> DISPLAY_CODEC=jsonCodec(json->DisplayInfo.fromJson(net.vg.codex.advancement.ResourceFormats.display(json)),DisplayInfo::serializeToJson);
 public static final Codec<AdvancementRewards> REWARDS_CODEC=jsonCodec(AdvancementRewards::deserialize,AdvancementRewards::serializeToJson);
 public record View(Advancement nativeAdvancement) {
  public Optional<DisplayInfo> display(){return Optional.ofNullable(nativeAdvancement.getDisplay());}
  public Map<String,Criterion> criteria(){return nativeAdvancement.getCriteria();}
  public Requirements120 requirements(){return new Requirements120(nativeAdvancement.getRequirements());}
  public Optional<ResourceLocation> parent(){return Optional.ofNullable(nativeAdvancement.getParent()).map(Advancement::getId);}
  public AdvancementRewards rewards(){return nativeAdvancement.getRewards();}
  public Optional<Component> name(){return Optional.ofNullable(nativeAdvancement.getChatComponent());}
 }
 public static Component progressText(AdvancementProgress nativeProgress){String text=nativeProgress.getProgressText();return text==null?null:Component.literal(text);}
 public static View view(Advancement value){return new View(value);}
 private static Advancement empty(ResourceLocation id){return new Advancement(id,null,null,AdvancementRewards.EMPTY,Map.of(),new String[0][],false);}
 public static Advancement withId(ResourceLocation id,Advancement source){return new Advancement(id,source.getParent(),source.getDisplay(),source.getRewards(),source.getCriteria(),source.getRequirements(),source.sendsTelemetryEvent());}
 public static Advancement preview(Optional<ResourceLocation> parent,Optional<DisplayInfo> display,AdvancementRewards rewards,Map<String,Criterion> criteria,Requirements120 requirements,boolean telemetry){return new Advancement(new ResourceLocation("codex","preview"),parent.map(LegacyAdvancements::empty).orElse(null),display.orElse(null),rewards,criteria,requirements.rows(),telemetry);}
 public static void addAll(AdvancementList list,Collection<Advancement> values){Map<ResourceLocation,Advancement.Builder> builders=new LinkedHashMap<>();for(var value:values)builders.put(value.getId(),value.deconstruct());list.add(builders);}
}

*///?}
