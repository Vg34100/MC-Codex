package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;

import java.util.List;

/**
 * Server -> every connected client: which items a given advancement's criteria reference (see
 * {@code AdvancementPackIO#extractCriterionItemId}), for the hover box's "Required" hint row.
 * Broadcast the same way and at the same points as {@link AdvancementRewardsPayload} - not
 * gated behind op/editor permissions, since every player benefits from the hint, not just editors.
 */
public record AdvancementRequiredItemsPayload(List<AdvancementRequired> required) implements CustomPacketPayload {
    public static final Type<AdvancementRequiredItemsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "advancement_required_items"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AdvancementRequiredItemsPayload> STREAM_CODEC =
            AdvancementRequired.STREAM_CODEC.apply(ByteBufCodecs.list())
                    .map(AdvancementRequiredItemsPayload::new, AdvancementRequiredItemsPayload::required);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record AdvancementRequired(Identifier advancementId, List<Identifier> itemIds, List<CriterionHint> hints) {
        public static final StreamCodec<RegistryFriendlyByteBuf, AdvancementRequired> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, AdvancementRequired::advancementId,
                Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), AdvancementRequired::itemIds,
                CriterionHint.STREAM_CODEC.apply(ByteBufCodecs.list()), AdvancementRequired::hints,
                AdvancementRequired::new
        );
    }

    /** A non-item requirement such as a recipe unlock.  The server sends an icon plus a plain
     * explanation because vanilla deliberately strips criterion conditions from client sync. */
    public record CriterionHint(Identifier iconItemId, String text) {
        public static final StreamCodec<RegistryFriendlyByteBuf, CriterionHint> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, CriterionHint::iconItemId,
                ByteBufCodecs.stringUtf8(256), CriterionHint::text,
                CriterionHint::new
        );
    }
}
