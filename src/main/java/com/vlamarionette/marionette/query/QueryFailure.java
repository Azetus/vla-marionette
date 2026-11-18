package com.vlamarionette.marionette.query;

/** Main-thread read failure mapped to a query response reason. */
public final class QueryFailure extends Exception {

    private final String reason;

    public QueryFailure(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
