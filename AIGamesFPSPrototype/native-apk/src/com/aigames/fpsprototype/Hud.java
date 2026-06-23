package com.aigames.fpsprototype;

/**
 * Shared HUD layout in pixels, used by BOTH the touch handler (hit-testing) and
 * the renderer (drawing), so the visible widgets line up with their touch zones.
 * The move stick sits bottom-left, the fire button bottom-right.
 */
final class Hud {
    static final float MOVE_MARGIN = 230f;   // stick center distance from left & bottom
    static final float MOVE_RADIUS = 150f;
    static final float FIRE_MARGIN = 240f;   // fire button center distance from right & bottom
    static final float FIRE_RADIUS = 120f;
    static final float SWITCH_RADIUS = 64f;  // weapon-switch button (up-left of fire)

    static float moveCx()          { return MOVE_MARGIN; }
    static float moveCy(int h)     { return h - MOVE_MARGIN; }
    static float fireCx(int w)     { return w - FIRE_MARGIN; }
    static float fireCy(int h)     { return h - FIRE_MARGIN; }
    static float switchCx(int w)   { return w - FIRE_MARGIN - 185f; }
    static float switchCy(int h)   { return h - FIRE_MARGIN + 55f; }

    private Hud() {}
}
