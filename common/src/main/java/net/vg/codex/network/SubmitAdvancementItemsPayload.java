package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;

import java.util.Optional;

/** Requests a server-authoritative partial submission for one Codex submission criterion. */
public record SubmitAdvancementItemsPayload(Identifier advancementId, String criterion, Optional<Identifier> itemId) implements CustomPacketPayload {
    public static final Type<SubmitAdvancementItemsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "submit_advancement_items"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SubmitAdvancementItemsPayload> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, SubmitAdvancementItemsPayload::advancementId,
            ByteBufCodecs.stringUtf8(256), SubmitAdvancementItemsPayload::criterion,
            ByteBufCodecs.optional(Identifier.STREAM_CODEC), SubmitAdvancementItemsPayload::itemId,
            SubmitAdvancementItemsPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
