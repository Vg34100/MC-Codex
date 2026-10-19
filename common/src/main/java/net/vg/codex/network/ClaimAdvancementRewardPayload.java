package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;

/** Client request to collect one reward entry, or every remaining entry when {@code rewardIndex} is -1. */
public record ClaimAdvancementRewardPayload(Identifier advancementId, int rewardIndex) implements CustomPacketPayload {
    public static final Type<ClaimAdvancementRewardPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "claim_advancement_reward"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClaimAdvancementRewardPayload> STREAM_CODEC =
            StreamCodec.composite(Identifier.STREAM_CODEC, ClaimAdvancementRewardPayload::advancementId,
                    net.minecraft.network.codec.ByteBufCodecs.VAR_INT, ClaimAdvancementRewardPayload::rewardIndex,
                    ClaimAdvancementRewardPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
