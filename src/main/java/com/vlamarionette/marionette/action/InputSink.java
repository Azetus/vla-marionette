package com.vlamarionette.marionette.action;

/**
 * Input backend used by the scheduler. Implementations may touch Minecraft and
 * therefore must only be called on the client thread.
 */
public interface InputSink {

    void applyFrame(ActionFrame frame);

    void flushMouse();

    void releaseAll();
}
