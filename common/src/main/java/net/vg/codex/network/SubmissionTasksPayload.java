package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;
import java.util.List;

/** Submission task definitions and this player's consumed totals for the normal quest UI. */
public record SubmissionTasksPayload(List<Task> tasks) implements CustomPacketPayload {
    public static final Type<SubmissionTasksPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "submission_tasks"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SubmissionTasksPayload> STREAM_CODEC =
            Task.STREAM_CODEC.apply(ByteBufCodecs.list()).map(SubmissionTasksPayload::new, SubmissionTasksPayload::tasks);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public record Task(Identifier advancementId, String criterion, List<ItemProgress> items) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Task> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Task::advancementId,
                ByteBufCodecs.stringUtf8(256), Task::criterion,
                ItemProgress.STREAM_CODEC.apply(ByteBufCodecs.list()), Task::items,
                Task::new);
    }
    public record ItemProgress(Identifier itemId, int required, int submitted) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ItemProgress> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, ItemProgress::itemId,
                ByteBufCodecs.VAR_INT, ItemProgress::required,
                ByteBufCodecs.VAR_INT, ItemProgress::submitted,
                ItemProgress::new);
    }
}
