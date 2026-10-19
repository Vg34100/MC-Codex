package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;

import java.util.List;

/** Live, already-formatted statistic thresholds for the quest hover UI. */
public record StatisticProgressPayload(List<Entry> entries) implements CustomPacketPayload {
    public static final Type<StatisticProgressPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "statistic_progress"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StatisticProgressPayload> STREAM_CODEC =
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()).map(StatisticProgressPayload::new, StatisticProgressPayload::entries);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public record Entry(Identifier advancementId, String criterion, String current, String target) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Entry::advancementId,
                ByteBufCodecs.stringUtf8(256), Entry::criterion,
                ByteBufCodecs.stringUtf8(256), Entry::current,
                ByteBufCodecs.stringUtf8(256), Entry::target,
                Entry::new);
    }
}
