package com.vlamarionette.marionette.query;

/**
 * Shape/validation failure for an inbound query or chat request.
 *
 * <p>The request id is null when it could not be parsed, so the error response
 * can still be sent with an uncorrelated id.
 */
public final class QueryRequestException extends Exception {

    private final Integer requestId;
    private final String reason;

    public QueryRequestException(Integer requestId, String reason, String message) {
        super(message);
        this.requestId = requestId;
        this.reason = reason;
    }

    public Integer requestId() {
        return requestId;
    }

    public String reason() {
        return reason;
    }
}
