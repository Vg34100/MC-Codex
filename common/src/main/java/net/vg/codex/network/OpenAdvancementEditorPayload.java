package net.vg.codex.network;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;

import java.util.List;

/** Server -> client: sent in reply to /codex advancements edit with the current advancement tree. */
public record OpenAdvancementEditorPayload(List<AdvancementHolder> advancements) implements CustomPacketPayload {
    public static final Type<OpenAdvancementEditorPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "open_advancement_editor"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenAdvancementEditorPayload> STREAM_CODEC =
            AdvancementHolder.LIST_STREAM_CODEC.map(OpenAdvancementEditorPayload::new, OpenAdvancementEditorPayload::advancements);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
