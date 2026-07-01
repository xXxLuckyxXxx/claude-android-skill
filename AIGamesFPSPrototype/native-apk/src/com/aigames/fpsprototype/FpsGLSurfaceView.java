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
    private int switchPointerId = -1;
    private int aimPointerId = -1;
    private int jumpPointerId = -1;
    private int interactPointerId = -1;
    private float lookLastX, lookLastY;

    public FpsGLSurfaceView(Context context, int build) {
        super(context);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(new MultisampleConfigChooser(4));   // 4x MSAA, safe fallback
        setRenderer(new FpsRenderer(input, build, context));
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    // --- editor touch tracking ---
    private int edTapId = -1;
    private float edDownX, edDownY;
    private long edDownTime;
    private boolean edMoved;
    private static final float EDIT_TAP_SLOP = 26f;   // px travel under which a press counts as a tap
    // Two pointer slots, filled in touch-down order (NOT by screen-half). Any two active fingers drive the
    // camera (pinch/twist/tilt) wherever they are -- one-left-one-right zooms just like two-on-the-right;
    // a lone finger pans/drags only if it went down in the left half. Routing lives in
    // InputState.routeEditGesture; here we just track which pointer owns each slot and its down-half.
    private int edSlot0 = -1, edSlot1 = -1;
    private boolean edSlot0Left, edSlot1Left;   // whether that slot's finger went DOWN in the left half

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (input.isEditMode()) { handleEditTouch(e); return true; }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (input.isMenuMode()) {        // hub / summary: route the tap to the menu
                    int mi = e.getActionIndex();
                    input.requestMenuTap(e.getX(mi), e.getY(mi));
                    break;
                }
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
            case MotionEvent.ACTION_UP: {
                releasePointer(e.getPointerId(e.getActionIndex()));
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                // The whole gesture is cancelled (e.g. system interruption): no
                // per-pointer up events will follow, so release ALL pointers and
                // stop moving — otherwise a latched finger leaves controls stuck.
                movePointerId = -1;
                lookPointerId = -1;
                firePointerId = -1;
                switchPointerId = -1;
                aimPointerId = -1;
                jumpPointerId = -1;
                interactPointerId = -1;
                input.setMove(0f, 0f);
                input.setFireHeld(false);
                break;
            }
        }
        return true;
    }

    /** Editor mode: track up to two pointers in touch-down order, then let InputState.routeEditGesture
     *  decide each event whether they drive the camera (any two fingers) or a lone-left pan/drag. A clean
     *  short press anywhere becomes a tap (select / UI). */
    private void handleEditTouch(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                edTapId = e.getPointerId(0);
                edDownX = e.getX(0); edDownY = e.getY(0);
                edDownTime = e.getEventTime(); edMoved = false;
                acquireEditSlot(e.getPointerId(0), e.getX(0));
                break;
            case MotionEvent.ACTION_POINTER_DOWN: {
                edMoved = true;   // a second finger arrived -> whatever the first finger was doing, it's not a tap
                int idx = e.getActionIndex();
                acquireEditSlot(e.getPointerId(idx), e.getX(idx));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (edTapId != -1) {
                    int idx = e.findPointerIndex(edTapId);
                    if (idx >= 0) {
                        float dx = e.getX(idx) - edDownX, dy = e.getY(idx) - edDownY;
                        if (dx * dx + dy * dy > EDIT_TAP_SLOP * EDIT_TAP_SLOP) edMoved = true;
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                edMoved = true;   // finger count dropping mid-gesture -> not a tap
                releaseEditSlot(e.getPointerId(e.getActionIndex()));
                break;
            case MotionEvent.ACTION_UP:
                if (edTapId != -1 && !edMoved && (e.getEventTime() - edDownTime) < 260) {
                    input.requestMenuTap(edDownX, edDownY);   // a clean tap: routed to UI / select, any zone
                }
                edTapId = -1;
                edSlot0 = -1; edSlot1 = -1;   // the whole gesture just ended
                break;
            case MotionEvent.ACTION_CANCEL:
                edTapId = -1; edMoved = true;
                edSlot0 = -1; edSlot1 = -1;
                break;
        }
        // Resolve each slot's live position (dropping any pointer that has lifted), then route.
        boolean a0 = false; float x0 = 0, y0 = 0;
        if (edSlot0 != -1) {
            int idx = e.findPointerIndex(edSlot0);
            if (idx >= 0) { a0 = true; x0 = e.getX(idx); y0 = e.getY(idx); } else edSlot0 = -1;
        }
        boolean a1 = false; float x1 = 0, y1 = 0;
        if (edSlot1 != -1) {
            int idx = e.findPointerIndex(edSlot1);
            if (idx >= 0) { a1 = true; x1 = e.getX(idx); y1 = e.getY(idx); } else edSlot1 = -1;
        }
        InputState.routeEditGesture(a0, x0, y0, edSlot0Left, a1, x1, y1, edSlot1Left, edPanOut, edCamOut);
        input.setEditPan(edPanOut[0] != 0f, edPanOut[1], edPanOut[2]);
        input.setEditCam((int) edCamOut[0], edCamOut[1], edCamOut[2], edCamOut[3], edCamOut[4]);
    }

    private final float[] edPanOut = new float[3], edCamOut = new float[5];

    /** A newly-touched-down pointer takes the first free slot (slot0 then slot1), recording whether it
     *  went down in the left half. Beyond two fingers, extra pointers are ignored. */
    private void acquireEditSlot(int pid, float x) {
        boolean left = x < getWidth() * 0.5f;
        if (edSlot0 == -1) { edSlot0 = pid; edSlot0Left = left; }
        else if (edSlot1 == -1) { edSlot1 = pid; edSlot1Left = left; }
    }
    private void releaseEditSlot(int pid) {
        if (pid == edSlot0) edSlot0 = -1;
        else if (pid == edSlot1) edSlot1 = -1;
    }

    private void releasePointer(int pid) {
        if (pid == movePointerId) {
            movePointerId = -1;
            input.setMove(0f, 0f);
        } else if (pid == lookPointerId) {
            lookPointerId = -1;
        } else if (pid == firePointerId) {
            firePointerId = -1;
            input.setFireHeld(false);
        } else if (pid == switchPointerId) {
            switchPointerId = -1;
        } else if (pid == aimPointerId) {
            aimPointerId = -1;
        } else if (pid == jumpPointerId) {
            jumpPointerId = -1;
        } else if (pid == interactPointerId) {
            interactPointerId = -1;
        }
    }

    private void assign(int pid, float x, float y) {
        int w = getWidth(), h = getHeight();

        // Fire button (bottom-right) takes priority.
        float fdx = x - Hud.fireCx(w), fdy = y - Hud.fireCy(h);
        if (firePointerId == -1 && fdx * fdx + fdy * fdy <= Hud.FIRE_RADIUS * Hud.FIRE_RADIUS) {
            firePointerId = pid;
            input.setFireHeld(true);
            input.requestFire();
            return;
        }

        // Weapon-switch button (up-left of fire): one tap cycles to the next weapon.
        float sdx = x - Hud.switchCx(w), sdy = y - Hud.switchCy(h);
        if (switchPointerId == -1 && sdx * sdx + sdy * sdy <= Hud.SWITCH_RADIUS * Hud.SWITCH_RADIUS) {
            switchPointerId = pid;
            input.requestSwitch();
            return;
        }

        // Aim toggle (right edge): one tap aims down the sights, another lowers them.
        float adx = x - Hud.aimCx(w), ady = y - Hud.aimCy(h);
        if (aimPointerId == -1 && adx * adx + ady * ady <= Hud.AIM_RADIUS * Hud.AIM_RADIUS) {
            aimPointerId = pid;
            input.requestAimToggle();
            return;
        }

        // Jump button (right edge, below aim).
        float jdx = x - Hud.jumpCx(w), jdy = y - Hud.jumpCy(h);
        if (jumpPointerId == -1 && jdx * jdx + jdy * jdy <= Hud.JUMP_RADIUS * Hud.JUMP_RADIUS) {
            jumpPointerId = pid;
            input.requestJump();
            return;
        }

        // Door prompt (bottom-centre): only a button while a door is in range, else it stays
        // a normal move/look area so there's no dead zone.
        if (input.isDoorInRange()) {
            float idx = x - Hud.interactCx(w), idy = y - Hud.interactCy(h);
            if (interactPointerId == -1 && idx * idx + idy * idy <= Hud.INTERACT_RADIUS * Hud.INTERACT_RADIUS) {
                interactPointerId = pid;
                input.requestInteract();
                return;
            }
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
