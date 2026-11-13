package net.vg.codex.network;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
public record RecipeEditorResponse(String json) implements CustomPacketPayload {
 public static final Type<RecipeEditorResponse> TYPE=new Type<>(Identifier.parse("codex:recipe_editor_response"));
 public static final StreamCodec<RegistryFriendlyByteBuf,RecipeEditorResponse> STREAM_CODEC=StreamCodec.composite(ByteBufCodecs.stringUtf8(262144),RecipeEditorResponse::json,RecipeEditorResponse::new);
 @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
