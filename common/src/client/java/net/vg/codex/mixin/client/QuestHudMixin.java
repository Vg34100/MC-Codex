package net.vg.codex.mixin.client;
import net.vg.codex.client.advancements.ClientQuests;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Gui.class)
abstract class QuestHudMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void codex$tracker(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) { ClientQuests.render(graphics); }
}
