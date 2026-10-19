package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;

import java.util.List;

/** Client -> server: removes these advancements (and, client-side, their whole subtree) from the editable pack. */
public record DeleteAdvancementsPayload(List<Identifier> ids) implements CustomPacketPayload {
    public static final Type<DeleteAdvancementsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "delete_advancements"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DeleteAdvancementsPayload> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), DeleteAdvancementsPayload::ids,
            DeleteAdvancementsPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
