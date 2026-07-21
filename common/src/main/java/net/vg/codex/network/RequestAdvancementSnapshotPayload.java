package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;

/**
 * Client -> server: requests the full unfiltered advancement snapshot, same data the
 * /codex advancements edit command sends, but triggered by the in-screen edit-mode toggle
 * instead of a chat command.
 */
public record RequestAdvancementSnapshotPayload() implements CustomPacketPayload {
    public static final Type<RequestAdvancementSnapshotPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "request_advancement_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestAdvancementSnapshotPayload> STREAM_CODEC =
            StreamCodec.unit(new RequestAdvancementSnapshotPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
