package net.vg.codex.network;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
/** Compact, chunked presentation of the currently viewed tree; no inventory authority. */
public record QuestPresentationPayload(Identifier root, String json, boolean replace) implements CustomPacketPayload {
    public static final Type<QuestPresentationPayload> TYPE = new Type<>(Identifier.parse("codex:quest_presentation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, QuestPresentationPayload> STREAM_CODEC = StreamCodec.composite(
        Identifier.STREAM_CODEC, QuestPresentationPayload::root, ByteBufCodecs.stringUtf8(65536), QuestPresentationPayload::json,
        ByteBufCodecs.BOOL, QuestPresentationPayload::replace, QuestPresentationPayload::new);
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
