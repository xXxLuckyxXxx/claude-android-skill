package com.aigames.fpsprototype;

import android.content.Context;
import android.content.SharedPreferences;
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
 * Textured first-person GLES 2.0 renderer + timed shooting game with a more
 * cinematic look: detailed procedural textures, 3-point lighting with rim
 * light, ACES filmic tonemapping, a vignette, gradient sky, distance fog,
 * glowing sphere targets, and a multi-part weapon viewmodel (barrel, stock,
 * magazine, scope) with recoil + muzzle flash. Build number shown in the HUD.
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
    private int progVig, aPVig;
    private int progBlob, aPBlob, uBlobMVP, uBlobA;
    private int prog2, aP2, uScale2, uOff2, uCol2;

    // Light travel direction (downward), used for projected blob shadows.
    private static final float LDX = 0.358f, LDY = -0.894f, LDZ = 0.268f;

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
    private int combo = 1;
    private float comboTimer = 0f;
    private int highScore = 0;
    private static final float COMBO_WINDOW = 2.2f;
    private final SharedPreferences prefs;
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

    public FpsRenderer(InputState input, int buildNumber, Context ctx) {
        this.input = input;
        this.buildNumber = buildNumber;
        this.prefs = ctx.getSharedPreferences("aigames_fps", Context.MODE_PRIVATE);
        this.highScore = prefs.getInt("highscore", 0);
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

        progVig = buildProgram(VERT_VIG_SRC, FRAG_VIG_SRC);
        aPVig = GLES20.glGetAttribLocation(progVig, "aP");

        progBlob = buildProgram(VERT_BLOB_SRC, FRAG_BLOB_SRC);
        aPBlob = GLES20.glGetAttribLocation(progBlob, "aP");
        uBlobMVP = GLES20.glGetUniformLocation(progBlob, "uMVP");
        uBlobA = GLES20.glGetUniformLocation(progBlob, "uA");

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
        GLES20.glUniform3f(uSkyTop, 0.05f, 0.07f, 0.16f);
        GLES20.glUniform3f(uSkyBot, 0.52f, 0.58f, 0.68f);
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

        // projected blob / contact shadows on the floor
        drawShadows();

        GLES20.glUseProgram(prog3);
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

        // additive glow (bloom-style) around the live targets
        drawGlow();

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
        Matrix.translateM(gunBase, 0, 0.15f, -0.16f, -0.52f - recoil * 0.30f);
        Matrix.rotateM(gunBase, 0, -3.0f + recoil * 9f, 1f, 0f, 0f);
        Matrix.rotateM(gunBase, 0, -4f, 0f, 1f, 0f);

        // body / receiver
        gunPart(0f, 0.00f, 0.04f, 0.090f, 0.11f, 0.42f, 0.13f, 0.14f, 0.17f);
        // stock (rear)
        gunPart(0f, -0.02f, 0.26f, 0.075f, 0.10f, 0.16f, 0.12f, 0.12f, 0.15f);
        // barrel
        gunPart(0f, 0.02f, -0.34f, 0.040f, 0.040f, 0.34f, 0.20f, 0.21f, 0.24f);
        // muzzle ring
        gunPart(0f, 0.02f, -0.52f, 0.052f, 0.052f, 0.06f, 0.09f, 0.09f, 0.11f);
        // scope body
        gunPart(0f, 0.105f, -0.04f, 0.040f, 0.050f, 0.18f, 0.10f, 0.10f, 0.12f);
        // scope lens (glass tint)
        gunPart(0f, 0.105f, -0.14f, 0.052f, 0.062f, 0.03f, 0.06f, 0.20f, 0.26f);
        // magazine
        gunPart(0f, -0.14f, -0.02f, 0.052f, 0.16f, 0.085f, 0.16f, 0.16f, 0.20f);
        // grip
        gunPart(0f, -0.14f, 0.14f, 0.060f, 0.15f, 0.080f, 0.11f, 0.10f, 0.10f);
        // trigger guard
        gunPart(0f, -0.07f, 0.06f, 0.050f, 0.045f, 0.05f, 0.09f, 0.09f, 0.11f);

        if (muzzleTimer > 0f) {
            float k = muzzleTimer / MUZZLE_TIME;
            // bright opaque core
            Matrix.setIdentityM(partM, 0);
            Matrix.translateM(partM, 0, 0f, 0.02f, -0.56f);
            float s = 0.05f + 0.05f * k;
            Matrix.scaleM(partM, 0, s, s, s);
            Matrix.multiplyMM(tmpA, 0, gunBase, 0, partM, 0);
            Matrix.multiplyMM(mvp, 0, proj, 0, tmpA, 0);
            submit(cube, 36, mvp, tmpA, 4f, 2.8f * k, 2.3f * k, 1.3f * k);
            // additive flash halo
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
            GLES20.glDepthMask(false);
            Matrix.setIdentityM(partM, 0);
            Matrix.translateM(partM, 0, 0f, 0.02f, -0.56f);
            float gs = 0.16f + 0.14f * k;
            Matrix.scaleM(partM, 0, gs, gs, gs);
            Matrix.multiplyMM(tmpA, 0, gunBase, 0, partM, 0);
            Matrix.multiplyMM(mvp, 0, proj, 0, tmpA, 0);
            submit(sphere, sphereVerts, mvp, tmpA, 4f, 0.9f * k, 0.6f * k, 0.25f * k);
            GLES20.glDepthMask(true);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    private void drawShadows() {
        GLES20.glUseProgram(progBlob);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        circle.position(0);
        GLES20.glEnableVertexAttribArray(aPBlob);
        GLES20.glVertexAttribPointer(aPBlob, 2, GLES20.GL_FLOAT, false, 8, circle);
        for (int i = 0; i < BOXES.length; i++) {
            float[] b = BOXES[i];
            blob(b[0], b[1], b[2], Math.max(b[3], b[5]) * 0.62f);
        }
        for (int i = 0; i < TARGETS.length; i++) {
            if (!targetAlive[i]) continue;
            targetPos(i, tmpPos);
            blob(tmpPos[0], tmpPos[1], tmpPos[2], TARGET_SCALE * 0.62f);
        }
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void blob(float ox, float oy, float oz, float baseR) {
        float s = oy / (-LDY);                          // distance to floor along light
        float fx = ox + LDX * s, fz = oz + LDZ * s;
        float r = baseR * (1f + s * 0.06f);
        float alpha = 0.5f / (1f + s * 0.22f);
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, fx, 0.02f, fz);
        Matrix.scaleM(model, 0, r, 1f, r);
        Matrix.multiplyMM(tmpA, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmpA, 0);
        GLES20.glUniformMatrix4fv(uBlobMVP, 1, false, mvp, 0);
        GLES20.glUniform1f(uBlobA, alpha);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, circleVerts);
    }

    private void drawGlow() {
        GLES20.glUseProgram(prog3);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        GLES20.glDepthMask(false);
        float k = 1.7f;
        for (int i = 0; i < TARGETS.length; i++) {
            if (!targetAlive[i]) continue;
            targetPos(i, tmpPos);
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, tmpPos[0], tmpPos[1], tmpPos[2]);
            Matrix.scaleM(model, 0, TARGET_SCALE * k, TARGET_SCALE * k, TARGET_SCALE * k);
            drawWorld(sphere, sphereVerts, 5f, 0.9f, 0.42f, 0.12f);
        }
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
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
        if (comboTimer > 0f) { comboTimer -= dt; if (comboTimer <= 0f) combo = 1; }

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
                if (score > highScore) {                 // persist new best
                    highScore = score;
                    prefs.edit().putInt("highscore", highScore).apply();
                }
            }
        }
        input.setGameOver(gameOver);
    }

    private void restart() {
        score = 0;
        combo = 1;
        comboTimer = 0f;
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
            targetAlive[idx] = false; targetRespawn[idx] = RESPAWN_TIME;
            combo = (comboTimer > 0f) ? Math.min(combo + 1, 9) : 1;   // chain hits -> higher multiplier
            comboTimer = COMBO_WINDOW;
            score += combo;
            lastShotHit = true;
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

        // Cinematic vignette over the scene (before HUD widgets).
        GLES20.glUseProgram(progVig);
        quad.position(0);
        GLES20.glEnableVertexAttribArray(aPVig);
        GLES20.glVertexAttribPointer(aPVig, 2, GLES20.GL_FLOAT, false, 8, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);

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

            // combo multiplier (chain hits) + depleting timer bar
            if (combo > 1) {
                float ct = comboTimer / COMBO_WINDOW;
                drawRectPx(width * 0.5f, 126f, 130f * ct, 6f, 1f, 0.78f, 0.2f, 0.9f);
                float mg = combo >= 5 ? 0.42f : (combo >= 3 ? 0.75f : 1f);
                float mb = combo >= 3 ? 0.2f : 0.5f;
                drawNumberCentered(combo, 162f, 20f, 34f, 6f, 12f, 1f, mg, mb, 0.95f);
            }
        } else {
            drawQuadNDC(0f, 0f, 1f, 1f, 0f, 0f, 0f, 0.62f);
            drawNumberCentered(score, height * 0.40f, 56f, 96f, 12f, 30f, 1f, 1f, 1f, 1f);
            // best score (gold) below the run score
            drawNumberCentered(highScore, height * 0.40f + 98f, 22f, 40f, 6f, 14f, 1f, 0.84f, 0.3f, 0.95f);
            drawCircle(width * 0.5f, height * 0.74f, 86f, 0.2f, 0.9f, 0.35f, 0.85f);
            drawRectPx(width * 0.5f, height * 0.74f, 34f, 34f, 0.05f, 0.15f, 0.05f, 0.9f);
        }

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

    // --- textures (procedural, detailed) ---

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
        int N = 512;
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF0C0E12);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(11);

        // four metal plates with bevels
        int s = N / 2, gap = 14;
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                float L = i * s + gap, T = j * s + gap, R = (i + 1) * s - gap, B = (j + 1) * s - gap;
                int base = 0xFF161B23 + rnd.nextInt(0x0A) * 0x010101;
                p.setStyle(Paint.Style.FILL); p.setColor(base); c.drawRect(L, T, R, B, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f);
                p.setColor(0xFF323C4A); c.drawRect(L + 3, T + 3, R - 3, B - 3, p);   // inner bevel
                p.setColor(0xFF0A0C10); c.drawRect(L, T, R, B, p);                    // outer shade
            }
        }
        // glowing seam cross
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0F4E63); c.drawRect(0, s - 7, N, s + 7, p); c.drawRect(s - 7, 0, s + 7, N, p);
        p.setColor(0xFF31B6D8); c.drawRect(0, s - 2, N, s + 2, p); c.drawRect(s - 2, 0, s + 2, N, p);
        // bolts at plate corners
        p.setColor(0xFF3A4554);
        int[] bx = {gap + 14, s - gap - 14, s + gap + 14, N - gap - 14};
        for (int xi = 0; xi < 4; xi++) for (int yi = 0; yi < 4; yi++) c.drawCircle(bx[xi], bx[yi], 4f, p);
        // scratches
        p.setStyle(Paint.Style.STROKE);
        for (int k = 0; k < 80; k++) {
            int a = 0x10 + rnd.nextInt(0x30);
            p.setStrokeWidth(1f + rnd.nextFloat());
            p.setColor((a << 24) | 0x00C8D2E0);
            float x0 = rnd.nextInt(N), y0 = rnd.nextInt(N), ln = 8 + rnd.nextInt(60), ang = rnd.nextFloat() * 6.28f;
            c.drawLine(x0, y0, x0 + ln * (float) Math.cos(ang), y0 + ln * (float) Math.sin(ang), p);
        }
        // grime speckles
        p.setStyle(Paint.Style.FILL);
        for (int k = 0; k < 2600; k++) {
            int g = rnd.nextInt(80);
            p.setColor((0x26 << 24) | (g << 16) | (g << 8) | g);
            float x = rnd.nextInt(N), y = rnd.nextInt(N);
            c.drawRect(x, y, x + 1 + rnd.nextInt(2), y + 1 + rnd.nextInt(2), p);
        }
        return b;
    }

    private static Bitmap makeMetalBitmap() {
        int N = 512;
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        Random rnd = new Random(5);
        // brushed vertical base
        for (int x = 0; x < N; x++) {
            int gr = 0x2C + rnd.nextInt(0x1C);
            p.setColor(0xFF000000 | (gr << 16) | (gr << 8) | (gr + 6));
            c.drawLine(x, 0, x, N, p);
        }
        // horizontal panel bands
        p.setColor(0xFF181C22); c.drawRect(0, 70, N, 96, p); c.drawRect(0, N - 96, N, N - 70, p);
        // warning stripe accent
        p.setColor(0xFFB7892A);
        for (int x = -N; x < N; x += 36) { p.setStrokeWidth(0f); }
        p.setStyle(Paint.Style.FILL);
        for (int x = -N; x < N; x += 40) {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x, 116); path.lineTo(x + 18, 116); path.lineTo(x + 18 - 14, 140); path.lineTo(x - 14, 140); path.close();
            p.setColor((x / 40) % 2 == 0 ? 0xFFB7892A : 0xFF14171C); c.drawPath(path, p);
        }
        // border frame + rivets
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(8f); p.setColor(0xFF101319); c.drawRect(6, 6, N - 6, N - 6, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFF424C5C);
        int m = 26;
        c.drawCircle(m, m, 7f, p); c.drawCircle(N - m, m, 7f, p); c.drawCircle(m, N - m, 7f, p); c.drawCircle(N - m, N - m, 7f, p);
        // scratches
        p.setStyle(Paint.Style.STROKE);
        for (int k = 0; k < 60; k++) {
            int a = 0x14 + rnd.nextInt(0x2A);
            p.setStrokeWidth(1f); p.setColor((a << 24) | 0x00D8DEE8);
            float x0 = rnd.nextInt(N), y0 = rnd.nextInt(N), ln = 10 + rnd.nextInt(50);
            c.drawLine(x0, y0, x0 + ln, y0 + (rnd.nextInt(7) - 3), p);
        }
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
        "vec3 aces(vec3 x){ return clamp((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14),0.0,1.0); }" +
        "void main(){" +
        "  vec3 N=normalize(vNormal); vec3 col;" +
        "  if(uMode<0.5){" +
        "    vec3 tex=texture2D(uTex,vUV).rgb;" +
        "    vec3 V=normalize(uCamPos-vWorld); vec3 L=normalize(uLightDir);" +
        "    float df=max(dot(N,L),0.0);" +
        "    float fl=max(dot(N,normalize(vec3(0.5,0.25,0.6))),0.0);" +
        "    float rim=pow(1.0-max(dot(N,V),0.0),3.0);" +
        "    vec3 H=normalize(L+V); float sp=pow(max(dot(N,H),0.0),48.0);" +
        "    vec3 base=tex*uColor;" +
        "    col=base*(vec3(0.16,0.18,0.24)+df*vec3(1.15,1.05,0.9)+fl*vec3(0.25,0.32,0.45))" +
        "        +sp*vec3(0.9)*tex.r+rim*vec3(0.18,0.22,0.30);" +
        "  } else if(uMode<2.5){" +
        "    vec3 V=normalize(uCamPos-vWorld); float fr=pow(1.0-max(dot(N,V),0.0),2.0);" +
        "    float pulse=0.80+0.20*sin(uTime*5.0);" +
        "    col=uColor*pulse+uColor*fr*1.8+fr*vec3(0.35);" +
        "  } else if(uMode<3.5){" +
        "    vec3 L=normalize(vec3(-0.3,0.55,0.75)); vec3 V=vec3(0.0,0.0,1.0);" +
        "    float df=max(dot(N,L),0.0); vec3 H=normalize(L+V); float sp=pow(max(dot(N,H),0.0),40.0);" +
        "    float rim=pow(1.0-max(dot(N,V),0.0),3.0);" +
        "    col=uColor*(vec3(0.18,0.20,0.26)+df*vec3(1.1))+sp*vec3(0.8)+rim*vec3(0.15);" +
        "    gl_FragColor=vec4(pow(aces(col),vec3(0.4545)),1.0); return;" +
        "  } else if(uMode<4.5){" +
        "    gl_FragColor=vec4(pow(min(uColor,vec3(1.0)),vec3(0.4545)),1.0); return;" +
        "  } else {" +                          // additive glow halo
        "    vec3 V=normalize(uCamPos-vWorld); float f=max(dot(N,V),0.0);" +
        "    gl_FragColor=vec4(uColor*pow(f,2.0),1.0); return;" +
        "  }" +
        "  float dist=length(uCamPos-vWorld); float fog=clamp((dist-10.0)/60.0,0.0,0.82);" +
        "  col=mix(col,uFogColor,fog);" +
        "  gl_FragColor=vec4(pow(aces(col),vec3(0.4545)),1.0);" +
        "}";

    private static final String VERT_SKY_SRC =
        "attribute vec2 aP; varying float vy; void main(){ vy=aP.y; gl_Position=vec4(aP,0.999,1.0); }";

    private static final String FRAG_SKY_SRC =
        "precision mediump float; varying float vy; uniform vec3 uTop; uniform vec3 uBot;" +
        "void main(){ float t=clamp(vy*0.5+0.5,0.0,1.0); vec3 c=mix(uBot,uTop,t*t);" +
        "  float h=1.0-smoothstep(0.0,0.18,abs(vy)); c+=vec3(0.20,0.14,0.09)*h;" +
        "  gl_FragColor=vec4(pow(c,vec3(0.4545)),1.0); }";

    private static final String VERT_VIG_SRC =
        "attribute vec2 aP; varying vec2 vP; void main(){ vP=aP; gl_Position=vec4(aP,0.0,1.0); }";

    private static final String FRAG_VIG_SRC =
        "precision mediump float; varying vec2 vP;" +
        "void main(){ float d=length(vP); float a=smoothstep(0.65,1.45,d)*0.6; gl_FragColor=vec4(0.0,0.0,0.0,a); }";

    private static final String VERT_BLOB_SRC =
        "attribute vec2 aP; uniform mat4 uMVP; varying float vR;" +
        "void main(){ vR=length(aP); gl_Position=uMVP*vec4(aP.x,0.0,aP.y,1.0); }";

    private static final String FRAG_BLOB_SRC =
        "precision mediump float; varying float vR; uniform float uA;" +
        "void main(){ gl_FragColor=vec4(0.0,0.0,0.0,(1.0-vR)*uA); }";

    private static final String VERT2_SRC =
        "attribute vec2 aP; uniform vec2 uScale; uniform vec2 uOff;" +
        "void main(){ gl_Position=vec4(aP*uScale+uOff,0.0,1.0); }";

    private static final String FRAG2_SRC =
        "precision mediump float; uniform vec4 uCol; void main(){ gl_FragColor=uCol; }";
}
