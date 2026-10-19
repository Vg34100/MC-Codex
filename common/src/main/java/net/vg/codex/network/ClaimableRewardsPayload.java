package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;

import java.util.List;

/** The individual reward entries currently available to one player. */
public record ClaimableRewardsPayload(List<Claimable> rewards) implements CustomPacketPayload {
    public static final Type<ClaimableRewardsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "claimable_rewards"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClaimableRewardsPayload> STREAM_CODEC =
            StreamCodec.composite(Claimable.STREAM_CODEC.apply(ByteBufCodecs.list()), ClaimableRewardsPayload::rewards,
                    ClaimableRewardsPayload::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public record Claimable(Identifier advancementId, List<Integer> rewardIndexes) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Claimable> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Claimable::advancementId,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), Claimable::rewardIndexes,
                Claimable::new);
    }
}
