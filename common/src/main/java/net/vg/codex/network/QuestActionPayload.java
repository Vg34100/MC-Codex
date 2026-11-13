package net.vg.codex.network;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
public record QuestActionPayload(Identifier id, String action, String value) implements CustomPacketPayload {
    public static final Type<QuestActionPayload> TYPE = new Type<>(Identifier.parse("codex:quest_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, QuestActionPayload> STREAM_CODEC = StreamCodec.composite(
        Identifier.STREAM_CODEC, QuestActionPayload::id, ByteBufCodecs.stringUtf8(24), QuestActionPayload::action,
        ByteBufCodecs.stringUtf8(128), QuestActionPayload::value, QuestActionPayload::new);
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
