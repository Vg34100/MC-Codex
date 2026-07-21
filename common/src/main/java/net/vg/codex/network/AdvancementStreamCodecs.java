package net.vg.codex.network;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Vanilla's own {@code AdvancementHolder.STREAM_CODEC} (and {@code Advancement.STREAM_CODEC} it
 * wraps) deliberately omits criteria and rewards when writing to the network - confirmed via
 * {@code Advancement.write(RegistryFriendlyByteBuf)} bytecode, which only serializes parent,
 * display, requirements and the telemetry flag. That's correct for vanilla's own player-facing
 * sync (a normal client only needs display info + progress, never the trigger conditions or
 * reward contents), but it silently dropped criteria/rewards for our editor payloads, which
 * genuinely need the full data. This wraps the JSON {@link Advancement#CODEC} (which does include
 * everything) for network transport instead of using vanilla's stripped-down wire format.
 */
final class AdvancementStreamCodecs {
    private static final StreamCodec<RegistryFriendlyByteBuf, Advancement> ADVANCEMENT_STREAM_CODEC =
            ByteBufCodecs.fromCodecWithRegistries(Advancement.CODEC);

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
