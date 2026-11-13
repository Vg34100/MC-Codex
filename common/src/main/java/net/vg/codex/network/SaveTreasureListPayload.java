package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SaveTreasureListPayload(Identifier id, String definition) implements CustomPacketPayload {
    public static final Type<SaveTreasureListPayload> TYPE = new Type<>(Identifier.parse("codex:save_treasure_list"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SaveTreasureListPayload> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, SaveTreasureListPayload::id,
            ByteBufCodecs.stringUtf8(65_536), SaveTreasureListPayload::definition, SaveTreasureListPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
