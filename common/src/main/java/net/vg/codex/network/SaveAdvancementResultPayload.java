package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;

/** Sent only after the edited datapack has finished reloading on the server. */
public record SaveAdvancementResultPayload(int saved, int failed) implements CustomPacketPayload {
    public static final Type<SaveAdvancementResultPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "save_advancement_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SaveAdvancementResultPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SaveAdvancementResultPayload::saved,
            ByteBufCodecs.VAR_INT, SaveAdvancementResultPayload::failed,
            SaveAdvancementResultPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
