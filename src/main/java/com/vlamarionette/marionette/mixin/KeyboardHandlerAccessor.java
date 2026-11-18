package com.vlamarionette.marionette.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes vanilla's private key dispatch so injected keys reach Screen/KeyMapping. */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerAccessor {

    @Invoker("keyPress")
    void vla$keyPress(long windowHandle, int action, KeyEvent event);
}
