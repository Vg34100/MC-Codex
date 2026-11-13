package net.vg.codex.network;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
/** Bounded request envelope, including optimistic document version. */
public record RecipeEditorRequest(String json) implements CustomPacketPayload {
 public static final Type<RecipeEditorRequest> TYPE=new Type<>(Identifier.parse("codex:recipe_editor_request"));
 public static final StreamCodec<RegistryFriendlyByteBuf,RecipeEditorRequest> STREAM_CODEC=StreamCodec.composite(ByteBufCodecs.stringUtf8(65536),RecipeEditorRequest::json,RecipeEditorRequest::new);
 @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
