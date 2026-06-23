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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * First-person survival shooter (GLES 2.0). Low-poly enemy figures spawn around
 * the arena and walk toward the player (animated legs/arms, not gliding); shoot
 * them before they reach you and drain your health. Three weapons — pistol, SMG
 * and shotgun — each with its own magazine, reserve ammo, recoil and feel; kills
 * top up your ammo. Headshots do far more damage than body hits. Solid cover you
 * cannot walk through, textured PBR-ish lighting, fog, gradient sky, vignette,
 * contact shadows, weapon viewmodels, procedural SFX, combo multiplier and a
 * persistent high score.
 */
public class FpsRenderer implements GLSurfaceView.Renderer {

    private static final float MOVE_SPEED = 4.5f;
    private static final float LOOK_SENS = 0.0045f;
    private static final float EYE_H = 1.6f;          // eye height above the feet
    private static final float GRAVITY = 13.0f;
    private static final float JUMP_V = 4.7f;          // a plain hop lifts the feet ~0.85 m
    private static final float MANTLE_MAX = 1.85f;     // tallest ledge you can climb onto
    private static final float SPRINT_MULT = 1.45f;
    private static final int STRIDE = 32;             // 8 floats: pos3, normal3, uv2
    private static final float MUZZLE_TIME = 0.08f;
    private static final float HIT_TIME = 0.25f;
    private static final float FLASH_TIME = 0.15f;
    private static final float HITMARK_TIME = 0.22f;
    private static final float COMBO_WINDOW = 2.2f;

    // Weapons: 0 = pistol, 1 = SMG (full-auto), 2 = shotgun.
    private static final int W_COUNT = 3;
    private static final int[]     W_MAG       = {12, 30, 6};
    private static final int[]     W_RES_START = {48, 90, 24};
    private static final int[]     W_RES_MAX   = {120, 240, 48};
    private static final float[]   W_INTERVAL  = {0.20f, 0.085f, 0.72f};   // s between shots
    private static final float[]   W_RELOAD    = {1.15f, 1.70f, 2.00f};
    private static final int[]     W_PELLETS   = {1, 1, 9};
    private static final float[]   W_SPREAD    = {0.0f, 0.018f, 0.085f};
    private static final float[]   W_BODYDMG   = {55f, 26f, 20f};
    private static final float[]   W_HEADDMG   = {120f, 60f, 46f};
    private static final float[]   W_RECOIL    = {0.030f, 0.016f, 0.075f};
    private static final float[]   W_SHAKE     = {0.55f, 0.32f, 1.00f};
    private static final int[]     W_KILL_REWARD = {6, 10, 4};
    private static final boolean[] W_AUTO      = {false, true, false};

    // Enemies / survival.
    private static final int MAX_ENEMIES = 7;
    private static final float ENEMY_SPEED = 1.7f;     // base m/s
    private static final float ENEMY_FULL_HP = 100f;
    private static final float SPAWN_DIST = 30f;
    private static final float REACH_DIST = 1.5f;
    private static final float ENEMY_DMG = 20f;
    private static final float MAX_HP = 100f;
    private static final float ARENA_LIMIT = 23f;

    private static final float LDX = 0.358f, LDY = -0.894f, LDZ = 0.268f;

    private final InputState input;
    private final int buildNumber;
    private final SharedPreferences prefs;
    private final Sfx sfx;
    private final Random rng = new Random(20);
    private final float[] lookTmp = new float[2];

    private int width = 1, height = 1;

    private int prog3, aPos, aNormal, aUV, uMVP, uModel, uColor, uMode, uLightDir, uCamPos, uFogColor, uTime, uTex;
    private int progSky, aPSky, uSkyTop, uSkyBot;
    private int progVig, aPVig;
    private int progBlob, aPBlob, uBlobMVP, uBlobA;
    private int prog2, aP2, uScale2, uOff2, uCol2;

    private int floorTex, metalTex, terrainTex, cityTex, vegTex;

    private FloatBuffer cube, floor, sphere, quad, circle, terrain, cityGround, vegetation;
    private int sphereVerts, circleVerts, terrainVerts, vegVerts;

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
    private float aim = 0f;          // 0 = hip fire, 1 = aiming down the iron sights
    private boolean aimOn = false;   // toggle target
    private float aspect = 1f;

    // Vertical movement + sprint.
    private float feetY = 0f;        // height of the player's feet (0 = floor)
    private float vy = 0f;           // vertical velocity
    private boolean grounded = true;
    private boolean sprinting = false;
    private float sprintAnim = 0f;   // eased 0..1 for the sprint weapon pose
    private float bobPhase = 0f;     // head-bob / footstep phase

    private float muzzleTimer = 0f, hitTimer = 0f, flashTimer = 0f, hurtFlash = 0f;
    private float hitMarkerTimer = 0f;
    private boolean hitMarkerHead = false;
    private int hitBox = -1;
    private boolean lastShotHit = false, lastShotHead = false;

    private int score = 0;
    private int combo = 1;
    private float comboTimer = 0f;
    private int highScore = 0;
    private float playerHP = MAX_HP;
    private boolean gameOver = false;

    // Per-weapon ammo state.
    private int curWeapon = 0;
    private final int[] wMag = new int[W_COUNT];
    private final int[] wReserve = new int[W_COUNT];
    private float fireCd = 0f;
    private float reloadTimer = 0f, reloadTotal = 1f;
    private float shake = 0f;
    private float switchAnim = 0f;

    private final float[] enX = new float[MAX_ENEMIES];
    private final float[] enZ = new float[MAX_ENEMIES];
    private final float[] enFace = new float[MAX_ENEMIES];
    private final float[] enHP = new float[MAX_ENEMIES];
    private final float[] enPhase = new float[MAX_ENEMIES];
    private final float[] enHurt = new float[MAX_ENEMIES];
    private final int[] enOutfit = new int[MAX_ENEMIES];
    private final int[] enFaceType = new int[MAX_ENEMIES];
    private final boolean[] enAlive = new boolean[MAX_ENEMIES];
    private float spawnTimer = 0f;

    // Clothing schemes: shirt RGB (torso + arms) + pants RGB (legs). Bright/saturated so
    // enemies stand out from the grey buildings and green ground.
    private static final float[][] OUTFITS = {
        {0.88f, 0.16f, 0.15f,  0.15f, 0.17f, 0.30f},   // red shirt / navy
        {0.13f, 0.45f, 0.90f,  0.20f, 0.20f, 0.23f},   // blue / charcoal
        {0.18f, 0.68f, 0.28f,  0.34f, 0.23f, 0.12f},   // green / brown
        {0.97f, 0.56f, 0.10f,  0.12f, 0.12f, 0.14f},   // orange / black
        {0.64f, 0.22f, 0.82f,  0.18f, 0.18f, 0.26f},   // purple / dark
        {0.05f, 0.72f, 0.74f,  0.16f, 0.20f, 0.32f},   // teal / navy
        {0.93f, 0.32f, 0.62f,  0.26f, 0.26f, 0.30f},   // pink / grey
        {0.90f, 0.88f, 0.84f,  0.20f, 0.28f, 0.52f},   // white tee / jeans
    };

    private final float[] colTmp = new float[2];

    // World geometry — cover crates/pillars first, then building wall/roof boxes.
    // Each: posX, posY, posZ, scaleX, scaleY, scaleZ, r, g, b  (posY is the centre).
    private static final int COVER_BOXES = 4;        // first N cast a ground-shadow blob
    private final float[][] boxes;

    // Interactive doors. doorData[i] = cx, cy, cz, hw, hh, ht, axis(0=spansX,1=spansZ), hingeSign, r,g,b.
    private static final float INTERACT_DIST = 2.6f;
    private final float[][] doorData;
    private final float[] doorOpen, doorTarget;       // 0 = closed, 1 = open
    private int nearDoor = -1;

    private static final float[] FOG = {0.46f, 0.52f, 0.62f};

    public FpsRenderer(InputState input, int buildNumber, Context ctx) {
        this.input = input;
        this.buildNumber = buildNumber;
        this.prefs = ctx.getSharedPreferences("aigames_fps", Context.MODE_PRIVATE);
        this.highScore = prefs.getInt("highscore", 0);
        this.sfx = new Sfx(ctx);

        ArrayList<float[]> bl = new ArrayList<float[]>();
        ArrayList<float[]> dl = new ArrayList<float[]>();
        buildWorldInto(bl, dl);
        this.boxes = bl.toArray(new float[0][]);
        this.doorData = dl.toArray(new float[0][]);
        this.doorOpen = new float[doorData.length];
        this.doorTarget = new float[doorData.length];
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

        float[] terr = makeTerrain(56, 80f);
        terrain = makeBuffer(terr);
        terrainVerts = terr.length / 8;
        terrainTex = uploadTexture(makeTerrainBitmap());

        cityGround = makeBuffer(makeFlatQuad(21f, 0.02f));
        cityTex = uploadTexture(makeCityBitmap());

        vegetation = makeBuffer(makeVegetation());   // grass, flowers, bushes (sets vegVerts)
        vegTex = uploadPalette(makeVegPalette());

        restart();
        lastNanos = System.nanoTime();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = w;
        height = Math.max(1, h);
        GLES20.glViewport(0, 0, w, h);
        aspect = (float) w / height;
        Matrix.perspectiveM(proj, 0, 68f, aspect, 0.05f, 300f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        float dt = (now - lastNanos) / 1_000_000_000f;
        lastNanos = now;
        if (dt > 0.1f) dt = 0.1f;
        timeAcc += dt;

        updateDoors(dt);
        updateCamera(dt);
        updateEnemies(dt);
        tickTimers(dt);

        float fov = 68f - 26f * aim + 6f * sprintAnim;   // zoom in on ADS, widen on sprint
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.05f, 300f);

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

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, terrainTex);
        Matrix.setIdentityM(model, 0);
        drawWorld(terrain, terrainVerts, 0f, 1f, 1f, 1f);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cityTex);   // streets + sidewalks over the flat core
        drawWorld(cityGround, 6, 0f, 1f, 1f, 1f);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vegTex);    // grass / flowers / bushes
        Matrix.setIdentityM(model, 0);
        drawWorld(vegetation, vegVerts, 0f, 1f, 1f, 1f);

        drawShadows();

        GLES20.glUseProgram(prog3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, metalTex);
        for (int i = 0; i < boxes.length; i++) {
            float[] b = boxes[i];
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, b[0], b[1], b[2]);
            Matrix.scaleM(model, 0, b[3], b[4], b[5]);
            float boost = (i == hitBox && hitTimer > 0f) ? 1.6f : 1f;
            drawWorld(cube, 36, 0f, b[6] * boost, b[7] * boost, b[8] * boost);
        }

        drawDoors();
        drawEnemies();

        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
        drawGun();

        drawHud();
    }

    // --- enemies ---

    private void updateEnemies(float dt) {
        if (gameOver) return;
        float speed = ENEMY_SPEED * (1f + Math.min(score, 100) * 0.012f);
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (enHurt[i] > 0f) enHurt[i] -= dt;
            if (!enAlive[i]) continue;
            float dx = px - enX[i], dz = pz - enZ[i];
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            enFace[i] = (float) Math.atan2(dx, dz);
            if (d < REACH_DIST) {
                enAlive[i] = false;
                playerHP -= ENEMY_DMG;
                hurtFlash = 0.6f;
                combo = 1; comboTimer = 0f;
                sfx.hurt();
                if (playerHP <= 0f) { playerHP = 0f; triggerGameOver(); }
            } else if (d > 1e-4f) {
                float a = steerDir(enX[i], enZ[i], (float) Math.atan2(dx, dz));  // navigate round buildings
                enFace[i] = a;                            // face the way it actually moves
                float step = speed * dt;
                enX[i] += (float) Math.sin(a) * step;
                enZ[i] += (float) Math.cos(a) * step;
                enPhase[i] += step * 9f;                  // advance walk cycle with distance
                colTmp[0] = enX[i]; colTmp[1] = enZ[i];
                collide(0.4f, 0f, false, colTmp);         // backstop: never pass through cover
                enX[i] = colTmp[0]; enZ[i] = colTmp[1];
            }
        }
        spawnTimer -= dt;
        if (spawnTimer <= 0f) {
            spawnEnemy();
            spawnTimer = Math.max(0.5f, 1.6f - score * 0.012f);
        }
    }

    private void spawnEnemy() {
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (!enAlive[i]) {
                float a = rng.nextFloat() * 6.2832f;
                enX[i] = (float) Math.cos(a) * SPAWN_DIST;
                enZ[i] = (float) Math.sin(a) * SPAWN_DIST;
                enFace[i] = 0f;
                enHP[i] = ENEMY_FULL_HP;
                enPhase[i] = rng.nextFloat() * 6.2832f;
                enHurt[i] = 0f;
                enOutfit[i] = rng.nextInt(OUTFITS.length);
                enFaceType[i] = rng.nextInt(6);
                enAlive[i] = true;
                return;
            }
        }
    }

    private void triggerGameOver() {
        if (gameOver) return;
        gameOver = true;
        aimOn = false;
        sfx.over();
        if (score > highScore) {
            highScore = score;
            prefs.edit().putInt("highscore", highScore).apply();
        }
    }

    // Steering: try headings fanned out from "straight at the player" and take the first one whose
    // short probe ahead is clear of buildings — so enemies round corners and use the streets.
    private static final float[] STEER_OFFSETS = {0f, 0.45f, -0.45f, 0.95f, -0.95f, 1.5f, -1.5f, 2.1f, -2.1f};

    private float steerDir(float ex, float ez, float desired) {
        for (int k = 0; k < STEER_OFFSETS.length; k++) {
            float a = desired + STEER_OFFSETS[k];
            float pxp = ex + (float) Math.sin(a) * 1.5f;
            float pzp = ez + (float) Math.cos(a) * 1.5f;
            if (clearOfBuildings(pxp, pzp, 0.5f)) return a;
        }
        return desired;                                   // boxed in: go straight, collide() will slide
    }

    private boolean clearOfBuildings(float x, float z, float r) {
        for (int i = 0; i < boxes.length; i++) {
            float[] b = boxes[i];
            if (b[1] + b[4] * 0.5f < 0.5f) continue;      // ignore anything you'd just step over
            if (b[1] - b[4] * 0.5f >= 1.8f) continue;     // overhead (door lintel / roof): enemy walks under
            if (x > b[0] - b[3] * 0.5f - r && x < b[0] + b[3] * 0.5f + r
             && z > b[2] - b[5] * 0.5f - r && z < b[2] + b[5] * 0.5f + r) return false;
        }
        return true;
    }

    private void drawEnemies() {
        GLES20.glUseProgram(prog3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, metalTex);
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (!enAlive[i]) continue;
            float sw = (float) Math.sin(enPhase[i]);          // limb swing
            float bob = Math.abs(sw) * 0.045f;                // gait bob
            float k = enHurt[i] > 0f ? 1.8f : 1f;             // hit flash
            float[] o = OUTFITS[enOutfit[i]];
            float shR = o[0] * k, shG = o[1] * k, shB = o[2] * k;   // shirt (torso + arms)
            float paR = o[3] * k, paG = o[4] * k, paB = o[5] * k;   // pants (legs)
            Matrix.setIdentityM(gunBase, 0);                  // reuse as enemy base
            Matrix.translateM(gunBase, 0, enX[i], terrainH(enX[i], enZ[i]) + bob, enZ[i]);
            Matrix.rotateM(gunBase, 0, (float) Math.toDegrees(enFace[i]), 0f, 1f, 0f);
            enemyPart(0f, 0.95f, 0f, 0.50f, 0.75f, 0.30f, shR, shG, shB);                       // torso (shirt)
            enemyPart(0f, 1.50f, 0f, 0.34f, 0.34f, 0.34f, 0.97f * k, 0.83f * k, 0.30f * k);     // yellow head
            enemyFace(enFaceType[i]);                                                           // goofy face (varies)
            enemyLimb(-0.14f, 0.70f, 0f, 0.35f, 0.18f, 0.70f, 0.22f,  sw * 26f, paR, paG, paB); // leg L
            enemyLimb( 0.14f, 0.70f, 0f, 0.35f, 0.18f, 0.70f, 0.22f, -sw * 26f, paR, paG, paB); // leg R
            enemyLimb(-0.33f, 1.30f, 0f, 0.29f, 0.13f, 0.58f, 0.16f, -sw * 22f, shR, shG, shB); // arm L
            enemyLimb( 0.33f, 1.30f, 0f, 0.29f, 0.13f, 0.58f, 0.16f,  sw * 22f, shR, shG, shB); // arm R
        }
    }

    /** One of several funny / dopey faces (chosen per enemy). Features drawn UNLIT (mode 4)
     *  for high contrast on the yellow head; local +z always faces the player. */
    private void enemyFace(int t) {
        float fz = 0.176f;
        switch (t) {
            case 0:   // big goofy open grin  :D
                enemyFacePart(-0.085f, 1.560f, fz, 0.052f, 0.054f, 0.02f, 0f, 0f, 0f);   // eye L
                enemyFacePart( 0.085f, 1.560f, fz, 0.052f, 0.054f, 0.02f, 0f, 0f, 0f);   // eye R
                enemyFacePart( 0.000f, 1.452f, fz, 0.190f, 0.040f, 0.02f, 0f, 0f, 0f);   // wide mouth top
                enemyFacePart( 0.000f, 1.410f, fz, 0.120f, 0.055f, 0.02f, 0f, 0f, 0f);   // open lower
                break;
            case 1:   // surprised dope  o_o
                enemyFacePart(-0.088f, 1.560f, fz, 0.070f, 0.070f, 0.02f, 0f, 0f, 0f);   // big eye L
                enemyFacePart( 0.088f, 1.560f, fz, 0.070f, 0.070f, 0.02f, 0f, 0f, 0f);   // big eye R
                enemyFacePart( 0.000f, 1.430f, fz, 0.062f, 0.072f, 0.02f, 0f, 0f, 0f);   // small "o" mouth
                break;
            case 2:   // tongue out  :P
                enemyFacePart(-0.085f, 1.560f, fz, 0.050f, 0.052f, 0.02f, 0f, 0f, 0f);
                enemyFacePart( 0.085f, 1.560f, fz, 0.050f, 0.052f, 0.02f, 0f, 0f, 0f);
                enemyFacePart( 0.000f, 1.470f, fz, 0.150f, 0.028f, 0.02f, 0f, 0f, 0f);   // mouth line
                enemyFacePart( 0.035f, 1.420f, fz + 0.012f, 0.062f, 0.078f, 0.02f, 0.96f, 0.36f, 0.46f); // pink tongue
                break;
            case 3:   // happy closed eyes  ^_^
                enemyFacePart(-0.122f, 1.566f, fz, 0.032f, 0.030f, 0.02f, 0f, 0f, 0f);   // eye L arc (ends up)
                enemyFacePart(-0.085f, 1.546f, fz, 0.032f, 0.030f, 0.02f, 0f, 0f, 0f);
                enemyFacePart(-0.048f, 1.566f, fz, 0.032f, 0.030f, 0.02f, 0f, 0f, 0f);
                enemyFacePart( 0.048f, 1.566f, fz, 0.032f, 0.030f, 0.02f, 0f, 0f, 0f);   // eye R arc
                enemyFacePart( 0.085f, 1.546f, fz, 0.032f, 0.030f, 0.02f, 0f, 0f, 0f);
                enemyFacePart( 0.122f, 1.566f, fz, 0.032f, 0.030f, 0.02f, 0f, 0f, 0f);
                enemyFacePart( 0.000f, 1.430f, fz, 0.075f, 0.030f, 0.02f, 0f, 0f, 0f);   // small smile
                enemyFacePart(-0.058f, 1.446f, fz, 0.038f, 0.030f, 0.02f, 0f, 0f, 0f);
                enemyFacePart( 0.058f, 1.446f, fz, 0.038f, 0.030f, 0.02f, 0f, 0f, 0f);
                break;
            case 4:   // wonky / cross-eyed derp
                enemyFacePart(-0.078f, 1.578f, fz, 0.058f, 0.058f, 0.02f, 0f, 0f, 0f);   // higher, bigger
                enemyFacePart( 0.092f, 1.534f, fz, 0.044f, 0.044f, 0.02f, 0f, 0f, 0f);   // lower, smaller
                enemyFacePart( 0.000f, 1.448f, fz, 0.095f, 0.026f, 0.02f, 0f, 0f, 0f);   // mouth
                enemyFacePart(-0.062f, 1.462f, fz, 0.040f, 0.026f, 0.02f, 0f, 0f, 0f);   // little wave
                break;
            default:  // big U smile
                enemyFacePart(-0.085f, 1.560f, fz, 0.050f, 0.052f, 0.02f, 0f, 0f, 0f);
                enemyFacePart( 0.085f, 1.560f, fz, 0.050f, 0.052f, 0.02f, 0f, 0f, 0f);
                enemyFacePart( 0.000f, 1.420f, fz, 0.078f, 0.030f, 0.02f, 0f, 0f, 0f);   // low centre
                enemyFacePart(-0.072f, 1.440f, fz, 0.046f, 0.030f, 0.02f, 0f, 0f, 0f);
                enemyFacePart( 0.072f, 1.440f, fz, 0.046f, 0.030f, 0.02f, 0f, 0f, 0f);
                enemyFacePart(-0.112f, 1.475f, fz, 0.046f, 0.030f, 0.02f, 0f, 0f, 0f);   // corners up
                enemyFacePart( 0.112f, 1.475f, fz, 0.046f, 0.030f, 0.02f, 0f, 0f, 0f);
                break;
        }
    }

    /** Like enemyPart but UNLIT (mode 4) for crisp, high-contrast facial features. */
    private void enemyFacePart(float lx, float ly, float lz, float sx, float sy, float sz,
                               float r, float g, float b) {
        Matrix.setIdentityM(partM, 0);
        Matrix.translateM(partM, 0, lx, ly, lz);
        Matrix.scaleM(partM, 0, sx, sy, sz);
        Matrix.multiplyMM(model, 0, gunBase, 0, partM, 0);
        drawWorld(cube, 36, 4f, r, g, b);
    }

    private void enemyPart(float lx, float ly, float lz, float sx, float sy, float sz,
                           float r, float g, float b) {
        Matrix.setIdentityM(partM, 0);
        Matrix.translateM(partM, 0, lx, ly, lz);
        Matrix.scaleM(partM, 0, sx, sy, sz);
        Matrix.multiplyMM(model, 0, gunBase, 0, partM, 0);   // model = base * part (world)
        drawWorld(cube, 36, 0f, r, g, b);
    }

    /** A limb that pivots about its top (hip/shoulder) so it swings when walking. */
    private void enemyLimb(float pivX, float pivY, float pivZ, float halfLen,
                           float sx, float sy, float sz, float angleDeg,
                           float r, float g, float b) {
        Matrix.setIdentityM(partM, 0);
        Matrix.translateM(partM, 0, pivX, pivY, pivZ);
        Matrix.rotateM(partM, 0, angleDeg, 1f, 0f, 0f);
        Matrix.translateM(partM, 0, 0f, -halfLen, 0f);
        Matrix.scaleM(partM, 0, sx, sy, sz);
        Matrix.multiplyMM(model, 0, gunBase, 0, partM, 0);
        drawWorld(cube, 36, 0f, r, g, b);
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

    // --- interactive doors ---

    private void updateDoors(float dt) {
        int near = -1;
        float best = INTERACT_DIST * INTERACT_DIST;
        if (!gameOver) {
            for (int i = 0; i < doorData.length; i++) {
                float dx = px - doorData[i][0], dz = pz - doorData[i][2];
                float d2 = dx * dx + dz * dz;
                if (d2 < best) { best = d2; near = i; }
            }
        }
        nearDoor = near;
        input.setDoorInRange(near >= 0);
        boolean tap = input.consumeInteract();
        if (tap && near >= 0) {
            doorTarget[near] = (doorTarget[near] > 0.5f) ? 0f : 1f;   // toggle open / close
            sfx.reload();                                            // mechanical clack
        }
        for (int i = 0; i < doorData.length; i++) {
            doorOpen[i] += (doorTarget[i] - doorOpen[i]) * Math.min(1f, dt * 6f);
            if (doorOpen[i] < 0.001f) doorOpen[i] = 0f;
            else if (doorOpen[i] > 0.999f) doorOpen[i] = 1f;
        }
    }

    private void drawDoors() {
        GLES20.glUseProgram(prog3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, metalTex);
        for (int i = 0; i < doorData.length; i++) {
            float[] dd = doorData[i];
            float ang = doorOpen[i] * 85f;                  // swing open up to ~85°
            Matrix.setIdentityM(model, 0);
            if (dd[6] == 0f) {                              // wall spans X, hinge on the +X edge
                Matrix.translateM(model, 0, dd[0] + dd[3], dd[1], dd[2]);
                Matrix.rotateM(model, 0, ang, 0f, 1f, 0f);
                Matrix.translateM(model, 0, -dd[3], 0f, 0f);
                Matrix.scaleM(model, 0, dd[3] * 2f, dd[4] * 2f, dd[5] * 2f);
            } else {                                        // wall spans Z, hinge on the +Z edge
                Matrix.translateM(model, 0, dd[0], dd[1], dd[2] + dd[3]);
                Matrix.rotateM(model, 0, ang, 0f, 1f, 0f);
                Matrix.translateM(model, 0, 0f, 0f, -dd[3]);
                Matrix.scaleM(model, 0, dd[5] * 2f, dd[4] * 2f, dd[3] * 2f);
            }
            float em = (i == nearDoor) ? 1.3f : 1f;         // highlight the door you can use
            drawWorld(cube, 36, 0f, dd[8] * em, dd[9] * em, dd[10] * em);
        }
    }

    // --- weapon viewmodels ---

    private void drawGun() {
        float a = aim, s = sprintAnim;
        Matrix.setIdentityM(gunBase, 0);
        // hip -> ADS: slide to centre, raise so the sights sit on the screen centre.
        float tx = mix(0.15f, 0.0f, a) + 0.05f * s;            // sprint pulls it inward
        float ty = mix(-0.16f, -0.12f, a) - switchAnim * 0.22f - 0.12f * s;  // ...and down
        float tz = mix(-0.52f, -0.30f, a) - recoil * 0.30f * (1f - 0.5f * a) + 0.16f * s;
        Matrix.translateM(gunBase, 0, tx, ty, tz);
        Matrix.rotateM(gunBase, 0, -3.0f * (1f - a) + recoil * 9f * (1f - 0.5f * a) + switchAnim * 22f + 20f * s, 1f, 0f, 0f);
        Matrix.rotateM(gunBase, 0, -4f * (1f - a) + 24f * s, 0f, 1f, 0f);
        Matrix.rotateM(gunBase, 0, 26f * s, 0f, 0f, 1f);       // cant across the body when sprinting

        float mz;
        if (curWeapon == 0) mz = drawPistol();
        else if (curWeapon == 1) mz = drawRifle();
        else mz = drawShotgun();

        if (muzzleTimer > 0f) drawMuzzle(mz, curWeapon == 2 ? 1.7f : 1f);
    }

    private float drawPistol() {
        gunPart(0f, 0.00f, -0.02f, 0.075f, 0.100f, 0.30f, 0.14f, 0.15f, 0.18f); // slide
        gunPart(0f, 0.01f, -0.20f, 0.050f, 0.055f, 0.10f, 0.10f, 0.10f, 0.12f); // barrel tip
        gunPart(0f, -0.13f, 0.10f, 0.070f, 0.170f, 0.085f, 0.10f, 0.09f, 0.08f); // grip
        gunPart(0f, -0.05f, 0.04f, 0.055f, 0.050f, 0.05f, 0.08f, 0.08f, 0.10f);  // trigger guard
        drawSights();
        return -0.30f;
    }

    private float drawRifle() {
        gunPart(0f, 0.00f, 0.04f, 0.090f, 0.11f, 0.42f, 0.13f, 0.14f, 0.17f);
        gunPart(0f, -0.02f, 0.26f, 0.075f, 0.10f, 0.16f, 0.12f, 0.12f, 0.15f);
        gunPart(0f, 0.02f, -0.34f, 0.040f, 0.040f, 0.34f, 0.20f, 0.21f, 0.24f);
        gunPart(0f, 0.02f, -0.52f, 0.052f, 0.052f, 0.06f, 0.09f, 0.09f, 0.11f);
        gunPart(0f, 0.050f, -0.10f, 0.045f, 0.035f, 0.22f, 0.10f, 0.10f, 0.12f);  // low handguard rail
        gunPart(0f, -0.14f, -0.02f, 0.052f, 0.16f, 0.085f, 0.18f, 0.18f, 0.22f);
        gunPart(0f, -0.14f, 0.14f, 0.060f, 0.15f, 0.080f, 0.12f, 0.10f, 0.09f);
        gunPart(0f, -0.07f, 0.06f, 0.050f, 0.045f, 0.05f, 0.09f, 0.09f, 0.11f);
        drawSights();
        return -0.56f;
    }

    private float drawShotgun() {
        gunPart(0f, 0.00f, 0.06f, 0.085f, 0.105f, 0.40f, 0.17f, 0.12f, 0.09f);  // receiver (wood)
        gunPart(0f, 0.030f, -0.30f, 0.050f, 0.050f, 0.42f, 0.23f, 0.24f, 0.27f); // barrel top
        gunPart(0f, -0.028f, -0.30f, 0.050f, 0.050f, 0.42f, 0.20f, 0.21f, 0.24f); // barrel bottom
        gunPart(0f, 0.00f, -0.18f, 0.078f, 0.060f, 0.18f, 0.10f, 0.08f, 0.06f);  // pump/forend
        gunPart(0f, -0.02f, 0.30f, 0.070f, 0.120f, 0.18f, 0.18f, 0.12f, 0.09f);  // stock (wood)
        gunPart(0f, -0.12f, 0.16f, 0.060f, 0.130f, 0.085f, 0.12f, 0.09f, 0.07f); // grip
        drawSights();
        return -0.62f;
    }

    /** Iron sights at FIXED positions so every weapon shows the same sight picture at the same
     *  on-screen size: a slim rear notch, a thin front blade and a small glowing bead = aim point. */
    private void drawSights() {
        float rearZ = -0.05f, frontZ = -0.30f;
        gunPart(-0.033f, 0.112f, rearZ, 0.009f, 0.042f, 0.024f, 0.05f, 0.05f, 0.06f);  // rear post L
        gunPart( 0.033f, 0.112f, rearZ, 0.009f, 0.042f, 0.024f, 0.05f, 0.05f, 0.06f);  // rear post R
        gunPart(0f, 0.103f, frontZ, 0.006f, 0.044f, 0.012f, 0.06f, 0.06f, 0.07f);      // thin front blade
        gunBeadPart(0f, 0.120f, frontZ, 0.011f,                                         // small bead = aim point
                weaponR(curWeapon) * 1.2f, weaponG(curWeapon) * 1.2f, weaponB(curWeapon) * 1.2f);
    }

    private void gunBeadPart(float tx, float ty, float tz, float s, float r, float g, float b) {
        Matrix.setIdentityM(partM, 0);
        Matrix.translateM(partM, 0, tx, ty, tz);
        Matrix.scaleM(partM, 0, s, s, s);
        Matrix.multiplyMM(tmpA, 0, gunBase, 0, partM, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmpA, 0);
        submit(cube, 36, mvp, tmpA, 4f, r, g, b);     // mode 4 = emissive bead
    }

    private static float mix(float a, float b, float t) { return a + (b - a) * t; }

    private void drawMuzzle(float mz, float scaleMul) {
        float k = muzzleTimer / MUZZLE_TIME;
        Matrix.setIdentityM(partM, 0);
        Matrix.translateM(partM, 0, 0f, 0.02f, mz);
        float s = (0.05f + 0.05f * k) * scaleMul;
        Matrix.scaleM(partM, 0, s, s, s);
        Matrix.multiplyMM(tmpA, 0, gunBase, 0, partM, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmpA, 0);
        submit(cube, 36, mvp, tmpA, 4f, 2.8f * k, 2.3f * k, 1.3f * k);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        GLES20.glDepthMask(false);
        Matrix.setIdentityM(partM, 0);
        Matrix.translateM(partM, 0, 0f, 0.02f, mz);
        float gs = (0.16f + 0.14f * k) * scaleMul;
        Matrix.scaleM(partM, 0, gs, gs, gs);
        Matrix.multiplyMM(tmpA, 0, gunBase, 0, partM, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmpA, 0);
        submit(sphere, sphereVerts, mvp, tmpA, 4f, 0.9f * k, 0.6f * k, 0.25f * k);
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
        if (input.consumeAimToggle()) aimOn = !aimOn;
        if (input.consumeSwitch()) cycleWeapon();

        float sens = LOOK_SENS * (1f - 0.45f * aim);     // steadier aim down sights
        input.consumeLook(lookTmp);
        yaw += lookTmp[0] * sens;
        pitch -= lookTmp[1] * sens;
        float limit = 1.48f;
        pitch = clamp(pitch, -limit, limit);

        boolean fired = false;
        if (input.consumeFire()) { fire(); fired = true; }
        if (!fired && !gameOver && W_AUTO[curWeapon] && input.isFireHeld()) fire();

        // --- horizontal movement; sprint when the stick is pushed fully forward ---
        float mx = input.moveX(), my = input.moveY();
        float mlen = (float) Math.sqrt(mx * mx + my * my);
        sprinting = !gameOver && grounded && mlen > 0.9f && my > 0.5f && aim < 0.2f;
        float speed = MOVE_SPEED * (sprinting ? SPRINT_MULT : 1f);
        float fwdX = (float) Math.sin(yaw), fwdZ = -(float) Math.cos(yaw);
        float rgtX = (float) Math.cos(yaw), rgtZ = (float) Math.sin(yaw);
        px += (rgtX * mx + fwdX * my) * speed * dt;
        pz += (rgtZ * mx + fwdZ * my) * speed * dt;
        colTmp[0] = px; colTmp[1] = pz;
        collide(0.32f, feetY, true, colTmp);             // walls block, ledges below the feet don't
        px = colTmp[0]; pz = colTmp[1];

        // --- vertical movement: jump / climb / gravity ---
        boolean wasGrounded = grounded;
        if (input.consumeJump() && !gameOver) doJump();
        vy -= GRAVITY * dt;
        feetY += vy * dt;
        float support = groundSupport();
        if (feetY <= support) {
            feetY = support; vy = 0f; grounded = true;
        } else if (wasGrounded && vy <= 0f && feetY - support <= 0.6f) {
            feetY = support; vy = 0f; grounded = true;     // stick to slopes / small steps when walking
        } else {
            grounded = false;
        }
        py = feetY + EYE_H;

        // --- head bob / sway while moving (stronger when sprinting) ---
        float moving = clamp(mlen, 0f, 1f) * (grounded ? 1f : 0.3f);
        bobPhase += speed * moving * dt * 2.3f;
        float bobY = (float) Math.sin(bobPhase * 2f) * (0.012f + 0.055f * sprintAnim) * moving;
        float swayYaw = (float) Math.sin(bobPhase) * (0.004f + 0.013f * sprintAnim) * moving;
        float swayPitch = (float) Math.sin(bobPhase * 2f) * (0.002f + 0.009f * sprintAnim) * moving;

        // --- build the view (recoil + screen shake + head movement) ---
        float jx = 0f, jy = 0f;
        if (shake > 0f) {
            jx = (rng.nextFloat() * 2f - 1f) * shake * 0.012f;
            jy = (rng.nextFloat() * 2f - 1f) * shake * 0.012f;
        }
        float effPitch = clamp(pitch + recoil + jy + swayPitch, -limit, limit);
        float vYaw = yaw + jx + swayYaw;
        float eyeY = py + bobY;
        float cosP = (float) Math.cos(effPitch);
        float ldx = cosP * (float) Math.sin(vYaw);
        float ldy = (float) Math.sin(effPitch);
        float ldz = -cosP * (float) Math.cos(vYaw);
        Matrix.setLookAtM(view, 0, px, eyeY, pz, px + ldx, eyeY + ldy, pz + ldz, 0f, 1f, 0f);
    }

    /** Jump if grounded; if a low ledge is right in front, size the boost to climb it. */
    private void doJump() {
        if (grounded) { vy = JUMP_V; grounded = false; }
        float best = -1f;
        for (int i = 0; i < boxes.length; i++) {
            float[] b = boxes[i];
            float boxTop = b[1] + b[4] * 0.5f;
            float rise = boxTop - feetY;
            if (rise <= 0.30f || rise > MANTLE_MAX) continue;
            float ddx = Math.max(Math.max(b[0] - b[3] * 0.5f - px, px - (b[0] + b[3] * 0.5f)), 0f);
            float ddz = Math.max(Math.max(b[2] - b[5] * 0.5f - pz, pz - (b[2] + b[5] * 0.5f)), 0f);
            float d = (float) Math.sqrt(ddx * ddx + ddz * ddz);
            if (d < 0.75f && boxTop > best) best = boxTop;
        }
        if (best >= 0f) {
            float need = (float) Math.sqrt(2f * GRAVITY * (best - feetY + 0.30f));
            if (need > vy) vy = need;
            grounded = false;
        }
    }

    /** Highest support under the player: the terrain, or a box top they're standing over. */
    private float groundSupport() {
        float support = terrainH(px, pz);
        for (int i = 0; i < boxes.length; i++) {
            float[] b = boxes[i];
            float boxTop = b[1] + b[4] * 0.5f;
            if (boxTop > feetY + 0.05f) continue;        // can't rest on something above the feet
            float hx = b[3] * 0.5f + 0.30f, hz = b[5] * 0.5f + 0.30f;   // small ledge margin
            if (px > b[0] - hx && px < b[0] + hx && pz > b[2] - hz && pz < b[2] + hz) {
                if (boxTop > support) support = boxTop;
            }
        }
        return support;
    }

    /** Circle (radius r, feet at footY) vs the static boxes on XZ, with optional arena clamp. */
    private void collide(float r, float footY, boolean clampArena, float[] io) {
        float x = io[0], z = io[1];
        for (int i = 0; i < boxes.length; i++) {
            float[] b = boxes[i];
            if (b[1] + b[4] * 0.5f <= footY + 0.05f) continue;   // standing on/above it: walkable
            if (b[1] - b[4] * 0.5f >= footY + 1.8f) continue;    // overhead (door lintel / roof): pass under
            float minx = b[0] - b[3] * 0.5f - r, maxx = b[0] + b[3] * 0.5f + r;
            float minz = b[2] - b[5] * 0.5f - r, maxz = b[2] + b[5] * 0.5f + r;
            if (x > minx && x < maxx && z > minz && z < maxz) {
                float pL = x - minx, pR = maxx - x, pD = z - minz, pU = maxz - z;
                float m = Math.min(Math.min(pL, pR), Math.min(pD, pU));
                if (m == pL) x = minx;
                else if (m == pR) x = maxx;
                else if (m == pD) z = minz;
                else z = maxz;
            }
        }
        for (int i = 0; i < doorData.length; i++) {       // closed doors block; open ones don't
            if (doorOpen[i] > 0.5f) continue;
            float[] dd = doorData[i];
            float hx = dd[6] == 0f ? dd[3] : dd[5];
            float hz = dd[6] == 0f ? dd[5] : dd[3];
            float minx = dd[0] - hx - r, maxx = dd[0] + hx + r, minz = dd[2] - hz - r, maxz = dd[2] + hz + r;
            if (x > minx && x < maxx && z > minz && z < maxz) {
                float pL = x - minx, pR = maxx - x, pD = z - minz, pU = maxz - z;
                float m = Math.min(Math.min(pL, pR), Math.min(pD, pU));
                if (m == pL) x = minx;
                else if (m == pR) x = maxx;
                else if (m == pD) z = minz;
                else z = maxz;
            }
        }
        if (clampArena) {
            x = clamp(x, -ARENA_LIMIT, ARENA_LIMIT);
            z = clamp(z, -ARENA_LIMIT, ARENA_LIMIT);
        }
        io[0] = x; io[1] = z;
    }

    private void tickTimers(float dt) {
        if (muzzleTimer > 0f) muzzleTimer -= dt;
        if (hitTimer > 0f) hitTimer -= dt;
        if (flashTimer > 0f) flashTimer -= dt;
        if (hurtFlash > 0f) hurtFlash -= dt;
        if (hitMarkerTimer > 0f) hitMarkerTimer -= dt;
        if (fireCd > 0f) fireCd -= dt;
        if (shake > 0f) { shake -= dt * 6f; if (shake < 0f) shake = 0f; }
        if (switchAnim > 0f) { switchAnim -= dt * 4f; if (switchAnim < 0f) switchAnim = 0f; }
        if (recoil > 0f) { recoil -= dt * 0.25f; if (recoil < 0f) recoil = 0f; }
        if (comboTimer > 0f) { comboTimer -= dt; if (comboTimer <= 0f) combo = 1; }
        if (reloadTimer > 0f) { reloadTimer -= dt; if (reloadTimer <= 0f) finishReload(); }
        aim += ((aimOn ? 1f : 0f) - aim) * Math.min(1f, dt * 12f);
        if (aim < 0.001f) aim = 0f; else if (aim > 0.999f) aim = 1f;
        sprintAnim += ((sprinting ? 1f : 0f) - sprintAnim) * Math.min(1f, dt * 9f);
        if (sprintAnim < 0.001f) sprintAnim = 0f; else if (sprintAnim > 0.999f) sprintAnim = 1f;
        input.setGameOver(gameOver);
    }

    private void restart() {
        score = 0; combo = 1; comboTimer = 0f;
        curWeapon = 0;
        for (int w = 0; w < W_COUNT; w++) { wMag[w] = W_MAG[w]; wReserve[w] = W_RES_START[w]; }
        reloadTimer = 0f; fireCd = 0f; shake = 0f; switchAnim = 0f; recoil = 0f;
        playerHP = MAX_HP; hurtFlash = 0f;
        px = 0f; pz = 9f; yaw = 0f; pitch = -0.08f;
        feetY = 0f; vy = 0f; grounded = true; py = EYE_H;
        aimOn = false; aim = 0f; sprinting = false; sprintAnim = 0f; bobPhase = 0f;
        gameOver = false;
        for (int i = 0; i < doorData.length; i++) { doorOpen[i] = 0f; doorTarget[i] = 0f; }
        nearDoor = -1;
        for (int i = 0; i < MAX_ENEMIES; i++) enAlive[i] = false;
        spawnTimer = 0.8f;
        spawnEnemy(); spawnEnemy(); spawnEnemy();
    }

    // --- shooting & weapons ---

    private void fire() {
        if (gameOver) { restart(); return; }
        if (sprinting) return;             // gun is lowered while sprinting
        if (reloadTimer > 0f) return;
        if (fireCd > 0f) return;
        if (wMag[curWeapon] <= 0) { beginReload(); return; }
        wMag[curWeapon]--;
        fireCd = W_INTERVAL[curWeapon];

        muzzleTimer = MUZZLE_TIME * (curWeapon == 2 ? 1.6f : 1f);
        flashTimer = FLASH_TIME;
        recoil += W_RECOIL[curWeapon];
        if (recoil > 0.26f) recoil = 0.26f;
        shake = W_SHAKE[curWeapon];
        playWeaponSound();

        int pellets = W_PELLETS[curWeapon];
        float spread = W_SPREAD[curWeapon] * (1f - 0.75f * aim);   // ADS tightens the spread
        boolean anyHit = false, anyHead = false;

        for (int p = 0; p < pellets; p++) {
            float cosP = (float) Math.cos(pitch);
            float dx = cosP * (float) Math.sin(yaw);
            float dy = (float) Math.sin(pitch);
            float dz = -cosP * (float) Math.cos(yaw);
            if (spread > 0f) {
                dx += (rng.nextFloat() * 2f - 1f) * spread;
                dy += (rng.nextFloat() * 2f - 1f) * spread;
                dz += (rng.nextFloat() * 2f - 1f) * spread;
                float inv = 1f / (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                dx *= inv; dy *= inv; dz *= inv;
            }

            float best = Float.MAX_VALUE;
            int type = 0, idx = -1;                 // 1 = body, 2 = head, 3 = scenery
            for (int i = 0; i < MAX_ENEMIES; i++) {
                if (!enAlive[i]) continue;
                float bt = rayBox(px, py, pz, dx, dy, dz,
                        enX[i] - 0.45f, 0f, enZ[i] - 0.45f, enX[i] + 0.45f, 1.24f, enZ[i] + 0.45f);
                if (bt >= 0f && bt < best) { best = bt; type = 1; idx = i; }
                float ht = rayBox(px, py, pz, dx, dy, dz,
                        enX[i] - 0.24f, 1.26f, enZ[i] - 0.24f, enX[i] + 0.24f, 1.78f, enZ[i] + 0.24f);
                if (ht >= 0f && ht < best) { best = ht; type = 2; idx = i; }
            }
            for (int i = 0; i < boxes.length; i++) {
                float[] b = boxes[i];
                float t = rayBox(px, py, pz, dx, dy, dz,
                        b[0] - b[3] * 0.5f, b[1] - b[4] * 0.5f, b[2] - b[5] * 0.5f,
                        b[0] + b[3] * 0.5f, b[1] + b[4] * 0.5f, b[2] + b[5] * 0.5f);
                if (t >= 0f && t < best) { best = t; type = 3; idx = i; }
            }

            if (type == 1 || type == 2) {
                boolean head = (type == 2);
                anyHit = true;
                if (head) anyHead = true;
                enHurt[idx] = 0.12f;
                enHP[idx] -= head ? W_HEADDMG[curWeapon] : W_BODYDMG[curWeapon];
                if (enHP[idx] <= 0f) onKill(idx, head);
            } else if (type == 3) {
                hitBox = idx; hitTimer = HIT_TIME;
            }
        }

        lastShotHit = anyHit;
        lastShotHead = anyHead;
        if (anyHit) { hitMarkerTimer = HITMARK_TIME; hitMarkerHead = anyHead; }
        if (anyHead) sfx.head();
        else if (anyHit) sfx.hit();

        if (wMag[curWeapon] <= 0) beginReload();
    }

    private void onKill(int idx, boolean head) {
        enAlive[idx] = false;
        combo = (comboTimer > 0f) ? Math.min(combo + 1, 9) : 1;
        comboTimer = COMBO_WINDOW;
        score += combo * (head ? 2 : 1);
        grantAmmoForKill();
    }

    /** Kills restock ammo: current weapon gets the reward, the others a trickle. */
    private void grantAmmoForKill() {
        wReserve[curWeapon] = Math.min(wReserve[curWeapon] + W_KILL_REWARD[curWeapon], W_RES_MAX[curWeapon]);
        for (int w = 0; w < W_COUNT; w++) {
            if (w == curWeapon) continue;
            wReserve[w] = Math.min(wReserve[w] + 1, W_RES_MAX[w]);
        }
    }

    private void beginReload() {
        if (reloadTimer > 0f) return;
        if (wMag[curWeapon] >= W_MAG[curWeapon]) return;
        if (wReserve[curWeapon] <= 0) { sfx.dry(); fireCd = 0.25f; return; }  // out of ammo
        reloadTimer = W_RELOAD[curWeapon];
        reloadTotal = W_RELOAD[curWeapon];
        sfx.reload();
    }

    private void finishReload() {
        int need = W_MAG[curWeapon] - wMag[curWeapon];
        int take = Math.min(need, wReserve[curWeapon]);
        wMag[curWeapon] += take;
        wReserve[curWeapon] -= take;
    }

    private void cycleWeapon() {
        curWeapon = (curWeapon + 1) % W_COUNT;
        reloadTimer = 0f;          // cancel any in-progress reload
        fireCd = 0.14f;            // brief lockout after the swap
        switchAnim = 1f;
        sfx.swap();
    }

    private void playWeaponSound() {
        if (curWeapon == 0) sfx.shoot();
        else if (curWeapon == 1) sfx.rifle();
        else sfx.shotgun();
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

    private void drawShadows() {
        GLES20.glUseProgram(progBlob);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        circle.position(0);
        GLES20.glEnableVertexAttribArray(aPBlob);
        GLES20.glVertexAttribPointer(aPBlob, 2, GLES20.GL_FLOAT, false, 8, circle);
        for (int i = 0; i < COVER_BOXES && i < boxes.length; i++) {
            float[] b = boxes[i];
            blob(b[0], 0f, b[1], b[2], Math.max(b[3], b[5]) * 0.62f);
        }
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (enAlive[i]) blob(enX[i], terrainH(enX[i], enZ[i]), 1.0f, enZ[i], 0.55f);
        }
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void blob(float ox, float groundY, float oy, float oz, float baseR) {
        float s = oy / (-LDY);
        float fx = ox + LDX * s, fz = oz + LDZ * s;
        float r = baseR * (1f + s * 0.06f);
        float alpha = 0.5f / (1f + s * 0.22f);
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, fx, groundY + 0.02f, fz);
        Matrix.scaleM(model, 0, r, 1f, r);
        Matrix.multiplyMM(tmpA, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmpA, 0);
        GLES20.glUniformMatrix4fv(uBlobMVP, 1, false, mvp, 0);
        GLES20.glUniform1f(uBlobA, alpha);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, circleVerts);
    }

    // --- HUD ---

    private void drawHud() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20.glUseProgram(progVig);
        quad.position(0);
        GLES20.glEnableVertexAttribArray(aPVig);
        GLES20.glVertexAttribPointer(aPVig, 2, GLES20.GL_FLOAT, false, 8, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);

        GLES20.glUseProgram(prog2);

        if (hurtFlash > 0f) {
            drawQuadNDC(0f, 0f, 1f, 1f, 0.8f, 0.06f, 0.06f, 0.5f * Math.min(hurtFlash, 1f));
        }

        if (!gameOver) {
            if (muzzleTimer > 0f) drawQuadNDC(0f, 0f, 1f, 1f, 1f, 0.9f, 0.6f, 0.16f * (muzzleTimer / MUZZLE_TIME));

            float ccx = width * 0.5f, ccy = height * 0.5f;

            float mcx = Hud.moveCx(), mcy = Hud.moveCy(height);
            drawCircle(mcx, mcy, Hud.MOVE_RADIUS, 1f, 1f, 1f, 0.16f);
            float kx = input.moveX(), ky = input.moveY();
            float klen = (float) Math.sqrt(kx * kx + ky * ky);
            if (klen > 1f) { kx /= klen; ky /= klen; }
            drawCircle(mcx + kx * Hud.MOVE_RADIUS, mcy - ky * Hud.MOVE_RADIUS, 56f, 1f, 1f, 1f, 0.55f);
            drawCircle(Hud.fireCx(width), Hud.fireCy(height), Hud.FIRE_RADIUS, 1f, 0.30f, 0.25f, 0.50f);

            // weapon switch button (shows current weapon 1/2/3, tinted per weapon)
            float swx = Hud.switchCx(width), swy = Hud.switchCy(height);
            drawCircle(swx, swy, Hud.SWITCH_RADIUS, weaponR(curWeapon), weaponG(curWeapon), weaponB(curWeapon), 0.45f);
            drawNumberAt(curWeapon + 1, swx, swy, 22f, 34f, 6f, 12f, 1f, 1f, 1f, 0.95f);

            // aim (iron sights) toggle — brighter while aiming
            float aimx = Hud.aimCx(width), aimy = Hud.aimCy(height);
            drawCircle(aimx, aimy, Hud.AIM_RADIUS, 0.55f + 0.45f * aim, 0.9f, 0.6f + 0.4f * aim, 0.26f + 0.34f * aim);
            drawCircle(aimx, aimy, 22f, 1f, 1f, 1f, 0.10f + 0.55f * aim);
            drawRectPx(aimx, aimy, 6f, 6f, 1f, 1f, 1f, 0.4f + 0.55f * aim);

            // jump button (up pyramid), brighter mid-air
            float jpx = Hud.jumpCx(width), jpy = Hud.jumpCy(height);
            float ja = grounded ? 0.30f : 0.6f;
            drawCircle(jpx, jpy, Hud.JUMP_RADIUS, 0.5f, 0.75f, 1f, ja);
            drawRectPx(jpx, jpy - 9f, 9f, 5f, 1f, 1f, 1f, 0.85f);
            drawRectPx(jpx, jpy - 2f, 17f, 5f, 1f, 1f, 1f, 0.85f);
            drawRectPx(jpx, jpy + 5f, 25f, 5f, 1f, 1f, 1f, 0.85f);

            // door prompt — appears only when you're in range of a door; tap it to open/close
            if (nearDoor >= 0) {
                float ix = Hud.interactCx(width), iy = Hud.interactCy(height);
                boolean open = doorOpen[nearDoor] > 0.5f;
                drawCircle(ix, iy, Hud.INTERACT_RADIUS, open ? 1f : 0.25f, open ? 0.55f : 0.95f, open ? 0.25f : 0.45f, 0.42f);
                drawRectPx(ix, iy, 44f, 62f, 0.93f, 0.93f, 0.80f, 0.92f);   // door frame
                drawRectPx(ix, iy, 32f, 52f, 0.46f, 0.30f, 0.17f, 0.96f);   // door leaf
                drawRectPx(ix + 9f, iy, 6f, 7f, 0.96f, 0.90f, 0.45f, 1f);   // knob
            }

            // crosshair (colors when the last shot landed: gold head, green body)
            float cr = 1f, cg = 1f, cb = 1f;
            if (flashTimer > 0f && lastShotHit) {
                if (lastShotHead) { cr = 1f; cg = 0.85f; cb = 0.20f; }
                else { cr = 0.30f; cg = 1f; cb = 0.40f; }
            }
            float xa = 0.9f * (1f - aim) * (1f - 0.7f * sprintAnim);   // fade when aiming / sprinting
            drawRectPx(ccx, ccy, 28f, 3f, cr, cg, cb, xa);
            drawRectPx(ccx, ccy, 3f, 28f, cr, cg, cb, xa);

            // expanding hit marker (gold = headshot, white = body)
            if (hitMarkerTimer > 0f) {
                float k = hitMarkerTimer / HITMARK_TIME;
                float off = 14f + 26f * (1f - k);
                float hr = hitMarkerHead ? 1f : 0.9f, hg = hitMarkerHead ? 0.82f : 1f, hb = hitMarkerHead ? 0.2f : 0.95f;
                float a = 0.9f * k;
                drawRectPx(ccx, ccy - off, 3f, 12f, hr, hg, hb, a);
                drawRectPx(ccx, ccy + off, 3f, 12f, hr, hg, hb, a);
                drawRectPx(ccx - off, ccy, 12f, 3f, hr, hg, hb, a);
                drawRectPx(ccx + off, ccy, 12f, 3f, hr, hg, hb, a);
            }

            drawNumberCentered(score, 80f, 26f, 46f, 7f, 16f, 0.55f, 1f, 1f, 0.95f);

            // health bar (top-left)
            float hp = playerHP / MAX_HP; if (hp < 0f) hp = 0f;
            float bx0 = 40f, bw = 230f, by = 54f;
            drawRectPx(bx0 + bw * 0.5f, by, bw, 18f, 0.18f, 0.18f, 0.22f, 0.6f);
            drawRectPx(bx0 + bw * hp * 0.5f, by, bw * hp, 18f, 1f - hp, 0.2f + 0.7f * hp, 0.22f, 0.92f);

            if (combo > 1) {
                float ct = comboTimer / COMBO_WINDOW;
                drawRectPx(width * 0.5f, 126f, 130f * ct, 6f, 1f, 0.78f, 0.2f, 0.9f);
                float mg = combo >= 5 ? 0.42f : (combo >= 3 ? 0.75f : 1f);
                float mb = combo >= 3 ? 0.2f : 0.5f;
                drawNumberCentered(combo, 162f, 20f, 34f, 6f, 12f, 1f, mg, mb, 0.95f);
            }

            // ammo: magazine (big) + reserve (small), or a reload bar
            float ax = Hud.fireCx(width), ay = Hud.fireCy(height) - Hud.FIRE_RADIUS - 52f;
            if (reloadTimer > 0f) {
                float rp = 1f - reloadTimer / reloadTotal;
                drawRectPx(ax, ay, 130f, 12f, 0.25f, 0.25f, 0.28f, 0.6f);
                drawRectPx(ax - 65f + 65f * rp, ay, 130f * rp, 12f, 1f, 0.8f, 0.2f, 0.9f);
            } else {
                int mag = wMag[curWeapon];
                boolean low = mag <= Math.max(2, W_MAG[curWeapon] / 6);
                drawNumberAt(mag, ax - 26f, ay, 24f, 38f, 7f, 13f,
                        low ? 1f : 0.9f, low ? 0.3f : 0.95f, low ? 0.3f : 1f, 0.95f);
                drawNumberAt(wReserve[curWeapon], ax + 46f, ay + 6f, 14f, 24f, 4f, 9f, 0.7f, 0.75f, 0.82f, 0.85f);
            }
        } else {
            drawQuadNDC(0f, 0f, 1f, 1f, 0f, 0f, 0f, 0.62f);
            drawNumberCentered(score, height * 0.40f, 56f, 96f, 12f, 30f, 1f, 1f, 1f, 1f);
            drawNumberCentered(highScore, height * 0.40f + 98f, 22f, 40f, 6f, 14f, 1f, 0.84f, 0.3f, 0.95f);
            drawCircle(width * 0.5f, height * 0.74f, 86f, 0.2f, 0.9f, 0.35f, 0.85f);
            drawRectPx(width * 0.5f, height * 0.74f, 34f, 34f, 0.05f, 0.15f, 0.05f, 0.9f);
        }

        drawNumberLeft(buildNumber, width - 116f, 52f, 18f, 30f, 5f, 11f, 1f, 0.8f, 0.2f, 0.95f);

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private static float weaponR(int w) { return w == 0 ? 0.30f : (w == 1 ? 0.40f : 1.00f); }
    private static float weaponG(int w) { return w == 0 ? 0.85f : (w == 1 ? 1.00f : 0.62f); }
    private static float weaponB(int w) { return w == 0 ? 1.00f : (w == 1 ? 0.45f : 0.20f); }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static final int[] SEG = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};

    private void drawNumberCentered(int n, float cy, float dw, float dh, float t, float gap,
                                    float r, float g, float b, float a) {
        if (n < 0) n = 0;
        String s = Integer.toString(n);
        float total = s.length() * dw + (s.length() - 1) * gap;
        float x = width * 0.5f - total * 0.5f + dw * 0.5f;
        for (int i = 0; i < s.length(); i++) { drawDigit(s.charAt(i) - '0', x, cy, dw, dh, t, r, g, b, a); x += dw + gap; }
    }

    private void drawNumberAt(int n, float cx, float cy, float dw, float dh, float t, float gap,
                              float r, float g, float b, float a) {
        if (n < 0) n = 0;
        String s = Integer.toString(n);
        float total = s.length() * dw + (s.length() - 1) * gap;
        float x = cx - total * 0.5f + dw * 0.5f;
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

    // --- textures ---

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
        int s = N / 2, gap = 14;
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                float L = i * s + gap, T = j * s + gap, R = (i + 1) * s - gap, B = (j + 1) * s - gap;
                p.setStyle(Paint.Style.FILL); p.setColor(0xFF161B23 + rnd.nextInt(0x0A) * 0x010101); c.drawRect(L, T, R, B, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f);
                p.setColor(0xFF323C4A); c.drawRect(L + 3, T + 3, R - 3, B - 3, p);
                p.setColor(0xFF0A0C10); c.drawRect(L, T, R, B, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0F4E63); c.drawRect(0, s - 7, N, s + 7, p); c.drawRect(s - 7, 0, s + 7, N, p);
        p.setColor(0xFF31B6D8); c.drawRect(0, s - 2, N, s + 2, p); c.drawRect(s - 2, 0, s + 2, N, p);
        p.setColor(0xFF3A4554);
        int[] bx = {gap + 14, s - gap - 14, s + gap + 14, N - gap - 14};
        for (int xi = 0; xi < 4; xi++) for (int yi = 0; yi < 4; yi++) c.drawCircle(bx[xi], bx[yi], 4f, p);
        p.setStyle(Paint.Style.STROKE);
        for (int k = 0; k < 80; k++) {
            int a = 0x10 + rnd.nextInt(0x30);
            p.setStrokeWidth(1f + rnd.nextFloat());
            p.setColor((a << 24) | 0x00C8D2E0);
            float x0 = rnd.nextInt(N), y0 = rnd.nextInt(N), ln = 8 + rnd.nextInt(60), ang = rnd.nextFloat() * 6.28f;
            c.drawLine(x0, y0, x0 + ln * (float) Math.cos(ang), y0 + ln * (float) Math.sin(ang), p);
        }
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
        for (int x = 0; x < N; x++) {
            int gr = 0x2C + rnd.nextInt(0x1C);
            p.setColor(0xFF000000 | (gr << 16) | (gr << 8) | (gr + 6));
            c.drawLine(x, 0, x, N, p);
        }
        p.setColor(0xFF181C22); c.drawRect(0, 70, N, 96, p); c.drawRect(0, N - 96, N, N - 70, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(8f); p.setColor(0xFF101319); c.drawRect(6, 6, N - 6, N - 6, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFF424C5C);
        int m = 26;
        c.drawCircle(m, m, 7f, p); c.drawCircle(N - m, m, 7f, p); c.drawCircle(m, N - m, 7f, p); c.drawCircle(N - m, N - m, 7f, p);
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

    // --- terrain & world building ---

    private static float smoothstep(float a, float b, float x) {
        float t = clamp((x - a) / (b - a), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** Rolling-hills height field: flat over the city core, hillier outskirts toward the edges. */
    private static float terrainH(float x, float z) {
        float r = (float) Math.sqrt(x * x + z * z);
        float amp = smoothstep(21f, 40f, r);
        float h = 1.6f * (float) Math.sin(x * 0.16f) * (float) Math.cos(z * 0.14f)
                + 0.9f * (float) Math.sin(x * 0.06f + 1.3f)
                + 0.8f * (float) Math.cos(z * 0.075f + 2.1f)
                + 0.5f * (float) Math.sin((x + z) * 0.11f);
        return amp * h;
    }

    private static float[] makeTerrain(int cells, float size) {
        float half = size * 0.5f, cs = size / cells;
        float[] d = new float[cells * cells * 6 * 8];
        int o = 0;
        for (int i = 0; i < cells; i++) {
            float x0 = -half + i * cs, x1 = x0 + cs;
            for (int j = 0; j < cells; j++) {
                float z0 = -half + j * cs, z1 = z0 + cs;
                o = terrVtx(d, o, x0, z0);
                o = terrVtx(d, o, x1, z0);
                o = terrVtx(d, o, x1, z1);
                o = terrVtx(d, o, x0, z0);
                o = terrVtx(d, o, x1, z1);
                o = terrVtx(d, o, x0, z1);
            }
        }
        return d;
    }

    private static int terrVtx(float[] d, int o, float x, float z) {
        float e = 0.5f;
        float nx = terrainH(x - e, z) - terrainH(x + e, z);
        float nz = terrainH(x, z - e) - terrainH(x, z + e);
        float ny = 2f * e;
        float inv = 1f / (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        return put(d, o, x, terrainH(x, z), z, nx * inv, ny * inv, nz * inv, x * 0.25f, z * 0.25f);
    }

    // Facade colour palette: concrete, brick, steel-blue, sandstone, dark concrete.
    private static final float[][] PALETTE = {
        {0.62f, 0.60f, 0.56f}, {0.66f, 0.45f, 0.38f}, {0.50f, 0.55f, 0.62f},
        {0.72f, 0.68f, 0.58f}, {0.46f, 0.50f, 0.52f},
    };

    /** A small city: a grid of varied buildings around an open central plaza + spawn lane. */
    // --- vegetation (one baked mesh, coloured via a tiny palette texture) ---

    private static float cell(int i) { return (i + 0.5f) / 16f; }   // palette column centre

    /** Scatter grass tufts, flowers and bushes over the flat core + surrounding meadow,
     *  skipping roads and building footprints. Baked into one mesh; sets vegVerts. */
    private float[] makeVegetation() {
        float[] d = new float[360000];
        int o = 0;
        Random r = new Random(404);
        float ug0 = cell(0), ug1 = cell(1), ug2 = cell(2), uStem = cell(3), uCtr = cell(11), uBush = cell(12);
        int[] flowerCells = {4, 5, 6, 7, 8, 9, 10};

        for (int n = 0, tries = 0; n < 360 && tries < 5000; tries++) {     // grass tufts
            float a = r.nextFloat() * 6.2832f, rad = r.nextFloat() * 28f;
            float x = (float) Math.cos(a) * rad, z = (float) Math.sin(a) * rad;
            if (inBuildingXZ(x, z, 0.2f) || onRoadXZ(x, z)) continue;
            float by = terrainH(x, z);
            int blades = 4 + r.nextInt(4);
            for (int b = 0; b < blades; b++) {
                float bx = x + (r.nextFloat() - 0.5f) * 0.18f, bz = z + (r.nextFloat() - 0.5f) * 0.18f;
                float ug = r.nextInt(3) == 0 ? ug0 : (r.nextBoolean() ? ug1 : ug2);
                o = vBlade(d, o, bx, by, bz, r.nextFloat() * 6.2832f,
                        0.16f + r.nextFloat() * 0.22f, 0.02f + r.nextFloat() * 0.02f, 0.04f + r.nextFloat() * 0.10f, ug);
            }
            n++;
        }
        for (int n = 0, tries = 0; n < 140 && tries < 4000; tries++) {     // flowers
            float a = r.nextFloat() * 6.2832f, rad = r.nextFloat() * 28f;
            float x = (float) Math.cos(a) * rad, z = (float) Math.sin(a) * rad;
            if (inBuildingXZ(x, z, 0.25f) || onRoadXZ(x, z)) continue;
            float by = terrainH(x, z);
            float stemH = 0.22f + r.nextFloat() * 0.20f, topY = by + stemH;
            o = vBlade(d, o, x - 0.05f, by, z, r.nextFloat() * 6.2832f, 0.14f, 0.02f, 0.05f, ug1);   // tufts at base
            o = vBlade(d, o, x + 0.05f, by, z, r.nextFloat() * 6.2832f, 0.16f, 0.02f, 0.05f, ug2);
            o = vBox(d, o, x, by + stemH * 0.5f, z, 0.018f, stemH, 0.018f, uStem);                  // stem
            float petU = cell(flowerCells[r.nextInt(flowerCells.length)]);
            int petals = 5 + r.nextInt(2);
            float pa0 = r.nextFloat() * 6.2832f, plen = 0.07f + r.nextFloat() * 0.04f, pw = 0.05f + r.nextFloat() * 0.02f;
            for (int p = 0; p < petals; p++)
                o = vPetal(d, o, x, topY, z, pa0 + p * (6.2832f / petals), plen, 0.02f, pw, petU);
            o = vBox(d, o, x, topY + 0.012f, z, 0.045f, 0.03f, 0.045f, uCtr);                        // centre
            n++;
        }
        for (int n = 0, tries = 0; n < 22 && tries < 2000; tries++) {      // bushes
            float a = r.nextFloat() * 6.2832f, rad = 4f + r.nextFloat() * 24f;
            float x = (float) Math.cos(a) * rad, z = (float) Math.sin(a) * rad;
            if (inBuildingXZ(x, z, 0.4f) || onRoadXZ(x, z)) continue;
            float by = terrainH(x, z), br = 0.22f + r.nextFloat() * 0.18f;
            int lobes = 5 + r.nextInt(4);
            for (int l = 0; l < lobes; l++) {
                float lx = x + (r.nextFloat() - 0.5f) * br * 1.6f, lz = z + (r.nextFloat() - 0.5f) * br * 1.6f;
                float s = 0.16f + r.nextFloat() * 0.14f;
                o = vBox(d, o, lx, by + 0.12f + r.nextFloat() * 0.18f, lz, s, s, s, uBush);
            }
            n++;
        }
        vegVerts = o / 8;
        return java.util.Arrays.copyOf(d, o);
    }

    private boolean inBuildingXZ(float x, float z, float m) {
        for (int i = 0; i < boxes.length; i++) {
            float[] b = boxes[i];
            if (x > b[0] - b[3] * 0.5f - m && x < b[0] + b[3] * 0.5f + m
             && z > b[2] - b[5] * 0.5f - m && z < b[2] + b[5] * 0.5f + m) return true;
        }
        return false;
    }

    private static boolean onRoadXZ(float x, float z) {
        float[] roads = {-20f, -12f, -4f, 4f, 12f, 20f};
        for (int i = 0; i < roads.length; i++)
            if (Math.abs(x - roads[i]) < 1.9f || Math.abs(z - roads[i]) < 1.9f) return true;
        return false;
    }

    /** A tapered, slightly bent grass/leaf blade (2 triangles). */
    private static int vBlade(float[] d, int o, float cx, float by, float cz, float ang,
                              float h, float w, float bend, float u) {
        float dx = (float) Math.cos(ang), dz = (float) Math.sin(ang);
        float px = -dz, pz = dx, hw = w * 0.5f, tw = w * 0.12f;
        float blx = cx - px * hw, blz = cz - pz * hw, brx = cx + px * hw, brz = cz + pz * hw;
        float tx = cx + dx * bend, tz = cz + dz * bend, ty = by + h;
        float tlx = tx - px * tw, tlz = tz - pz * tw, trx = tx + px * tw, trz = tz + pz * tw;
        o = put(d, o, blx, by, blz, 0f, 1f, 0f, u, 0.5f);
        o = put(d, o, brx, by, brz, 0f, 1f, 0f, u, 0.5f);
        o = put(d, o, trx, ty, trz, 0f, 1f, 0f, u, 0.5f);
        o = put(d, o, blx, by, blz, 0f, 1f, 0f, u, 0.5f);
        o = put(d, o, trx, ty, trz, 0f, 1f, 0f, u, 0.5f);
        o = put(d, o, tlx, ty, tlz, 0f, 1f, 0f, u, 0.5f);
        return o;
    }

    /** A flower petal: a small quad fanning outward and up from the flower head. */
    private static int vPetal(float[] d, int o, float cx, float cy, float cz, float ang,
                              float len, float rise, float wid, float u) {
        float dx = (float) Math.cos(ang), dz = (float) Math.sin(ang);
        float px = -dz, pz = dx, hw = wid * 0.5f;
        float blx = cx - px * hw, blz = cz - pz * hw, brx = cx + px * hw, brz = cz + pz * hw;
        float tx = cx + dx * len, tz = cz + dz * len, ty = cy + rise;
        float tlx = tx - px * hw * 0.4f, tlz = tz - pz * hw * 0.4f, trx = tx + px * hw * 0.4f, trz = tz + pz * hw * 0.4f;
        o = put(d, o, blx, cy, blz, 0f, 1f, 0f, u, 0.5f);
        o = put(d, o, brx, cy, brz, 0f, 1f, 0f, u, 0.5f);
        o = put(d, o, trx, ty, trz, 0f, 1f, 0f, u, 0.5f);
        o = put(d, o, blx, cy, blz, 0f, 1f, 0f, u, 0.5f);
        o = put(d, o, trx, ty, trz, 0f, 1f, 0f, u, 0.5f);
        o = put(d, o, tlx, ty, tlz, 0f, 1f, 0f, u, 0.5f);
        return o;
    }

    private static int vBox(float[] d, int o, float cx, float cy, float cz, float sx, float sy, float sz, float u) {
        float hx = sx * 0.5f, hy = sy * 0.5f, hz = sz * 0.5f;
        o = vQuad(d, o, cx - hx, cy + hy, cz - hz, cx + hx, cy + hy, cz - hz, cx + hx, cy + hy, cz + hz, cx - hx, cy + hy, cz + hz, 0f, 1f, 0f, u);
        o = vQuad(d, o, cx - hx, cy - hy, cz + hz, cx + hx, cy - hy, cz + hz, cx + hx, cy - hy, cz - hz, cx - hx, cy - hy, cz - hz, 0f, -1f, 0f, u);
        o = vQuad(d, o, cx + hx, cy - hy, cz + hz, cx + hx, cy + hy, cz + hz, cx + hx, cy + hy, cz - hz, cx + hx, cy - hy, cz - hz, 1f, 0f, 0f, u);
        o = vQuad(d, o, cx - hx, cy - hy, cz - hz, cx - hx, cy + hy, cz - hz, cx - hx, cy + hy, cz + hz, cx - hx, cy - hy, cz + hz, -1f, 0f, 0f, u);
        o = vQuad(d, o, cx - hx, cy - hy, cz + hz, cx - hx, cy + hy, cz + hz, cx + hx, cy + hy, cz + hz, cx + hx, cy - hy, cz + hz, 0f, 0f, 1f, u);
        o = vQuad(d, o, cx + hx, cy - hy, cz - hz, cx + hx, cy + hy, cz - hz, cx - hx, cy + hy, cz - hz, cx - hx, cy - hy, cz - hz, 0f, 0f, -1f, u);
        return o;
    }

    private static int vQuad(float[] d, int o, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float ex, float ey, float ez, float nx, float ny, float nz, float u) {
        o = put(d, o, ax, ay, az, nx, ny, nz, u, 0.5f);
        o = put(d, o, bx, by, bz, nx, ny, nz, u, 0.5f);
        o = put(d, o, cx, cy, cz, nx, ny, nz, u, 0.5f);
        o = put(d, o, ax, ay, az, nx, ny, nz, u, 0.5f);
        o = put(d, o, cx, cy, cz, nx, ny, nz, u, 0.5f);
        o = put(d, o, ex, ey, ez, nx, ny, nz, u, 0.5f);
        return o;
    }

    private static Bitmap makeVegPalette() {
        int W = 16, H = 4;
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        int[] c = {
            0xFF2E6B1F, 0xFF3F8A24, 0xFF5CA836, 0xFF356B1C,   // grass dark/mid/light, stem
            0xFFE03A3A, 0xFFF7D43A, 0xFFF4F0E6, 0xFFF06FB0,   // red, yellow, white, pink
            0xFF9B4FE0, 0xFF4F7BF0, 0xFFF58A20, 0xFFF2C53A,   // purple, blue, orange, centre
            0xFF2C5E1C, 0xFF4A9A2C, 0xFF6BB840, 0xFF3A7A22,   // bush + extra greens
        };
        for (int x = 0; x < W; x++)
            for (int y = 0; y < H; y++) b.setPixel(x, y, c[x]);
        return b;
    }

    private static int uploadPalette(Bitmap bmp) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        bmp.recycle();
        return id[0];
    }

    private static float[] makeFlatQuad(float half, float y) {
        float[] d = new float[6 * 8];
        int o = 0;
        o = put(d, o, -half, y, -half, 0, 1, 0, 0f, 0f);
        o = put(d, o,  half, y, -half, 0, 1, 0, 1f, 0f);
        o = put(d, o,  half, y,  half, 0, 1, 0, 1f, 1f);
        o = put(d, o, -half, y, -half, 0, 1, 0, 0f, 0f);
        o = put(d, o,  half, y,  half, 0, 1, 0, 1f, 1f);
        o = put(d, o, -half, y,  half, 0, 1, 0, 0f, 1f);
        return d;
    }

    /** Top-down city ground: concrete blocks, asphalt streets with sidewalk borders + lane dashes. */
    private static Bitmap makeCityBitmap() {
        int N = 1024;
        float half = 21f;
        Bitmap bmp = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF6E7173);                          // concrete block base
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float[] roads = {-20f, -12f, -4f, 4f, 12f, 20f};
        for (int i = 0; i < roads.length; i++) {          // sidewalk borders (lighter), both axes
            cityBand(c, p, roads[i], 2.7f, half, N, true, 0xFF9A9E94);
            cityBand(c, p, roads[i], 2.7f, half, N, false, 0xFF9A9E94);
        }
        for (int i = 0; i < roads.length; i++) {          // asphalt on top
            cityBand(c, p, roads[i], 1.7f, half, N, true, 0xFF34373A);
            cityBand(c, p, roads[i], 1.7f, half, N, false, 0xFF34373A);
        }
        p.setColor(0xFFD9C24E);                            // dashed centre lines
        for (int i = 0; i < roads.length; i++) {
            cityDashes(c, p, roads[i], half, N, true);
            cityDashes(c, p, roads[i], half, N, false);
        }
        p.setColor(0x30FFFFFF);                            // plaza highlight in the centre
        float pa = (-4f + half) / (2f * half) * N, pb = (4f + half) / (2f * half) * N;
        c.drawRect(pa, pa, pb, pb, p);
        Random rnd = new Random(31);                       // grain
        for (int k = 0; k < 4500; k++) {
            int v = rnd.nextInt(38);
            p.setColor((0x22 << 24) | (v << 16) | (v << 8) | v);
            float x = rnd.nextInt(N), y = rnd.nextInt(N);
            c.drawRect(x, y, x + 1 + rnd.nextInt(2), y + 1, p);
        }
        return bmp;
    }

    private static void cityBand(Canvas c, Paint p, float center, float halfW, float half, int N, boolean vertical, int col) {
        p.setColor(col);
        float a = (center - halfW + half) / (2f * half) * N;
        float b = (center + halfW + half) / (2f * half) * N;
        if (vertical) c.drawRect(a, 0, b, N, p);
        else c.drawRect(0, a, N, b, p);
    }

    private static void cityDashes(Canvas c, Paint p, float center, float half, int N, boolean vertical) {
        float cpx = (center + half) / (2f * half) * N, w = 3f, dash = N / 50f;
        for (float t = 0; t < N; t += dash * 2f) {
            if (vertical) c.drawRect(cpx - w, t, cpx + w, t + dash, p);
            else c.drawRect(t, cpx - w, t + dash, cpx + w, p);
        }
    }

    private static void buildWorldInto(List<float[]> L, List<float[]> doors) {
        // plaza cover (first COVER_BOXES entries get a shadow blob): two climbable crates, two pillars
        L.add(new float[]{-2.5f, 0.75f, 4f, 1.5f, 1.5f, 1.5f, 1.05f, 0.70f, 0.40f});
        L.add(new float[]{ 2.5f, 0.75f, 4f, 1.5f, 1.5f, 1.5f, 1.05f, 0.70f, 0.40f});
        L.add(new float[]{-3.0f, 1.5f, -1f, 1.2f, 3.0f, 1.2f, 0.85f, 0.88f, 0.95f});
        L.add(new float[]{ 3.0f, 1.5f, -1f, 1.2f, 3.0f, 1.2f, 0.85f, 0.88f, 0.95f});
        // city blocks on a grid; keep them in the flat core and clear of the plaza / spawn lane
        Random rc = new Random(101);
        float[] gs = {-16f, -8f, 0f, 8f, 16f};                     // blocks spaced 8 m -> wide streets
        for (int ix = 0; ix < gs.length; ix++) {
            for (int iz = 0; iz < gs.length; iz++) {
                float cx = gs[ix], cz = gs[iz];
                if (cx * cx + cz * cz > 18.5f * 18.5f) continue;   // stay on the flat core
                if (cx == 0f && cz >= 0f) continue;                // open plaza + spawn corridor
                float w = 2.8f + rc.nextFloat() * 1.3f;
                float d = 2.8f + rc.nextFloat() * 1.3f;
                boolean tower = cx * cx + cz * cz > 170f;          // outer ring -> high-rise
                float h = tower ? 5.5f + rc.nextFloat() * 2.5f : 3.0f + rc.nextFloat() * 1.6f;
                int doorSide = (Math.abs(cx) >= Math.abs(cz)) ? (cx < 0 ? 2 : 3) : (cz < 0 ? 0 : 1);
                float[] p = PALETTE[rc.nextInt(PALETTE.length)];
                addBuilding(L, doors, cx, cz, w, d, h, doorSide, rc.nextInt(3), p[0], p[1], p[2]);
            }
        }
    }

    /** A four-walled building with one doorway (doorSide 0=+z,1=-z,2=+x,3=-x), a roof, and a
     *  roof style: 0 = flat, 1 = parapet wall, 2 = rooftop unit + antenna. Records an openable door. */
    private static void addBuilding(List<float[]> L, List<float[]> doors, float cx, float cz, float w, float d, float h,
                                    int doorSide, int roofStyle, float r, float g, float b) {
        float t = 0.3f, doorW = 1.9f, doorH = 2.3f;
        addWall(L, cx, cz + d * 0.5f, w, t, h, doorSide == 0, doorW, doorH, true, r, g, b);   // +z
        addWall(L, cx, cz - d * 0.5f, w, t, h, doorSide == 1, doorW, doorH, true, r, g, b);   // -z
        addWall(L, cx + w * 0.5f, cz, t, d, h, doorSide == 2, doorW, doorH, false, r, g, b);  // +x
        addWall(L, cx - w * 0.5f, cz, t, d, h, doorSide == 3, doorW, doorH, false, r, g, b);  // -x
        L.add(new float[]{cx, h + 0.15f, cz, w + t, 0.3f, d + t, r * 0.9f, g * 0.9f, b * 0.95f}); // roof
        float dcx, dcz; int axis;                          // openable door leaf in the gap
        if (doorSide == 0)      { dcx = cx; dcz = cz + d * 0.5f; axis = 0; }
        else if (doorSide == 1) { dcx = cx; dcz = cz - d * 0.5f; axis = 0; }
        else if (doorSide == 2) { dcx = cx + w * 0.5f; dcz = cz; axis = 1; }
        else                    { dcx = cx - w * 0.5f; dcz = cz; axis = 1; }
        doors.add(new float[]{dcx, doorH * 0.5f, dcz, doorW * 0.5f - 0.05f, doorH * 0.5f, 0.07f, axis, 1f,
                0.46f, 0.30f, 0.17f});
        if (roofStyle == 1) {                              // parapet wall around the roof edge
            float py = h + 0.30f + 0.25f, ph = 0.5f;
            L.add(new float[]{cx, py, cz + d * 0.5f, w, ph, t, r * 0.85f, g * 0.85f, b * 0.9f});
            L.add(new float[]{cx, py, cz - d * 0.5f, w, ph, t, r * 0.85f, g * 0.85f, b * 0.9f});
            L.add(new float[]{cx + w * 0.5f, py, cz, t, ph, d, r * 0.85f, g * 0.85f, b * 0.9f});
            L.add(new float[]{cx - w * 0.5f, py, cz, t, ph, d, r * 0.85f, g * 0.85f, b * 0.9f});
        } else if (roofStyle == 2) {                       // rooftop HVAC block + antenna
            L.add(new float[]{cx + w * 0.16f, h + 0.65f, cz, w * 0.42f, 0.7f, d * 0.42f, r * 0.8f, g * 0.8f, b * 0.85f});
            L.add(new float[]{cx - w * 0.26f, h + 0.95f, cz - d * 0.2f, 0.08f, 1.3f, 0.08f, 0.30f, 0.30f, 0.34f});
        }
    }

    private static void addWall(List<float[]> L, float cx, float cz, float sx, float sz, float h,
                                boolean door, float doorW, float doorH, boolean spanX,
                                float r, float g, float b) {
        if (!door) {
            L.add(new float[]{cx, h * 0.5f, cz, sx, h, sz, r, g, b});
            return;
        }
        if (spanX) {                                   // wall runs along X; gap in the middle
            float segW = (sx - doorW) * 0.5f;
            L.add(new float[]{cx - (doorW * 0.5f + segW * 0.5f), h * 0.5f, cz, segW, h, sz, r, g, b});
            L.add(new float[]{cx + (doorW * 0.5f + segW * 0.5f), h * 0.5f, cz, segW, h, sz, r, g, b});
            L.add(new float[]{cx, doorH + (h - doorH) * 0.5f, cz, doorW, h - doorH, sz, r, g, b}); // lintel
        } else {                                       // wall runs along Z
            float segD = (sz - doorW) * 0.5f;
            L.add(new float[]{cx, h * 0.5f, cz - (doorW * 0.5f + segD * 0.5f), sx, h, segD, r, g, b});
            L.add(new float[]{cx, h * 0.5f, cz + (doorW * 0.5f + segD * 0.5f), sx, h, segD, r, g, b});
            L.add(new float[]{cx, doorH + (h - doorH) * 0.5f, cz, sx, h - doorH, doorW, r, g, b}); // lintel
        }
    }

    private static Bitmap makeTerrainBitmap() {
        int N = 512;
        Bitmap bmp = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF3A4A2A);                        // base grass-green
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(23);
        for (int k = 0; k < 60; k++) {                  // soft dirt/grass blotches
            int shade = rnd.nextInt(3);
            int col = shade == 0 ? 0x2F3D22 : (shade == 1 ? 0x4A5C30 : 0x5A4A30);
            p.setColor(0x55000000 | col);
            c.drawCircle(rnd.nextInt(N), rnd.nextInt(N), 18 + rnd.nextInt(70), p);
        }
        for (int k = 0; k < 5000; k++) {                // grass speckle
            int v = rnd.nextInt(70);
            p.setColor((0x40 << 24) | ((0x30 + v) << 16) | ((0x44 + v) << 8) | (0x22 + v / 2));
            float x = rnd.nextInt(N), y = rnd.nextInt(N);
            c.drawRect(x, y, x + 1 + rnd.nextInt(2), y + 1 + rnd.nextInt(3), p);
        }
        p.setColor(0x66556055);                          // scattered pebbles
        for (int k = 0; k < 120; k++) c.drawCircle(rnd.nextInt(N), rnd.nextInt(N), 1 + rnd.nextInt(2), p);
        return bmp;
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
        "  } else {" +
        "    gl_FragColor=vec4(pow(min(uColor,vec3(1.0)),vec3(0.4545)),1.0); return;" +
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
