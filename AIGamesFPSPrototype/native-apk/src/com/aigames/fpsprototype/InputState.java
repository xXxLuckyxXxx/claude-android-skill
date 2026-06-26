package com.aigames.fpsprototype;

/**
 * Thread-safe bridge between the UI touch thread (producer) and the GL render
 * thread (consumer). Movement is a continuous vector; look is an accumulated
 * delta; fire is a one-shot request. The renderer also publishes the game-over
 * flag here so the view can turn any tap into a restart.
 */
public class InputState {
    private float moveX, moveY;     // continuous, range [-1, 1]
    private float lookDX, lookDY;   // accumulated pixels since last consume
    private boolean fireRequested;
    private boolean fireHeld;            // fire button currently down (for full-auto weapons)
    private boolean switchRequested;     // one-shot weapon switch
    private boolean aimToggleRequested;  // one-shot aim-down-sights toggle
    private boolean jumpRequested;       // one-shot jump
    private boolean interactRequested;   // one-shot door interact
    private boolean doorInRange;         // published by the renderer: a door is within reach
    private boolean menuMode;            // published by the renderer: a menu (hub/summary) is open
    private boolean menuTapRequested;    // one-shot menu tap, in screen pixels
    private float menuTapX, menuTapY;

    public synchronized void setMove(float x, float y) { moveX = x; moveY = y; }
    public synchronized void addLook(float dx, float dy) { lookDX += dx; lookDY += dy; }
    public synchronized void requestFire() { fireRequested = true; }
    public synchronized void requestSwitch() { switchRequested = true; }
    public synchronized void requestAimToggle() { aimToggleRequested = true; }
    public synchronized void requestJump() { jumpRequested = true; }
    public synchronized void requestInteract() { interactRequested = true; }
    public synchronized void setFireHeld(boolean v) { fireHeld = v; }
    public synchronized boolean isFireHeld() { return fireHeld; }
    public synchronized void setDoorInRange(boolean v) { doorInRange = v; }
    public synchronized boolean isDoorInRange() { return doorInRange; }

    public synchronized float moveX() { return moveX; }
    public synchronized float moveY() { return moveY; }

    /** Writes accumulated look delta into out[0]=dx, out[1]=dy and resets it. */
    public synchronized void consumeLook(float[] out) {
        out[0] = lookDX;
        out[1] = lookDY;
        lookDX = 0f;
        lookDY = 0f;
    }

    /** Returns true exactly once per fire request. */
    public synchronized boolean consumeFire() {
        boolean f = fireRequested;
        fireRequested = false;
        return f;
    }

    /** Returns true exactly once per weapon-switch request. */
    public synchronized boolean consumeSwitch() {
        boolean s = switchRequested;
        switchRequested = false;
        return s;
    }

    /** Returns true exactly once per aim-toggle request. */
    public synchronized boolean consumeAimToggle() {
        boolean a = aimToggleRequested;
        aimToggleRequested = false;
        return a;
    }

    /** Returns true exactly once per jump request. */
    public synchronized boolean consumeJump() {
        boolean j = jumpRequested;
        jumpRequested = false;
        return j;
    }

    /** Returns true exactly once per door-interact request. */
    public synchronized boolean consumeInteract() {
        boolean i = interactRequested;
        interactRequested = false;
        return i;
    }

    // Published by the renderer; read by the view to route taps to the menu instead of gameplay.
    public synchronized void setMenuMode(boolean v) { menuMode = v; }
    public synchronized boolean isMenuMode() { return menuMode; }

    /** One-shot menu tap at screen pixels. */
    public synchronized void requestMenuTap(float x, float y) {
        menuTapRequested = true; menuTapX = x; menuTapY = y;
    }

    /** Writes the pending menu tap into out[0]=x, out[1]=y; returns true exactly once per tap. */
    public synchronized boolean consumeMenuTap(float[] out) {
        if (!menuTapRequested) return false;
        out[0] = menuTapX; out[1] = menuTapY;
        menuTapRequested = false;
        return true;
    }
}
