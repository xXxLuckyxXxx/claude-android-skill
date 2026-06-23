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
    private boolean fireHeld;        // fire button currently down (for full-auto weapons)
    private boolean switchRequested; // one-shot weapon switch
    private boolean gameOver;

    public synchronized void setMove(float x, float y) { moveX = x; moveY = y; }
    public synchronized void addLook(float dx, float dy) { lookDX += dx; lookDY += dy; }
    public synchronized void requestFire() { fireRequested = true; }
    public synchronized void requestSwitch() { switchRequested = true; }
    public synchronized void setFireHeld(boolean v) { fireHeld = v; }
    public synchronized boolean isFireHeld() { return fireHeld; }

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

    // Published by the renderer; read by the view (tap-anywhere-to-restart).
    public synchronized void setGameOver(boolean v) { gameOver = v; }
    public synchronized boolean isGameOver() { return gameOver; }
}
