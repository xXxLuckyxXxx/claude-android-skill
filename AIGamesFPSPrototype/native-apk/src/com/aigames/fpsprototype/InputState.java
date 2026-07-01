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

    private boolean hubMenuRequested;            // one-shot: return to the hub/main menu from gameplay
    public synchronized void requestHubMenu() { hubMenuRequested = true; }
    public synchronized boolean consumeHubMenu() {
        boolean h = hubMenuRequested;
        hubMenuRequested = false;
        return h;
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

    private boolean edDoubleTapRequested; private float edDoubleTapX, edDoubleTapY;
    /** One-shot editor double-tap at screen pixels (deep-edit an object: house -> interior view). */
    public synchronized void requestEditDoubleTap(float x, float y) { edDoubleTapRequested = true; edDoubleTapX = x; edDoubleTapY = y; }
    public synchronized boolean consumeEditDoubleTap(float[] out) {
        if (!edDoubleTapRequested) return false;
        out[0] = edDoubleTapX; out[1] = edDoubleTapY;
        edDoubleTapRequested = false;
        return true;
    }

    // ===================== in-game editor: stick + right-hand multi-touch =====================
    // Like a real mobile FPS: the LEFT side is a floating movement stick (translates the camera over the
    // town), and the RIGHT side handles the rest -- one finger drags a selected object, two fingers
    // pinch=zoom / twist=yaw / vertical-drift=tilt. Discrete taps (select/place) still come via
    // requestMenuTap. Three published channels: stick, pan (right 1-finger object drag), cam (right 2).
    private boolean editMode;
    private boolean edStickActive;               // left move stick engaged this frame
    private float edStickX, edStickY;            // stick deflection, [-1,1], x=screen-right, y=screen-up
    private float edStickOx, edStickOy;          // floating stick origin (finger-down point), screen px, for drawing
    private boolean edPanActive;                 // right single finger driving object drag this frame
    private float edPanX, edPanY;
    private int edCamCount;                      // right camera fingers this frame: 0 or 2
    private float edCamAx, edCamAy, edCamBx, edCamBy;

    /**
     * Pure routing rule for the editor's RIGHT-hand pointer slots -> pan channel + camera channel. Static
     * and side-effect-free so it is unit-testable off-device (no Android/GL). Slot order is touch-down order.
     *   - two active fingers  -> camera pair (count=2, A=slot0, B=slot1), pan inactive;
     *   - one active finger    -> pan active (drags the selected object), camera count=0;
     *   - none                 -> idle.
     * panOut = {active(0/1), x, y};  camOut = {count, ax, ay, bx, by}.
     */
    static void routeEditRight(boolean a0, float x0, float y0,
                               boolean a1, float x1, float y1,
                               float[] panOut, float[] camOut) {
        panOut[0] = 0f; panOut[1] = 0f; panOut[2] = 0f;
        camOut[0] = 0f; camOut[1] = 0f; camOut[2] = 0f; camOut[3] = 0f; camOut[4] = 0f;
        if (a0 && a1) {                       // two right fingers -> camera pinch/twist/tilt
            camOut[0] = 2f;
            camOut[1] = x0; camOut[2] = y0;
            camOut[3] = x1; camOut[4] = y1;
        } else if (a0) {                      // one right finger -> object drag
            panOut[0] = 1f; panOut[1] = x0; panOut[2] = y0;
        } else if (a1) {
            panOut[0] = 1f; panOut[1] = x1; panOut[2] = y1;
        }
    }

    /**
     * Floating-stick deflection from its origin: out = {x, y} where x is screen-right (+) and y is
     * screen-UP (+), each in [-1,1] with the overall magnitude clamped to 1. Pure + unit-testable.
     */
    static void stickVector(float curX, float curY, float originX, float originY, float radius, float[] out) {
        float dx = (curX - originX) / radius;
        float dy = -(curY - originY) / radius;         // screen y grows downward -> negate so up is +
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1f) { dx /= len; dy /= len; }
        out[0] = dx; out[1] = dy;
    }

    public synchronized void setEditMode(boolean v) { editMode = v; }
    public synchronized boolean isEditMode() { return editMode; }

    // The renderer publishes the vertical band (screen px) that is clear of editor UI (top bar/palette
    // above, action bar/hint below); the view only lets a finger become the move stick inside this band,
    // so a press on a button is never swallowed by the stick.
    private float edStickMinY = 0f, edStickMaxY = 1e9f;
    public synchronized void setEditStickBounds(float minY, float maxY) { edStickMinY = minY; edStickMaxY = maxY; }
    public synchronized void getEditStickBounds(float[] out) { out[0] = edStickMinY; out[1] = edStickMaxY; }

    /** View publishes the left move stick: deflection (mx,my in [-1,1]) + its floating origin (px). */
    public synchronized void setEditStick(boolean active, float mx, float my, float ox, float oy) {
        edStickActive = active; edStickX = mx; edStickY = my; edStickOx = ox; edStickOy = oy;
    }

    /** Renderer reads the stick: out = {active(0/1), mx, my, originX, originY}. */
    public synchronized void getEditStick(float[] out) {
        out[0] = edStickActive ? 1f : 0f; out[1] = edStickX; out[2] = edStickY; out[3] = edStickOx; out[4] = edStickOy;
    }

    /** View publishes the right single-finger (object drag) pointer. */
    public synchronized void setEditPan(boolean active, float x, float y) {
        edPanActive = active; edPanX = x; edPanY = y;
    }

    /** Renderer reads the pan pointer: out = {active(0/1), x, y}. */
    public synchronized void getEditPan(float[] out) {
        out[0] = edPanActive ? 1f : 0f; out[1] = edPanX; out[2] = edPanY;
    }

    /** View publishes the right two-finger (camera) pointer(s). */
    public synchronized void setEditCam(int count, float ax, float ay, float bx, float by) {
        edCamCount = count; edCamAx = ax; edCamAy = ay; edCamBx = bx; edCamBy = by;
    }

    /** Renderer reads the camera pointers: out = {count, ax, ay, bx, by}. */
    public synchronized void getEditCam(float[] out) {
        out[0] = edCamCount; out[1] = edCamAx; out[2] = edCamAy; out[3] = edCamBx; out[4] = edCamBy;
    }
}
