package net.vg.codex.network;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
public record QuestInfoPayload(Identifier id, String json) implements CustomPacketPayload {
    public static final Type<QuestInfoPayload> TYPE = new Type<>(Identifier.parse("codex:quest_info"));
    public static final StreamCodec<RegistryFriendlyByteBuf, QuestInfoPayload> STREAM_CODEC = StreamCodec.composite(
        Identifier.STREAM_CODEC, QuestInfoPayload::id, ByteBufCodecs.stringUtf8(65536), QuestInfoPayload::json, QuestInfoPayload::new);
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
