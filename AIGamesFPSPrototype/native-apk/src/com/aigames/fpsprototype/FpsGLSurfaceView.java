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
    // Zone assignment (decided once, at touch-down, like the gameplay move/look split): left half = pan,
    // right half = camera (needs two fingers there for angle+zoom).
    private int edPanId = -1;
    private int edCamId1 = -1, edCamId2 = -1;

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

    /** Editor mode: assign each new pointer to a zone (mirrors the gameplay move/look split -- left half =
     *  pan, right half = camera), publish the two zone channels every event, and turn a clean short press
     *  into a tap regardless of zone. */
    private void handleEditTouch(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                edTapId = e.getPointerId(0);
                edDownX = e.getX(0); edDownY = e.getY(0);
                edDownTime = e.getEventTime(); edMoved = false;
                assignEditZone(e.getPointerId(0), e.getX(0));
                break;
            case MotionEvent.ACTION_POINTER_DOWN: {
                edMoved = true;   // a second finger arrived -> whatever the first finger was doing, it's not a tap
                int idx = e.getActionIndex();
                assignEditZone(e.getPointerId(idx), e.getX(idx));
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
                releaseEditZone(e.getPointerId(e.getActionIndex()));
                break;
            case MotionEvent.ACTION_UP:
                if (edTapId != -1 && !edMoved && (e.getEventTime() - edDownTime) < 260) {
                    input.requestMenuTap(edDownX, edDownY);   // a clean tap: routed to UI / select, any zone
                }
                edTapId = -1;
                edPanId = -1; edCamId1 = -1; edCamId2 = -1;   // the whole gesture just ended
                break;
            case MotionEvent.ACTION_CANCEL:
                edTapId = -1; edMoved = true;
                edPanId = -1; edCamId1 = -1; edCamId2 = -1;
                break;
        }
        // publish the current position of whichever pointer(s) own each zone this event
        boolean panActive = false; float panX = 0, panY = 0;
        if (edPanId != -1) {
            int idx = e.findPointerIndex(edPanId);
            if (idx >= 0) { panActive = true; panX = e.getX(idx); panY = e.getY(idx); } else edPanId = -1;
        }
        int camCount = 0; float camAx = 0, camAy = 0, camBx = 0, camBy = 0;
        if (edCamId1 != -1) {
            int idx = e.findPointerIndex(edCamId1);
            if (idx >= 0) { camAx = e.getX(idx); camAy = e.getY(idx); camCount++; } else edCamId1 = -1;
        }
        if (edCamId2 != -1) {
            int idx = e.findPointerIndex(edCamId2);
            if (idx >= 0) { camBx = e.getX(idx); camBy = e.getY(idx); camCount++; } else edCamId2 = -1;
        }
        input.setEditPan(panActive, panX, panY);
        input.setEditCam(camCount, camAx, camAy, camBx, camBy);
    }

    /** A newly-touched-down pointer joins the pan zone (left half, max 1) or the camera zone (right half,
     *  max 2), decided once by its starting X -- it keeps that role for its whole gesture even if it later
     *  drifts across the midline. */
    private void assignEditZone(int pid, float x) {
        if (x < getWidth() * 0.5f) {
            if (edPanId == -1) edPanId = pid;
        } else {
            if (edCamId1 == -1) edCamId1 = pid;
            else if (edCamId2 == -1) edCamId2 = pid;
        }
    }
    private void releaseEditZone(int pid) {
        if (pid == edPanId) edPanId = -1;
        else if (pid == edCamId1) edCamId1 = -1;
        else if (pid == edCamId2) edCamId2 = -1;
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
