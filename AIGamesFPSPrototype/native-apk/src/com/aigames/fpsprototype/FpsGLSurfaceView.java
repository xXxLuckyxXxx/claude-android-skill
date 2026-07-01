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
    // Real-mobile-FPS layout: the LEFT lower area is a floating movement stick (translates the camera);
    // the RIGHT side is up to two pointers (1 = drag selected object, 2 = zoom/rotate/tilt). A finger's
    // role is fixed at touch-down by where it lands.
    private int edStickId = -1;               // pointer owning the left move stick
    private float edStickOx, edStickOy;       // floating stick origin = that finger's down point
    private int edRight0 = -1, edRight1 = -1; // right-hand pointers, in touch-down order

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

    /** Editor mode: the lower-left area is a floating move stick; the right side is up to two pointers
     *  (1 = drag selected object, 2 = zoom/rotate/tilt via InputState.routeEditRight). A clean short press
     *  anywhere still becomes a tap (select / place / UI). */
    private void handleEditTouch(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                edTapId = e.getPointerId(0);
                edDownX = e.getX(0); edDownY = e.getY(0);
                edDownTime = e.getEventTime(); edMoved = false;
                acquireEditPointer(e.getPointerId(0), e.getX(0), e.getY(0));
                break;
            case MotionEvent.ACTION_POINTER_DOWN: {
                edMoved = true;   // a second finger arrived -> whatever the first finger was doing, it's not a tap
                int idx = e.getActionIndex();
                acquireEditPointer(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (edTapId != -1) {
                    int idx = e.findPointerIndex(edTapId);
                    if (idx >= 0) {
                        float dx = e.getX(idx) - edDownX, dy = e.getY(idx) - edDownY;
                        // The stick finger uses a tighter slop, so even a small stick nudge cancels the tap
                        // (otherwise a quick <260ms nudge would also fire a spurious select/place).
                        float slop = (edTapId == edStickId) ? 14f : EDIT_TAP_SLOP;
                        if (dx * dx + dy * dy > slop * slop) edMoved = true;
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                edMoved = true;   // finger count dropping mid-gesture -> not a tap
                releaseEditPointer(e.getPointerId(e.getActionIndex()));
                break;
            case MotionEvent.ACTION_UP:
                if (edTapId != -1 && !edMoved && (e.getEventTime() - edDownTime) < 260) {
                    input.requestMenuTap(edDownX, edDownY);   // a clean tap: routed to UI / select / place
                }
                edTapId = -1;
                edStickId = -1; edRight0 = -1; edRight1 = -1;   // the whole gesture just ended
                break;
            case MotionEvent.ACTION_CANCEL:
                edTapId = -1; edMoved = true;
                edStickId = -1; edRight0 = -1; edRight1 = -1;
                break;
        }

        // --- publish the left move stick (floating origin -> normalized deflection) ---
        if (edStickId != -1) {
            int idx = e.findPointerIndex(edStickId);
            if (idx >= 0) {
                InputState.stickVector(e.getX(idx), e.getY(idx), edStickOx, edStickOy, editStickRadius(), edStickOut);
                input.setEditStick(true, edStickOut[0], edStickOut[1], edStickOx, edStickOy);
            } else { edStickId = -1; input.setEditStick(false, 0, 0, 0, 0); }
        } else {
            input.setEditStick(false, 0, 0, 0, 0);
        }

        // --- publish the right-hand pointers (object drag / camera) ---
        boolean a0 = false; float x0 = 0, y0 = 0;
        if (edRight0 != -1) {
            int idx = e.findPointerIndex(edRight0);
            if (idx >= 0) { a0 = true; x0 = e.getX(idx); y0 = e.getY(idx); } else edRight0 = -1;
        }
        boolean a1 = false; float x1 = 0, y1 = 0;
        if (edRight1 != -1) {
            int idx = e.findPointerIndex(edRight1);
            if (idx >= 0) { a1 = true; x1 = e.getX(idx); y1 = e.getY(idx); } else edRight1 = -1;
        }
        InputState.routeEditRight(a0, x0, y0, a1, x1, y1, edPanOut, edCamOut);
        input.setEditPan(edPanOut[0] != 0f, edPanOut[1], edPanOut[2]);
        input.setEditCam((int) edCamOut[0], edCamOut[1], edCamOut[2], edCamOut[3], edCamOut[4]);
    }

    private final float[] edPanOut = new float[3], edCamOut = new float[5], edStickOut = new float[2], edStickBounds = new float[2];

    /** Floating stick radius: full deflection at ~16% of screen height, so a comfortable thumb throw. */
    private float editStickRadius() { return Math.max(80f, getHeight() * 0.16f); }

    /** A newly-touched-down pointer: if it lands in the left move-stick area -- the left ~44% of width,
     *  inside the UI-clear vertical band the renderer publishes (so a press on the top bar / palette /
     *  bottom action bar is NOT swallowed) -- and the stick is free, it becomes the stick with its down
     *  point as the floating origin; otherwise it fills the first free right-hand slot. */
    private void acquireEditPointer(int pid, float x, float y) {
        input.getEditStickBounds(edStickBounds);
        boolean inStickZone = x < getWidth() * 0.44f && y > edStickBounds[0] && y < edStickBounds[1];
        if (inStickZone && edStickId == -1) {
            edStickId = pid; edStickOx = x; edStickOy = y;
        } else if (edRight0 == -1) { edRight0 = pid; }
        else if (edRight1 == -1) { edRight1 = pid; }
    }
    private void releaseEditPointer(int pid) {
        if (pid == edStickId) edStickId = -1;
        else if (pid == edRight0) edRight0 = -1;
        else if (pid == edRight1) edRight1 = -1;
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
