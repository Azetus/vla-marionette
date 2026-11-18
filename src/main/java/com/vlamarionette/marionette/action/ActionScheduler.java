package com.vlamarionette.marionette.action;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Pure tick-level action state machine.
 *
 * <p>{@link #submit} is called from the network thread and only touches plain
 * state; {@link #onPre}/{@link #onPost}/{@link #release} are called on the
 * client thread and drive the input sink. Receipt ordering is preserved by
 * sending {@code received} inside the same lock that accepts the chunk.
 */
public final class ActionScheduler {

    private final InputSink input;
    private final Consumer<JsonObject> receipts;
    private final Set<Integer> acceptedSeq = new HashSet<>();

    private ActionChunk active;
    private ActionChunk scheduled;

    public ActionScheduler(InputSink input, Consumer<JsonObject> receipts) {
        this.input = input;
        this.receipts = receipts;
    }

    public synchronized SubmitResult submit(ActionChunk chunk, long currentTick) {
        if (acceptedSeq.contains(chunk.seq())) {
            return new SubmitResult(false, "duplicate");
        }
        if (active != null || scheduled != null) {
            return new SubmitResult(false, "busy");
        }
        if (chunk.startTick() <= currentTick) {
            return new SubmitResult(false, "late");
        }
        acceptedSeq.add(chunk.seq());
        scheduled = chunk;
        receipts.accept(ActionReceipt.received(chunk.seq()));
        return new SubmitResult(true, null);
    }

    public synchronized void onPre(long tick) {
        if (active == null && scheduled != null && tick == scheduled.startTick()) {
            active = scheduled;
            scheduled = null;
            receipts.accept(ActionReceipt.applied(active.seq(), tick));
            input.applyFrame(active.frames().get(0));
            return;
        }

        if (active != null) {
            int index = (int) (tick - active.startTick());
            if (index >= 0 && index < active.frames().size()) {
                input.applyFrame(active.frames().get(index));
            }
            return;
        }

        // A gap between chunks is an empty frame: release any held raw input.
        input.releaseAll();
    }

    public synchronized void onPost(long tick) {
        if (active != null) {
            input.flushMouse();
            if (tick + 1 >= active.endTickExclusive()) {
                receipts.accept(ActionReceipt.ended(active.seq(), tick + 1));
                active = null;
            }
        } else {
            input.flushMouse();
        }
    }

    public synchronized void release(long tick, String reason) {
        if (active != null) {
            receipts.accept(ActionReceipt.interrupted(active.seq(), reason, tick));
            active = null;
        }
        if (scheduled != null) {
            receipts.accept(ActionReceipt.interrupted(scheduled.seq(), reason, tick));
            scheduled = null;
        }
        input.releaseAll();
    }

    public synchronized void newSession() {
        resetSession();
        input.releaseAll();
    }

    /** Plain state reset for callers that run before Minecraft is constructed. */
    public synchronized void resetSession() {
        acceptedSeq.clear();
        active = null;
        scheduled = null;
    }

    public synchronized boolean hasAction() {
        return active != null || scheduled != null;
    }

    public record SubmitResult(boolean accepted, String reason) {
    }
}
