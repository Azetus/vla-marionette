package com.vlamarionette.marionette.mixin;

import com.vlamarionette.marionette.action.InputInjection;
import com.vlamarionette.marionette.action.InputLock;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks physical keyboard input while the automation lock is armed.
 *
 * <p>Injected events are marked by InputInjection and pass. ESC is always
 * allowed; from the world it enters manual takeover and opens the pause screen.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void vla$lockPhysicalKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (InputInjection.active()) {
            return;
        }
        InputLock.notePhysicalKey(event.key(), action);

        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (action == GLFW.GLFW_PRESS) {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.screen == null) {
                    InputLock.beginTakeover();
                }
            }
            return;
        }

        if (InputLock.shouldBlockPhysical(false)) {
            ci.cancel();
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void vla$lockPhysicalText(long window, CharacterEvent event, CallbackInfo ci) {
        if (!InputInjection.active() && InputLock.shouldBlockPhysical(false)) {
            ci.cancel();
        }
    }
}
