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

    // ===================== in-game editor: two-slot multi-touch channel =====================
    // Two pointer slots, filled in touch-down order (not by screen-half). The routing rule
    // (routeEditGesture) then decides each frame: any TWO active fingers drive the camera (pinch=zoom,
    // twist=yaw, vertical drift=tilt) no matter where they are -- so one-left + one-right zooms just like
    // two-on-the-right. A lone finger pans/drags only if it went down in the left half; a lone right
    // finger is a no-op (it would otherwise fight object selection). Discrete taps still come via
    // requestMenuTap.
    private boolean editMode;
    private boolean edPanActive;                 // single finger driving pan/drag this frame
    private float edPanX, edPanY;
    private int edCamCount;                      // camera fingers this frame: 0 or 2
    private float edCamAx, edCamAy, edCamBx, edCamBy;

    /**
     * Pure routing rule for the editor's two pointer slots -> pan channel + camera channel. Static and
     * side-effect-free so it is unit-testable off-device (no Android/GL). Slot order is the touch-down
     * order; {@code leftN} is whether that finger went DOWN in the left screen half.
     *   - two active fingers  -> camera pair (count=2, A=slot0, B=slot1), pan inactive;
     *   - one active finger   -> pan active IFF it went down in the left half, camera count=0;
     *   - none                -> idle.
     * panOut = {active(0/1), x, y};  camOut = {count, ax, ay, bx, by}.
     */
    static void routeEditGesture(boolean a0, float x0, float y0, boolean left0,
                                 boolean a1, float x1, float y1, boolean left1,
                                 float[] panOut, float[] camOut) {
        panOut[0] = 0f; panOut[1] = 0f; panOut[2] = 0f;
        camOut[0] = 0f; camOut[1] = 0f; camOut[2] = 0f; camOut[3] = 0f; camOut[4] = 0f;
        if (a0 && a1) {                       // any two fingers -> camera pinch/twist/tilt, wherever they are
            camOut[0] = 2f;
            camOut[1] = x0; camOut[2] = y0;
            camOut[3] = x1; camOut[4] = y1;
        } else if (a0) {                      // lone finger: pan only if it started on the left
            if (left0) { panOut[0] = 1f; panOut[1] = x0; panOut[2] = y0; }
        } else if (a1) {
            if (left1) { panOut[0] = 1f; panOut[1] = x1; panOut[2] = y1; }
        }
    }

    public synchronized void setEditMode(boolean v) { editMode = v; }
    public synchronized boolean isEditMode() { return editMode; }

    /** View publishes the left-zone (pan) pointer. */
    public synchronized void setEditPan(boolean active, float x, float y) {
        edPanActive = active; edPanX = x; edPanY = y;
    }

    /** Renderer reads the pan pointer: out = {active(0/1), x, y}. */
    public synchronized void getEditPan(float[] out) {
        out[0] = edPanActive ? 1f : 0f; out[1] = edPanX; out[2] = edPanY;
    }

    /** View publishes the right-zone (camera) pointer(s). */
    public synchronized void setEditCam(int count, float ax, float ay, float bx, float by) {
        edCamCount = count; edCamAx = ax; edCamAy = ay; edCamBx = bx; edCamBy = by;
    }

    /** Renderer reads the camera pointers: out = {count, ax, ay, bx, by}. */
    public synchronized void getEditCam(float[] out) {
        out[0] = edCamCount; out[1] = edCamAx; out[2] = edCamAy; out[3] = edCamBx; out[4] = edCamBy;
    }
}
