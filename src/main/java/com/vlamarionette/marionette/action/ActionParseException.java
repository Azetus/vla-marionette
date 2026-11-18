package com.vlamarionette.marionette.action;

/** Parse/validation failure mapped to an ActionReceipt rejection reason. */
public final class ActionParseException extends Exception {

    private final String reason;

    public ActionParseException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
