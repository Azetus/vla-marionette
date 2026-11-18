package com.vlamarionette.marionette.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes vanilla mouse dispatch so injected mouse events reach Screen/world. */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {

    @Invoker("onMove")
    void vla$onMove(long windowHandle, double xpos, double ypos);

    @Invoker("onButton")
    void vla$onButton(long windowHandle, MouseButtonInfo buttonInfo, int action);

    @Invoker("onScroll")
    void vla$onScroll(long windowHandle, double xOffset, double yOffset);
}
