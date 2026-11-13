package net.vg.codex.validation.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Native window hints avoid activation; no operating-system input or focus commands are sent. */
@Mixin(Window.class)
abstract class TestWindowFocusMixin {
    //? if >=26.3 {
    /*@Inject(method="createWindow",at=@At("HEAD"))
    private void codexTests$noActivation(CallbackInfoReturnable<Long> ci) {
        if(Boolean.getBoolean("codex.test.client"))org.lwjgl.sdl.SDLHints.SDL_SetHint(org.lwjgl.sdl.SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN,"0");
    }
    *///?} elif >=26.1 {
    /*@Inject(method="createGlfwWindow",at=@At(value="INVOKE",target="Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private static void codexTests$noActivation(CallbackInfoReturnable<Long> ci) {
        if(Boolean.getBoolean("codex.test.client")) {
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_FOCUSED,org.lwjgl.glfw.GLFW.GLFW_FALSE);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_FOCUS_ON_SHOW,org.lwjgl.glfw.GLFW.GLFW_FALSE);
        }
    }
    *///?} else {
    @Inject(method="<init>",at=@At(value="INVOKE",target="Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private void codexTests$noActivation(CallbackInfo ci) {
        if(Boolean.getBoolean("codex.test.client")) {
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_FOCUSED,org.lwjgl.glfw.GLFW.GLFW_FALSE);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_FOCUS_ON_SHOW,org.lwjgl.glfw.GLFW.GLFW_FALSE);
        }
    }
    //?}
}
