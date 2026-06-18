package com.aigames.fpsprototype;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

/**
 * GLSurfaceView that turns multi-touch into FPS controls:
 *   left  half of the screen = virtual move stick (strafe / forward),
 *   right half of the screen = look (yaw / pitch).
 * Each gesture is owned by the first finger that lands in its half.
 */
public class FpsGLSurfaceView extends GLSurfaceView {

    private static final float MOVE_RADIUS = 140f;   // px to reach full tilt

    private final InputState input = new InputState();

    private int movePointerId = -1;
    private int lookPointerId = -1;
    private float moveStartX, moveStartY;
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
                        float dx = clamp((x - moveStartX) / MOVE_RADIUS, -1f, 1f);
                        float dy = clamp((y - moveStartY) / MOVE_RADIUS, -1f, 1f);
                        input.setMove(dx, -dy);          // up on screen = forward
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
                }
                break;
            }
        }
        return true;
    }

    private void assign(int pid, float x, float y) {
        boolean leftHalf = x < getWidth() * 0.5f;
        if (leftHalf && movePointerId == -1) {
            movePointerId = pid;
            moveStartX = x;
            moveStartY = y;
            input.setMove(0f, 0f);
        } else if (!leftHalf && lookPointerId == -1) {
            lookPointerId = pid;
            lookLastX = x;
            lookLastY = y;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
