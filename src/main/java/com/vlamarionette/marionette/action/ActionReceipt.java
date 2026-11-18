package com.vlamarionette.marionette.action;

import com.google.gson.JsonObject;

/** Builds ActionReceipt messages; Transport adds session_id. */
public final class ActionReceipt {

    private ActionReceipt() {
    }

    public static JsonObject received(int seq) {
        return base(seq, "received");
    }

    public static JsonObject applied(int seq, long tick) {
        JsonObject receipt = base(seq, "applied");
        receipt.addProperty("tick", tick);
        return receipt;
    }

    public static JsonObject ended(int seq, long tick) {
        JsonObject receipt = base(seq, "ended");
        receipt.addProperty("tick", tick);
        return receipt;
    }

    public static JsonObject rejected(int seq, String reason) {
        JsonObject receipt = base(seq, "rejected");
        receipt.addProperty("reason", reason);
        return receipt;
    }

    public static JsonObject interrupted(int seq, String reason, long tick) {
        JsonObject receipt = base(seq, "interrupted");
        receipt.addProperty("reason", reason);
        receipt.addProperty("tick", tick);
        return receipt;
    }

    private static JsonObject base(int seq, String status) {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("type", "ActionReceipt");
        receipt.addProperty("seq", seq);
        receipt.addProperty("status", status);
        return receipt;
    }
}
