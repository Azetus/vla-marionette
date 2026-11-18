package com.vlamarionette.marionette.mixin;

import com.vlamarionette.marionette.action.InputInjection;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Injected mouse events must accumulate and flush even when the window is
 * unfocused/minimized; vanilla gates both on isWindowActive().
 */
@Mixin(Minecraft.class)
public abstract class MinecraftFocusMixin {

    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void vla$allowInjectedInput(CallbackInfoReturnable<Boolean> cir) {
        if (InputInjection.active()) {
            cir.setReturnValue(true);
        }
    }
}
