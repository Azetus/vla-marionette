package com.vlamarionette.marionette.action;

import com.mojang.blaze3d.platform.Window;
import com.vlamarionette.marionette.mixin.KeyboardHandlerAccessor;
import com.vlamarionette.marionette.mixin.MouseHandlerAccessor;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/**
 * Raw input backend.
 *
 * <p>Keyboard and mouse events go through vanilla's own dispatch, so an open
 * Screen receives them exactly like physical input. Mouse displacement is
 * accumulated into a virtual cursor and flushed by the scheduler on
 * ClientTickEvent.Post, before {@code ended}.
 */
public final class McInputInjector implements InputSink {

    private final Set<Integer> heldKeys = new HashSet<>();
    private final Set<Integer> heldButtons = new HashSet<>();

    private double pendingDx;
    private double pendingDy;
    private double pendingScroll;
    private double virtualX;
    private double virtualY;
    private boolean mouseInitialized;

    @Override
    public void applyFrame(ActionFrame frame) {
        for (int key : new HashSet<>(heldKeys)) {
            if (!frame.keys().contains(key)) {
                heldKeys.remove(key);
                int mods = modifiers(heldKeys);
                dispatchKey(key, GLFW.GLFW_RELEASE, mods);
            }
        }
        for (int key : frame.keys()) {
            if (heldKeys.add(key)) {
                int mods = modifiers(heldKeys);
                dispatchKey(key, GLFW.GLFW_PRESS, mods);
            }
        }

        for (int button : new HashSet<>(heldButtons)) {
            if (!frame.buttons().contains(button)) {
                heldButtons.remove(button);
                dispatchButton(button, GLFW.GLFW_RELEASE);
            }
        }
        for (int button : frame.buttons()) {
            if (heldButtons.add(button)) {
                dispatchButton(button, GLFW.GLFW_PRESS);
            }
        }

        pendingDx += frame.mouseDx();
        pendingDy += frame.mouseDy();
        pendingScroll += frame.scroll();
    }

    @Override
    public void flushMouse() {
        if (pendingDx == 0.0 && pendingDy == 0.0 && pendingScroll == 0.0) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        long handle = window.handle();
        MouseHandlerAccessor mouse = (MouseHandlerAccessor) mc.mouseHandler;

        if (!mouseInitialized) {
            virtualX = window.getWidth() / 2.0;
            virtualY = window.getHeight() / 2.0;
            InputInjection.run(() -> mouse.vla$onMove(handle, virtualX, virtualY));
            mouseInitialized = true;
        }

        if (pendingDx != 0.0 || pendingDy != 0.0) {
            virtualX += pendingDx;
            virtualY += pendingDy;
            if (mc.screen != null) {
                virtualX = Math.max(0.0, Math.min(window.getWidth(), virtualX));
                virtualY = Math.max(0.0, Math.min(window.getHeight(), virtualY));
            }
            double x = virtualX;
            double y = virtualY;
            InputInjection.run(() -> mouse.vla$onMove(handle, x, y));
        }

        if (pendingScroll != 0.0) {
            double scroll = pendingScroll;
            InputInjection.run(() -> mouse.vla$onScroll(handle, 0.0, scroll));
        }

        pendingDx = 0.0;
        pendingDy = 0.0;
        pendingScroll = 0.0;

        // Consume through the vanilla render-loop entry point, but at a time we
        // control: this makes ended cover the last frame's mouse input.
        InputInjection.run(mc.mouseHandler::handleAccumulatedMovement);
    }

    @Override
    public void releaseAll() {
        for (int key : new HashSet<>(heldKeys)) {
            int mods = modifiers(heldKeys);
            dispatchKey(key, GLFW.GLFW_RELEASE, mods);
        }
        heldKeys.clear();

        for (int button : new HashSet<>(heldButtons)) {
            dispatchButton(button, GLFW.GLFW_RELEASE);
        }
        heldButtons.clear();

        pendingDx = 0.0;
        pendingDy = 0.0;
        pendingScroll = 0.0;
    }

    private void dispatchKey(int key, int action, int mods) {
        Minecraft mc = Minecraft.getInstance();
        KeyboardHandlerAccessor keyboard = (KeyboardHandlerAccessor) mc.keyboardHandler;
        long handle = mc.getWindow().handle();
        KeyEvent event = new KeyEvent(key, 0, mods);
        InputInjection.run(() -> keyboard.vla$keyPress(handle, action, event));
    }

    private void dispatchButton(int button, int action) {
        Minecraft mc = Minecraft.getInstance();
        MouseHandlerAccessor mouse = (MouseHandlerAccessor) mc.mouseHandler;
        long handle = mc.getWindow().handle();
        MouseButtonInfo info = new MouseButtonInfo(button, modifiers(heldKeys));
        InputInjection.run(() -> mouse.vla$onButton(handle, info, action));
    }

    private static int modifiers(Set<Integer> keys) {
        int mods = 0;
        if (keys.contains(GLFW.GLFW_KEY_LEFT_SHIFT) || keys.contains(GLFW.GLFW_KEY_RIGHT_SHIFT)) {
            mods |= 1;
        }
        if (keys.contains(GLFW.GLFW_KEY_LEFT_CONTROL) || keys.contains(GLFW.GLFW_KEY_RIGHT_CONTROL)) {
            mods |= 2;
        }
        if (keys.contains(GLFW.GLFW_KEY_LEFT_ALT) || keys.contains(GLFW.GLFW_KEY_RIGHT_ALT)) {
            mods |= 4;
        }
        return mods;
    }
}
