package net.vg.codex.network;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;

import java.util.List;

/** Client -> server: the edited/created advancements to write to the pack folder and reload. */
public record SaveAdvancementTreePayload(List<AdvancementHolder> advancements) implements CustomPacketPayload {
    public static final Type<SaveAdvancementTreePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "save_advancement_tree"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SaveAdvancementTreePayload> STREAM_CODEC =
            AdvancementStreamCodecs.HOLDER_LIST_STREAM_CODEC.map(SaveAdvancementTreePayload::new, SaveAdvancementTreePayload::advancements);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
