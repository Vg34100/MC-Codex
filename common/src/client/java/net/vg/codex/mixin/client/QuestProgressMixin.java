package net.vg.codex.mixin.client;

import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.vg.codex.client.advancements.QuestFeedback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe real native progress packets even when the advancement screen is closed. */
@Mixin(ClientAdvancements.class)
abstract class QuestProgressMixin {
    @Inject(method = "update", at = @At("TAIL"))
    private void codex$progress(ClientboundUpdateAdvancementsPacket packet, CallbackInfo ci) {
        QuestFeedback.nativeProgress((ClientAdvancements)(Object)this, packet);
    }
}
