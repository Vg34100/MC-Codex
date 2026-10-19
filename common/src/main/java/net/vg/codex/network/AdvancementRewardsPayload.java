package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;

import java.util.List;
import java.util.Optional;

/**
 * Server -> every connected client (not gated behind op/editor permissions, unlike
 * {@link OpenAdvancementEditorPayload}): every advancement's actual reward contents. Confirmed via
 * bytecode that vanilla's own {@code Advancement.STREAM_CODEC} - the real network sync every client
 * receives during normal play - hardcodes {@code AdvancementRewards.EMPTY} and an empty criteria
 * map on decode; it never sends real rewards (or criteria) to any client regardless of what's
 * actually saved server-side. Without this broadcast, a normal player's hover could never show
 * what an advancement actually grants - not just item rewards (not a vanilla field to begin with),
 * but experience/recipes/loot tables/functions too, despite those being real vanilla fields.
 */
public record AdvancementRewardsPayload(List<AdvancementReward> rewards) implements CustomPacketPayload {
    public static final Type<AdvancementRewardsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "advancement_rewards"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AdvancementRewardsPayload> STREAM_CODEC =
            AdvancementReward.STREAM_CODEC.apply(ByteBufCodecs.list())
                    .map(AdvancementRewardsPayload::new, AdvancementRewardsPayload::rewards);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record AdvancementReward(Identifier advancementId, int experience, List<Identifier> recipes,
                                     List<Identifier> loot, Optional<Identifier> function, List<ItemReward> items) {
        public static final StreamCodec<RegistryFriendlyByteBuf, AdvancementReward> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, AdvancementReward::advancementId,
                ByteBufCodecs.VAR_INT, AdvancementReward::experience,
                Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), AdvancementReward::recipes,
                Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), AdvancementReward::loot,
                Identifier.STREAM_CODEC.apply(ByteBufCodecs::optional), AdvancementReward::function,
                ItemReward.STREAM_CODEC.apply(ByteBufCodecs.list()), AdvancementReward::items,
                AdvancementReward::new
        );
    }

    public record ItemReward(Identifier itemId, int count) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ItemReward> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, ItemReward::itemId,
                ByteBufCodecs.VAR_INT, ItemReward::count,
                ItemReward::new
        );
    }
}
