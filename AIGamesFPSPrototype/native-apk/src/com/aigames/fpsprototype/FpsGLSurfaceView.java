package com.aigames.fpsprototype;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

/**
 * GLSurfaceView that turns multi-touch into FPS controls:
 *   bottom-left  = fixed virtual move stick (strafe / forward),
 *   bottom-right = fire button,
 *   right half   = look (yaw / pitch).
 * Each gesture is owned by the first finger that claims its zone, so move,
 * look and fire can happen simultaneously.
 */
public class FpsGLSurfaceView extends GLSurfaceView {

    private final InputState input = new InputState();

    private int movePointerId = -1;
    private int lookPointerId = -1;
    private int firePointerId = -1;
    private float lookLastX, lookLastY;

    public FpsGLSurfaceView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        setRenderer(new FpsRenderer(input));
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                assign(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int pid = e.getPointerId(i);
                    float x = e.getX(i), y = e.getY(i);
                    if (pid == movePointerId) {
                        updateMove(x, y);
                    } else if (pid == lookPointerId) {
                        input.addLook(x - lookLastX, y - lookLastY);
                        lookLastX = x;
                        lookLastY = y;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int idx = e.getActionIndex();
                int pid = e.getPointerId(idx);
                if (pid == movePointerId) {
                    movePointerId = -1;
                    input.setMove(0f, 0f);
                } else if (pid == lookPointerId) {
                    lookPointerId = -1;
                } else if (pid == firePointerId) {
                    firePointerId = -1;
                }
                break;
            }
        }
        return true;
    }

    private void assign(int pid, float x, float y) {
        int w = getWidth(), h = getHeight();

        // Fire button (bottom-right) takes priority.
        float fdx = x - Hud.fireCx(w), fdy = y - Hud.fireCy(h);
        if (firePointerId == -1 && fdx * fdx + fdy * fdy <= Hud.FIRE_RADIUS * Hud.FIRE_RADIUS) {
            firePointerId = pid;
            input.requestFire();
            return;
        }

        boolean leftHalf = x < w * 0.5f;
        if (leftHalf && movePointerId == -1) {
            movePointerId = pid;
            updateMove(x, y);
        } else if (!leftHalf && lookPointerId == -1) {
            lookPointerId = pid;
            lookLastX = x;
            lookLastY = y;
        }
    }

    private void updateMove(float x, float y) {
        float dx = clamp((x - Hud.moveCx()) / Hud.MOVE_RADIUS, -1f, 1f);
        float dy = clamp((y - Hud.moveCy(getHeight())) / Hud.MOVE_RADIUS, -1f, 1f);
        input.setMove(dx, -dy);                  // up on screen = forward
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
