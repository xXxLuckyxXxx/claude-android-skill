package com.aigames.fpsprototype;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Textured first-person GLES 2.0 renderer + timed shooting game. Adds real
 * (procedurally generated) textures for the floor and cover, Blinn-Phong
 * lighting with a fake specular map, gradient sky, distance fog, gamma
 * correction, glowing sphere targets, and a low-poly weapon viewmodel. The
 * current build number is shown in the HUD so app updates are verifiable.
 */
public class FpsRenderer implements GLSurfaceView.Renderer {

    private static final float MOVE_SPEED = 4.5f;
    private static final float LOOK_SENS = 0.0045f;
    private static final int STRIDE = 32;             // 8 floats: pos3, normal3, uv2
    private static final float MUZZLE_TIME = 0.08f;
    private static final float HIT_TIME = 0.25f;
    private static final float FLASH_TIME = 0.15f;
    private static final float RESPAWN_TIME = 1.6f;
    private static final float TARGET_SCALE = 0.7f;
    private static final float ROUND_TIME = 30f;

    private final InputState input;
    private final int buildNumber;
    private final float[] lookTmp = new float[2];
    private final float[] tmpPos = new float[3];

    private int width = 1, height = 1;

    private int prog3, aPos, aNormal, aUV, uMVP, uModel, uColor, uMode, uLightDir, uCamPos, uFogColor, uTime, uTex;
    private int progSky, aPSky, uSkyTop, uSkyBot;
    private int prog2, aP2, uScale2, uOff2, uCol2;

    private int floorTex, metalTex;

    private FloatBuffer cube, floor, sphere, quad, circle;
    private int sphereVerts, circleVerts;

    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] model = new float[16];
    private final float[] mvp = new float[16];
    private final float[] tmpA = new float[16];
    private final float[] gunBase = new float[16];
    private final float[] partM = new float[16];

    private float px = 0f, py = 1.6f, pz = 9f;
    private float yaw = 0f, pitch = -0.08f;
    private float recoil = 0f;
    private long lastNanos;
    private float timeAcc = 0f;

    private float muzzleTimer = 0f, hitTimer = 0f, flashTimer = 0f;
    private int hitBox = -1;
    private boolean lastShotHit = false;

    private int score = 0;
    private float roundTime = ROUND_TIME;
    private boolean gameOver = false;

    private static final float[][] TARGETS = {
        {-6f, 1.6f, 2f}, {6f, 1.6f, 2f}, {0f, 2.4f, 10f}, {-3f, 1.1f, 7f}, {3f, 1.1f, 7f},
    };
    private final boolean[] targetAlive = new boolean[TARGETS.length];
    private final float[] targetRespawn = new float[TARGETS.length];

    private static final float[][] BOXES = {
        {-4f, 1.5f, 4f,  1.2f, 3.0f, 1.2f, 0.85f, 0.88f, 0.95f},
        { 4f, 1.5f, 4f,  1.2f, 3.0f, 1.2f, 0.85f, 0.88f, 0.95f},
        {-2f, 0.75f, 0f, 1.5f, 1.5f, 1.5f, 1.05f, 0.70f, 0.40f},
        { 2.5f, 0.75f, 1f, 1.5f, 1.5f, 1.5f, 1.05f, 0.70f, 0.40f},
        { 0f, 2.0f, 12f, 20f, 4.0f, 1.0f, 0.78f, 0.82f, 0.90f},
    };

    private static final float[] FOG = {0.46f, 0.52f, 0.62f};

    public FpsRenderer(InputState input, int buildNumber) {
        this.input = input;
        this.buildNumber = buildNumber;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.46f, 0.52f, 0.62f, 1f);

        prog3 = buildProgram(VERT3_SRC, FRAG3_SRC);
        aPos = GLES20.glGetAttribLocation(prog3, "aPos");
        aNormal = GLES20.glGetAttribLocation(prog3, "aNormal");
        aUV = GLES20.glGetAttribLocation(prog3, "aUV");
        uMVP = GLES20.glGetUniformLocation(prog3, "uMVP");
        uModel = GLES20.glGetUniformLocation(prog3, "uModel");
        uColor = GLES20.glGetUniformLocation(prog3, "uColor");
        uMode = GLES20.glGetUniformLocation(prog3, "uMode");
        uLightDir = GLES20.glGetUniformLocation(prog3, "uLightDir");
        uCamPos = GLES20.glGetUniformLocation(prog3, "uCamPos");
        uFogColor = GLES20.glGetUniformLocation(prog3, "uFogColor");
        uTime = GLES20.glGetUniformLocation(prog3, "uTime");
        uTex = GLES20.glGetUniformLocation(prog3, "uTex");

        progSky = buildProgram(VERT_SKY_SRC, FRAG_SKY_SRC);
        aPSky = GLES20.glGetAttribLocation(progSky, "aP");
        uSkyTop = GLES20.glGetUniformLocation(progSky, "uTop");
        uSkyBot = GLES20.glGetUniformLocation(progSky, "uBot");

        prog2 = buildProgram(VERT2_SRC, FRAG2_SRC);
        aP2 = GLES20.glGetAttribLocation(prog2, "aP");
        uScale2 = GLES20.glGetUniformLocation(prog2, "uScale");
        uOff2 = GLES20.glGetUniformLocation(prog2, "uOff");
        uCol2 = GLES20.glGetUniformLocation(prog2, "uCol");

        GLES20.glUseProgram(prog3);
        GLES20.glUniform1i(uTex, 0);

        cube = makeBuffer(makeCube());
        floor = makeBuffer(makeFloor(40f, 40f));
        float[] sph = makeSphere(14, 20);
        sphere = makeBuffer(sph);
        sphereVerts = sph.length / 8;
        quad = makeBuffer(QUAD_DATA);
        int seg = 28;
        circle = makeCircle(seg);
        circleVerts = seg + 2;

        floorTex = uploadTexture(makeFloorBitmap());
        metalTex = uploadTexture(makeMetalBitmap());

        restart();
        lastNanos = System.nanoTime();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = w;
        height = Math.max(1, h);
        GLES20.glViewport(0, 0, w, h);
        Matrix.perspectiveM(proj, 0, 68f, (float) w / height, 0.05f, 300f);
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

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // Sky
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glUseProgram(progSky);
        GLES20.glUniform3f(uSkyTop, 0.06f, 0.08f, 0.18f);
        GLES20.glUniform3f(uSkyBot, 0.50f, 0.56f, 0.66f);
        quad.position(0);
        GLES20.glEnableVertexAttribArray(aPSky);
        GLES20.glVertexAttribPointer(aPSky, 2, GLES20.GL_FLOAT, false, 8, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glDepthMask(true);

        // Scene
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(prog3);
        GLES20.glUniform3f(uLightDir, -0.4f, 1.0f, -0.3f);
        GLES20.glUniform3f(uCamPos, px, py, pz);
        GLES20.glUniform3f(uFogColor, FOG[0], FOG[1], FOG[2]);
        GLES20.glUniform1f(uTime, timeAcc);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, floorTex);
        Matrix.setIdentityM(model, 0);
        drawWorld(floor, 6, 0f, 1f, 1f, 1f);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, metalTex);
        for (int i = 0; i < BOXES.length; i++) {
            float[] b = BOXES[i];
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, b[0], b[1], b[2]);
            Matrix.scaleM(model, 0, b[3], b[4], b[5]);
            float boost = (i == hitBox && hitTimer > 0f) ? 1.6f : 1f;
            drawWorld(cube, 36, 0f, b[6] * boost, b[7] * boost, b[8] * boost);
        }

        for (int i = 0; i < TARGETS.length; i++) {
            if (!targetAlive[i]) continue;
            targetPos(i, tmpPos);
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, tmpPos[0], tmpPos[1], tmpPos[2]);
            Matrix.scaleM(model, 0, TARGET_SCALE, TARGET_SCALE, TARGET_SCALE);
            drawWorld(sphere, sphereVerts, 2f, 1.0f, 0.5f, 0.12f);
        }

        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
        drawGun();

        drawHud();
    }

    private void targetPos(int i, float[] out) {
        float[] h = TARGETS[i];
        out[0] = h[0] + 2.5f * (float) Math.sin(timeAcc * 1.3 + i * 1.7);
        out[1] = h[1] + 0.4f * (float) Math.sin(timeAcc * 2.0 + i * 2.3);
        out[2] = h[2];
    }

    private void drawWorld(FloatBuffer buf, int count, float mode, float r, float g, float b) {
        Matrix.multiplyMM(tmpA, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmpA, 0);
        submit(buf, count, mvp, model, mode, r, g, b);
    }

    private void submit(FloatBuffer buf, int count, float[] mvpM, float[] modelM,
                        float mode, float r, float g, float b) {
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvpM, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, modelM, 0);
        GLES20.glUniform1f(uMode, mode);
        GLES20.glUniform3f(uColor, r, g, b);

        buf.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, buf);
        buf.position(3);
        GLES20.glEnableVertexAttribArray(aNormal);
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, STRIDE, buf);
        buf.position(6);
        GLES20.glEnableVertexAttribArray(aUV);
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, STRIDE, buf);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
    }

    private void drawGun() {
        Matrix.setIdentityM(gunBase, 0);
        Matrix.translateM(gunBase, 0, 0.16f, -0.17f, -0.50f - recoil * 0.30f);
        Matrix.rotateM(gunBase, 0, -3.5f + recoil * 9f, 1f, 0f, 0f);
        Matrix.rotateM(gunBase, 0, -4f, 0f, 1f, 0f);

        gunPart(0f, 0.00f, 0.02f, 0.085f, 0.10f, 0.34f, 0.16f, 0.17f, 0.20f);
        gunPart(0f, 0.02f, -0.30f, 0.045f, 0.045f, 0.30f, 0.09f, 0.09f, 0.11f);
        gunPart(0f, -0.13f, 0.10f, 0.065f, 0.16f, 0.085f, 0.12f, 0.10f, 0.09f);
        gunPart(0f, -0.12f, -0.02f, 0.055f, 0.15f, 0.075f, 0.18f, 0.18f, 0.22f);
        gunPart(0f, 0.085f, -0.06f, 0.018f, 0.045f, 0.02f, 0.07f, 0.07f, 0.08f);

        if (muzzleTimer > 0f) {
            float k = muzzleTimer / MUZZLE_TIME;
            Matrix.setIdentityM(partM, 0);
            Matrix.translateM(partM, 0, 0f, 0.02f, -0.47f);
            float s = 0.05f + 0.07f * k;
            Matrix.scaleM(partM, 0, s, s, s);
            Matrix.multiplyMM(tmpA, 0, gunBase, 0, partM, 0);
            Matrix.multiplyMM(mvp, 0, proj, 0, tmpA, 0);
            submit(cube, 36, mvp, tmpA, 4f, 2.6f * k, 2.1f * k, 1.1f * k);
        }
    }

    private void gunPart(float tx, float ty, float tz, float sx, float sy, float sz,
                         float r, float g, float b) {
        Matrix.setIdentityM(partM, 0);
        Matrix.translateM(partM, 0, tx, ty, tz);
        Matrix.scaleM(partM, 0, sx, sy, sz);
        Matrix.multiplyMM(tmpA, 0, gunBase, 0, partM, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmpA, 0);
        submit(cube, 36, mvp, tmpA, 3f, r, g, b);
    }

    private void updateCamera(float dt) {
        input.consumeLook(lookTmp);
        yaw += lookTmp[0] * LOOK_SENS;
        pitch -= lookTmp[1] * LOOK_SENS;
        float limit = 1.48f;
        if (pitch > limit) pitch = limit;
        if (pitch < -limit) pitch = -limit;

        if (input.consumeFire()) fire();

        float mx = input.moveX(), my = input.moveY();
        float fwdX = (float) Math.sin(yaw), fwdZ = -(float) Math.cos(yaw);
        float rgtX = (float) Math.cos(yaw), rgtZ = (float) Math.sin(yaw);
        px += (rgtX * mx + fwdX * my) * MOVE_SPEED * dt;
        pz += (rgtZ * mx + fwdZ * my) * MOVE_SPEED * dt;

        float effPitch = pitch + recoil;
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
            if (roundTime <= 0f) { roundTime = 0f; gameOver = true; }
        }
        input.setGameOver(gameOver);
    }

    private void restart() {
        score = 0;
        roundTime = ROUND_TIME;
        gameOver = false;
        for (int i = 0; i < targetAlive.length; i++) { targetAlive[i] = true; targetRespawn[i] = 0f; }
    }

    private void fire() {
        if (gameOver) { restart(); return; }
        muzzleTimer = MUZZLE_TIME;
        flashTimer = FLASH_TIME;
        recoil += 0.025f;
        if (recoil > 0.18f) recoil = 0.18f;

        float cosP = (float) Math.cos(pitch);
        float dx = cosP * (float) Math.sin(yaw);
        float dy = (float) Math.sin(pitch);
        float dz = -cosP * (float) Math.cos(yaw);

        float best = Float.MAX_VALUE;
        int type = 0, idx = -1;
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

        if (type == 1) {
            targetAlive[idx] = false; targetRespawn[idx] = RESPAWN_TIME; score++; lastShotHit = true;
        } else if (type == 2) {
            hitBox = idx; hitTimer = HIT_TIME; lastShotHit = false;
        } else {
            lastShotHit = false;
        }
    }

    private static float rayBox(float ox, float oy, float oz, float dx, float dy, float dz,
                                float minx, float miny, float minz, float maxx, float maxy, float maxz) {
        float tmin = -Float.MAX_VALUE, tmax = Float.MAX_VALUE;
        if (Math.abs(dx) < 1e-6f) { if (ox < minx || ox > maxx) return -1f; }
        else { float a = (minx - ox) / dx, b = (maxx - ox) / dx; if (a > b) { float s = a; a = b; b = s; } if (a > tmin) tmin = a; if (b < tmax) tmax = b; }
        if (Math.abs(dy) < 1e-6f) { if (oy < miny || oy > maxy) return -1f; }
        else { float a = (miny - oy) / dy, b = (maxy - oy) / dy; if (a > b) { float s = a; a = b; b = s; } if (a > tmin) tmin = a; if (b < tmax) tmax = b; }
        if (Math.abs(dz) < 1e-6f) { if (oz < minz || oz > maxz) return -1f; }
        else { float a = (minz - oz) / dz, b = (maxz - oz) / dz; if (a > b) { float s = a; a = b; b = s; } if (a > tmin) tmin = a; if (b < tmax) tmax = b; }
        if (tmax < Math.max(tmin, 0f)) return -1f;
        return tmin >= 0f ? tmin : tmax;
    }

    // --- HUD ---

    private void drawHud() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(prog2);

        if (!gameOver) {
            if (muzzleTimer > 0f) drawQuadNDC(0f, 0f, 1f, 1f, 1f, 0.9f, 0.6f, 0.18f * (muzzleTimer / MUZZLE_TIME));
            float mcx = Hud.moveCx(), mcy = Hud.moveCy(height);
            drawCircle(mcx, mcy, Hud.MOVE_RADIUS, 1f, 1f, 1f, 0.16f);
            float kx = input.moveX(), ky = input.moveY();
            float klen = (float) Math.sqrt(kx * kx + ky * ky);
            if (klen > 1f) { kx /= klen; ky /= klen; }
            drawCircle(mcx + kx * Hud.MOVE_RADIUS, mcy - ky * Hud.MOVE_RADIUS, 56f, 1f, 1f, 1f, 0.55f);
            drawCircle(Hud.fireCx(width), Hud.fireCy(height), Hud.FIRE_RADIUS, 1f, 0.30f, 0.25f, 0.50f);

            float cr = 1f, cg = 1f, cb = 1f;
            if (flashTimer > 0f && lastShotHit) { cr = 0.30f; cg = 1f; cb = 0.40f; }
            float ccx = width * 0.5f, ccy = height * 0.5f;
            drawRectPx(ccx, ccy, 28f, 3f, cr, cg, cb, 0.9f);
            drawRectPx(ccx, ccy, 3f, 28f, cr, cg, cb, 0.9f);

            drawNumberCentered(score, 80f, 26f, 46f, 7f, 16f, 0.55f, 1f, 1f, 0.95f);
            int secs = (int) Math.ceil(roundTime);
            boolean low = secs <= 5;
            drawNumberLeft(secs, 56f, 64f, 20f, 36f, 6f, 12f, 1f, low ? 0.3f : 1f, low ? 0.3f : 1f, 0.95f);
        } else {
            drawQuadNDC(0f, 0f, 1f, 1f, 0f, 0f, 0f, 0.62f);
            drawNumberCentered(score, height * 0.40f, 56f, 96f, 12f, 30f, 1f, 1f, 1f, 1f);
            drawCircle(width * 0.5f, height * 0.72f, 86f, 0.2f, 0.9f, 0.35f, 0.85f);
            drawRectPx(width * 0.5f, height * 0.72f, 34f, 34f, 0.05f, 0.15f, 0.05f, 0.9f);
        }

        // Build number (top-right) — confirms which APK is installed.
        drawNumberLeft(buildNumber, width - 116f, 52f, 18f, 30f, 5f, 11f, 1f, 0.8f, 0.2f, 0.95f);

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private static final int[] SEG = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};

    private void drawNumberCentered(int n, float cy, float dw, float dh, float t, float gap,
                                    float r, float g, float b, float a) {
        if (n < 0) n = 0;
        String s = Integer.toString(n);
        float total = s.length() * dw + (s.length() - 1) * gap;
        float x = width * 0.5f - total * 0.5f + dw * 0.5f;
        for (int i = 0; i < s.length(); i++) { drawDigit(s.charAt(i) - '0', x, cy, dw, dh, t, r, g, b, a); x += dw + gap; }
    }

    private void drawNumberLeft(int n, float firstCx, float cy, float dw, float dh, float t, float gap,
                                float r, float g, float b, float a) {
        if (n < 0) n = 0;
        String s = Integer.toString(n);
        float x = firstCx;
        for (int i = 0; i < s.length(); i++) { drawDigit(s.charAt(i) - '0', x, cy, dw, dh, t, r, g, b, a); x += dw + gap; }
    }

    private void drawDigit(int d, float cx, float cy, float dw, float dh, float t,
                           float r, float g, float b, float a) {
        if (d < 0 || d > 9) return;
        int m = SEG[d];
        if ((m & 0x01) != 0) drawRectPx(cx, cy - dh * 0.5f, dw, t, r, g, b, a);
        if ((m & 0x02) != 0) drawRectPx(cx + dw * 0.5f, cy - dh * 0.25f, t, dh * 0.5f, r, g, b, a);
        if ((m & 0x04) != 0) drawRectPx(cx + dw * 0.5f, cy + dh * 0.25f, t, dh * 0.5f, r, g, b, a);
        if ((m & 0x08) != 0) drawRectPx(cx, cy + dh * 0.5f, dw, t, r, g, b, a);
        if ((m & 0x10) != 0) drawRectPx(cx - dw * 0.5f, cy + dh * 0.25f, t, dh * 0.5f, r, g, b, a);
        if ((m & 0x20) != 0) drawRectPx(cx - dw * 0.5f, cy - dh * 0.25f, t, dh * 0.5f, r, g, b, a);
        if ((m & 0x40) != 0) drawRectPx(cx, cy, dw, t, r, g, b, a);
    }

    private void drawCircle(float cxPx, float cyPx, float rPx, float r, float g, float b, float a) {
        GLES20.glUniform2f(uScale2, rPx / width * 2f, rPx / height * 2f);
        GLES20.glUniform2f(uOff2, cxPx / width * 2f - 1f, 1f - cyPx / height * 2f);
        GLES20.glUniform4f(uCol2, r, g, b, a);
        bind2D(circle);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, circleVerts);
    }

    private void drawRectPx(float cxPx, float cyPx, float wPx, float hPx, float r, float g, float b, float a) {
        GLES20.glUniform2f(uScale2, (wPx * 0.5f) / width * 2f, (hPx * 0.5f) / height * 2f);
        GLES20.glUniform2f(uOff2, cxPx / width * 2f - 1f, 1f - cyPx / height * 2f);
        GLES20.glUniform4f(uCol2, r, g, b, a);
        bind2D(quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
    }

    private void drawQuadNDC(float offX, float offY, float sx, float sy, float r, float g, float b, float a) {
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

    // --- textures (procedural) ---

    private static int uploadTexture(Bitmap bmp) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        bmp.recycle();
        return id[0];
    }

    private static Bitmap makeFloorBitmap() {
        Bitmap b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF0E1014);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int s = 128;
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                float L = i * s + 8, T = j * s + 8, R = (i + 1) * s - 8, B = (j + 1) * s - 8;
                p.setStyle(Paint.Style.FILL); p.setColor(0xFF1B212B); c.drawRect(L, T, R, B, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3f); p.setColor(0xFF2C3644); c.drawRect(L, T, R, B, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF12536A); c.drawRect(0, 123, 256, 133, p); c.drawRect(123, 0, 133, 256, p);
        p.setColor(0xFF26A6C8); c.drawRect(0, 127, 256, 129, p); c.drawRect(127, 0, 129, 256, p);
        Random rnd = new Random(7);
        for (int k = 0; k < 700; k++) {
            int gray = rnd.nextInt(60);
            p.setColor((0x30 << 24) | (gray << 16) | (gray << 8) | gray);
            c.drawRect(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256) + 1, rnd.nextInt(256) + 1, p);
        }
        return b;
    }

    private static Bitmap makeMetalBitmap() {
        Bitmap b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        Random rnd = new Random(3);
        for (int x = 0; x < 256; x++) {
            int gr = 0x2A + rnd.nextInt(0x1A);
            p.setColor(0xFF000000 | (gr << 16) | (gr << 8) | (gr + 6));
            c.drawLine(x, 0, x, 256, p);
        }
        p.setColor(0xFF1A1E24); c.drawRect(0, 38, 256, 50, p); c.drawRect(0, 206, 256, 218, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(7f); p.setColor(0xFF14171C); c.drawRect(5, 5, 251, 251, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFF3C4554);
        c.drawCircle(22, 22, 6, p); c.drawCircle(234, 22, 6, p); c.drawCircle(22, 234, 6, p); c.drawCircle(234, 234, 6, p);
        return b;
    }

    // --- geometry (pos3, normal3, uv2) ---

    private static int put(float[] d, int o, float x, float y, float z, float nx, float ny, float nz, float u, float v) {
        d[o++] = x; d[o++] = y; d[o++] = z; d[o++] = nx; d[o++] = ny; d[o++] = nz; d[o++] = u; d[o++] = v;
        return o;
    }

    private static int faceQuad(float[] d, int o,
                                float ax, float ay, float az, float bx, float by, float bz,
                                float cx, float cy, float cz, float ex, float ey, float ez,
                                float nx, float ny, float nz) {
        o = put(d, o, ax, ay, az, nx, ny, nz, 0f, 0f);
        o = put(d, o, bx, by, bz, nx, ny, nz, 1f, 0f);
        o = put(d, o, cx, cy, cz, nx, ny, nz, 1f, 1f);
        o = put(d, o, ax, ay, az, nx, ny, nz, 0f, 0f);
        o = put(d, o, cx, cy, cz, nx, ny, nz, 1f, 1f);
        o = put(d, o, ex, ey, ez, nx, ny, nz, 0f, 1f);
        return o;
    }

    private static float[] makeCube() {
        float h = 0.5f;
        float[] d = new float[36 * 8];
        int o = 0;
        o = faceQuad(d, o,  h, -h, h,  h, -h, -h,  h, h, -h,  h, h, h,  1, 0, 0);
        o = faceQuad(d, o, -h, -h, -h, -h, -h, h, -h, h, h, -h, h, -h, -1, 0, 0);
        o = faceQuad(d, o, -h, h, h,  h, h, h,  h, h, -h, -h, h, -h,  0, 1, 0);
        o = faceQuad(d, o, -h, -h, -h,  h, -h, -h,  h, -h, h, -h, -h, h,  0, -1, 0);
        o = faceQuad(d, o, -h, -h, h,  h, -h, h,  h, h, h, -h, h, h,  0, 0, 1);
        o = faceQuad(d, o,  h, -h, -h, -h, -h, -h, -h, h, -h,  h, h, -h,  0, 0, -1);
        return d;
    }

    private static float[] makeFloor(float half, float tiles) {
        float[] d = new float[6 * 8];
        int o = 0;
        o = put(d, o, -half, 0, -half, 0, 1, 0, 0f, 0f);
        o = put(d, o, half, 0, -half, 0, 1, 0, tiles, 0f);
        o = put(d, o, half, 0, half, 0, 1, 0, tiles, tiles);
        o = put(d, o, -half, 0, -half, 0, 1, 0, 0f, 0f);
        o = put(d, o, half, 0, half, 0, 1, 0, tiles, tiles);
        o = put(d, o, -half, 0, half, 0, 1, 0, 0f, tiles);
        return d;
    }

    private static float[] makeSphere(int stacks, int slices) {
        float[] d = new float[stacks * slices * 6 * 8];
        int o = 0;
        for (int i = 0; i < stacks; i++) {
            float v0 = (float) i / stacks, v1 = (float) (i + 1) / stacks;
            float phi0 = (float) (Math.PI * v0), phi1 = (float) (Math.PI * v1);
            for (int j = 0; j < slices; j++) {
                float u0 = (float) j / slices, u1 = (float) (j + 1) / slices;
                float th0 = (float) (2.0 * Math.PI * u0), th1 = (float) (2.0 * Math.PI * u1);
                o = sphVtx(d, o, phi0, th0, u0, v0);
                o = sphVtx(d, o, phi1, th0, u0, v1);
                o = sphVtx(d, o, phi1, th1, u1, v1);
                o = sphVtx(d, o, phi0, th0, u0, v0);
                o = sphVtx(d, o, phi1, th1, u1, v1);
                o = sphVtx(d, o, phi0, th1, u1, v0);
            }
        }
        return d;
    }

    private static int sphVtx(float[] d, int o, float phi, float theta, float u, float v) {
        float sp = (float) Math.sin(phi);
        float nx = sp * (float) Math.cos(theta), ny = (float) Math.cos(phi), nz = sp * (float) Math.sin(theta);
        return put(d, o, nx * 0.5f, ny * 0.5f, nz * 0.5f, nx, ny, nz, u, v);
    }

    private static FloatBuffer makeBuffer(float[] data) {
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }

    private static FloatBuffer makeCircle(int seg) {
        float[] d = new float[(seg + 2) * 2];
        d[0] = 0f; d[1] = 0f;
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

    private static final float[] QUAD_DATA = {-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f};

    private static final String VERT3_SRC =
        "uniform mat4 uMVP; uniform mat4 uModel;" +
        "attribute vec4 aPos; attribute vec3 aNormal; attribute vec2 aUV;" +
        "varying vec3 vNormal; varying vec3 vWorld; varying vec2 vUV;" +
        "void main(){ vWorld=(uModel*aPos).xyz; vNormal=aNormal; vUV=aUV; gl_Position=uMVP*aPos; }";

    private static final String FRAG3_SRC =
        "precision mediump float;" +
        "varying vec3 vNormal; varying vec3 vWorld; varying vec2 vUV;" +
        "uniform vec3 uColor; uniform float uMode; uniform vec3 uLightDir;" +
        "uniform vec3 uCamPos; uniform vec3 uFogColor; uniform float uTime; uniform sampler2D uTex;" +
        "void main(){" +
        "  vec3 N=normalize(vNormal); vec3 col;" +
        "  if(uMode<0.5){" +
        "    vec3 tex=texture2D(uTex,vUV).rgb;" +
        "    vec3 V=normalize(uCamPos-vWorld); vec3 L=normalize(uLightDir);" +
        "    float df=max(dot(N,L),0.0); vec3 H=normalize(L+V); float sp=pow(max(dot(N,H),0.0),36.0);" +
        "    vec3 base=tex*uColor;" +
        "    col=base*(vec3(0.22,0.24,0.30)+df*vec3(1.05,1.0,0.92))+sp*vec3(0.6)*tex.r;" +
        "  } else if(uMode<2.5){" +
        "    vec3 V=normalize(uCamPos-vWorld); float fr=pow(1.0-max(dot(N,V),0.0),2.0);" +
        "    float pulse=0.78+0.22*sin(uTime*5.0);" +
        "    col=uColor*pulse+uColor*fr*1.4+fr*vec3(0.30);" +
        "  } else if(uMode<3.5){" +
        "    vec3 L=normalize(vec3(-0.3,0.55,0.75)); vec3 V=vec3(0.0,0.0,1.0);" +
        "    float df=max(dot(N,L),0.0); vec3 H=normalize(L+V); float sp=pow(max(dot(N,H),0.0),32.0);" +
        "    col=uColor*(vec3(0.22,0.24,0.30)+df*vec3(1.0))+sp*vec3(0.6);" +
        "    gl_FragColor=vec4(pow(col,vec3(0.4545)),1.0); return;" +
        "  } else {" +
        "    gl_FragColor=vec4(pow(uColor,vec3(0.4545)),1.0); return;" +
        "  }" +
        "  float dist=length(uCamPos-vWorld); float fog=clamp((dist-10.0)/60.0,0.0,0.8);" +
        "  col=mix(col,uFogColor,fog);" +
        "  gl_FragColor=vec4(pow(col,vec3(0.4545)),1.0);" +
        "}";

    private static final String VERT_SKY_SRC =
        "attribute vec2 aP; varying float vy; void main(){ vy=aP.y; gl_Position=vec4(aP,0.999,1.0); }";

    private static final String FRAG_SKY_SRC =
        "precision mediump float; varying float vy; uniform vec3 uTop; uniform vec3 uBot;" +
        "void main(){ float t=clamp(vy*0.5+0.5,0.0,1.0); vec3 c=mix(uBot,uTop,t*t);" +
        "  float h=1.0-smoothstep(0.0,0.18,abs(vy)); c+=vec3(0.18,0.13,0.08)*h;" +
        "  gl_FragColor=vec4(pow(c,vec3(0.4545)),1.0); }";

    private static final String VERT2_SRC =
        "attribute vec2 aP; uniform vec2 uScale; uniform vec2 uOff;" +
        "void main(){ gl_Position=vec4(aP*uScale+uOff,0.0,1.0); }";

    private static final String FRAG2_SRC =
        "precision mediump float; uniform vec4 uCol; void main(){ gl_FragColor=uCol; }";
}
