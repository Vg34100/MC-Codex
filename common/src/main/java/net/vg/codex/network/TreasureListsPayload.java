package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.List;

/** Server-authored editor data, sent only to permitted editors. */
public record TreasureListsPayload(String definitions, List<Identifier> lootTables, String status) implements CustomPacketPayload {
    public static final Type<TreasureListsPayload> TYPE = new Type<>(Identifier.parse("codex:treasure_lists"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TreasureListsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(1_048_576), TreasureListsPayload::definitions,
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), TreasureListsPayload::lootTables,
            ByteBufCodecs.stringUtf8(512), TreasureListsPayload::status, TreasureListsPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
