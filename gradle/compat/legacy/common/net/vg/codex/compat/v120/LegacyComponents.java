//? if <1.21 {
/*package net.vg.codex.compat.v120;
import com.mojang.serialization.*;
import net.minecraft.network.chat.Component;
public final class LegacyComponents {
 private LegacyComponents(){}
 public static final Codec<Component> CODEC=new GsonCodec120<>(value->{Component text=Component.Serializer.fromJson(value);if(text==null)throw new IllegalArgumentException("Missing text");return text;},Component.Serializer::toJsonTree);
}
*///?}
