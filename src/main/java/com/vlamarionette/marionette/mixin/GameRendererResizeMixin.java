package com.vlamarionette.marionette.mixin;

import com.vlamarionette.marionette.capture.CaptureSpec;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Forces the main render target to the fixed capture size.
 *
 * <p>{@code GameRenderer#resize} is the single entry point that resizes both the
 * main target and the level renderer. Changing its arguments keeps those two
 * consistent; cancelling the method would not.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererResizeMixin {

    @ModifyVariable(method = "resize", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int vla$forceWidth(int width) {
        return CaptureSpec.width();
    }

    @ModifyVariable(method = "resize", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int vla$forceHeight(int height) {
        return CaptureSpec.height();
    }
}
