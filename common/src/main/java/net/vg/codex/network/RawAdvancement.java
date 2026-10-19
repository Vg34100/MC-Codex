package net.vg.codex.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * One advancement transported as raw JSON text rather than a decoded {@code Advancement} object.
 * Some vanilla advancement data uses registry-dependent codecs (e.g. a location criterion's
 * structure-tag field, a {@code HolderSet}) that don't reliably decode with just the client's
 * synced registry access - confirmed by a real crash on adventure/minecraft_trials_edition's
 * "structures" field, which threw regardless of whether the wire format was NBT or JSON. Since the
 * editor only ever needs display info (safe - item registry only) and raw criteria JSON for its
 * list/picker UI, never a fully-typed {@code Advancement}, transporting plain JSON text and only
 * ever decoding through the full {@code Advancement}/{@code Criterion} codecs on the server (which
 * has complete registry access) avoids this whole class of bug rather than patching one field
 * at a time.
 */
public record RawAdvancement(Identifier id, String json) {
    private static final int MAX_JSON_LENGTH = 2_097_152;

    public static final StreamCodec<RegistryFriendlyByteBuf, RawAdvancement> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, RawAdvancement::id,
            ByteBufCodecs.stringUtf8(MAX_JSON_LENGTH), RawAdvancement::json,
            RawAdvancement::new
    );
}
