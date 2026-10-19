package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;

import java.util.List;

/** Server-authored optional coordinates for the normal (non-editor) advancements view. */
public record AdvancementLayoutPayload(List<NodeLayout> positions) implements CustomPacketPayload {
    public static final Type<AdvancementLayoutPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "advancement_layout"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AdvancementLayoutPayload> STREAM_CODEC =
            NodeLayout.STREAM_CODEC.apply(ByteBufCodecs.list()).map(AdvancementLayoutPayload::new, AdvancementLayoutPayload::positions);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record NodeLayout(Identifier advancementId, int x, int y) {
        public static final StreamCodec<RegistryFriendlyByteBuf, NodeLayout> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, NodeLayout::advancementId,
                ByteBufCodecs.VAR_INT, NodeLayout::x,
                ByteBufCodecs.VAR_INT, NodeLayout::y,
                NodeLayout::new
        );
    }
}
