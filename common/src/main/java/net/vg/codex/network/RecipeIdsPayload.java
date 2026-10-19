package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;

import java.util.List;

/** Server-authoritative recipe ids for the editor's searchable recipe picker. */
public record RecipeIdsPayload(List<Identifier> recipeIds) implements CustomPacketPayload {
    public static final Type<RecipeIdsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "recipe_ids"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RecipeIdsPayload> STREAM_CODEC =
            StreamCodec.composite(Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), RecipeIdsPayload::recipeIds,
                    RecipeIdsPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
