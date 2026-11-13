package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Bounded server-authoritative tab summaries, including nodes hidden by vanilla visibility. */
public record TabSummaryPayload(String json, boolean replace) implements CustomPacketPayload {
    public static final Type<TabSummaryPayload> TYPE = new Type<>(Identifier.parse("codex:tab_summary"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TabSummaryPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(65536), TabSummaryPayload::json, ByteBufCodecs.BOOL, TabSummaryPayload::replace, TabSummaryPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
