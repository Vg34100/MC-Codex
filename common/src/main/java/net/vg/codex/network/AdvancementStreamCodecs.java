package net.vg.codex.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;

import java.util.List;

/**
 * Vanilla's own {@code AdvancementHolder.STREAM_CODEC} (and {@code Advancement.STREAM_CODEC} it
 * wraps) deliberately omits criteria and rewards when writing to the network - confirmed via
 * {@code Advancement.write(RegistryFriendlyByteBuf)} bytecode, which only serializes parent,
 * display, requirements and the telemetry flag. That's correct for vanilla's own player-facing
 * sync (a normal client only needs display info + progress, never the trigger conditions or
 * reward contents), but it silently dropped criteria/rewards for our editor payloads, which
 * genuinely need the full data.
 *
 * <p>This wraps the JSON {@link Advancement#CODEC} (which does include everything) for network
 * transport instead - as a JSON string over the wire, not NBT. {@code ByteBufCodecs.
 * fromCodecWithRegistries} (NBT-based) was tried first but broke on real-world data: a location
 * criterion with a bare structure tag string (e.g. {@code "structures": "minecraft:trial_chambers"})
 * decoded fine through JsonOps (which the file-based read/write path already uses successfully)
 * but threw "Not a list" under NBT ops, because whatever single-value-or-list coercion that field's
 * codec relies on isn't ops-agnostic. Keeping the exact same JsonOps path end to end for network
 * transport sidesteps that entirely.
 */
final class AdvancementStreamCodecs {
    private static final Gson GSON = new GsonBuilder().create();
    // Advancement JSON can get large (nested predicates, long item/tag lists) - well past the
    // default FriendlyByteBuf.readUtf() cap, hence the explicit generous max length.
    private static final int MAX_JSON_LENGTH = 2_097_152;

    private static final StreamCodec<RegistryFriendlyByteBuf, Advancement> ADVANCEMENT_STREAM_CODEC = StreamCodec.of(
            (buf, advancement) -> {
                RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, buf.registryAccess());
                JsonElement json = Advancement.CODEC.encodeStart(ops, advancement).getOrThrow();
                buf.writeUtf(GSON.toJson(json), MAX_JSON_LENGTH);
            },
            buf -> {
                JsonElement json = JsonParser.parseString(buf.readUtf(MAX_JSON_LENGTH));
                RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, buf.registryAccess());
                return Advancement.CODEC.parse(ops, json).getOrThrow();
            }
    );

    private static final StreamCodec<RegistryFriendlyByteBuf, AdvancementHolder> HOLDER_STREAM_CODEC =
            StreamCodec.composite(
                    Identifier.STREAM_CODEC, AdvancementHolder::id,
                    ADVANCEMENT_STREAM_CODEC, AdvancementHolder::value,
                    AdvancementHolder::new
            );

    static final StreamCodec<RegistryFriendlyByteBuf, List<AdvancementHolder>> HOLDER_LIST_STREAM_CODEC =
            HOLDER_STREAM_CODEC.apply(ByteBufCodecs.list());

    private AdvancementStreamCodecs() {
    }
}
