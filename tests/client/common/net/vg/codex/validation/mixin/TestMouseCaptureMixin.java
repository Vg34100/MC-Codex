package net.vg.codex.validation.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test clients use game-local callbacks; Minecraft must not capture the desktop pointer. */
@Mixin(MouseHandler.class)
abstract class TestMouseCaptureMixin {
    @Inject(method="grabMouse",at=@At("HEAD"),cancellable=true)
    private void codexTests$noDesktopCapture(CallbackInfo ci) {
        if(Boolean.getBoolean("codex.test.client"))ci.cancel();
    }
}
