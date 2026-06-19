package com.aigames.fpsprototype;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * First-person GLES 2.0 renderer + a timed shooting-range game: weaving glowing
 * targets, a 30-second round with a HUD countdown, score, and a game-over
 * overlay (tap anywhere to restart). A 3D pass draws the lit arena and targets;
 * a 2D pass draws the HUD (move stick, fire button, crosshair, muzzle flash,
 * 7-segment score + timer, and the game-over screen).
 */
public class FpsRenderer implements GLSurfaceView.Renderer {

    private static final float MOVE_SPEED = 4.5f;     // m/s
    private static final float LOOK_SENS = 0.0045f;   // rad per px
    private static final int STRIDE = 24;             // 6 floats * 4 bytes
    private static final float MUZZLE_TIME = 0.08f;
    private static final float HIT_TIME = 0.25f;
    private static final float FLASH_TIME = 0.15f;
    private static final float RESPAWN_TIME = 1.6f;
    private static final float TARGET_SCALE = 0.7f;
    private static final float ROUND_TIME = 30f;

    private final InputState input;
    private final float[] lookTmp = new float[2];
    private final float[] tmpPos = new float[3];

    private int width = 1, height = 1;

    // 3D program.
    private int prog3, aPos, aNormal, uMVP, uModel, uColor, uChecker, uLightDir;
    // 2D HUD program.
    private int prog2, aP2, uScale2, uOff2, uCol2;

    private FloatBuffer cube, floor, circle, quad;
    private int circleVerts;

    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] model = new float[16];
    private final float[] mvp = new float[16];

    // Camera.
    private float px = 0f, py = 1.6f, pz = 9f;
    private float yaw = 0f, pitch = -0.08f;
    private float recoil = 0f;
    private long lastNanos;
    private float timeAcc = 0f;

    // Combat feedback timers.
    private float muzzleTimer = 0f;
    private float hitTimer = 0f;
    private float flashTimer = 0f;
    private int hitBox = -1;
    private boolean lastShotHit = false;

    // Game state.
    private int score = 0;
    private float roundTime = ROUND_TIME;
    private boolean gameOver = false;

    // Target home positions (live positions weave around these).
    private static final float[][] TARGETS = {
        {-6f, 1.6f, 2f},
        { 6f, 1.6f, 2f},
        { 0f, 2.4f, 10f},
        {-3f, 1.1f, 7f},
        { 3f, 1.1f, 7f},
    };
    private final boolean[] targetAlive = new boolean[TARGETS.length];
    private final float[] targetRespawn = new float[TARGETS.length];

    // Static scenery (cover): posX, posY, posZ, scaleX, scaleY, scaleZ, r, g, b.
    private static final float[][] BOXES = {
        {-4f, 1.5f, 4f,  1.2f, 3.0f, 1.2f, 0.55f, 0.57f, 0.60f},
        { 4f, 1.5f, 4f,  1.2f, 3.0f, 1.2f, 0.55f, 0.57f, 0.60f},
        {-2f, 0.75f, 0f, 1.5f, 1.5f, 1.5f, 0.72f, 0.45f, 0.20f},
        { 2.5f, 0.75f, 1f, 1.5f, 1.5f, 1.5f, 0.72f, 0.45f, 0.20f},
        { 0f, 2.0f, 12f, 20f, 4.0f, 1.0f, 0.50f, 0.52f, 0.55f},
    };

    public FpsRenderer(InputState input) {
        this.input = input;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.06f, 0.07f, 0.10f, 1f);

        prog3 = buildProgram(VERT3_SRC, FRAG3_SRC);
        aPos = GLES20.glGetAttribLocation(prog3, "aPos");
        aNormal = GLES20.glGetAttribLocation(prog3, "aNormal");
        uMVP = GLES20.glGetUniformLocation(prog3, "uMVP");
        uModel = GLES20.glGetUniformLocation(prog3, "uModel");
        uColor = GLES20.glGetUniformLocation(prog3, "uColor");
        uChecker = GLES20.glGetUniformLocation(prog3, "uChecker");
        uLightDir = GLES20.glGetUniformLocation(prog3, "uLightDir");

        prog2 = buildProgram(VERT2_SRC, FRAG2_SRC);
        aP2 = GLES20.glGetAttribLocation(prog2, "aP");
        uScale2 = GLES20.glGetUniformLocation(prog2, "uScale");
        uOff2 = GLES20.glGetUniformLocation(prog2, "uOff");
        uCol2 = GLES20.glGetUniformLocation(prog2, "uCol");

        cube = makeBuffer(CUBE_DATA);
        floor = makeBuffer(FLOOR_DATA);
        quad = makeBuffer(QUAD_DATA);
        int seg = 28;
        circle = makeCircle(seg);
        circleVerts = seg + 2;

        restart();
        lastNanos = System.nanoTime();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = w;
        height = Math.max(1, h);
        GLES20.glViewport(0, 0, w, h);
        Matrix.perspectiveM(proj, 0, 70f, (float) w / height, 0.1f, 300f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        float dt = (now - lastNanos) / 1_000_000_000f;
        lastNanos = now;
        if (dt > 0.1f) dt = 0.1f;
        timeAcc += dt;

        updateCamera(dt);
        tickTimers(dt);

        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // --- 3D pass ---
        GLES20.glUseProgram(prog3);
        GLES20.glUniform3f(uLightDir, -0.4f, 1.0f, -0.3f);

        Matrix.setIdentityM(model, 0);
        drawMesh(floor, 6, model, 0f, 0f, 0f, 1f);

        for (int i = 0; i < BOXES.length; i++) {
            float[] b = BOXES[i];
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, b[0], b[1], b[2]);
            Matrix.scaleM(model, 0, b[3], b[4], b[5]);
            float boost = (i == hitBox && hitTimer > 0f) ? 1.9f : 1f;
            drawMesh(cube, 36, model, b[6] * boost, b[7] * boost, b[8] * boost, 0f);
        }

        drawTargets();

        // --- 2D HUD pass ---
        drawHud();
    }

    /** Live (moving) position of target i, written into out[0..2]. */
    private void targetPos(int i, float[] out) {
        float[] h = TARGETS[i];
        out[0] = h[0] + 2.5f * (float) Math.sin(timeAcc * 1.3 + i * 1.7);
        out[1] = h[1] + 0.4f * (float) Math.sin(timeAcc * 2.0 + i * 2.3);
        out[2] = h[2];
    }

    private void drawTargets() {
        float pulse = 0.7f + 0.3f * (float) Math.sin(timeAcc * 5.0);
        for (int i = 0; i < TARGETS.length; i++) {
            if (!targetAlive[i]) continue;
            targetPos(i, tmpPos);
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, tmpPos[0], tmpPos[1], tmpPos[2]);
            Matrix.rotateM(model, 0, timeAcc * 60f, 0f, 1f, 0f);   // gentle spin
            Matrix.scaleM(model, 0, TARGET_SCALE, TARGET_SCALE, TARGET_SCALE);
            drawMesh(cube, 36, model, 1.0f * pulse, 0.55f * pulse, 0.12f, 0f);
        }
    }

    private void updateCamera(float dt) {
        input.consumeLook(lookTmp);
        yaw += lookTmp[0] * LOOK_SENS;
        pitch -= lookTmp[1] * LOOK_SENS;
        float limit = 1.48f;                          // ~85 degrees
        if (pitch > limit) pitch = limit;
        if (pitch < -limit) pitch = -limit;

        if (input.consumeFire()) fire();

        float mx = input.moveX();
        float my = input.moveY();
        float fwdX = (float) Math.sin(yaw), fwdZ = -(float) Math.cos(yaw);
        float rgtX = (float) Math.cos(yaw), rgtZ = (float) Math.sin(yaw);
        px += (rgtX * mx + fwdX * my) * MOVE_SPEED * dt;
        pz += (rgtZ * mx + fwdZ * my) * MOVE_SPEED * dt;

        float effPitch = pitch + recoil;              // recoil is a visual kick only
        if (effPitch > limit) effPitch = limit;
        float cosP = (float) Math.cos(effPitch);
        float ldx = cosP * (float) Math.sin(yaw);
        float ldy = (float) Math.sin(effPitch);
        float ldz = -cosP * (float) Math.cos(yaw);
        Matrix.setLookAtM(view, 0, px, py, pz, px + ldx, py + ldy, pz + ldz, 0f, 1f, 0f);
    }

    private void tickTimers(float dt) {
        if (muzzleTimer > 0f) muzzleTimer -= dt;
        if (hitTimer > 0f) hitTimer -= dt;
        if (flashTimer > 0f) flashTimer -= dt;
        if (recoil > 0f) { recoil -= dt * 0.25f; if (recoil < 0f) recoil = 0f; }

        for (int i = 0; i < targetAlive.length; i++) {
            if (!targetAlive[i]) {
                targetRespawn[i] -= dt;
                if (targetRespawn[i] <= 0f) targetAlive[i] = true;
            }
        }

        if (!gameOver) {
            roundTime -= dt;
            if (roundTime <= 0f) {
                roundTime = 0f;
                gameOver = true;
            }
        }
        input.setGameOver(gameOver);
    }

    private void restart() {
        score = 0;
        roundTime = ROUND_TIME;
        gameOver = false;
        for (int i = 0; i < targetAlive.length; i++) {
            targetAlive[i] = true;
            targetRespawn[i] = 0f;
        }
    }

    private void fire() {
        if (gameOver) {                 // a tap on the game-over screen restarts
            restart();
            return;
        }

        muzzleTimer = MUZZLE_TIME;
        flashTimer = FLASH_TIME;
        recoil += 0.025f;
        if (recoil > 0.18f) recoil = 0.18f;

        // Ray from the camera along the (un-recoiled) aim direction.
        float cosP = (float) Math.cos(pitch);
        float dx = cosP * (float) Math.sin(yaw);
        float dy = (float) Math.sin(pitch);
        float dz = -cosP * (float) Math.cos(yaw);

        float best = Float.MAX_VALUE;
        int type = 0;   // 1 = target, 2 = box
        int idx = -1;

        float h = TARGET_SCALE * 0.5f;
        for (int i = 0; i < TARGETS.length; i++) {
            if (!targetAlive[i]) continue;
            targetPos(i, tmpPos);
            float t = rayBox(px, py, pz, dx, dy, dz,
                    tmpPos[0] - h, tmpPos[1] - h, tmpPos[2] - h,
                    tmpPos[0] + h, tmpPos[1] + h, tmpPos[2] + h);
            if (t >= 0f && t < best) { best = t; type = 1; idx = i; }
        }
        for (int i = 0; i < BOXES.length; i++) {
            float[] b = BOXES[i];
            float t = rayBox(px, py, pz, dx, dy, dz,
                    b[0] - b[3] * 0.5f, b[1] - b[4] * 0.5f, b[2] - b[5] * 0.5f,
                    b[0] + b[3] * 0.5f, b[1] + b[4] * 0.5f, b[2] + b[5] * 0.5f);
            if (t >= 0f && t < best) { best = t; type = 2; idx = i; }
        }

        if (type == 1) {                       // target kill
            targetAlive[idx] = false;
            targetRespawn[idx] = RESPAWN_TIME;
            score++;
            lastShotHit = true;
        } else if (type == 2) {                // hit cover
            hitBox = idx;
            hitTimer = HIT_TIME;
            lastShotHit = false;
        } else {
            lastShotHit = false;
        }
    }

    /** Slab ray/AABB intersection; returns the nearest positive t, or -1. */
    private static float rayBox(float ox, float oy, float oz, float dx, float dy, float dz,
                                float minx, float miny, float minz,
                                float maxx, float maxy, float maxz) {
        float tmin = -Float.MAX_VALUE, tmax = Float.MAX_VALUE;
        if (Math.abs(dx) < 1e-6f) { if (ox < minx || ox > maxx) return -1f; }
        else { float a = (minx - ox) / dx, b = (maxx - ox) / dx; if (a > b) { float s = a; a = b; b = s; }
               if (a > tmin) tmin = a; if (b < tmax) tmax = b; }
        if (Math.abs(dy) < 1e-6f) { if (oy < miny || oy > maxy) return -1f; }
        else { float a = (miny - oy) / dy, b = (maxy - oy) / dy; if (a > b) { float s = a; a = b; b = s; }
               if (a > tmin) tmin = a; if (b < tmax) tmax = b; }
        if (Math.abs(dz) < 1e-6f) { if (oz < minz || oz > maxz) return -1f; }
        else { float a = (minz - oz) / dz, b = (maxz - oz) / dz; if (a > b) { float s = a; a = b; b = s; }
               if (a > tmin) tmin = a; if (b < tmax) tmax = b; }
        if (tmax < Math.max(tmin, 0f)) return -1f;
        return tmin >= 0f ? tmin : tmax;
    }

    private void drawMesh(FloatBuffer buf, int vertexCount, float[] m,
                          float r, float g, float b, float checker) {
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
        Matrix.multiplyMM(mvp, 0, mvp, 0, m, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0);
        GLES20.glUniform3f(uColor, r, g, b);
        GLES20.glUniform1f(uChecker, checker);

        buf.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, buf);
        buf.position(3);
        GLES20.glEnableVertexAttribArray(aNormal);
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, STRIDE, buf);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);
    }

    private void drawHud() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(prog2);

        if (!gameOver) {
            if (muzzleTimer > 0f) {
                float a = 0.30f * (muzzleTimer / MUZZLE_TIME);
                drawQuadNDC(0f, 0f, 1f, 1f, 1f, 0.9f, 0.6f, a);   // fullscreen flash
            }

            // Move stick: translucent base + knob offset by the current input.
            float mcx = Hud.moveCx(), mcy = Hud.moveCy(height);
            drawCircle(mcx, mcy, Hud.MOVE_RADIUS, 1f, 1f, 1f, 0.16f);
            float kx = input.moveX(), ky = input.moveY();
            float klen = (float) Math.sqrt(kx * kx + ky * ky);
            if (klen > 1f) { kx /= klen; ky /= klen; }
            drawCircle(mcx + kx * Hud.MOVE_RADIUS, mcy - ky * Hud.MOVE_RADIUS, 56f, 1f, 1f, 1f, 0.55f);

            // Fire button.
            drawCircle(Hud.fireCx(width), Hud.fireCy(height), Hud.FIRE_RADIUS, 1f, 0.30f, 0.25f, 0.50f);

            // Crosshair: green flash on a confirmed target kill.
            float cr = 1f, cg = 1f, cb = 1f;
            if (flashTimer > 0f && lastShotHit) { cr = 0.30f; cg = 1f; cb = 0.40f; }
            float ccx = width * 0.5f, ccy = height * 0.5f;
            drawRectPx(ccx, ccy, 28f, 3f, cr, cg, cb, 0.9f);
            drawRectPx(ccx, ccy, 3f, 28f, cr, cg, cb, 0.9f);

            // Score (top-center) and round timer (top-left, red when low).
            drawNumberCentered(score, 80f, 26f, 46f, 7f, 16f, 0.55f, 1f, 1f, 0.95f);
            int secs = (int) Math.ceil(roundTime);
            boolean low = secs <= 5;
            drawNumberLeft(secs, 56f, 64f, 20f, 36f, 6f, 12f,
                    low ? 1f : 1f, low ? 0.3f : 1f, low ? 0.3f : 1f, 0.95f);
        } else {
            // Game over: dim, big final score, restart cue (tap anywhere).
            drawQuadNDC(0f, 0f, 1f, 1f, 0f, 0f, 0f, 0.62f);
            drawNumberCentered(score, height * 0.40f, 56f, 96f, 12f, 30f, 1f, 1f, 1f, 1f);
            drawCircle(width * 0.5f, height * 0.72f, 86f, 0.2f, 0.9f, 0.35f, 0.85f);
            // simple "play" triangle inside the restart button
            float bx = width * 0.5f, by = height * 0.72f;
            drawRectPx(bx, by, 34f, 34f, 0.05f, 0.15f, 0.05f, 0.9f);
        }

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // --- 7-segment number drawing --------------------------------------------

    private static final int[] SEG = {
        0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F  // 0..9
    };

    private void drawNumberCentered(int n, float cy, float dw, float dh, float t, float gap,
                                    float r, float g, float b, float a) {
        if (n < 0) n = 0;
        String s = Integer.toString(n);
        float total = s.length() * dw + (s.length() - 1) * gap;
        float x = width * 0.5f - total * 0.5f + dw * 0.5f;
        for (int i = 0; i < s.length(); i++) {
            drawDigit(s.charAt(i) - '0', x, cy, dw, dh, t, r, g, b, a);
            x += dw + gap;
        }
    }

    private void drawNumberLeft(int n, float firstCx, float cy, float dw, float dh, float t, float gap,
                                float r, float g, float b, float a) {
        if (n < 0) n = 0;
        String s = Integer.toString(n);
        float x = firstCx;
        for (int i = 0; i < s.length(); i++) {
            drawDigit(s.charAt(i) - '0', x, cy, dw, dh, t, r, g, b, a);
            x += dw + gap;
        }
    }

    private void drawDigit(int d, float cx, float cy, float dw, float dh, float t,
                           float r, float g, float b, float a) {
        if (d < 0 || d > 9) return;
        int m = SEG[d];
        if ((m & 0x01) != 0) drawRectPx(cx, cy - dh * 0.5f, dw, t, r, g, b, a);             // a
        if ((m & 0x02) != 0) drawRectPx(cx + dw * 0.5f, cy - dh * 0.25f, t, dh * 0.5f, r, g, b, a); // b
        if ((m & 0x04) != 0) drawRectPx(cx + dw * 0.5f, cy + dh * 0.25f, t, dh * 0.5f, r, g, b, a); // c
        if ((m & 0x08) != 0) drawRectPx(cx, cy + dh * 0.5f, dw, t, r, g, b, a);             // d
        if ((m & 0x10) != 0) drawRectPx(cx - dw * 0.5f, cy + dh * 0.25f, t, dh * 0.5f, r, g, b, a); // e
        if ((m & 0x20) != 0) drawRectPx(cx - dw * 0.5f, cy - dh * 0.25f, t, dh * 0.5f, r, g, b, a); // f
        if ((m & 0x40) != 0) drawRectPx(cx, cy, dw, t, r, g, b, a);                         // g
    }

    // --- 2D draw helpers (pixel coordinates -> NDC) ---

    private void drawCircle(float cxPx, float cyPx, float rPx, float r, float g, float b, float a) {
        GLES20.glUniform2f(uScale2, rPx / width * 2f, rPx / height * 2f);
        GLES20.glUniform2f(uOff2, cxPx / width * 2f - 1f, 1f - cyPx / height * 2f);
        GLES20.glUniform4f(uCol2, r, g, b, a);
        bind2D(circle);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, circleVerts);
    }

    private void drawRectPx(float cxPx, float cyPx, float wPx, float hPx,
                            float r, float g, float b, float a) {
        GLES20.glUniform2f(uScale2, (wPx * 0.5f) / width * 2f, (hPx * 0.5f) / height * 2f);
        GLES20.glUniform2f(uOff2, cxPx / width * 2f - 1f, 1f - cyPx / height * 2f);
        GLES20.glUniform4f(uCol2, r, g, b, a);
        bind2D(quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
    }

    private void drawQuadNDC(float offX, float offY, float sx, float sy,
                             float r, float g, float b, float a) {
        GLES20.glUniform2f(uScale2, sx, sy);
        GLES20.glUniform2f(uOff2, offX, offY);
        GLES20.glUniform4f(uCol2, r, g, b, a);
        bind2D(quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
    }

    private void bind2D(FloatBuffer buf) {
        buf.position(0);
        GLES20.glEnableVertexAttribArray(aP2);
        GLES20.glVertexAttribPointer(aP2, 2, GLES20.GL_FLOAT, false, 8, buf);
    }

    // --- buffers ---

    private static FloatBuffer makeBuffer(float[] data) {
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }

    private static FloatBuffer makeCircle(int seg) {
        float[] d = new float[(seg + 2) * 2];
        d[0] = 0f; d[1] = 0f;                          // fan center
        for (int k = 0; k <= seg; k++) {
            float ang = (float) (2.0 * Math.PI * k / seg);
            d[2 + 2 * k] = (float) Math.cos(ang);
            d[3 + 2 * k] = (float) Math.sin(ang);
        }
        return makeBuffer(d);
    }

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private static final float[] QUAD_DATA = {
        -1f, -1f,  1f, -1f,  1f, 1f,
        -1f, -1f,  1f,  1f, -1f, 1f,
    };

    private static final float[] FLOOR_DATA = floorQuad(40f);

    private static float[] floorQuad(float s) {
        return new float[] {
            -s, 0f, -s, 0f, 1f, 0f,   s, 0f, -s, 0f, 1f, 0f,   s, 0f, s, 0f, 1f, 0f,
            -s, 0f, -s, 0f, 1f, 0f,   s, 0f, s, 0f, 1f, 0f,   -s, 0f, s, 0f, 1f, 0f,
        };
    }

    private static final float[] CUBE_DATA = unitCube();

    private static float[] unitCube() {
        float h = 0.5f;
        return new float[] {
            // +X
             h, -h, -h, 1, 0, 0,   h, h, -h, 1, 0, 0,   h, h, h, 1, 0, 0,
             h, -h, -h, 1, 0, 0,   h, h, h, 1, 0, 0,    h, -h, h, 1, 0, 0,
            // -X
            -h, -h, -h, -1, 0, 0, -h, h, h, -1, 0, 0,  -h, h, -h, -1, 0, 0,
            -h, -h, -h, -1, 0, 0, -h, -h, h, -1, 0, 0, -h, h, h, -1, 0, 0,
            // +Y
            -h, h, -h, 0, 1, 0,    h, h, h, 0, 1, 0,    h, h, -h, 0, 1, 0,
            -h, h, -h, 0, 1, 0,   -h, h, h, 0, 1, 0,    h, h, h, 0, 1, 0,
            // -Y
            -h, -h, -h, 0, -1, 0,  h, -h, -h, 0, -1, 0, h, -h, h, 0, -1, 0,
            -h, -h, -h, 0, -1, 0,  h, -h, h, 0, -1, 0, -h, -h, h, 0, -1, 0,
            // +Z
            -h, -h, h, 0, 0, 1,    h, -h, h, 0, 0, 1,   h, h, h, 0, 0, 1,
            -h, -h, h, 0, 0, 1,    h, h, h, 0, 0, 1,   -h, h, h, 0, 0, 1,
            // -Z
            -h, -h, -h, 0, 0, -1,  h, h, -h, 0, 0, -1,  h, -h, -h, 0, 0, -1,
            -h, -h, -h, 0, 0, -1, -h, h, -h, 0, 0, -1,  h, h, -h, 0, 0, -1,
        };
    }

    private static final String VERT3_SRC =
        "uniform mat4 uMVP; uniform mat4 uModel;" +
        "attribute vec4 aPos; attribute vec3 aNormal;" +
        "varying vec3 vNormal; varying vec3 vWorld;" +
        "void main(){ vWorld=(uModel*aPos).xyz; vNormal=aNormal; gl_Position=uMVP*aPos; }";

    private static final String FRAG3_SRC =
        "precision mediump float;" +
        "varying vec3 vNormal; varying vec3 vWorld;" +
        "uniform vec3 uColor; uniform float uChecker; uniform vec3 uLightDir;" +
        "void main(){" +
        "  vec3 n=normalize(vNormal);" +
        "  float d=max(dot(n,normalize(uLightDir)),0.0);" +
        "  vec3 base=uColor;" +
        "  if(uChecker>0.5){" +
        "    float c=mod(floor(vWorld.x)+floor(vWorld.z),2.0);" +
        "    base=mix(vec3(0.12,0.13,0.15),vec3(0.22,0.24,0.28),c);" +
        "  }" +
        "  vec3 col=base*(0.28+0.8*d);" +
        "  gl_FragColor=vec4(col,1.0);" +
        "}";

    private static final String VERT2_SRC =
        "attribute vec2 aP; uniform vec2 uScale; uniform vec2 uOff;" +
        "void main(){ gl_Position=vec4(aP*uScale+uOff,0.0,1.0); }";

    private static final String FRAG2_SRC =
        "precision mediump float; uniform vec4 uCol;" +
        "void main(){ gl_FragColor=uCol; }";
}
