package com.vlamarionette.marionette.mixin;

import com.vlamarionette.marionette.action.InjectedInputState;
import com.vlamarionette.marionette.action.InputLock;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes vanilla's physical-key polling see injected modifier keys.
 *
 * <p>{@code Minecraft.hasShiftDown()/hasControlDown()/hasAltDown()} read
 * {@code InputConstants.isKeyDown}, which asks GLFW for the physical keyboard
 * state. Injected {@code KeyEvent}s only update {@code KeyMapping}, so a raw
 * frame holding Ctrl would not make Ctrl+Q drop a stack. This mixin ORs the
 * current injected frame's modifiers into those three queries while a bridge
 * session is armed. The injected set is replaced per frame and cleared by
 * {@code McInputInjector.releaseAll()}.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftModifierMixin {

    @Inject(method = "hasShiftDown", at = @At("RETURN"), cancellable = true)
    private void vla$injectedShift(CallbackInfoReturnable<Boolean> cir) {
        vla$bridgeInjectedModifier(cir,
                GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    @Inject(method = "hasControlDown", at = @At("RETURN"), cancellable = true)
    private void vla$injectedControl(CallbackInfoReturnable<Boolean> cir) {
        vla$bridgeInjectedModifier(cir,
                GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    @Inject(method = "hasAltDown", at = @At("RETURN"), cancellable = true)
    private void vla$injectedAlt(CallbackInfoReturnable<Boolean> cir) {
        vla$bridgeInjectedModifier(cir,
                GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    private static void vla$bridgeInjectedModifier(CallbackInfoReturnable<Boolean> cir,
            int left, int right) {
        if (!cir.getReturnValue() && InputLock.isArmed() && InjectedInputState.isDown(left, right)) {
            cir.setReturnValue(true);
        }
    }
}
