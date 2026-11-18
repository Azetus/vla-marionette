package com.vlamarionette.marionette.capture;

/**
 * Last known capture state, suitable for inclusion in the periodic State
 * message. {@code reason} is {@code null} while valid.
 */
public record CaptureStatus(boolean valid, String reason, long lastObsId, long lastFrameTick, int encodeFailures) {

    public static final String NO_LEVEL = "no_level";
    public static final String BAD_SIZE = "bad_size";
    public static final String NO_TARGET = "no_target";
    public static final String NOT_READY = "not_ready";
    public static final String ENCODE_ERROR = "encode_error";

    public static CaptureStatus invalid(String reason, long lastObsId, long lastFrameTick, int encodeFailures) {
        return new CaptureStatus(false, reason, lastObsId, lastFrameTick, encodeFailures);
    }

    public static CaptureStatus valid(long obsId, long tick, int encodeFailures) {
        return new CaptureStatus(true, null, obsId, tick, encodeFailures);
    }
}
