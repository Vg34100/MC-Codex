package net.vg.codex.mixin.client;
import net.vg.codex.client.advancements.ClientQuests;
//? if >=1.21 {
import net.minecraft.client.DeltaTracker;
//?}
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Gui.class)
abstract class QuestHudMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    //? if <1.21 {
    /*private void codex$tracker(GuiGraphicsExtractor graphics, float delta, CallbackInfo ci) { ClientQuests.render(graphics); }
    *///?} else {
    private void codex$tracker(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) { ClientQuests.render(graphics); }
    //?}
}
