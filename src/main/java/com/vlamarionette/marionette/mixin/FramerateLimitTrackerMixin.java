package com.vlamarionette.marionette.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Raises the minimized-window frame limit from the vanilla 10 FPS to 30 FPS.
 *
 * <p>Capture samples on render frames at 5 Hz. At 10 FPS a 200 ms sample often
 * slips to the next frame (300 ms), so measured cadence drops to about 4.1 Hz.
 * 30 FPS gives six frames per sample window; normal window focus, AFK throttling
 * and every other limit are unchanged.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {

    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void vla$raiseMinimizedLimit(CallbackInfoReturnable<Integer> cir) {
        FramerateLimitTracker tracker = (FramerateLimitTracker) (Object) this;
        if (tracker.getThrottleReason()
                == FramerateLimitTracker.FramerateThrottleReason.WINDOW_ICONIFIED
                && cir.getReturnValue() < 30) {
            cir.setReturnValue(30);
        }
    }
}
