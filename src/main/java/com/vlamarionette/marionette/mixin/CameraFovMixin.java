package com.vlamarionette.marionette.mixin;

import com.vlamarionette.marionette.mc.FovSpec;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces the final world FOV with the fixed value from the startup config.
 *
 * <p>Hooking the return of {@code Camera#calculateFov} pins the projection and
 * the value reported by {@code Camera#getFov()} to the same number, so a frame
 * and its reported FOV cannot disagree. {@code vanilla} mode leaves the method
 * untouched. Panorama mode (the title-screen background) keeps vanilla's 90
 * degrees. HUD/hand projection and the crosshair ray are not affected.
 */
@Mixin(Camera.class)
public abstract class CameraFovMixin {

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void vla$fixedFov(CallbackInfoReturnable<Float> cir) {
        if (!FovSpec.fixed()) {
            return;
        }
        Camera camera = (Camera) (Object) this;
        if (camera.isPanoramicMode()) {
            return;
        }
        cir.setReturnValue(FovSpec.fixedFov());
    }
}
