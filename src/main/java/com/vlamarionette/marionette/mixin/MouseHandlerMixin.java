package com.vlamarionette.marionette.mixin;

import com.vlamarionette.marionette.action.InputInjection;
import com.vlamarionette.marionette.action.InputLock;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Blocks physical mouse input while the automation lock is armed. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void vla$lockMove(long window, double xpos, double ypos, CallbackInfo ci) {
        if (!InputInjection.active() && InputLock.shouldBlockPhysical(false)) {
            ci.cancel();
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void vla$lockButton(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (InputInjection.active()) {
            return;
        }
        InputLock.notePhysicalButton(buttonInfo.button(), action);
        if (InputLock.shouldBlockPhysical(false)) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void vla$lockScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (!InputInjection.active() && InputLock.shouldBlockPhysical(false)) {
            ci.cancel();
        }
    }
}
