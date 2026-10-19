package net.vg.codex.network;

import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.vg.codex.ModMain;

import java.util.List;

/**
 * Server -> client: sent in reply to /codex advancements edit with the current advancement tree,
 * plus each advancement's real progress (see {@link ProgressEntry}) - the editor's local tree has
 * no player-progress tracking of its own by design (it shows every advancement including
 * hidden/locked ones the player may never have discovered), and cross-referencing view mode's own
 * live data instead only works for advancements the player has actually revealed through normal
 * play. The server always has real {@link AdvancementProgress} for every advancement via
 * {@code PlayerAdvancements#getOrStartProgress}, regardless of vanilla's client-visibility rules,
 * so sending it here directly - rather than leaving edit mode's progress display permanently blank
 * for anything not yet discovered - is the only way to show it there at all.
 */
public record OpenAdvancementEditorPayload(List<RawAdvancement> advancements, List<ProgressEntry> progress) implements CustomPacketPayload {
    public static final Type<OpenAdvancementEditorPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ModMain.MOD_ID, "open_advancement_editor"));

    // AdvancementProgress's own network (de)serialization, the same one vanilla's real advancement
    // sync uses. Deliberately incomplete on its own: it only (de)serializes the criteria map, never
    // the `requirements` field getPercent()/getProgressText() both hard-depend on (confirmed via
    // bytecode - AdvancementProgress's own constructor defaults requirements to
    // AdvancementRequirements.EMPTY, whose size() is 0, and getProgressText() has its own
    // "requirements.size() <= 1 -> return null" guard, which fires unconditionally as a result).
    // Vanilla itself only ever needs requirements client-side already attached to the Advancement
    // object it came with; we transport it here explicitly (ProgressEntry#requirements) and call
    // AdvancementProgress#update with it after decoding (see CodexAdvancementsScreen#enterEditMode)
    // to actually finish constructing a working progress object.
    private static final StreamCodec<FriendlyByteBuf, AdvancementProgress> PROGRESS_STREAM_CODEC = StreamCodec.of(
            (buf, progress) -> progress.serializeToNetwork(buf),
            AdvancementProgress::fromNetwork
    );

    // AdvancementRequirements has its own plain FriendlyByteBuf wire format (a network constructor
    // plus a matching write method) - not registry-dependent, so this is safe to decode client-side
    // same as PROGRESS_STREAM_CODEC above.
    private static final StreamCodec<FriendlyByteBuf, AdvancementRequirements> REQUIREMENTS_STREAM_CODEC = StreamCodec.of(
            (buf, requirements) -> requirements.write(buf),
            AdvancementRequirements::new
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenAdvancementEditorPayload> STREAM_CODEC = StreamCodec.composite(
            RawAdvancement.STREAM_CODEC.apply(ByteBufCodecs.list()), OpenAdvancementEditorPayload::advancements,
            ProgressEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), OpenAdvancementEditorPayload::progress,
            OpenAdvancementEditorPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record ProgressEntry(Identifier advancementId, AdvancementProgress progress, AdvancementRequirements requirements) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ProgressEntry> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, ProgressEntry::advancementId,
                PROGRESS_STREAM_CODEC, ProgressEntry::progress,
                REQUIREMENTS_STREAM_CODEC, ProgressEntry::requirements,
                ProgressEntry::new
        );
    }
}
