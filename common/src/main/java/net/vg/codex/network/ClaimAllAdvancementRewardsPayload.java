package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;

/** Requests every currently available personal claim; unavailable entries are left untouched. */
public record ClaimAllAdvancementRewardsPayload() implements CustomPacketPayload {
    public static final Type<ClaimAllAdvancementRewardsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "claim_all_advancement_rewards"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClaimAllAdvancementRewardsPayload> STREAM_CODEC =
            StreamCodec.unit(new ClaimAllAdvancementRewardsPayload());
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
