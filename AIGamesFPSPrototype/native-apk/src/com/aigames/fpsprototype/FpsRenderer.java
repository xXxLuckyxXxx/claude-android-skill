package com.aigames.fpsprototype;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
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
    private static final float SPRINT_MULT = 1.36f;   // top sprint boost (ramped in via sprintAnim, not instant)
    private static final int STRIDE = 32;             // 8 floats: pos3, normal3, uv2
    private static final float MUZZLE_TIME = 0.08f;
    private static final float HIT_TIME = 0.25f;
    private static final float FLASH_TIME = 0.15f;
    private static final float HITMARK_TIME = 0.22f;
    private static final float COMBO_WINDOW = 2.2f;

    // Weapons: 0 = pistol, 1 = SMG (full-auto), 2 = shotgun, 3 = sniper (bought in the shop).
    private static final int W_COUNT = 4;
    private static final int[]     W_MAG       = {12, 30, 6, 5};
    private static final int[]     W_RES_START = {48, 90, 24, 25};
    private static final int[]     W_RES_MAX   = {120, 240, 48, 60};
    private static final float[]   W_INTERVAL  = {0.20f, 0.085f, 0.72f, 1.05f};   // s between shots
    private static final float[]   W_RELOAD    = {1.15f, 1.70f, 2.00f, 2.40f};
    private static final int[]     W_PELLETS   = {1, 1, 9, 1};
    private static final float[]   W_SPREAD    = {0.0f, 0.018f, 0.085f, 0.0f};
    private static final float[]   W_BODYDMG   = {55f, 26f, 20f, 90f};
    private static final float[]   W_HEADDMG   = {120f, 60f, 46f, 260f};
    private static final float[]   W_RECOIL    = {0.040f, 0.022f, 0.090f, 0.13f};   // punchier kick (recovers fast)
    private static final float[]   W_SHAKE     = {0.55f, 0.32f, 1.00f, 1.20f};
    private static final int[]     W_KILL_REWARD = {6, 10, 4, 3};
    private static final boolean[] W_AUTO      = {false, true, false, false};

    // Iron sights mounted per weapon: gun-top under the sights, sight-line height, rear/front Z.
    // (Index 3 = sniper; it draws a scope instead, but SIGHT_BEAD still sets its ADS aim height.)
    private static final float[] SIGHT_BASE  = {0.065f, 0.066f, 0.050f, 0.050f};
    private static final float[] SIGHT_BEAD  = {0.090f, 0.110f, 0.078f, 0.115f};
    private static final float[] SIGHT_REARZ = {0.100f, 0.090f, 0.050f, 0.100f};
    private static final float[] SIGHT_FRONTZ = {-0.180f, -0.300f, -0.550f, -0.200f};

    // === Meta-progression (persists across deaths) ===
    // Per-weapon upgrade categories.
    private static final int UPG_DMG = 0, UPG_RATE = 1, UPG_MAG = 2, UPG_RELOAD = 3, UPG_COUNT = 4;
    private static final int UPG_MAX_TIER = 5;
    private static final float[] UPG_DMG_MULT    = {1f, 1.12f, 1.25f, 1.40f, 1.58f, 1.80f};
    private static final float[] UPG_RATE_MULT   = {1f, 0.93f, 0.87f, 0.81f, 0.75f, 0.70f}; // multiplies interval
    private static final float[] UPG_RELOAD_MULT = {1f, 0.92f, 0.85f, 0.78f, 0.72f, 0.66f};
    private static final int[]   UPG_COST        = {0, 500, 1500, 4000, 10000, 25000};       // cost to reach tier t

    // Abilities (global perks, levelled with cash).
    private static final int AB_MAXHP = 0, AB_FASTRELOAD = 1, AB_DMGBOOST = 2, AB_HEADMASTERY = 3,
                             AB_ADRENALINE = 4, AB_MOVESPEED = 5, AB_DOUBLEJUMP = 6, AB_CASHBONUS = 7, AB_COUNT = 8;
    private static final int[] AB_MAX_LEVEL = {5, 1, 5, 3, 3, 4, 1, 5};
    private static final int[][] AB_COST = {
        {500, 1200, 3000, 6000, 12000},   // MAXHP        +20 hp / level
        {2500},                           // FASTRELOAD   -15% reload (global)
        {800, 2000, 4500, 9000, 18000},   // DMGBOOST     +6% all damage / level
        {1500, 4000, 10000},              // HEADMASTERY  +15% head damage / level
        {1200, 3500, 8000},               // ADRENALINE   heal 6/12/18 on kill
        {700, 1800, 4000, 9000},          // MOVESPEED    +6% / level
        {6000},                           // DOUBLEJUMP
        {500, 1500, 3500, 7000, 14000},   // CASHBONUS    +10% cash per kill / level
    };

    // Shop: which weapons must be bought, and the level needed to buy them. Pistol is free.
    private static final int[] WEAPON_COST    = {0, 4000, 8000, 20000};
    private static final int[] WEAPON_REQ_LVL = {0, 3, 6, 12};

    // Display names for the hub menus.
    private static final String[] WEAPON_NAME = {"PISTOL", "SMG", "SHOTGUN", "SNIPER"};
    private static final String[] UPG_NAME = {"DAMAGE", "FIRE RATE", "MAGAZINE", "RELOAD"};
    private static final String[] AB_NAME = {"MAX HEALTH", "FAST RELOAD", "DAMAGE UP", "HEADSHOT UP",
                                             "ADRENALINE", "MOVE SPEED", "DOUBLE JUMP", "GREED"};

    // Enemies / survival.
    private static final int MAX_ENEMIES = 12;         // more of the horde on the field at once
    private static final float LEASH_DIST = 46f;       // a zombie further than this (and out of view) is pulled back near you
    private final float[] spawnTmp = new float[2];
    private static final float ENEMY_SPEED = 1.85f;    // base m/s
    private static final float ENEMY_FULL_HP = 100f;
    private static final float SPAWN_DIST = 38f;        // spawn a touch closer so they reach you sooner
    private static final float REACH_DIST = 1.5f;
    private static final float ENEMY_DMG = 20f;
    private static final float MAX_HP = 100f;
    private static final float ARENA_LIMIT = 36f;

    private static final float LDX = 0.358f, LDY = -0.894f, LDZ = 0.268f;

    private final InputState input;
    private final int buildNumber;
    private final SharedPreferences prefs;
    private final Sfx sfx;
    private final Random rng = new Random(20);
    private final float[] lookTmp = new float[2];

    private int width = 1, height = 1;
    private float us = 1.4f;          // global UI scale (text + buttons), grows with screen height

    private int prog3, aPos, aNormal, aUV, uMVP, uModel, uColor, uMode, uLightDir, uCamPos, uFogColor, uTime, uTex;
    private int uSunInt, uAmbient, uFogRange, uWorldUV;
    private int progSky, aPSky, uSkyFwd, uSkyRight, uSkyUp, uSkySun, uSkyHalfFov, uSkyTime, uSkyHorizon, uSkyZenith;
    private int progVig, aPVig;
    private int progBlob, aPBlob, uBlobMVP, uBlobA;
    private int prog2, aP2, uScale2, uOff2, uCol2;
    private int progText, aPText, aUVText, uScaleT, uOffT, uColT, uUVoffT, uUVscaleT, uFontTex;

    private int floorTex, metalTex, terrainTex, cityTex, vegTex, fontTex, winTex, roofTex, wallTex, roadTex, woodTex;
    private int brickTex, stoneTex, sidingTex;   // per-archetype facade materials (Stage 1 graphics upgrade)

    private FloatBuffer cube, floor, sphere, quad, circle, terrain, cityGround, vegetation, textQuad, roofMesh, windowMesh, trimMesh, roadMesh, placedTrees, bandMesh, interiorMesh;
    private int sphereVerts, circleVerts, terrainVerts, vegVerts, roofVerts, windowVerts, trimVerts, roadVerts, placedTreeVerts, bandVerts, interiorVerts;
    private float[][] roadSegs;                    // custom streets {x1,z1,x2,z2,width} from the level; null = default grid
    private float[][] treeList;                    // level-placed trees {x,z,scale}; null = none
    private boolean hasCustomRoads;
    private float[] trimData;                      // window-sill boxes, built alongside the window mesh
    // {cx,cz,w,d,h,doorSide, rr,rg,rb, pitch,overhang, windows,winSize,
    //  glassR,glassG,glassB, foundH,foundR,foundG,foundB, trimR,trimG,trimB, storeys} per house (roof/window/band meshes)
    private java.util.List<float[]> houseRects;
    private float[][] roofGroups;                  // per-house roof colour groups: {firstVert, vertCount, r,g,b}
    private float[][] winGroups;                   // per-house window glass-tint groups: {firstVert, vertCount, r,g,b}
    private float[][] bandGroups;                  // foundation / trim / storey-band colour groups: {firstVert, vertCount, r,g,b}
    private float[][] interiorGroups;              // baked house-interior material groups: {firstVert, vertCount, r,g,b}
    private java.util.List<float[]> interiorPieces;// furniture/floor/ceiling specs {x,y,z,w,h,d,colorIdx}, built once
    private static final float[][] INT_COLS = {    // interior material palette (indexed by colorIdx)
        {0.46f, 0.33f, 0.21f},   // 0 wood floor
        {0.86f, 0.84f, 0.80f},   // 1 ceiling
        {0.54f, 0.39f, 0.24f},   // 2 wood furniture
        {0.34f, 0.24f, 0.16f},   // 3 dark wood (legs / wardrobe)
        {0.50f, 0.30f, 0.30f},   // 4 fabric (bed cover / rug)
        {0.85f, 0.83f, 0.78f},   // 5 mattress / pillow
        {0.33f, 0.33f, 0.36f},   // 6 metal (lamp cord)
        {0.97f, 0.87f, 0.60f},   // 7 lampshade (warm)
        {0.36f, 0.43f, 0.52f},   // 8 sofa / upholstery (muted blue)
        {0.62f, 0.43f, 0.35f},   // 9 accent fabric / books (terracotta)
        {0.27f, 0.45f, 0.28f},   // 10 plant foliage (green)
        {0.80f, 0.80f, 0.84f},   // 11 picture / mirror (light)
    };

    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] model = new float[16];
    private final float[] mvp = new float[16];
    private final float[] doorBase = new float[16];   // hinge transform for detailed swinging doors
    private final float[] tmpA = new float[16];
    private final float[] gunBase = new float[16];
    private final float[] partM = new float[16];

    private float px = 0f, py = 1.6f, pz = 9f;
    private float yaw = 0f, pitch = -0.08f;
    private final float[] camFwd = new float[3], camRight = new float[3], camUp = new float[3];  // view basis for the sky
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

    // floating reward popups ("+$50", "HEADSHOT") that rise and fade at a kill
    private static final int MAX_POPUPS = 16;
    private static final float POPUP_TIME = 0.95f;
    private final String[] popText = new String[MAX_POPUPS];
    private final float[] popX = new float[MAX_POPUPS], popY = new float[MAX_POPUPS], popT = new float[MAX_POPUPS];
    private final float[] popR = new float[MAX_POPUPS], popG = new float[MAX_POPUPS], popB = new float[MAX_POPUPS], popSz = new float[MAX_POPUPS];
    private int popNext = 0;
    private final java.util.Random fxRnd = new java.util.Random(7);
    private float hurtDirAngle = 0f, hurtDirTimer = 0f;   // red damage-direction indicator
    // killfeed (top-right list of recent eliminations)
    private static final int MAX_FEED = 5;
    private final String[] feedText = new String[MAX_FEED];
    private final float[] feedT = new float[MAX_FEED];
    private final int[] feedKind = new int[MAX_FEED];     // 0 body, 1 head, 2 boss
    private int feedNext = 0;
    private int hitBox = -1;
    private boolean lastShotHit = false, lastShotHead = false;

    private int score = 0;
    private int combo = 1;
    private float comboTimer = 0f;
    private int highScore = 0;
    private float playerHP = MAX_HP;

    // Game state machine: HUB (between runs, spend cash) / PLAYING / SUMMARY (run-over recap).
    private static final int ST_HUB = 0, ST_PLAYING = 1, ST_SUMMARY = 2;
    private int state = ST_HUB;
    private float deathAnim = 0f;      // >0 = playing the death sequence before the summary
    private boolean newBest = false;   // this run beat the high score
    // player-toggleable options (settings panel)
    private boolean showSettings = false;
    private boolean optShake = true, optDmgNum = true, optRadar = true, optKillfeed = true;
    private int hubTab = 0;              // 0 = weapons, 1 = upgrades, 2 = abilities
    private int hubTabShown = -1;        // tab whose content-reveal is currently animating
    private float hubTabAnim = 1f;       // 0..1 content-reveal progress for the active tab
    private float cashShown = -1f;       // animated (count-up) cash value for the hub header
    private float lpShown = -1f;         // animated (smooth-fill) xp-bar progress for the hub header
    private boolean hubWasShown = false; // tracks hub entry to trigger the entrance fade
    private float hubEnterAnim = 1f;     // 0..1 whole-hub entrance fade-in
    private float tabIndX = -1f;         // animated x of the sliding tab selection highlight
    private final float[] vpMat = new float[16], projIn = new float[4], projRes = new float[4], projScreen = new float[2];
    private int upgradeSel = 0;          // weapon currently shown in the upgrades tab
    private final float[] tap = new float[2];

    // Persistent meta-progression (loaded in ctor, saved on purchase/death; survives restart()).
    private long cash = 0;
    private long xp = 0;
    private int playerLevel = 1;
    private final int[] wOwned = new int[W_COUNT];                 // pistol forced to 1 on load
    private final int[][] upgTier = new int[W_COUNT][UPG_COUNT];
    private final int[] abLevel = new int[AB_COUNT];
    // Per-run economy (reset by restart()).
    private long runCash = 0, runXp = 0;
    private int runKills = 0, levelsGainedThisRun = 0;
    private int jumpsUsed = 0;

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
    private final float[] enMaxHP = new float[MAX_ENEMIES];
    private final float[] enKx = new float[MAX_ENEMIES], enKz = new float[MAX_ENEMIES];  // knockback velocity
    private float hitStop = 0f;        // brief slow-mo crunch on a kill
    // collectible drops (health / ammo orbs)
    private static final int MAX_PICKUPS = 10;
    private final float[] pkX = new float[MAX_PICKUPS], pkY = new float[MAX_PICKUPS], pkZ = new float[MAX_PICKUPS], pkLife = new float[MAX_PICKUPS];
    private final int[] pkType = new int[MAX_PICKUPS];   // 0 = health, 1 = ammo
    private int pkNext = 0;
    private final float[] enPhase = new float[MAX_ENEMIES];
    private final float[] enHurt = new float[MAX_ENEMIES];
    private final float[] enWind = new float[MAX_ENEMIES];   // >0 = winding up to strike, <0 = post-whiff recovery
    private static final float WINDUP = 0.42f;                // telegraph time before a zombie's hit lands
    private final int[] enOutfit = new int[MAX_ENEMIES];
    private final int[] enType = new int[MAX_ENEMIES];   // 0 = normal, 1 = runner (fast/weak), 2 = brute (slow/tanky)
    private final int[] enFaceType = new int[MAX_ENEMIES];
    private final boolean[] enAlive = new boolean[MAX_ENEMIES];
    private final float[] enScale = new float[MAX_ENEMIES];   // 1 = normal, >1 = (mini)boss
    private final int[] enBoss = new int[MAX_ENEMIES];        // 0 = normal, 1 = mini-boss, 2 = boss
    private float spawnTimer = 0f;

    // Waves.
    private int wave = 1;
    private int waveToSpawn = 0;       // enemies still to spawn this wave
    private int waveRemaining = 0;     // enemies still to remove (spawned-or-not) before the wave clears
    private int waveSize = 5;          // total enemies this wave (for the progress bar)
    private int bossPending = 0;       // >0 = next spawn is a (mini)boss of that tier
    private float waveBreak = 0f;      // >0 = between-wave reward pause
    private float waveBanner = 0f;     // banner display timer
    private float waveBannerMax = 1.8f;// the value waveBanner was started at (for entrance easing)
    private String waveBannerText = "";

    // Death pixel particles (enemies burst into voxels when killed).
    private static final int MAX_PARTICLES = 256;
    private final float[] pX = new float[MAX_PARTICLES], pY = new float[MAX_PARTICLES], pZ = new float[MAX_PARTICLES];
    private final float[] pVX = new float[MAX_PARTICLES], pVY = new float[MAX_PARTICLES], pVZ = new float[MAX_PARTICLES];
    private final float[] pLife = new float[MAX_PARTICLES], pMaxLife = new float[MAX_PARTICLES];
    private final float[] pR = new float[MAX_PARTICLES], pG = new float[MAX_PARTICLES], pB = new float[MAX_PARTICLES];
    private final float[] pSize = new float[MAX_PARTICLES];
    private int pNext = 0;

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
    private int numCover = COVER_BOXES;              // boxes that cast a shadow (props); set by the level loader

    // Interactive doors. doorData[i] = cx, cy, cz, hw, hh, ht, axis(0=spansX,1=spansZ), hingeSign, r,g,b.
    private static final float INTERACT_DIST = 2.6f;
    private final float[][] doorData;
    private final float[] doorOpen, doorTarget;       // 0 = closed, 1 = open
    private int nearDoor = -1;

    // World look — defaults match the built-in sunny day; a level's SET lines override these.
    private final float[] skyHorizon = {0.76f, 0.85f, 0.95f}, skyZenith = {0.16f, 0.41f, 0.74f};
    private final float[] sunDir = {-0.35f, 0.55f, -0.62f}, fog = {0.72f, 0.80f, 0.90f}, ground = {1f, 1f, 1f};
    private float sunInt = 1f, ambient = 1f, fogStart = 10f, fogEnd = 70f;

    public FpsRenderer(InputState input, int buildNumber, Context ctx) {
        this.input = input;
        this.buildNumber = buildNumber;
        this.prefs = ctx.getSharedPreferences("aigames_fps", Context.MODE_PRIVATE);
        this.highScore = prefs.getInt("highscore", 0);
        this.sfx = new Sfx(ctx);
        loadMeta();

        ArrayList<float[]> bl = new ArrayList<float[]>();
        ArrayList<float[]> dl = new ArrayList<float[]>();
        ArrayList<float[]> hl = new ArrayList<float[]>();
        // A custom level (placed via the web editor + adb push) overrides the procedural village.
        if (!buildWorldFromFile(ctx, bl, dl, hl)) buildWorldInto(bl, dl, hl);
        furnishAll(bl, hl);                                 // walk-in interiors: furniture colliders + visual specs
        this.boxes = bl.toArray(new float[0][]);
        this.doorData = dl.toArray(new float[0][]);
        this.houseRects = hl;
        this.doorOpen = new float[doorData.length];
        this.doorTarget = new float[doorData.length];
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(fog[0], fog[1], fog[2], 1f);

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
        uSunInt = GLES20.glGetUniformLocation(prog3, "uSunInt");
        uAmbient = GLES20.glGetUniformLocation(prog3, "uAmbient");
        uFogRange = GLES20.glGetUniformLocation(prog3, "uFogRange");
        uWorldUV = GLES20.glGetUniformLocation(prog3, "uWorldUV");

        progSky = buildProgram(VERT_SKY_SRC, FRAG_SKY_SRC);
        aPSky = GLES20.glGetAttribLocation(progSky, "aP");
        uSkyFwd = GLES20.glGetUniformLocation(progSky, "uFwd");
        uSkyRight = GLES20.glGetUniformLocation(progSky, "uRight");
        uSkyUp = GLES20.glGetUniformLocation(progSky, "uUp");
        uSkySun = GLES20.glGetUniformLocation(progSky, "uSun");
        uSkyHalfFov = GLES20.glGetUniformLocation(progSky, "uHalfFov");
        uSkyTime = GLES20.glGetUniformLocation(progSky, "uTime");
        uSkyHorizon = GLES20.glGetUniformLocation(progSky, "uHorizon");
        uSkyZenith = GLES20.glGetUniformLocation(progSky, "uZenith");

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

        progText = buildProgram(VERT_TEXT_SRC, FRAG_TEXT_SRC);
        aPText = GLES20.glGetAttribLocation(progText, "aP");
        aUVText = GLES20.glGetAttribLocation(progText, "aUV");
        uScaleT = GLES20.glGetUniformLocation(progText, "uScale");
        uOffT = GLES20.glGetUniformLocation(progText, "uOff");
        uColT = GLES20.glGetUniformLocation(progText, "uCol");
        uUVoffT = GLES20.glGetUniformLocation(progText, "uUVoff");
        uUVscaleT = GLES20.glGetUniformLocation(progText, "uUVscale");
        uFontTex = GLES20.glGetUniformLocation(progText, "uFont");

        GLES20.glUseProgram(prog3);
        GLES20.glUniform1i(uTex, 0);

        cube = makeBuffer(makeCube());
        floor = makeBuffer(makeFloor(40f, 40f));
        float[] sph = makeSphere(14, 20);
        sphere = makeBuffer(sph);
        sphereVerts = sph.length / 8;
        quad = makeBuffer(QUAD_DATA);
        textQuad = makeBuffer(TEXTQUAD_DATA);
        int seg = 28;
        circle = makeCircle(seg);
        circleVerts = seg + 2;

        floorTex = uploadTexture(makeFloorBitmap());
        metalTex = uploadTexture(makeMetalBitmap());
        fontTex = uploadFontTexture(makeFontAtlas());

        float[] terr = makeTerrain(100, 140f);
        terrain = makeBuffer(terr);
        terrainVerts = terr.length / 8;
        terrainTex = uploadTexture(makeTerrainBitmap());

        cityGround = makeBuffer(makeFlatQuad(35f, 0.02f));
        cityTex = uploadTexture(makeCityBitmap());
        roadTex = uploadTexture(makeRoadBitmap());
        if (hasCustomRoads) roadMesh = makeBuffer(makeRoadMesh());   // custom streets replace the default grid

        vegetation = makeBuffer(makeVegetation());   // grass, flowers, bushes (sets vegVerts)
        vegTex = uploadPalette(makeVegPalette());
        if (treeList != null) placedTrees = makeBuffer(makePlacedTrees());   // level-placed trees

        roofMesh = makeBuffer(makeRoofMesh());       // pitched gable roofs (sets roofVerts)
        windowMesh = makeBuffer(makeWindowMesh());   // wall windows (sets windowVerts + trimData + winGroups)
        trimMesh = makeBuffer(trimData);             // window sills
        bandMesh = makeBuffer(makeBandMesh());       // per-house foundation / trim / storey bands (sets bandVerts)
        roofTex = uploadTexture(makeRoofBitmap());
        winTex = uploadTexture(makeWindowBitmap());
        wallTex = uploadTexture(makeHouseBitmap());
        brickTex = uploadTexture(makeBrickBitmap());
        stoneTex = uploadTexture(makeStoneBitmap());
        sidingTex = uploadTexture(makeSidingBitmap());
        woodTex = uploadTexture(makeWoodBitmap());
        interiorMesh = makeBuffer(makeInteriorMesh());   // baked walk-in interiors (floors/ceilings/furniture)

        restart();
        state = ST_HUB;                 // app opens in the hub (PLAY to begin a run)
        lastNanos = System.nanoTime();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = w;
        height = Math.max(1, h);
        us = clamp(height / 800f, 1.4f, 2.3f);    // bigger UI on bigger/denser phone screens
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

        if (deathAnim > 0f) {                 // death sequence: freeze the scene, keep FX ticking
            deathAnim -= dt;
            updateParticles(dt);
            updatePopups(dt);
            if (deathAnim <= 0f) state = ST_SUMMARY;
        } else {
            float gdt = dt;
            if (hitStop > 0f) { hitStop -= dt; gdt = dt * 0.12f; }   // kill crunch: brief slow-mo on the world
            updateDoors(gdt);
            updateCamera(gdt);
            updateEnemies(gdt);
            updateParticles(gdt);
            updatePickups(gdt);
            updatePopups(dt);
            tickTimers(gdt);
        }

        float adsZoom = (curWeapon == 3) ? 46f : 30f;   // sniper zooms much harder through its scope
        float fov = 72f - adsZoom * aim + 6f * sprintAnim;
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.05f, 300f);

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // Sky
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glUseProgram(progSky);
        GLES20.glUniform3f(uSkyFwd, camFwd[0], camFwd[1], camFwd[2]);
        GLES20.glUniform3f(uSkyRight, camRight[0], camRight[1], camRight[2]);
        GLES20.glUniform3f(uSkyUp, camUp[0], camUp[1], camUp[2]);
        GLES20.glUniform3f(uSkySun, sunDir[0], sunDir[1], sunDir[2]);
        GLES20.glUniform3f(uSkyHorizon, skyHorizon[0], skyHorizon[1], skyHorizon[2]);
        GLES20.glUniform3f(uSkyZenith, skyZenith[0], skyZenith[1], skyZenith[2]);
        float skyTanY = (float) Math.tan(Math.toRadians(fov) * 0.5);
        GLES20.glUniform2f(uSkyHalfFov, skyTanY * aspect, skyTanY);
        GLES20.glUniform1f(uSkyTime, timeAcc);
        quad.position(0);
        GLES20.glEnableVertexAttribArray(aPSky);
        GLES20.glVertexAttribPointer(aPSky, 2, GLES20.GL_FLOAT, false, 8, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glDepthMask(true);

        // Scene
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(prog3);
        GLES20.glUniform3f(uLightDir, sunDir[0], sunDir[1], sunDir[2]);
        GLES20.glUniform3f(uCamPos, px, py, pz);
        GLES20.glUniform3f(uFogColor, fog[0], fog[1], fog[2]);
        GLES20.glUniform1f(uSunInt, sunInt);
        GLES20.glUniform1f(uAmbient, ambient);
        GLES20.glUniform2f(uFogRange, fogStart, fogEnd);
        GLES20.glUniform1f(uTime, timeAcc);
        GLES20.glUniform1f(uWorldUV, 0f);                      // ground/roads/veg use their baked mesh UVs
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, terrainTex);
        Matrix.setIdentityM(model, 0);
        drawWorld(terrain, terrainVerts, 0f, ground[0], ground[1], ground[2]);

        if (hasCustomRoads) {                                  // level-defined streets replace the default grid
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, roadTex);
            drawWorld(roadMesh, roadVerts, 0f, 1f, 1f, 1f);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cityTex);   // streets + sidewalks over the flat core
            drawWorld(cityGround, 6, 0f, 1f, 1f, 1f);
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vegTex);    // grass / flowers / bushes
        Matrix.setIdentityM(model, 0);
        drawWorld(vegetation, vegVerts, 0f, 1f, 1f, 1f);
        if (placedTrees != null) drawWorld(placedTrees, placedTreeVerts, 0f, 1f, 1f, 1f);   // level-placed trees (same atlas)

        drawShadows();

        GLES20.glUseProgram(prog3);
        GLES20.glUniform1f(uWorldUV, 1f);                      // walls: world-scale facade tiling + ground-contact AO
        int lastMat = -1;
        for (int i = 0; i < boxes.length; i++) {
            float[] b = boxes[i];
            if (b.length > 10 && b[10] != 0f) continue;       // collision-only proxy (interior furniture) — drawn via interiorMesh
            int mat = (b.length > 11) ? (int) b[11] : 0;      // 0 plaster (palette-tinted) · 1 brick · 2 stone · 3 timber
            if (mat != lastMat) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mat == 1 ? brickTex : mat == 2 ? stoneTex : mat == 3 ? sidingTex : wallTex);
                lastMat = mat;
            }
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, b[0], b[1], b[2]);
            if (b.length > 9 && b[9] != 0f) Matrix.rotateM(model, 0, b[9], 0f, 1f, 0f);   // props/fences carry a yaw
            Matrix.scaleM(model, 0, b[3], b[4], b[5]);
            float boost = (i == hitBox && hitTimer > 0f) ? 1.6f : 1f;
            float cr = mat == 0 ? b[6] : 1f, cg = mat == 0 ? b[7] : 1f, cb = mat == 0 ? b[8] : 1f;   // brick/stone/timber carry their own colour
            drawWorld(cube, 36, 0f, cr * boost, cg * boost, cb * boost);
        }
        GLES20.glUniform1f(uWorldUV, 0f);                      // restore mesh UVs for roofs/windows/bands/interiors

        Matrix.setIdentityM(model, 0);                         // pitched roofs + windows (baked, world-space)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, roofTex);    // neutral-grey shingle; per-house colour via uColor
        if (roofGroups != null) {
            for (float[] gp : roofGroups) drawWorldRange(roofMesh, (int) gp[0], (int) gp[1], 0f, gp[2], gp[3], gp[4]);
        } else {
            drawWorld(roofMesh, roofVerts, 0f, 0.69f, 0.31f, 0.16f);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, winTex);
        if (winGroups != null) {                               // per-house glass tint
            for (float[] gp : winGroups) drawWorldRange(windowMesh, (int) gp[0], (int) gp[1], 0f, gp[2], gp[3], gp[4]);
        } else {
            drawWorld(windowMesh, windowVerts, 0f, 1f, 1f, 1f);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wallTex);   // light stone window sills
        drawWorld(trimMesh, trimVerts, 0f, 0.95f, 0.93f, 0.86f);
        if (bandGroups != null) {                              // per-house foundation / trim / storey bands
            for (float[] gp : bandGroups) drawWorldRange(bandMesh, (int) gp[0], (int) gp[1], 0f, gp[2], gp[3], gp[4]);
        }
        if (interiorGroups != null) {                          // baked walk-in interiors (floors/ceilings/furniture)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, woodTex);
            for (float[] gp : interiorGroups) drawWorldRange(interiorMesh, (int) gp[0], (int) gp[1], 0f, gp[2], gp[3], gp[4]);
        }

        drawDoors();
        drawEnemies();
        drawParticles();
        drawPickups();

        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
        if (state == ST_PLAYING) drawGun();

        if (state == ST_PLAYING) drawHud();
        else if (state == ST_SUMMARY) drawSummary();
        else drawHub();
    }

    // --- enemies ---

    private void updateEnemies(float dt) {
        if (state != ST_PLAYING) return;
        if (waveBanner > 0f) waveBanner -= dt;
        if (waveBreak > 0f) {                              // between-wave reward pause
            waveBreak -= dt;
            if (waveBreak <= 0f) beginWave(wave);
            return;
        }
        float speed = ENEMY_SPEED * Math.min(2.2f, 1f + 0.03f * (wave - 1));   // faster every wave
        float dmgMul = Math.min(1.8f, 1f + 0.025f * (wave - 1));                // and they hit harder
        float sinYaw = (float) Math.sin(yaw), cosYaw = (float) Math.cos(yaw);
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (enHurt[i] > 0f) enHurt[i] -= dt;
            if (!enAlive[i]) continue;
            if (enKx[i] != 0f || enKz[i] != 0f) {              // apply + decay knockback, then resolve collision
                enX[i] += enKx[i] * dt; enZ[i] += enKz[i] * dt;
                float kd = (float) Math.exp(-9f * dt);
                enKx[i] *= kd; enKz[i] *= kd;
                if (enKx[i] * enKx[i] + enKz[i] * enKz[i] < 0.0004f) { enKx[i] = 0f; enKz[i] = 0f; }
                colTmp[0] = enX[i]; colTmp[1] = enZ[i]; collide(0.4f, 0f, false, colTmp); enX[i] = colTmp[0]; enZ[i] = colTmp[1];
            }
            float dx = px - enX[i], dz = pz - enZ[i];
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            enFace[i] = (float) Math.atan2(dx, dz);
            // Leash: a non-boss zombie that has drifted far AND is out of your forward view gets quietly
            // teleported to just around a corner near you — keeps the action coming, you never see it move.
            if (enBoss[i] == 0 && d > LEASH_DIST) {
                float facing = -dx * sinYaw + dz * cosYaw;     // forward · (enemy - player); high = dead ahead
                if (facing < d * 0.74f && spawnPointNear(spawnTmp)) { enX[i] = spawnTmp[0]; enZ[i] = spawnTmp[1]; continue; }
            }
            float reach = REACH_DIST * enScale[i];
            if (enWind[i] < 0f) { enWind[i] += dt; if (enWind[i] > 0f) enWind[i] = 0f; }   // post-whiff recovery
            if (enWind[i] > 0f) {                              // telegraphing a strike — frozen, facing the player
                enFace[i] = (float) Math.atan2(dx, dz);
                enWind[i] -= dt;
                if (enWind[i] <= 0f) {                         // strike resolves
                    enWind[i] = 0f;
                    if (d < reach * 1.3f) {                    // still in range → the hit lands
                        enAlive[i] = false;
                        playerHP -= ENEMY_DMG * dmgMul * (enBoss[i] > 0 ? 2.5f : 1f);
                        hurtFlash = 0.6f;
                        if (optShake) shake = Math.max(shake, enBoss[i] > 0 ? 0.45f : 0.28f);
                        {
                            float ddx = enX[i] - px, ddz = enZ[i] - pz;
                            float fX = camFwd[0], fZ = camFwd[2];
                            float fl = (float) Math.sqrt(fX * fX + fZ * fZ); if (fl < 1e-4f) fl = 1f; fX /= fl; fZ /= fl;
                            hurtDirAngle = (float) Math.atan2(ddx * (-fZ) + ddz * fX, ddx * fX + ddz * fZ);
                            hurtDirTimer = 0.85f;
                        }
                        combo = 1; comboTimer = 0f;
                        sfx.hurt();
                        waveRemaining--;
                        if (playerHP <= 0f) { playerHP = 0f; triggerGameOver(); }
                        else if (waveRemaining <= 0) clearWave();
                    } else {
                        enWind[i] = -0.5f;                     // player dodged the wind-up → recover, then re-chase
                    }
                }
            } else if (enWind[i] == 0f && d < reach) {         // reached the player → begin the wind-up telegraph
                enWind[i] = WINDUP;
                enFace[i] = (float) Math.atan2(dx, dz);
                sfx.swap();
            } else if (d > 1e-4f) {
                float typeMul = enBoss[i] > 0 ? 0.6f : (enType[i] == 1 ? 1.7f : (enType[i] == 2 ? 0.6f : 1f));
                float es = speed * typeMul;                        // runners rush, brutes/bosses lumber
                float a = steerDir(enX[i], enZ[i], (float) Math.atan2(dx, dz));  // navigate round buildings
                enFace[i] = a;                            // face the way it actually moves
                float step = es * dt * (enHurt[i] > 0f ? 0.4f : 1f);   // flinch: stagger briefly when shot
                enX[i] += (float) Math.sin(a) * step;
                enZ[i] += (float) Math.cos(a) * step;
                enPhase[i] += step * 9f;                  // advance walk cycle with distance
                colTmp[0] = enX[i]; colTmp[1] = enZ[i];
                collide(0.4f, 0f, false, colTmp);         // backstop: never pass through cover
                enX[i] = colTmp[0]; enZ[i] = colTmp[1];
            }
        }
        spawnTimer -= dt;
        if (waveToSpawn > 0 && spawnTimer <= 0f && aliveCount() < MAX_ENEMIES) {
            spawnEnemy();
            waveToSpawn--;
            spawnTimer = Math.max(0.30f, 1.2f - wave * 0.05f);   // horde arrives quicker each wave (gentler early)
        }
    }

    private int aliveCount() {
        int n = 0;
        for (int i = 0; i < MAX_ENEMIES; i++) if (enAlive[i]) n++;
        return n;
    }

    /** Start wave w: size grows over time; every 5th is a mini-boss, every 10th a boss. */
    private void beginWave(int w) {
        wave = w;
        int size = Math.max(3, Math.round(4 + (w - 1) * 1.8f));   // gentler opening waves
        waveToSpawn = size;
        waveRemaining = size;
        waveSize = size;
        bossPending = (w % 10 == 0) ? 2 : (w % 5 == 0 ? 1 : 0);
        spawnTimer = 0.3f;
        waveBannerText = (bossPending == 2 ? "BOSS WAVE " : (bossPending == 1 ? "MINI-BOSS WAVE " : "WAVE ")) + w;
        waveBanner = 1.8f; waveBannerMax = 1.8f;
    }

    /** Wave cleared: pay a bonus, show a banner, and pause before the next wave. */
    private void clearWave() {
        if (waveBreak > 0f) return;                       // guard against a multi-kill clearing twice
        long bonus = 120L * wave;
        cash += bonus; runCash += bonus;
        waveBannerText = "WAVE " + wave + " CLEAR  +$" + bonus;
        waveBanner = 2.4f; waveBannerMax = 2.4f;
        sfx.swap();
        spawnCoinRain();
        wave++;
        waveBreak = 2.6f;   // a longer breather to grab drops between waves
    }

    /** Celebratory gold-coin shower around the player on a wave clear. */
    private void spawnCoinRain() {
        for (int n = 0; n < 44; n++) {
            int i = pNext; pNext = (pNext + 1) % MAX_PARTICLES;
            pX[i] = px + (rng.nextFloat() - 0.5f) * 16f;
            pY[i] = py + 5f + rng.nextFloat() * 6f;
            pZ[i] = pz + (rng.nextFloat() - 0.5f) * 16f;
            pVX[i] = (rng.nextFloat() - 0.5f) * 1.6f;
            pVY[i] = -1f - rng.nextFloat() * 2.5f;
            pVZ[i] = (rng.nextFloat() - 0.5f) * 1.6f;
            pR[i] = 1f; pG[i] = 0.82f; pB[i] = 0.24f;       // gold
            pSize[i] = 0.06f + rng.nextFloat() * 0.045f;
            pMaxLife[i] = 1.3f + rng.nextFloat() * 0.9f;
            pLife[i] = pMaxLife[i];
        }
    }

    /** A point ~13-24 m from the player, biased to the REAR arc (out of the forward view) and on
     *  valid ground (inside the arena, not inside a building) — zombies appear "around the corner". */
    private boolean spawnPointNear(float[] out) {
        for (int tries = 0; tries < 28; tries++) {
            float rel = (float) Math.toRadians(95 + rng.nextFloat() * 170);   // 95..265° off the facing = behind/side
            float ang = yaw + rel;
            float dist = 13f + rng.nextFloat() * 11f;
            float x = px + (float) Math.sin(ang) * dist;
            float z = pz - (float) Math.cos(ang) * dist;
            if (x * x + z * z > 34f * 34f) continue;                          // stay on the flat core
            if (Math.abs(x) > ARENA_LIMIT || Math.abs(z) > ARENA_LIMIT) continue;
            if (inBuildingXZ(x, z, 0.7f)) continue;                           // not inside a building
            out[0] = x; out[1] = z; return true;
        }
        return false;
    }

    private void spawnEnemy() {
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (!enAlive[i]) {
                if (spawnPointNear(spawnTmp)) { enX[i] = spawnTmp[0]; enZ[i] = spawnTmp[1]; }
                else { float a = rng.nextFloat() * 6.2832f;          // fallback: ring around the PLAYER, not the map
                       enX[i] = px + (float) Math.cos(a) * 20f; enZ[i] = pz + (float) Math.sin(a) * 20f; }
                enFace[i] = 0f;
                enPhase[i] = rng.nextFloat() * 6.2832f;
                enHurt[i] = 0f; enWind[i] = 0f;
                enKx[i] = 0f; enKz[i] = 0f;
                enFaceType[i] = rng.nextInt(6);
                float hpMul = Math.min(6f, 1f + 0.09f * (wave - 1));   // tankier every wave
                if (bossPending > 0) {
                    enBoss[i] = bossPending;
                    enType[i] = 0;
                    enScale[i] = bossPending == 2 ? 1.9f : 1.45f;
                    enHP[i] = ENEMY_FULL_HP * hpMul * (bossPending == 2 ? 6f : 3f);
                    enOutfit[i] = 0;                       // menacing red
                    bossPending = 0;
                } else {
                    enBoss[i] = 0;
                    // variety from wave 2: ~22% runner (fast, frail), ~16% brute (slow, tanky)
                    float tr = (wave >= 2) ? rng.nextFloat() : 1f;
                    int type = tr < 0.22f ? 1 : (tr < 0.38f ? 2 : 0);
                    enType[i] = type;
                    enScale[i] = type == 1 ? 0.82f : (type == 2 ? 1.45f : 1f);
                    enHP[i] = ENEMY_FULL_HP * hpMul * (type == 1 ? 0.55f : (type == 2 ? 2.6f : 1f));
                    enOutfit[i] = type == 1 ? 4 % OUTFITS.length : (type == 2 ? 3 % OUTFITS.length : rng.nextInt(OUTFITS.length));
                }
                enMaxHP[i] = enHP[i];
                enAlive[i] = true;
                return;
            }
        }
    }

    // --- death pixel particles ---

    /** Burst an enemy into many coloured voxels (shirt / pants / yellow head). Bosses spew more. */
    private void spawnDeathBurst(float ex, float ez, float scale, int outfit) {
        float[] o = OUTFITS[outfit];
        float groundY = terrainH(ex, ez);
        int count = Math.round(26 * scale);
        for (int n = 0; n < count; n++) {
            int i = pNext;
            pNext = (pNext + 1) % MAX_PARTICLES;
            float ox = (rng.nextFloat() - 0.5f) * 0.6f * scale;
            float oz = (rng.nextFloat() - 0.5f) * 0.6f * scale;
            pX[i] = ex + ox;
            pY[i] = groundY + rng.nextFloat() * 1.7f * scale;
            pZ[i] = ez + oz;
            pVX[i] = ox * 5f + (rng.nextFloat() - 0.5f) * 2.2f;       // burst outward
            pVZ[i] = oz * 5f + (rng.nextFloat() - 0.5f) * 2.2f;
            pVY[i] = 2.4f + rng.nextFloat() * 3.6f;                   // pop up
            int c = rng.nextInt(3);
            if (c == 0)      { pR[i] = o[0]; pG[i] = o[1]; pB[i] = o[2]; }   // shirt
            else if (c == 1) { pR[i] = o[3]; pG[i] = o[4]; pB[i] = o[5]; }   // pants
            else             { pR[i] = 0.97f; pG[i] = 0.83f; pB[i] = 0.30f; } // yellow head
            pSize[i] = (0.05f + rng.nextFloat() * 0.045f) * scale;
            pMaxLife[i] = 0.55f + rng.nextFloat() * 0.45f;
            pLife[i] = pMaxLife[i];
        }
    }

    /** A small bright spark spray when a bullet connects — sprays back along the bullet's path
     *  (bdx,bdy,bdz = bullet travel dir; sparks fly toward the shooter), gold for headshots. */
    private void spawnHitSparks(float ex, float ey, float ez, boolean head, float bdx, float bdy, float bdz) {
        int count = head ? 9 : 5;
        for (int n = 0; n < count; n++) {
            int i = pNext;
            pNext = (pNext + 1) % MAX_PARTICLES;
            pX[i] = ex + (rng.nextFloat() - 0.5f) * 0.2f;
            pY[i] = ey + (rng.nextFloat() - 0.5f) * 0.2f;
            pZ[i] = ez + (rng.nextFloat() - 0.5f) * 0.2f;
            pVX[i] = -bdx * 3.2f + (rng.nextFloat() - 0.5f) * 2.6f;       // spray back toward the shooter
            pVY[i] = -bdy * 2.0f + 1.2f + rng.nextFloat() * 1.8f;
            pVZ[i] = -bdz * 3.2f + (rng.nextFloat() - 0.5f) * 2.6f;
            if (head) { pR[i] = 1f; pG[i] = 0.86f; pB[i] = 0.32f; }   // gold
            else      { pR[i] = 1f; pG[i] = 0.52f; pB[i] = 0.20f; }   // orange
            pSize[i] = 0.035f + rng.nextFloat() * 0.03f;
            pMaxLife[i] = 0.22f + rng.nextFloat() * 0.2f;
            pLife[i] = pMaxLife[i];
        }
    }

    private void updateParticles(float dt) {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (pLife[i] <= 0f) continue;
            pLife[i] -= dt;
            pVY[i] -= GRAVITY * dt;
            pX[i] += pVX[i] * dt;
            pY[i] += pVY[i] * dt;
            pZ[i] += pVZ[i] * dt;
            float g = terrainH(pX[i], pZ[i]) + pSize[i] * 0.5f;
            if (pY[i] < g) {                              // land: small bounce + friction
                pY[i] = g; pVY[i] = -pVY[i] * 0.25f;
                pVX[i] *= 0.6f; pVZ[i] *= 0.6f;
            }
        }
    }

    private void drawParticles() {
        GLES20.glUseProgram(prog3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, metalTex);   // mode 3 ignores the texture
        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (pLife[i] <= 0f) continue;
            float lf = pLife[i] / pMaxLife[i];               // 1 -> 0
            float s = pSize[i] * (0.35f + 0.65f * lf);        // shrink as it dies
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, pX[i], pY[i], pZ[i]);
            Matrix.scaleM(model, 0, s, s, s);
            float boost = lf > 0.8f ? 1.6f : 1f;              // brief bright pop on spawn
            drawWorld(cube, 36, 3f, pR[i] * boost, pG[i] * boost, pB[i] * boost);
        }
    }

    // --- collectible drops ---

    private void spawnPickup(float x, float z, int type) {
        int i = pkNext; pkNext = (pkNext + 1) % MAX_PICKUPS;
        pkX[i] = x; pkZ[i] = z; pkY[i] = terrainH(x, z) + 0.55f;
        pkType[i] = type; pkLife[i] = 12f;
    }

    private void updatePickups(float dt) {
        for (int i = 0; i < MAX_PICKUPS; i++) {
            if (pkLife[i] <= 0f) continue;
            pkLife[i] -= dt;
            float dx = px - pkX[i], dz = pz - pkZ[i];
            if (dx * dx + dz * dz < 1.5f * 1.5f) {            // walked over → collect
                pkLife[i] = 0f;
                if (pkType[i] == 0) {
                    playerHP = Math.min(effMaxHp(), playerHP + 30f);
                    spawnPopup("+30 HP", width * 0.5f, height * 0.56f, AC_GREEN[0], AC_GREEN[1], AC_GREEN[2], 24f);
                } else {
                    for (int w = 0; w < W_COUNT; w++) wReserve[w] = Math.min(W_RES_MAX[w], wReserve[w] + W_RES_MAX[w] / 3);
                    spawnPopup("+AMMO", width * 0.5f, height * 0.56f, AC_GOLD[0], AC_GOLD[1], AC_GOLD[2], 24f);
                }
                sfx.swap();
            }
        }
    }

    private void drawPickups() {
        GLES20.glUseProgram(prog3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, metalTex);
        float spin = timeAcc * 110f, bob = (float) Math.sin(timeAcc * 3f) * 0.12f;
        for (int i = 0; i < MAX_PICKUPS; i++) {
            if (pkLife[i] <= 0f) continue;
            float fade = pkLife[i] < 2f ? Math.max(0.25f, pkLife[i] / 2f) : 1f;   // dim out near despawn
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, pkX[i], pkY[i] + bob, pkZ[i]);
            Matrix.rotateM(model, 0, spin, 0f, 1f, 0f);
            Matrix.rotateM(model, 0, 32f, 1f, 0f, 0.35f);
            Matrix.scaleM(model, 0, 0.22f, 0.22f, 0.22f);
            if (pkType[i] == 0) drawWorld(cube, 36, 3f, 0.95f * fade, 0.22f * fade, 0.24f * fade);   // health: red
            else                drawWorld(cube, 36, 3f, 0.98f * fade, 0.8f * fade, 0.22f * fade);    // ammo: gold
        }
    }

    private void triggerGameOver() {
        if (state != ST_PLAYING || deathAnim > 0f) return;
        aimOn = false;
        sfx.over();
        newBest = score > highScore;
        if (newBest) {
            highScore = score;
            prefs.edit().putInt("highscore", highScore).apply();
        }
        saveMeta();
        deathAnim = 1.5f;            // play the death sequence, then hand off to the summary
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
            boolean interior = b.length > 10 && b[10] != 0f;   // furniture collider: steer around it even if low
            if (!interior && b[1] + b[4] * 0.5f < 0.5f) continue;   // ignore anything you'd just step over
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
            float hf = enHurt[i] > 0f ? 0.8f : 0f;            // hit flash (brighten)
            float wr = 0f;                                     // attack-telegraph red blend
            if (enWind[i] > 0f) {                              // reared up + flashing red, telegraphing the strike
                bob += 0.18f + 0.05f * (float) Math.sin(timeAcc * 12f);
                wr = 0.58f + 0.32f * (float) Math.sin(timeAcc * 18f);   // pulsing but always strongly red
            }
            float[] o = OUTFITS[enOutfit[i]];
            float shR = (o[0] + hf) * (1f - wr) + 1.00f * wr, shG = (o[1] + hf) * (1f - wr) + 0.16f * wr, shB = (o[2] + hf) * (1f - wr) + 0.13f * wr;
            float paR = (o[3] + hf) * (1f - wr) + 1.00f * wr, paG = (o[4] + hf) * (1f - wr) + 0.16f * wr, paB = (o[5] + hf) * (1f - wr) + 0.13f * wr;
            float hdR = (0.97f + hf) * (1f - wr) + 1.00f * wr, hdG = (0.83f + hf) * (1f - wr) + 0.16f * wr, hdB = (0.30f + hf) * (1f - wr) + 0.13f * wr;
            Matrix.setIdentityM(gunBase, 0);                  // reuse as enemy base
            Matrix.translateM(gunBase, 0, enX[i], terrainH(enX[i], enZ[i]) + bob, enZ[i]);
            Matrix.rotateM(gunBase, 0, (float) Math.toDegrees(enFace[i]), 0f, 1f, 0f);
            if (enScale[i] != 1f) Matrix.scaleM(gunBase, 0, enScale[i], enScale[i], enScale[i]);  // bosses are bigger
            enemyPart(0f, 0.95f, 0f, 0.50f, 0.75f, 0.30f, shR, shG, shB);                       // torso (shirt)
            enemyPart(0f, 1.50f, 0f, 0.34f, 0.34f, 0.34f, hdR, hdG, hdB);                      // head
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

    /** Like drawWorld but draws only [first, first+count) vertices — lets one baked mesh hold many colours. */
    private void drawWorldRange(FloatBuffer buf, int first, int count, float mode, float r, float g, float b) {
        Matrix.multiplyMM(tmpA, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmpA, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        GLES20.glUniform1f(uMode, mode);
        GLES20.glUniform3f(uColor, r, g, b);
        buf.position(0); GLES20.glEnableVertexAttribArray(aPos);    GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, buf);
        buf.position(3); GLES20.glEnableVertexAttribArray(aNormal); GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, STRIDE, buf);
        buf.position(6); GLES20.glEnableVertexAttribArray(aUV);     GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, STRIDE, buf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, first, count);
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
        if (state == ST_PLAYING) {
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
            float ang = doorOpen[i] * 85f * (dd.length > 12 ? dd[12] : 1f);   // signed so every leaf swings OUTWARD into the cleared front
            int axis = (int) dd[6];
            float tH = dd[3], vH = dd[4], pH = dd[5];        // half tangent / vertical / depth of the leaf
            Matrix.setIdentityM(doorBase, 0);               // hinge: origin ends at the leaf centre
            if (axis == 0) {                                // wall spans X, hinge on the +X edge
                Matrix.translateM(doorBase, 0, dd[0] + tH, dd[1], dd[2]);
                Matrix.rotateM(doorBase, 0, ang, 0f, 1f, 0f);
                Matrix.translateM(doorBase, 0, -tH, 0f, 0f);
            } else {                                        // wall spans Z, hinge on the +Z edge
                Matrix.translateM(doorBase, 0, dd[0], dd[1], dd[2] + tH);
                Matrix.rotateM(doorBase, 0, ang, 0f, 1f, 0f);
                Matrix.translateM(doorBase, 0, 0f, 0f, -tH);
            }
            float em = (i == nearDoor) ? 1.25f : 1f;        // highlight the door you can use
            float lr = dd[8] * em, lg = dd[9] * em, lb = dd[10] * em;
            int style = dd.length > 11 ? (int) dd[11] : 0;
            doorBit(axis, 0f, 0f, 0f, tH * 2f, vH * 2f, pH * 2f, lr, lg, lb);   // leaf
            float sr = dd[8] * 0.78f * em, sg = dd[9] * 0.78f * em, sb = dd[10] * 0.78f * em;  // recessed shade
            float gr = 0.55f, gg = 0.66f, gb = 0.74f;       // glass
            float fd = pH * 1.2f;                            // detail sits just proud of the leaf face
            for (int f = -1; f <= 1; f += 2) {              // detail on both faces (exterior + interior)
                float ld = f * fd;
                if (style == 0) {                          // panelled (4 panels)
                    for (int a2 = -1; a2 <= 1; a2 += 2) for (int b2 = -1; b2 <= 1; b2 += 2)
                        doorBit(axis, a2 * tH * 0.42f, b2 * vH * 0.42f, ld, tH * 0.58f, vH * 0.62f, pH * 0.45f, sr, sg, sb);
                } else if (style == 1) {                   // plank (vertical grooves)
                    for (int p = -2; p <= 2; p++)
                        doorBit(axis, p * tH * 0.36f, 0f, ld, pH * 1.0f, vH * 1.85f, pH * 0.4f, sr * 0.9f, sg * 0.9f, sb * 0.9f);
                } else if (style == 2) {                   // glazed (upper pane + lower panel)
                    doorBit(axis, 0f, vH * 0.42f, ld, tH * 1.35f, vH * 0.78f, pH * 0.45f, gr, gg, gb);
                    doorBit(axis, 0f, -vH * 0.45f, ld, tH * 1.35f, vH * 0.72f, pH * 0.45f, sr, sg, sb);
                } else {                                   // shop (big glazing + cross muntins)
                    doorBit(axis, 0f, vH * 0.08f, ld, tH * 1.55f, vH * 1.5f, pH * 0.45f, gr, gg, gb);
                    doorBit(axis, 0f, 0f, ld * 1.1f, tH * 1.7f, pH * 0.6f, pH * 0.6f, lr, lg, lb);
                    doorBit(axis, 0f, vH * 0.45f, ld * 1.1f, pH * 0.6f, vH * 1.0f, pH * 0.6f, lr, lg, lb);
                }
            }
            for (int f2 = -1; f2 <= 1; f2 += 2) {                          // lever handle on BOTH faces, on a back-plate
                float hd2 = f2 * pH * 1.05f;
                doorBit(axis, -tH * 0.70f, 0.0f, hd2, pH * 1.1f, vH * 0.34f, pH * 0.7f, 0.34f, 0.34f, 0.36f);          // dark back-plate
                doorBit(axis, -tH * 0.72f, 0.02f, hd2 * 1.3f, pH * 1.5f, vH * 0.12f, pH * 0.9f, 0.80f, 0.74f, 0.50f);  // brass lever
            }
        }
    }

    /** Draw one box of a door in the leaf's local frame (lt=along width, lv=vertical, ld=depth; sizes are full). */
    private void doorBit(int axis, float lt, float lv, float ld, float st, float sv, float sd, float r, float g, float b) {
        System.arraycopy(doorBase, 0, model, 0, 16);
        if (axis == 0) { Matrix.translateM(model, 0, lt, lv, ld); Matrix.scaleM(model, 0, st, sv, sd); }
        else           { Matrix.translateM(model, 0, ld, lv, lt); Matrix.scaleM(model, 0, sd, sv, st); }
        drawWorld(cube, 36, 0f, r, g, b);
    }

    // --- weapon viewmodels ---

    private void drawGun() {
        float a = aim, s = sprintAnim;
        Matrix.setIdentityM(gunBase, 0);
        // hip -> ADS: slide to centre, raise so the sights sit on the screen centre.
        float tx = mix(0.30f, 0.0f, a) + 0.05f * s;            // hip: held to lower-right; ADS: centred
        float ty = mix(-0.27f, -SIGHT_BEAD[curWeapon], a) - switchAnim * 0.22f - 0.12f * s;  // hip: lowered out of the way; ADS: sight on centre
        float tz = mix(-0.66f, -0.30f, a) - recoil * 0.30f * (1f - 0.5f * a) + 0.16f * s;    // hip: pushed back (smaller); ADS: close
        Matrix.translateM(gunBase, 0, tx, ty, tz);
        Matrix.rotateM(gunBase, 0, -2.0f * (1f - a) + recoil * 9f * (1f - 0.5f * a) + switchAnim * 22f + 20f * s, 1f, 0f, 0f);
        Matrix.rotateM(gunBase, 0, -8f * (1f - a) + 24f * s, 0f, 1f, 0f);
        Matrix.rotateM(gunBase, 0, 26f * s, 0f, 0f, 1f);       // cant across the body when sprinting

        float mz;
        if (curWeapon == 0) mz = drawPistol();
        else if (curWeapon == 1) mz = drawRifle();
        else if (curWeapon == 2) mz = drawShotgun();
        else mz = drawSniper();

        if (muzzleTimer > 0f) drawMuzzle(mz, curWeapon == 2 ? 1.7f : (curWeapon == 3 ? 1.3f : 1f));
    }

    private float drawPistol() {                                 // compact pistol (Glock-ish)
        float dk = 0.16f, dg = 0.17f, dd = 0.20f;               // gunmetal slide
        float fr = 0.10f, fg = 0.10f, fb = 0.12f;               // polymer frame
        gunPart(0f, 0.030f, -0.02f, 0.060f, 0.070f, 0.32f, dk, dg, dd);          // slide
        gunPart(0f, 0.060f, 0.120f, 0.060f, 0.030f, 0.10f, dk * 0.9f, dg * 0.9f, dd * 0.9f); // rear sight hump
        gunPart(0f, 0.010f, -0.205f, 0.034f, 0.040f, 0.07f, 0.06f, 0.06f, 0.07f); // muzzle end
        gunPart(0f, -0.020f, -0.05f, 0.050f, 0.045f, 0.26f, fr, fg, fb);          // frame / dust cover
        gunPart(0f, -0.072f, 0.020f, 0.044f, 0.028f, 0.075f, fr, fg, fb);         // trigger guard bar
        gunPart(0f, -0.058f, 0.030f, 0.016f, 0.034f, 0.018f, 0.05f, 0.05f, 0.06f); // trigger
        gunPartR(0f, -0.150f, 0.100f, 0.056f, 0.195f, 0.075f, 13f, fr + 0.02f, fg + 0.02f, fb + 0.02f); // raked grip
        gunPartR(0f, -0.250f, 0.122f, 0.060f, 0.028f, 0.080f, 13f, 0.07f, 0.07f, 0.08f);  // magazine base
        drawSights();
        return -0.24f;
    }

    private float drawRifle() {                                 // SMG (MP5-style)
        float bk = 0.12f, bg = 0.12f, bb = 0.14f;               // black body
        float mk = 0.20f, mg = 0.21f, mb = 0.24f;               // metal accents
        gunPart(0f, 0.0f, 0.04f, 0.058f, 0.075f, 0.36f, bk, bg, bb);             // receiver
        gunPart(0f, 0.052f, 0.10f, 0.045f, 0.028f, 0.20f, mk, mg, mb);           // top rib / rear sight base
        gunPart(0f, -0.02f, -0.20f, 0.052f, 0.060f, 0.20f, bk * 0.95f, bg * 0.95f, bb * 0.95f); // handguard
        gunPart(0f, 0.0f, -0.36f, 0.020f, 0.020f, 0.18f, 0.05f, 0.05f, 0.06f);   // barrel
        gunPart(0f, 0.038f, -0.31f, 0.026f, 0.050f, 0.03f, mk, mg, mb);          // front sight hood
        gunPart(0f, 0.0f, -0.46f, 0.032f, 0.032f, 0.05f, 0.07f, 0.07f, 0.08f);   // muzzle
        gunPartR(0f, -0.155f, -0.01f, 0.044f, 0.20f, 0.052f, -16f, mk * 0.7f, mg * 0.7f, mb * 0.7f);  // mag (upper)
        gunPartR(0f, -0.295f, -0.075f, 0.044f, 0.15f, 0.050f, -26f, mk * 0.65f, mg * 0.65f, mb * 0.65f); // mag (curved lower)
        gunPartR(0f, -0.150f, 0.155f, 0.048f, 0.16f, 0.058f, 15f, bk + 0.02f, bg + 0.02f, bb + 0.02f); // pistol grip
        gunPart(0f, 0.0f, 0.27f, 0.040f, 0.055f, 0.16f, bk, bg, bb);             // stock arm
        gunPart(0f, -0.015f, 0.38f, 0.048f, 0.085f, 0.05f, bk + 0.02f, bg + 0.02f, bb + 0.02f); // butt pad
        gunPart(-0.052f, 0.045f, -0.10f, 0.018f, 0.018f, 0.06f, mk + 0.06f, mg + 0.06f, mb + 0.06f); // cocking handle
        drawSights();
        return -0.50f;
    }

    private float drawShotgun() {                               // pump-action (Remington 870-style)
        float mk = 0.16f, mg = 0.17f, mb = 0.20f;               // blued steel
        float wr = 0.34f, wg = 0.20f, wb = 0.09f;               // wood
        gunPart(0f, 0.0f, 0.07f, 0.058f, 0.075f, 0.26f, mk * 0.9f, mg * 0.9f, mb * 0.9f); // receiver
        gunPart(0f, 0.032f, -0.34f, 0.028f, 0.028f, 0.52f, mk, mg, mb);          // barrel
        gunPart(0f, -0.016f, -0.30f, 0.026f, 0.026f, 0.42f, mk * 0.85f, mg * 0.85f, mb * 0.85f); // tube magazine
        gunPart(0f, -0.018f, -0.16f, 0.050f, 0.052f, 0.17f, wr, wg, wb);         // forend (wood)
        gunPart(0f, -0.018f, -0.16f, 0.056f, 0.044f, 0.155f, wr * 0.85f, wg * 0.85f, wb * 0.85f); // forend rib
        gunPartR(0f, -0.030f, 0.28f, 0.052f, 0.085f, 0.22f, -7f, wr, wg, wb);    // stock (wood, slight drop)
        gunPart(0f, -0.062f, 0.40f, 0.058f, 0.105f, 0.05f, wr * 0.9f, wg * 0.9f, wb * 0.9f); // butt pad
        gunPart(0f, -0.062f, 0.05f, 0.038f, 0.026f, 0.06f, mk, mg, mb);          // trigger guard
        drawSights();
        return -0.66f;
    }

    private float drawSniper() {                                // bolt-action sniper (AWP / R700-ish) with a scope
        float bk = 0.10f, bg = 0.11f, bb = 0.13f;               // dark body
        float mk = 0.18f, mg = 0.19f, mb = 0.22f;               // metal
        float sc = SIGHT_BEAD[3];                               // scope centre = ADS aim line height
        gunPart(0f, 0.0f, 0.06f, 0.056f, 0.072f, 0.42f, bk, bg, bb);             // receiver
        gunPart(0f, 0.006f, -0.44f, 0.022f, 0.022f, 0.70f, mk, mg, mb);          // long barrel
        gunPart(0f, 0.006f, -0.82f, 0.030f, 0.030f, 0.07f, 0.06f, 0.06f, 0.07f); // muzzle brake
        gunPartR(0f, -0.020f, 0.30f, 0.050f, 0.072f, 0.30f, -4f, bk + 0.02f, bg + 0.02f, bb + 0.02f); // straight stock
        gunPart(0f, -0.030f, 0.46f, 0.056f, 0.10f, 0.05f, bk, bg, bb);           // butt pad
        gunPartR(0f, -0.140f, 0.10f, 0.046f, 0.16f, 0.052f, 14f, bk + 0.02f, bg + 0.02f, bb + 0.02f); // pistol grip
        gunPartR(0f, -0.120f, -0.03f, 0.042f, 0.10f, 0.050f, 0f, mk * 0.8f, mg * 0.8f, mb * 0.8f);    // magazine
        gunPart(0.058f, 0.030f, 0.14f, 0.050f, 0.014f, 0.014f, mk + 0.05f, mg + 0.05f, mb + 0.05f);   // bolt handle
        // scope: two ring mounts, a tube, front/rear lens housings, and a red reticle bead (the aim point)
        float mc = (0.05f + sc) * 0.5f, mh = sc - 0.05f + 0.01f;
        gunPart(0f, mc, 0.10f, 0.016f, mh, 0.022f, mk, mg, mb);                  // rear scope mount
        gunPart(0f, mc, -0.12f, 0.016f, mh, 0.022f, mk, mg, mb);                 // front scope mount
        gunPart(0f, sc, -0.01f, 0.034f, 0.034f, 0.34f, 0.05f, 0.05f, 0.06f);     // scope tube
        gunPart(0f, sc, -0.20f, 0.040f, 0.040f, 0.03f, 0.04f, 0.04f, 0.05f);     // objective lens housing
        gunPart(0f, sc, 0.18f, 0.038f, 0.038f, 0.03f, 0.04f, 0.04f, 0.05f);      // ocular housing
        gunBeadPart(0f, sc, -0.18f, 0.009f, 1.4f, 0.22f, 0.22f);                 // red reticle dot (centres on ADS)
        return -0.86f;
    }

    /** Iron sights mounted on the current weapon: a small base/post connects the gun body up to the
     *  sight line, with a rear notch and a front blade + glowing bead. The bead is the aim point and,
     *  via drawGun's ADS lift (-SIGHT_BEAD), sits at screen centre when aiming. */
    private void drawSights() {
        int w = curWeapon;
        float sb = SIGHT_BASE[w], sy = SIGHT_BEAD[w], rz = SIGHT_REARZ[w], fz = SIGHT_FRONTZ[w];
        float pc = (sb + sy) * 0.5f, ph = (sy - sb) + 0.010f;            // sight-post centre + height
        float dk = 0.05f, dg = 0.05f, db = 0.06f;                        // matte sight metal
        // Rear notch (Kimme): two thin posts + a low base bridge — OPEN in the middle so the target
        // (and the front dot) show through. No solid block at eye level.
        gunPart(0f, sb + 0.005f, rz, 0.064f, 0.010f, 0.026f, dk, dg, db);     // low base bridge
        gunPart(-0.030f, pc, rz, 0.008f, ph, 0.024f, dk, dg, db);            // notch post L
        gunPart( 0.030f, pc, rz, 0.008f, ph, 0.024f, dk, dg, db);            // notch post R
        // Front sight (Korn): a thin post (Strich) with a bright glowing dot (Punkt) on top = aim point.
        gunPart(0f, pc, fz, 0.007f, ph, 0.012f, dk, dg, db);                 // front post
        gunBeadPart(0f, sy + 0.005f, fz, 0.015f,                             // bright dot (centres on ADS)
                0.55f + 0.45f * weaponR(w), 0.55f + 0.45f * weaponG(w), 0.55f + 0.45f * weaponB(w));
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

    /** Like gunPart but pitched about its X axis (for raked grips, angled/curved magazines, stocks). */
    private void gunPartR(float tx, float ty, float tz, float sx, float sy, float sz,
                          float rotXDeg, float r, float g, float b) {
        Matrix.setIdentityM(partM, 0);
        Matrix.translateM(partM, 0, tx, ty, tz);
        Matrix.rotateM(partM, 0, rotXDeg, 1f, 0f, 0f);
        Matrix.scaleM(partM, 0, sx, sy, sz);
        Matrix.multiplyMM(tmpA, 0, gunBase, 0, partM, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmpA, 0);
        submit(cube, 36, mvp, tmpA, 3f, r, g, b);
    }

    private void updateCamera(float dt) {
        if (state != ST_PLAYING) {
            // Frozen (hub / summary): hold the camera and just show the arena from the current pose.
            float cp = (float) Math.cos(pitch);
            Matrix.setLookAtM(view, 0, px, py, pz,
                    px + cp * (float) Math.sin(yaw), py + (float) Math.sin(pitch), pz - cp * (float) Math.cos(yaw),
                    0f, 1f, 0f);
            return;
        }
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
        if (!fired && W_AUTO[curWeapon] && input.isFireHeld()) fire();

        // --- horizontal movement; sprint when the stick is pushed fully forward ---
        float mx = input.moveX(), my = input.moveY();
        float mlen = (float) Math.sqrt(mx * mx + my * my);
        sprinting = grounded && mlen > 0.9f && my > 0.5f && aim < 0.2f;
        // sprint accelerates in gradually (eased by sprintAnim) instead of snapping to top speed
        float sprintMul = 1f + (SPRINT_MULT - 1f) * sprintAnim;
        float speed = MOVE_SPEED * sprintMul * (1f + 0.06f * abLevel[AB_MOVESPEED]);
        float fwdX = (float) Math.sin(yaw), fwdZ = -(float) Math.cos(yaw);
        float rgtX = (float) Math.cos(yaw), rgtZ = (float) Math.sin(yaw);
        px += (rgtX * mx + fwdX * my) * speed * dt;
        pz += (rgtZ * mx + fwdZ * my) * speed * dt;
        colTmp[0] = px; colTmp[1] = pz;
        collide(0.32f, feetY, true, colTmp);             // walls block, ledges below the feet don't
        px = colTmp[0]; pz = colTmp[1];

        // --- vertical movement: jump / climb / gravity ---
        boolean wasGrounded = grounded;
        if (input.consumeJump()) doJump();
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
        if (grounded) jumpsUsed = 0;                  // reset air-jumps on landing
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

        // Cache the camera basis (forward/right/up) so the sky shader can rebuild per-pixel view rays.
        camFwd[0] = ldx; camFwd[1] = ldy; camFwd[2] = ldz;
        float rl = (float) Math.sqrt(ldz * ldz + ldx * ldx);   // right = normalize(fwd x worldUp)
        if (rl < 1e-4f) rl = 1e-4f;
        camRight[0] = -ldz / rl; camRight[1] = 0f; camRight[2] = ldx / rl;
        camUp[0] = camRight[1] * ldz - camRight[2] * ldy;       // up = right x fwd
        camUp[1] = camRight[2] * ldx - camRight[0] * ldz;
        camUp[2] = camRight[0] * ldy - camRight[1] * ldx;
    }

    /** Jump if grounded (or once more in mid-air with the Double-Jump ability); climb low ledges. */
    private void doJump() {
        if (grounded) {
            vy = JUMP_V; grounded = false; jumpsUsed = 1;
        } else if (abLevel[AB_DOUBLEJUMP] > 0 && jumpsUsed < 2) {
            vy = JUMP_V; jumpsUsed++;                 // mid-air second jump
        }
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
        if (recoil > 0f) { recoil -= recoil * Math.min(1f, dt * 9f) + dt * 0.04f; if (recoil < 0f) recoil = 0f; }   // snappy exp. recovery
        if (comboTimer > 0f) { comboTimer -= dt; if (comboTimer <= 0f) combo = 1; }
        if (reloadTimer > 0f) { reloadTimer -= dt; if (reloadTimer <= 0f) finishReload(); }
        aim += (((aimOn && state == ST_PLAYING) ? 1f : 0f) - aim) * Math.min(1f, dt * 12f);
        if (aim < 0.001f) aim = 0f; else if (aim > 0.999f) aim = 1f;
        // ramp into the sprint gradually (~0.6 s to top speed), drop out of it quickly
        float saRate = sprinting ? 4f : 10f;
        sprintAnim += ((sprinting ? 1f : 0f) - sprintAnim) * Math.min(1f, dt * saRate);
        if (sprintAnim < 0.001f) sprintAnim = 0f; else if (sprintAnim > 0.999f) sprintAnim = 1f;
        input.setMenuMode(state != ST_PLAYING);
    }

    private void restart() {
        score = 0; combo = 1; comboTimer = 0f;
        runCash = 0; runXp = 0; runKills = 0; levelsGainedThisRun = 0; jumpsUsed = 0;
        curWeapon = firstOwnedWeapon();
        for (int w = 0; w < W_COUNT; w++) { wMag[w] = effMag(w); wReserve[w] = W_RES_START[w]; }
        reloadTimer = 0f; fireCd = 0f; shake = 0f; switchAnim = 0f; recoil = 0f;
        playerHP = effMaxHp(); hurtFlash = 0f;
        px = 0f; pz = 9f; yaw = 0f; pitch = -0.08f;
        feetY = 0f; vy = 0f; grounded = true; py = EYE_H;
        aimOn = false; aim = 0f; sprinting = false; sprintAnim = 0f; bobPhase = 0f;
        for (int i = 0; i < doorData.length; i++) { doorOpen[i] = 0f; doorTarget[i] = 0f; }
        nearDoor = -1;
        for (int i = 0; i < MAX_ENEMIES; i++) { enAlive[i] = false; enScale[i] = 1f; enBoss[i] = 0; enKx[i] = 0f; enKz[i] = 0f; enWind[i] = 0f; }
        for (int i = 0; i < MAX_PICKUPS; i++) pkLife[i] = 0f;
        hitStop = 0f;
        for (int i = 0; i < MAX_PARTICLES; i++) pLife[i] = 0f;
        waveBreak = 0f; waveBanner = 0f; bossPending = 0;
        beginWave(1);
    }

    /** Begin a fresh run from the hub. */
    private void startRun() {
        restart();
        state = ST_PLAYING;
        hubWasShown = false; hubTabShown = -1;   // replay hub entrance + content stagger next visit
    }

    // --- shooting & weapons ---

    private void fire() {
        if (state != ST_PLAYING || deathAnim > 0f) return;
        if (sprinting) return;             // gun is lowered while sprinting
        if (reloadTimer > 0f) return;
        if (fireCd > 0f) return;
        if (wMag[curWeapon] <= 0) { beginReload(); return; }
        wMag[curWeapon]--;
        fireCd = effInterval(curWeapon);

        muzzleTimer = MUZZLE_TIME * (curWeapon == 2 ? 1.6f : 1f);
        flashTimer = FLASH_TIME;
        recoil += W_RECOIL[curWeapon];
        if (recoil > 0.26f) recoil = 0.26f;
        if (optShake) shake = W_SHAKE[curWeapon];
        playWeaponSound();
        ejectShell();

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
                float sc = enScale[i];                    // bosses are bigger → bigger hit boxes
                float bt = rayBox(px, py, pz, dx, dy, dz,
                        enX[i] - 0.45f * sc, 0f, enZ[i] - 0.45f * sc, enX[i] + 0.45f * sc, 1.24f * sc, enZ[i] + 0.45f * sc);
                if (bt >= 0f && bt < best) { best = bt; type = 1; idx = i; }
                float ht = rayBox(px, py, pz, dx, dy, dz,
                        enX[i] - 0.24f * sc, 1.26f * sc, enZ[i] - 0.24f * sc, enX[i] + 0.24f * sc, 1.78f * sc, enZ[i] + 0.24f * sc);
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
                float dmg = head ? effHeadDmg(curWeapon) : effBodyDmg(curWeapon);
                enHP[idx] -= dmg;
                float kf = (head ? 4.6f : 2.3f) * (enBoss[idx] > 0 ? 0.22f : 1f);   // knockback (headshots hit harder)
                enKx[idx] += dx * kf; enKz[idx] += dz * kf;
                spawnHitSparks(enX[idx], terrainH(enX[idx], enZ[idx]) + (head ? 1.6f : 0.95f) * enScale[idx], enZ[idx], head, dx, dy, dz);
                // floating damage number at the enemy (projected to screen)
                if (optDmgNum && projectToScreen(enX[idx], 1.45f * enScale[idx], enZ[idx], projScreen)) {
                    float jx = (fxRnd.nextFloat() - 0.5f) * 36f * us;
                    if (head) spawnPopup("" + Math.round(dmg), projScreen[0] + jx, projScreen[1], 1f, 0.82f, 0.22f, 27f);
                    else      spawnPopup("" + Math.round(dmg), projScreen[0] + jx, projScreen[1], 1f, 0.97f, 0.85f, 20f);
                }
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

    private void spawnPopup(String s, float x, float y, float r, float g, float b, float sz) {
        int i = popNext; popNext = (popNext + 1) % MAX_POPUPS;
        popText[i] = s; popX[i] = x; popY[i] = y; popT[i] = POPUP_TIME;
        popR[i] = r; popG[i] = g; popB[i] = b; popSz[i] = sz;
    }

    private void pushFeed(String s, int kind) {
        int i = feedNext; feedNext = (feedNext + 1) % MAX_FEED;
        feedText[i] = s; feedKind[i] = kind; feedT[i] = 3.6f;
    }

    private void updatePopups(float dt) {
        if (hurtDirTimer > 0f) hurtDirTimer -= dt;
        for (int i = 0; i < MAX_FEED; i++) if (feedT[i] > 0f) feedT[i] -= dt;
        for (int i = 0; i < MAX_POPUPS; i++) {
            if (popT[i] <= 0f) continue;
            popT[i] -= dt;
            popY[i] -= 95f * us * dt;                     // rise
        }
    }

    /** Top-right killfeed: recent eliminations stacked newest-first, fading out. */
    private void drawKillfeed() {
        float x = width - 24f * us, y = 132f * us, rowH = 34f * us;
        int shown = 0;
        for (int n = 0; n < MAX_FEED && shown < MAX_FEED; n++) {
            int i = (feedNext - 1 - n + MAX_FEED * 2) % MAX_FEED;
            if (feedT[i] <= 0f || feedText[i] == null) continue;
            float a = Math.min(1f, feedT[i] / 0.5f);
            float cy = y + shown * rowH;
            float tw = measureText(feedText[i], 15f) + 40f * us;
            drawRoundRect(x - tw * 0.5f, cy, tw, 28f * us, 14f * us, 0.06f, 0.08f, 0.13f, 0.55f * a);
            float dr = feedKind[i] == 1 ? 1f : (feedKind[i] == 2 ? 1f : 0.95f);
            float dg = feedKind[i] == 1 ? 0.82f : (feedKind[i] == 2 ? 0.3f : 0.35f);
            float db = feedKind[i] == 1 ? 0.25f : (feedKind[i] == 2 ? 0.25f : 0.3f);
            drawCircle(x - tw + 16f * us, cy, 5f * us, dr, dg, db, 0.95f * a);
            drawText(feedText[i], x - tw + 28f * us, cy, 15f, 0.9f, 0.92f, 0.96f, a);
            shown++;
        }
    }

    private void drawPopups() {
        for (int i = 0; i < MAX_POPUPS; i++) {
            if (popT[i] <= 0f || popText[i] == null) continue;
            float k = popT[i] / POPUP_TIME;               // 1 → 0
            float a = Math.min(1f, k * 3.2f);             // fade out near the end
            float age = clamp(POPUP_TIME - popT[i], 0f, 10f);
            float pop = 1f + 0.35f * (1f - Math.min(1f, age * 9f)); // brief pop-in scale at spawn
            drawTextCenteredShadow(popText[i], popX[i], popY[i], popSz[i] * pop, popR[i], popG[i], popB[i], a);
        }
    }

    /** Eject a brass shell casing to the right of the gun (arcs and bounces via the particle physics). */
    private void ejectShell() {
        int i = pNext; pNext = (pNext + 1) % MAX_PARTICLES;
        float ox = camFwd[0] * 0.45f + camRight[0] * 0.14f, oz = camFwd[2] * 0.45f + camRight[2] * 0.14f;
        pX[i] = px + ox; pY[i] = py - 0.12f; pZ[i] = pz + oz;
        pVX[i] = camRight[0] * 2.7f + (rng.nextFloat() - 0.5f) * 0.6f;
        pVZ[i] = camRight[2] * 2.7f + (rng.nextFloat() - 0.5f) * 0.6f;
        pVY[i] = 1.7f + rng.nextFloat() * 0.7f;
        pR[i] = 0.85f; pG[i] = 0.6f; pB[i] = 0.18f;   // brass
        pSize[i] = 0.045f; pMaxLife[i] = 0.8f; pLife[i] = pMaxLife[i];
    }

    /** Project a world point to screen pixels via the current view+proj. Returns false if behind camera. */
    private boolean projectToScreen(float wx, float wy, float wz, float[] out) {
        Matrix.multiplyMM(vpMat, 0, proj, 0, view, 0);
        projIn[0] = wx; projIn[1] = wy; projIn[2] = wz; projIn[3] = 1f;
        Matrix.multiplyMV(projRes, 0, vpMat, 0, projIn, 0);
        if (projRes[3] <= 0.0001f) return false;
        out[0] = (projRes[0] / projRes[3] * 0.5f + 0.5f) * width;
        out[1] = (1f - (projRes[1] / projRes[3] * 0.5f + 0.5f)) * height;
        return true;
    }

    private void onKill(int idx, boolean head) {
        spawnDeathBurst(enX[idx], enZ[idx], enScale[idx], enOutfit[idx]);   // pixel-burst feedback
        hitStop = Math.max(hitStop, head ? 0.07f : 0.05f);                 // brief slow-mo crunch
        enAlive[idx] = false;
        combo = (comboTimer > 0f) ? Math.min(combo + 1, 9) : 1;
        comboTimer = COMBO_WINDOW;
        score += combo * (head ? 2 : 1);

        // combo rank banner at milestone kills
        String rank = null;
        if (combo == 2) rank = "DOUBLE KILL";
        else if (combo == 3) rank = "TRIPLE KILL";
        else if (combo == 5) rank = "MULTI KILL";
        else if (combo == 7) rank = "RAMPAGE";
        else if (combo == 9) rank = "UNSTOPPABLE";
        if (rank != null) spawnPopup(rank, width * 0.5f, height * 0.37f, 1f, 0.66f, 0.18f, 30f);
        pushFeed(enBoss[idx] > 0 ? "BOSS DOWN" : (head ? "HEADSHOT" : "ELIMINATED"), enBoss[idx] > 0 ? 2 : (head ? 1 : 0));
        if (optShake && enBoss[idx] > 0) shake = Math.max(shake, enBoss[idx] >= 2 ? 0.55f : 0.32f);   // boss-kill jolt
        // chance to drop a collectible (bosses always drop health)
        if (enBoss[idx] > 0) spawnPickup(enX[idx], enZ[idx], 0);
        else { float drop = rng.nextFloat(); if (drop < 0.15f) spawnPickup(enX[idx], enZ[idx], 0); else if (drop < 0.33f) spawnPickup(enX[idx], enZ[idx], 1); }

        // cash + xp: headshots worth +50%; cash scales with combo, wave (tougher = richer) and the Greed ability
        int baseGain = head ? 18 : 10;   // headshots pay better
        long cashGain = Math.round(baseGain * combo * (1f + 0.10f * abLevel[AB_CASHBONUS]) * (1f + 0.06f * (wave - 1)));
        cash += cashGain; runCash += cashGain;
        xp += baseGain; runXp += baseGain; runKills++;

        // floating reward popup near the reticle (jittered so multi-kills fan out)
        float jx = (fxRnd.nextFloat() - 0.5f) * 150f * us, jy = (fxRnd.nextFloat() - 0.5f) * 70f * us;
        float px2 = width * 0.5f + jx, py2 = height * 0.42f + jy;
        if (head) {
            spawnPopup("HEADSHOT", px2, py2 - 26f * us, 1f, 0.82f, 0.26f, 17f);
            spawnPopup("+$" + cashGain, px2, py2, 1f, 0.88f, 0.35f, 30f);
        } else {
            spawnPopup("+$" + cashGain, px2, py2, AC_GOLD[0], AC_GOLD[1], AC_GOLD[2], 24f);
        }
        if (combo >= 3) spawnPopup("x" + combo, px2 + 70f * us, py2 + 26f * us, 1f, 0.5f, 0.25f, 20f);
        int before = playerLevel;
        recomputeLevel();
        if (playerLevel > before) {
            levelsGainedThisRun += playerLevel - before;
            spawnPopup("LEVEL UP!", width * 0.5f, height * 0.33f, AC_CYAN[0], AC_CYAN[1], AC_CYAN[2], 32f);
            spawnPopup("LV " + playerLevel, width * 0.5f, height * 0.33f + 42f * us, 1f, 1f, 1f, 24f);
        }

        // Adrenaline: heal on kill
        if (abLevel[AB_ADRENALINE] > 0)
            playerHP = Math.min(effMaxHp(), playerHP + 6f * abLevel[AB_ADRENALINE]);

        grantAmmoForKill();
        waveRemaining--;
        if (waveRemaining <= 0) clearWave();
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
        if (wMag[curWeapon] >= effMag(curWeapon)) return;
        if (wReserve[curWeapon] <= 0) { sfx.dry(); fireCd = 0.25f; return; }  // out of ammo
        reloadTimer = effReload(curWeapon);
        reloadTotal = effReload(curWeapon);
        sfx.reload();
    }

    private void finishReload() {
        int need = effMag(curWeapon) - wMag[curWeapon];
        int take = Math.min(need, wReserve[curWeapon]);
        wMag[curWeapon] += take;
        wReserve[curWeapon] -= take;
    }

    private void cycleWeapon() {
        do { curWeapon = (curWeapon + 1) % W_COUNT; } while (wOwned[curWeapon] == 0);  // skip unowned
        reloadTimer = 0f;          // cancel any in-progress reload
        fireCd = 0.14f;            // brief lockout after the swap
        switchAnim = 1f;
        sfx.swap();
        // flash the weapon name above the ammo
        spawnPopup(WEAPON_NAME[curWeapon], Hud.fireCx(width), Hud.fireCy(height) - Hud.FIRE_RADIUS - 110f * us,
                weaponR(curWeapon), weaponG(curWeapon), weaponB(curWeapon), 22f);
    }

    // --- effective (upgraded) weapon stats: base array folded with upgrade tiers + abilities ---
    private float effBodyDmg(int w) {
        return W_BODYDMG[w] * UPG_DMG_MULT[upgTier[w][UPG_DMG]] * (1f + 0.06f * abLevel[AB_DMGBOOST]);
    }
    private float effHeadDmg(int w) {
        return W_HEADDMG[w] * UPG_DMG_MULT[upgTier[w][UPG_DMG]]
                * (1f + 0.06f * abLevel[AB_DMGBOOST]) * (1f + 0.15f * abLevel[AB_HEADMASTERY]);
    }
    private float effInterval(int w) { return W_INTERVAL[w] * UPG_RATE_MULT[upgTier[w][UPG_RATE]]; }
    private int   effMag(int w) { return Math.round(W_MAG[w] * (1f + 0.18f * upgTier[w][UPG_MAG])); }
    private float effReload(int w) {
        return W_RELOAD[w] * UPG_RELOAD_MULT[upgTier[w][UPG_RELOAD]] * (1f - 0.15f * abLevel[AB_FASTRELOAD]);
    }
    private float effMaxHp() { return MAX_HP + 20f * abLevel[AB_MAXHP]; }
    private int firstOwnedWeapon() {
        for (int w = 0; w < W_COUNT; w++) if (wOwned[w] != 0) return w;
        return 0;   // pistol is always owned
    }

    // --- meta progression: level curve + persistence ---
    /** XP to advance FROM level lvl to lvl+1 (hybrid linear-then-exponential). */
    private static int xpForLevel(int lvl) { return (int) Math.round(100.0 * Math.pow(lvl, 1.55)); }

    private void recomputeLevel() {
        int lvl = 1; long rem = xp;
        while (rem >= xpForLevel(lvl)) { rem -= xpForLevel(lvl); lvl++; if (lvl > 999) break; }
        playerLevel = lvl;
    }

    /** Fraction (0..1) of the way from the current level to the next. */
    private float levelProgress() {
        int lvl = 1; long rem = xp;
        while (rem >= xpForLevel(lvl)) { rem -= xpForLevel(lvl); lvl++; if (lvl > 999) return 1f; }
        return (float) rem / xpForLevel(lvl);
    }

    private void loadMeta() {
        cash = prefs.getLong("mp_cash", 0);
        xp = prefs.getLong("mp_xp", 0);
        for (int w = 0; w < W_COUNT; w++) {
            wOwned[w] = prefs.getInt("mp_owned_" + w, w == 0 ? 1 : 0);
            for (int c = 0; c < UPG_COUNT; c++) upgTier[w][c] = prefs.getInt("mp_upg_" + w + "_" + c, 0);
        }
        wOwned[0] = 1;   // pistol always owned
        for (int a = 0; a < AB_COUNT; a++) abLevel[a] = prefs.getInt("mp_ab_" + a, 0);
        optShake = prefs.getBoolean("opt_shake", true);
        optDmgNum = prefs.getBoolean("opt_dmgnum", true);
        optRadar = prefs.getBoolean("opt_radar", true);
        optKillfeed = prefs.getBoolean("opt_killfeed", true);
        recomputeLevel();
    }

    private void saveMeta() {
        SharedPreferences.Editor e = prefs.edit();
        e.putLong("mp_cash", cash);
        e.putLong("mp_xp", xp);
        for (int w = 0; w < W_COUNT; w++) {
            e.putInt("mp_owned_" + w, wOwned[w]);
            for (int c = 0; c < UPG_COUNT; c++) e.putInt("mp_upg_" + w + "_" + c, upgTier[w][c]);
        }
        for (int a = 0; a < AB_COUNT; a++) e.putInt("mp_ab_" + a, abLevel[a]);
        e.putBoolean("opt_shake", optShake);
        e.putBoolean("opt_dmgnum", optDmgNum);
        e.putBoolean("opt_radar", optRadar);
        e.putBoolean("opt_killfeed", optKillfeed);
        e.apply();
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
        for (int i = 0; i < numCover && i < boxes.length; i++) {
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

    // --- HUD: radar, enemy health bars, boss bar ---

    /** A corner radar: player-relative enemy blips, forward = up, with a sweeping line. */
    private void drawRadar() {
        float rad = 64f * us;
        float rcx = 24f * us + rad, rcy = 178f * us + rad;   // top-left, below the cash/level stack
        float fX = camFwd[0], fZ = camFwd[2];
        float fl = (float) Math.sqrt(fX * fX + fZ * fZ); if (fl < 1e-4f) fl = 1f; fX /= fl; fZ /= fl;
        float rX = -fZ, rZ = fX;                                 // right = forward rotated -90°
        drawCircle(rcx, rcy + 3f * us, rad + 5f * us, 0f, 0f, 0f, 0.32f);          // shadow
        drawCircle(rcx, rcy, rad + 2.5f * us, AC_CYAN[0], AC_CYAN[1], AC_CYAN[2], 0.45f); // border ring
        drawCircle(rcx, rcy, rad, 0.05f, 0.09f, 0.13f, 0.62f);                     // dish
        drawCircle(rcx, rcy, rad * 0.6f + 1.5f * us, 0.3f, 0.5f, 0.6f, 0.16f);     // mid range ring
        drawCircle(rcx, rcy, rad * 0.6f, 0.05f, 0.09f, 0.13f, 0.62f);
        drawRectPx(rcx, rcy, rad * 2f, 1.5f * us, 0.4f, 0.6f, 0.7f, 0.16f);        // crosshair
        drawRectPx(rcx, rcy, 1.5f * us, rad * 2f, 0.4f, 0.6f, 0.7f, 0.16f);
        float sa = timeAcc * 2.2f, ss = (float) Math.sin(sa), sc = (float) Math.cos(sa);
        for (int k = 1; k <= 6; k++) {                                            // sweeping line of dots
            float rr = rad * k / 6f;
            drawCircle(rcx + ss * rr, rcy - sc * rr, 2.2f * us, AC_CYAN[0], AC_CYAN[1], AC_CYAN[2], 0.22f * (1f - k / 7.5f));
        }
        drawRoundRect(rcx, rcy - rad + 6f * us, 4f * us, 10f * us, 2f * us, AC_CYAN[0], AC_CYAN[1], AC_CYAN[2], 0.85f); // forward notch
        float range = 40f;
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (!enAlive[i]) continue;
            float dx = enX[i] - px, dz = enZ[i] - pz;
            float fwd = dx * fX + dz * fZ, rgt = dx * rX + dz * rZ;
            float nx = rgt / range, ny = fwd / range;
            float bl = (float) Math.sqrt(nx * nx + ny * ny);
            boolean edge = bl > 1f; if (edge) { nx /= bl; ny /= bl; }
            float bx = rcx + nx * rad * 0.9f, by = rcy - ny * rad * 0.9f;
            boolean boss = enBoss[i] > 0;
            float bs = (boss ? 6.5f : 4.2f) * us, bp = boss ? (0.7f + 0.3f * pulse01(5f, 0f)) : 1f;
            drawCircle(bx, by, bs + 1.5f * us, 0f, 0f, 0f, 0.4f);
            drawCircle(bx, by, bs, 1f, boss ? 0.45f : 0.3f, boss ? 0.15f : 0.25f, (edge ? 0.5f : 0.95f) * bp);
        }
        drawCircle(rcx, rcy, 4.5f * us, 0.8f, 0.95f, 1f, 0.97f);                   // player
    }

    /** Small floating health bars over wounded enemies (and always over bosses). */
    private void drawEnemyHealthBars() {
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (!enAlive[i] || enMaxHP[i] <= 0f) continue;
            float frac = enHP[i] / enMaxHP[i];
            if (frac >= 0.999f && enBoss[i] == 0) continue;
            float topY = terrainH(enX[i], enZ[i]) + 1.95f * enScale[i];
            if (!projectToScreen(enX[i], topY, enZ[i], projScreen)) continue;
            float bx = projScreen[0], by = projScreen[1];
            if (bx < -50f || bx > width + 50f || by < 0f || by > height) continue;
            float bw = (enBoss[i] > 0 ? 104f : 60f) * us, bh = 8f * us;
            drawRoundRect(bx, by, bw + 3f * us, bh + 3f * us, bh, 0f, 0f, 0f, 0.5f);
            drawRoundRect(bx, by, bw, bh, bh * 0.5f, 0.2f, 0.2f, 0.24f, 0.85f);
            if (frac > 0f) drawRoundRect(bx - bw * 0.5f + bw * frac * 0.5f, by, bw * frac, bh, bh * 0.5f,
                    1f - frac, 0.2f + 0.7f * frac, 0.22f, 0.95f);
        }
    }

    /** A dramatic boss health bar across the top while a (mini)boss lives. */
    private void drawBossBar() {
        int bi = -1, tier = 0;
        for (int i = 0; i < MAX_ENEMIES; i++) if (enAlive[i] && enBoss[i] > tier) { bi = i; tier = enBoss[i]; }
        if (bi < 0) return;
        float frac = Math.max(0f, enHP[bi] / enMaxHP[bi]);
        float bw = Math.min(660f * us, width * 0.62f), bh = 26f * us, by = 232f * us;
        drawTextCenteredShadow(tier >= 2 ? "BOSS" : "MINI-BOSS", width * 0.5f, by - 24f * us, 18f, 1f, 0.5f, 0.4f, 0.95f);
        drawGlow(width * 0.5f, by, bw, bh, bh * 0.5f, 1f, 0.3f, 0.25f, 0.6f);
        drawRoundRect(width * 0.5f, by, bw + 4f * us, bh + 4f * us, bh * 0.5f + 2f * us, 0f, 0f, 0f, 0.55f);
        drawRoundRect(width * 0.5f, by, bw, bh, bh * 0.5f, 0.18f, 0.06f, 0.08f, 0.9f);
        if (frac > 0f) drawRoundRect(width * 0.5f - bw * 0.5f + bw * frac * 0.5f, by, bw * frac, bh, bh * 0.5f, 0.92f, 0.2f, 0.18f, 0.96f);
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
        // low-health damage vignette (red edges; pulses when critical)
        {
            float hpFrac = playerHP / effMaxHp();
            if (hpFrac < 0.45f) {
                float dv = (0.45f - hpFrac) / 0.45f;
                float crit = hpFrac < 0.22f ? (0.6f + 0.4f * pulse01(7f, 0f)) : 1f;
                drawEdgeVignette(0.85f, 0.12f, 0.12f, dv * 0.5f * crit);
            }
        }

        {
            if (muzzleTimer > 0f) drawQuadNDC(0f, 0f, 1f, 1f, 1f, 0.9f, 0.6f, 0.16f * (muzzleTimer / MUZZLE_TIME));

            drawEnemyHealthBars();   // world-anchored, under the controls
            drawBossBar();
            if (optRadar) drawRadar();
            if (optKillfeed) drawKillfeed();

            // aim-down-sights focus vignette (stronger through the sniper scope)
            if (aim > 0.02f) drawEdgeVignette(0f, 0f, 0f, aim * (curWeapon == 3 ? 0.5f : 0.28f));

            float ccx = width * 0.5f, ccy = height * 0.5f;

            // damage-direction indicator: a red arc pointing toward the last attacker
            if (hurtDirTimer > 0f) {
                float ha = Math.min(1f, hurtDirTimer / 0.85f) * 0.75f;
                float R = 150f * us;
                for (int k = -2; k <= 2; k++) {
                    float ang = hurtDirAngle + k * 0.14f, fall = 1f - Math.abs(k) / 3f;
                    float ix = ccx + (float) Math.sin(ang) * R, iy = ccy - (float) Math.cos(ang) * R;
                    drawCircle(ix, iy, (22f * fall + 8f) * us, 0.95f, 0.2f, 0.15f, ha * 0.5f * fall);
                }
            }

            // sprint speed lines: radial streaks from the centre outward, brighter toward the edge
            if (sprintAnim > 0.12f) {
                float sl = (sprintAnim - 0.12f) / 0.88f;
                float maxR = (float) Math.sqrt(width * width + height * height) * 0.5f;
                for (int s2 = 0; s2 < 20; s2++) {
                    float ang = s2 * 6.2832f / 20f;
                    float dxs = (float) Math.cos(ang), dys = (float) Math.sin(ang);
                    for (int k = 1; k <= 6; k++) {
                        float t2 = k / 6f, rr = (0.48f + 0.5f * t2) * maxR;
                        drawCircle(ccx + dxs * rr, ccy + dys * rr, (2.5f + 3.5f * t2) * us, 0.85f, 0.92f, 1f, sl * 0.3f * t2);
                    }
                }
            }

            // move stick: a defined ring base + a glossy knob that tracks the finger
            float mcx = Hud.moveCx(), mcy = Hud.moveCy(height);
            drawCircle(mcx, mcy, Hud.MOVE_RADIUS + 2.5f * us, 1f, 1f, 1f, 0.20f);            // ring
            drawCircle(mcx, mcy, Hud.MOVE_RADIUS, 0.10f, 0.12f, 0.16f, 0.34f);              // dark well
            float kx = input.moveX(), ky = input.moveY();
            float klen = (float) Math.sqrt(kx * kx + ky * ky);
            if (klen > 1f) { kx /= klen; ky /= klen; }
            drawPad(mcx + kx * Hud.MOVE_RADIUS, mcy - ky * Hud.MOVE_RADIUS, 66f, 0.85f, 0.88f, 0.95f, 0.7f);

            // fire button
            drawPad(Hud.fireCx(width), Hud.fireCy(height), Hud.FIRE_RADIUS, 0.95f, 0.26f, 0.22f, 0.62f);
            // reload progress ring around the fire button
            if (reloadTimer > 0f) {
                float fpx = Hud.fireCx(width), fpy = Hud.fireCy(height), prog = 1f - reloadTimer / reloadTotal;
                int N = 20;
                for (int d = 0; d < N; d++) {
                    float ang = d * 6.2832f / N - 1.5708f, rr = Hud.FIRE_RADIUS + 16f * us;
                    boolean on = d < prog * N;
                    drawCircle(fpx + (float) Math.cos(ang) * rr, fpy + (float) Math.sin(ang) * rr, 4f * us,
                            on ? 1f : 0.3f, on ? 0.85f : 0.32f, on ? 0.3f : 0.35f, on ? 0.95f : 0.5f);
                }
                drawTextCentered("RELOADING", fpx, fpy + Hud.FIRE_RADIUS + 30f * us, 15f, 1f, 0.8f, 0.3f, 0.85f);
            }

            // weapon switch button (shows current weapon 1/2/3, tinted per weapon)
            float swx = Hud.switchCx(width), swy = Hud.switchCy(height);
            drawPad(swx, swy, Hud.SWITCH_RADIUS, weaponR(curWeapon) * 0.85f, weaponG(curWeapon) * 0.85f, weaponB(curWeapon) * 0.85f, 0.55f);
            drawTextCenteredShadow("" + (curWeapon + 1), swx, swy, 30f, 1f, 1f, 1f, 0.97f);

            // aim (iron sights) toggle — brighter while aiming
            float aimx = Hud.aimCx(width), aimy = Hud.aimCy(height);
            drawPad(aimx, aimy, Hud.AIM_RADIUS, 0.30f + 0.30f * aim, 0.62f, 0.40f + 0.30f * aim, 0.42f + 0.40f * aim);
            drawCircle(aimx, aimy, 28f * us, 1f, 1f, 1f, 0.10f + 0.55f * aim);              // sight ring
            drawRectPx(aimx, aimy, 8f * us, 8f * us, 1f, 1f, 1f, 0.4f + 0.55f * aim);       // sight dot

            // jump button (up chevron), brighter mid-air
            float jpx = Hud.jumpCx(width), jpy = Hud.jumpCy(height);
            float ja = grounded ? 0.42f : 0.7f;
            drawPad(jpx, jpy, Hud.JUMP_RADIUS, 0.30f, 0.52f, 0.78f, ja);
            drawRectPx(jpx, jpy - 11f * us, 11f * us, 6f * us, 1f, 1f, 1f, 0.9f);
            drawRectPx(jpx, jpy - 2f * us, 21f * us, 6f * us, 1f, 1f, 1f, 0.9f);
            drawRectPx(jpx, jpy + 7f * us, 31f * us, 6f * us, 1f, 1f, 1f, 0.9f);

            // door prompt — appears only when you're in range of a door; tap it to open/close
            if (nearDoor >= 0) {
                float ix = Hud.interactCx(width), iy = Hud.interactCy(height);
                boolean open = doorOpen[nearDoor] > 0.5f;
                drawGlow(ix, iy, Hud.INTERACT_RADIUS * 2f, Hud.INTERACT_RADIUS * 2f, Hud.INTERACT_RADIUS,
                        open ? 0.9f : 0.3f, open ? 0.55f : 0.85f, open ? 0.3f : 0.45f, 0.8f);
                drawPad(ix, iy, Hud.INTERACT_RADIUS, open ? 0.85f : 0.28f, open ? 0.5f : 0.82f, open ? 0.28f : 0.45f, 0.55f);
                drawRoundRect(ix, iy, 40f * us, 60f * us, 6f * us, 0.93f, 0.93f, 0.80f, 0.96f);   // door frame
                drawRoundRect(ix, iy, 28f * us, 48f * us, 4f * us, 0.46f, 0.30f, 0.17f, 0.97f);   // door leaf
                drawCircle(ix + 8f * us, iy, 4f * us, 0.96f, 0.9f, 0.45f, 1f);                    // knob
                drawTextCenteredShadow(open ? "CLOSE" : "OPEN", ix, iy + Hud.INTERACT_RADIUS + 24f * us, 16f, 1f, 1f, 1f, 0.92f);
            }

            // crosshair (colors when the last shot landed: gold head, green body)
            float cr = 1f, cg = 1f, cb = 1f;
            if (flashTimer > 0f && lastShotHit) {
                if (lastShotHead) { cr = 1f; cg = 0.85f; cb = 0.20f; }
                else { cr = 0.30f; cg = 1f; cb = 0.40f; }
            }
            float xa = (1f - aim) * (1f - 0.6f * sprintAnim);          // fade when aiming / sprinting
            if (xa > 0.03f) {
                float gap = 9f + recoil * 145f, len = 13f, th = 3f;       // springs open with recoil, settles back
                // dark outline so the reticle stays visible over any background
                drawRectPx(ccx, ccy, 8f, 8f, 0f, 0f, 0f, 0.55f * xa);
                drawRectPx(ccx, ccy - gap - len * 0.5f, th + 2f, len + 2f, 0f, 0f, 0f, 0.45f * xa);
                drawRectPx(ccx, ccy + gap + len * 0.5f, th + 2f, len + 2f, 0f, 0f, 0f, 0.45f * xa);
                drawRectPx(ccx - gap - len * 0.5f, ccy, len + 2f, th + 2f, 0f, 0f, 0f, 0.45f * xa);
                drawRectPx(ccx + gap + len * 0.5f, ccy, len + 2f, th + 2f, 0f, 0f, 0f, 0.45f * xa);
                // bright centre dot = the exact aim point
                drawRectPx(ccx, ccy, 5f, 5f, cr, cg, cb, 0.98f * xa);
                // four gapped ticks around it
                drawRectPx(ccx, ccy - gap - len * 0.5f, th, len, cr, cg, cb, 0.92f * xa);
                drawRectPx(ccx, ccy + gap + len * 0.5f, th, len, cr, cg, cb, 0.92f * xa);
                drawRectPx(ccx - gap - len * 0.5f, ccy, len, th, cr, cg, cb, 0.92f * xa);
                drawRectPx(ccx + gap + len * 0.5f, ccy, len, th, cr, cg, cb, 0.92f * xa);
            }

            // (No centre crosshair while aiming — you aim through the iron sights / scope.)

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

            drawTextCenteredShadow("" + score, width * 0.5f, 32f * us, 34f, 0.9f, 1f, 1f, 0.97f);

            // health bar (top-left): medkit icon + rounded track/fill + frame + sheen
            float hp = playerHP / effMaxHp(); if (hp < 0f) hp = 0f;
            float by = 58f * us, bh = 22f * us, brad = bh * 0.5f;
            // medkit icon (red disc + white cross)
            float hicx = 52f, hir = 17f * us;
            drawCircle(hicx, by + 2f * us, hir + 1.5f * us, 0f, 0f, 0f, 0.4f);
            drawCircle(hicx, by, hir, 0.86f, 0.24f, 0.26f, 0.95f);
            drawCircle(hicx - hir * 0.32f, by - hir * 0.34f, hir * 0.4f, 1f, 0.6f, 0.6f, 0.5f);   // glint
            drawRoundRect(hicx, by, 15f * us, 5f * us, 1f * us, 1f, 1f, 1f, 0.97f);
            drawRoundRect(hicx, by, 5f * us, 15f * us, 1f * us, 1f, 1f, 1f, 0.97f);
            float bx0 = 76f, bw = 232f;
            if (hp < 0.3f && hp > 0f) {                                                          // low-health warning pulse
                float hb = pulse01(6f, 0f);
                drawRoundRect(bx0 + bw * 0.5f, by, bw + (12f + 9f * hb) * us, bh + (12f + 9f * hb) * us,
                              brad + (6f + 4f * hb) * us, 1f, 0.2f, 0.2f, 0.10f + 0.18f * hb);
            }
            drawRoundRect(bx0 + bw * 0.5f, by, bw + 6f * us, bh + 6f * us, brad + 3f * us, 0f, 0f, 0f, 0.45f); // frame/shadow
            drawRoundRect(bx0 + bw * 0.5f, by, bw, bh, brad, 0.16f, 0.16f, 0.20f, 0.75f);                     // track
            if (hp > 0.001f) {
                drawRoundRect(bx0 + bw * hp * 0.5f, by, bw * hp, bh, brad, 1f - hp, 0.2f + 0.7f * hp, 0.22f, 0.95f);
                drawRoundRect(bx0 + bw * hp * 0.5f, by - bh * 0.22f, bw * hp - 6f * us, bh * 0.34f, brad * 0.6f,
                              1f - 0.4f * hp, 0.5f + 0.5f * hp, 0.45f, 0.35f);                                // sheen
            }

            // cash + level + xp progress (top-left, under the health bar)
            drawTextShadow("$" + cash, 40f, 106f * us, 26f, AC_GOLD[0], AC_GOLD[1], AC_GOLD[2], 1f);
            drawTextShadow("LV " + playerLevel, 40f, 150f * us, 20f, AC_CYAN[0], AC_CYAN[1], AC_CYAN[2], 1f);
            float lp = levelProgress();
            float xbX = 40f + measureText("LV 00", 20f) + 16f, xbW = 150f;
            drawRoundRect(xbX + xbW * 0.5f, 150f * us, xbW, 10f * us, 5f * us, 0.18f, 0.18f, 0.22f, 0.7f);
            if (lp > 0.001f) drawRoundRect(xbX + xbW * lp * 0.5f, 150f * us, xbW * lp, 10f * us, 5f * us, 0.4f, 0.75f, 1f, 0.95f);

            if (combo > 1) {
                float ct = comboTimer / COMBO_WINDOW, cy2 = 210f * us;
                float mg = combo >= 5 ? 0.35f : (combo >= 3 ? 0.7f : 1f), mb = combo >= 3 ? 0.2f : 0.5f;
                String cs = "x" + combo;
                float cw = measureText(cs, 30f) + 64f * us, ch = 46f * us;
                if (combo >= 3) drawGlow(width * 0.5f, cy2, cw, ch, ch * 0.5f, 1f, mg, mb, 0.7f);
                drawRoundRect(width * 0.5f, cy2, cw, ch, ch * 0.5f, 0.06f, 0.07f, 0.12f, 0.78f);
                drawRoundRect(width * 0.5f - (cw - 18f * us) * 0.5f + (cw - 18f * us) * ct * 0.5f, cy2 + ch * 0.5f - 5f * us,
                        (cw - 18f * us) * ct, 4f * us, 2f * us, 1f, mg, mb, 0.92f);
                drawTextCenteredShadow(cs, width * 0.5f, cy2 - 2f * us, 30f, 1f, mg, mb, 0.97f);
            }

            // ammo: magazine (big) + reserve (small), or a reload bar
            float ax = Hud.fireCx(width), ay = Hud.fireCy(height) - Hud.FIRE_RADIUS - 58f * us;
            if (reloadTimer > 0f) {
                float rp = 1f - reloadTimer / reloadTotal;
                drawRoundRect(ax, ay, 150f * us, 15f * us, 7.5f * us, 0.22f, 0.22f, 0.26f, 0.7f);
                if (rp > 0.001f) drawRoundRect(ax - 75f * us + 75f * us * rp, ay, 150f * us * rp, 15f * us, 7.5f * us, 1f, 0.8f, 0.2f, 0.92f);
            } else {
                int mag = wMag[curWeapon];
                boolean low = mag <= Math.max(2, effMag(curWeapon) / 6);
                String magS = "" + mag, resS = "" + wReserve[curWeapon];
                float lpu = low ? pulse01(8f, 0f) : 0f;                       // warning pulse when low
                drawTextRight(magS, ax + 2f * us, ay, 42f + lpu * 4f, low ? 1f : 0.95f,
                        low ? 0.3f + 0.25f * lpu : 0.97f, low ? 0.3f : 1f, 0.97f);
                drawText("/ " + resS, ax + 12f * us, ay + 5f * us, 22f, 0.72f, 0.77f, 0.84f, 0.88f);
                if (low) drawTextCentered("LOW AMMO", ax, ay - 26f * us, 15f, 1f, 0.45f, 0.25f, 0.45f + 0.45f * lpu);
            }

            // wave indicator (top centre, under the score): a rounded pill
            String waveStr = "WAVE " + wave;
            float wpw = measureText(waveStr, 20f) + 40f * us;
            drawRoundRect(width * 0.5f, 134f * us, wpw, 38f * us, 19f * us, 0.06f, 0.08f, 0.14f, 0.72f);
            drawTextCentered(waveStr, width * 0.5f, 134f * us, 20f, 0.9f, 0.95f, 1f, 0.95f);
            // wave progress bar (enemies cleared) + remaining count
            float cleared = waveSize - waveRemaining; if (cleared < 0f) cleared = 0f;
            float wf = waveSize > 0 ? cleared / (float) waveSize : 0f;
            float pbw = Math.max(wpw, 190f * us), pby = 162f * us;
            drawRoundRect(width * 0.5f, pby, pbw, 7f * us, 3.5f * us, 0.12f, 0.14f, 0.18f, 0.75f);
            if (wf > 0.001f) drawRoundRect(width * 0.5f - pbw * 0.5f + pbw * wf * 0.5f, pby, pbw * wf, 7f * us, 3.5f * us, AC_CYAN[0], AC_CYAN[1], AC_CYAN[2], 0.93f);
            drawTextCentered(Math.max(0, waveRemaining) + " LEFT", width * 0.5f, pby + 18f * us, 13f, 0.72f, 0.82f, 0.92f, 0.82f);
            if (waveBanner > 0f) {
                float ba = Math.min(1f, waveBanner);                       // fade out at the end
                float elapsed = waveBannerMax - waveBanner;
                float pin = clamp(elapsed / 0.30f, 0f, 1f);                // 0→1 entrance
                float ease = 1f - (1f - pin) * (1f - pin);
                float scale = 0.72f + 0.28f * ease;
                boolean boss = bossPending > 0 || waveBannerText.indexOf("BOSS") >= 0;
                float gr = boss ? 1f : AC_CYAN[0], gg = boss ? 0.45f : AC_CYAN[1], gb = boss ? 0.32f : AC_CYAN[2];
                float bnY = height * 0.27f + (1f - ease) * -26f * us;      // slides down into place
                float sz = 40f * scale, tw = measureText(waveBannerText, sz) + 80f * us, bh2 = 64f * us * scale;
                drawGlow(width * 0.5f, bnY, tw, bh2, bh2 * 0.5f, gr, gg, gb, ba * 0.9f);
                drawRoundRect(width * 0.5f, bnY, tw + 5f * us, bh2 + 5f * us, bh2 * 0.5f + 2.5f * us, gr, gg, gb, ba * 0.5f); // rim
                drawRoundRect(width * 0.5f, bnY, tw, bh2, bh2 * 0.5f, 0.05f, 0.07f, 0.13f, ba * 0.9f);
                drawRoundRect(width * 0.5f, bnY - bh2 * 0.5f + 16f * us * scale, tw * 0.96f, 3f * us, 1.5f * us, gr, gg, gb, ba * 0.7f);
                drawTextCenteredShadow(waveBannerText, width * 0.5f, bnY, sz, 1f, boss ? 0.55f : 0.95f, boss ? 0.4f : 0.6f, ba);
            }
            // between-wave countdown to the next wave
            if (waveBreak > 0f) {
                int cd = Math.max(1, (int) Math.ceil(waveBreak));
                drawTextCenteredShadow("NEXT WAVE IN " + cd, width * 0.5f, height * 0.40f, 22f, 0.85f, 0.92f, 1f, 0.92f);
            }

            drawPopups();   // floating "+$" / HEADSHOT / combo reward texts
        }

        // build number: a small dim chip in the top-right corner (was a big gold readout)
        String bstr = "B" + buildNumber;
        float bcw = measureText(bstr, 15f) + 26f * us, bchx = width - 18f * us - bcw * 0.5f;
        drawRoundRect(bchx, 32f * us, bcw, 32f * us, 16f * us, 0.06f, 0.08f, 0.13f, 0.45f);
        drawTextCentered(bstr, bchx, 32f * us, 15f, 0.66f, 0.72f, 0.8f, 0.8f);

        // death sequence overlay: darkening red wash + "ELIMINATED" punching in
        if (deathAnim > 0f) {
            float t = 1f - deathAnim / 1.5f;                       // 0 → 1
            drawQuadNDC(0f, 0f, 1f, 1f, 0.45f, 0.02f, 0.02f, 0.35f + 0.45f * t);
            drawEdgeVignette(0.7f, 0.05f, 0.05f, 0.4f + 0.3f * t);
            float sc = 0.55f + 0.5f * Math.min(1f, t * 2.2f);
            drawTextCenteredShadow("ELIMINATED", width * 0.5f, height * 0.43f, 56f * sc, 1f, 0.28f, 0.24f, Math.min(1f, t * 3f));
        }

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // --- menus (summary + hub) ---

    private void menuPreamble() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(prog2);
    }

    private boolean hitRect(float cx, float cy, float w, float h, float tx, float ty) {
        return tx > cx - w * 0.5f && tx < cx + w * 0.5f && ty > cy - h * 0.5f && ty < cy + h * 0.5f;
    }

    /** Run-over recap: shows what you earned, then CONTINUE → hub (where you spend it). */
    private void drawSummary() {
        menuPreamble();
        drawQuadNDC(0f, 0f, 1f, 1f, 0.04f, 0.02f, 0.03f, 0.80f);
        float cx = width * 0.5f;
        float btnH = 110f * us, btnW = Math.min(470f * us, width * 0.8f), btnY = height - 30f * us - btnH * 0.5f;
        if (input.consumeMenuTap(tap) && hitRect(cx, btnY, btnW, btnH, tap[0], tap[1])) {
            state = ST_HUB; sfx.swap();
        }
        drawTextCenteredShadow("RUN OVER", cx, height * 0.14f, 56f, 1f, 0.45f, 0.3f, 1f);
        drawTextCenteredShadow("SCORE " + score, cx, height * 0.14f + 86f * us, 30f, 1f, 1f, 1f, 0.95f);
        if (newBest) {
            float nb = pulse01(4f, 0f);
            String nbs = "NEW BEST!";
            float nbw = measureText(nbs, 24f) + 50f * us, nby = height * 0.14f + 130f * us;
            drawGlow(cx, nby, nbw, 40f * us, 20f * us, AC_GOLD[0], AC_GOLD[1], AC_GOLD[2], 0.6f + 0.4f * nb);
            drawRoundRect(cx, nby, nbw, 40f * us, 20f * us, 0.18f, 0.14f, 0.04f, 0.9f);
            drawTextCenteredShadow(nbs, cx, nby, 24f, 1f, 0.86f, 0.3f, 1f);
        } else {
            drawTextCenteredShadow("BEST " + highScore, cx, height * 0.14f + 130f * us, 22f, AC_GOLD[0], AC_GOLD[1], AC_GOLD[2], 0.9f);
        }

        // earnings card
        float y = height * 0.44f;
        drawCard(cx, y + 58f * us, Math.min(640f * us, width * 0.9f), 210f * us, 24f * us, 0.09f, 0.11f, 0.16f, 0.95f);
        drawTextCenteredShadow("EARNED $" + runCash, cx, y, 34f, AC_GOLD[0], AC_GOLD[1], AC_GOLD[2], 1f);
        drawTextCenteredShadow("+" + runXp + " XP", cx, y + 52f * us, 24f, AC_CYAN[0], AC_CYAN[1], AC_CYAN[2], 0.95f);
        drawTextCenteredShadow(runKills + " KILLS   WAVE " + wave, cx, y + 96f * us, 24f, 0.85f, 0.85f, 0.92f, 0.95f);
        if (levelsGainedThisRun > 0)
            drawTextCenteredShadow("LEVEL UP - NOW LV " + playerLevel, cx, y + 144f * us, 26f, AC_GREEN[0], AC_GREEN[1], AC_GREEN[2], 1f);

        float cpb = pulse01(2.8f, 0f);
        drawRoundRect(cx, btnY, btnW + (18f + 14f * cpb) * us, btnH + (18f + 14f * cpb) * us, btnH * 0.5f + (9f + 7f * cpb) * us, 0.24f, 0.85f, 0.40f, 0.07f + 0.08f * cpb);
        drawButton(cx, btnY, btnW, btnH, btnH * 0.5f, 0.20f, 0.74f, 0.34f, 1f, true);
        drawSheen(cx, btnY, btnW, btnH, btnH * 0.5f, 3.0f, 0f, 0.20f);
        drawTextCenteredShadow("CONTINUE", cx, btnY, 34f, 1f, 1f, 1f, 1f);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /** A small settings gear icon (toothed disc). */
    private void drawGear(float cx, float cy, float r, float a) {
        for (int t = 0; t < 8; t++) {
            float ang = t * 0.7854f;
            drawRectPx(cx + (float) Math.cos(ang) * r, cy + (float) Math.sin(ang) * r, r * 0.55f, r * 0.55f, 0.82f, 0.85f, 0.92f, a);
        }
        drawCircle(cx, cy, r, 0.82f, 0.85f, 0.92f, a);
        drawCircle(cx, cy, r * 0.42f, 0.08f, 0.10f, 0.15f, a);
    }

    private void toggleOpt(int i) {
        if (i == 0) optShake = !optShake;
        else if (i == 1) optDmgNum = !optDmgNum;
        else if (i == 2) optRadar = !optRadar;
        else optKillfeed = !optKillfeed;
        sfx.swap();
    }

    /** Modal settings overlay: toggle the optional HUD/FX features on or off (persisted). */
    private void drawSettingsOverlay(boolean tapped) {
        drawQuadNDC(0f, 0f, 1f, 1f, 0.02f, 0.03f, 0.05f, 0.72f);
        float cx = width * 0.5f, cw = Math.min(580f * us, width * 0.72f), ch = 460f * us, cy = height * 0.46f;
        drawCard(cx, cy, cw, ch, 26f * us, 0.09f, 0.11f, 0.16f, 0.99f);
        drawTextCenteredShadow("SETTINGS", cx, cy - ch * 0.5f + 46f * us, 30f, 0.9f, 0.95f, 1f, 1f);
        drawRoundRect(cx, cy - ch * 0.5f + 74f * us, 120f * us, 4f * us, 2f * us, AC_CYAN[0], AC_CYAN[1], AC_CYAN[2], 0.85f);
        String[] labels = {"SCREEN SHAKE", "DAMAGE NUMBERS", "RADAR", "KILLFEED"};
        boolean[] vals = {optShake, optDmgNum, optRadar, optKillfeed};
        float rowY0 = cy - ch * 0.5f + 132f * us, rh = 66f * us;
        for (int i = 0; i < 4; i++) {
            float ry = rowY0 + i * rh;
            drawTextShadow(labels[i], cx - cw * 0.5f + 42f * us, ry, 20f, 0.86f, 0.9f, 0.96f, 1f);
            float tw = 104f * us, th = 44f * us, tx = cx + cw * 0.5f - 42f * us - tw * 0.5f;
            boolean on = vals[i];
            if (tapped && hitRect(tx, ry, tw + 24f * us, th + 16f * us, tap[0], tap[1])) { toggleOpt(i); on = !on; }
            drawRoundRect(tx, ry, tw, th, th * 0.5f, on ? 0.16f : 0.15f, on ? 0.52f : 0.16f, on ? 0.3f : 0.18f, 0.96f);
            drawCircle(on ? tx + tw * 0.5f - th * 0.5f : tx - tw * 0.5f + th * 0.5f, ry, th * 0.4f, 1f, 1f, 1f, 0.96f);
            drawTextCentered(on ? "ON" : "OFF", on ? tx - tw * 0.22f : tx + tw * 0.2f, ry, 13f, 1f, 1f, 1f, 0.85f);
        }
        float by = cy + ch * 0.5f - 50f * us, bw = 220f * us, bh = 58f * us;
        if (tapped && hitRect(cx, by, bw, bh, tap[0], tap[1])) { showSettings = false; saveMeta(); sfx.swap(); }
        drawButton(cx, by, bw, bh, bh * 0.5f, 0.2f, 0.55f, 0.85f, 1f, false);
        drawTextCenteredShadow("CLOSE", cx, by, 22f, 1f, 1f, 1f, 1f);
    }

    /** The hub: balance, three tabs, the PLAY button, and the active tab's content. */
    private void drawHub() {
        menuPreamble();
        fillVGradient(0.058f, 0.078f, 0.170f, 0.012f, 0.016f, 0.038f);        // deep indigo → near-black
        drawMenuMotes();                                                       // slow drifting light motes
        drawVignette();                                                        // edge depth (under the UI)
        if (!hubWasShown) { hubWasShown = true; hubEnterAnim = 0f; }           // entrance fade on (re)enter
        hubEnterAnim = Math.min(1f, hubEnterAnim + 0.05f);
        float cx = width * 0.5f;
        float hbH = 120f * us;                                                // header band height
        // header band with a faint top sheen + a glowing accent edge
        drawRectPx(cx, hbH * 0.5f, width, hbH, 0.065f, 0.085f, 0.145f, 0.98f);
        drawRectPx(cx, 2f * us, width, 4f * us, 0.16f, 0.20f, 0.30f, 0.5f);              // top sheen
        for (int i = 0; i < 4; i++)                                                       // soft glow under accent
            drawRectPx(cx, hbH + (4f + i * 6f) * us, width, 6f * us, AC_CYAN[0], AC_CYAN[1], AC_CYAN[2], 0.06f - i * 0.012f);
        drawRectPx(cx, hbH, width, 3f * us, AC_CYAN[0], AC_CYAN[1], AC_CYAN[2], 0.9f);    // accent line
        boolean tapped = input.consumeMenuTap(tap);
        boolean settingsTap = tapped && showSettings;   // route taps to the settings overlay while it's open
        if (showSettings) tapped = false;                // …and freeze the hub behind it
        // settings gear button (top-left, left of the tabs)
        float gx = 60f * us, gy = hbH + 60f * us;
        if (!showSettings && tapped && hitRect(gx, gy, 64f * us, 64f * us, tap[0], tap[1])) { showSettings = true; tapped = false; }
        drawGear(gx, gy, 22f * us, showSettings ? 1f : 0.72f);

        // header: cash chip (coin + gold) left — value counts up/down toward the real balance
        if (cashShown < 0f) cashShown = cash;
        cashShown += (cash - cashShown) * 0.14f;
        if (Math.abs(cash - cashShown) < 0.8f) cashShown = cash;
        String cashStr = "" + Math.round(cashShown);
        float coinR = 17f * us, cashTxtW = measureText(cashStr, 34f);
        float cashChipW = coinR * 2f + 18f * us + cashTxtW + 34f * us;
        float cashChipX = 20f * us + cashChipW * 0.5f;
        drawChip(cashChipX, 60f * us, cashChipW, 56f * us, 0.13f, 0.115f, 0.06f, 0.92f);
        drawCoin(cashChipX - cashChipW * 0.5f + 24f * us, 60f * us, coinR);
        drawText(cashStr, cashChipX - cashChipW * 0.5f + 24f * us + coinR + 14f * us, 60f * us, 34f, AC_GOLD[0], AC_GOLD[1], AC_GOLD[2], 1f);

        // title (centred) with a short accent underline + an occasional shimmer sweep
        drawTextCenteredShadow("AIGAMES FPS", cx, 50f * us, 27f, 0.92f, 0.96f, 1f, 0.97f);
        drawSheen(cx, 50f * us, measureText("AIGAMES FPS", 27f) + 24f * us, 40f * us, 14f * us, 5.0f, 0f, 0.16f);
        drawRoundRect(cx, 84f * us, 120f * us, 4f * us, 2f * us, AC_CYAN[0], AC_CYAN[1], AC_CYAN[2], 0.85f);

        // level chip (cyan) + xp bar right
        String lvStr = "LV " + playerLevel;
        float lvTxtW = measureText(lvStr, 26f), lvChipW = lvTxtW + 40f * us;
        float lvChipX = width - 24f * us - lvChipW * 0.5f;
        drawChip(lvChipX, 50f * us, lvChipW, 50f * us, 0.07f, 0.13f, 0.18f, 0.92f);
        drawTextCentered(lvStr, lvChipX, 50f * us, 26f, AC_CYAN[0], AC_CYAN[1], AC_CYAN[2], 1f);
        float lp = levelProgress(), xbw = Math.min(300f * us, width * 0.36f), xbx = width - 24f * us - xbw;
        if (lpShown < 0f) lpShown = lp;
        lpShown += (lp - lpShown) * 0.1f;                                  // smooth fill
        if (Math.abs(lp - lpShown) < 0.002f) lpShown = lp;
        drawRoundRect(xbx + xbw * 0.5f, 92f * us, xbw, 12f * us, 6f * us, 0.14f, 0.16f, 0.20f, 0.9f);
        if (lpShown > 0.001f) {
            drawRoundRect(xbx + xbw * lpShown * 0.5f, 92f * us, xbw * lpShown, 12f * us, 6f * us, AC_CYAN[0], AC_CYAN[1], AC_CYAN[2], 0.97f);
            drawRoundRect(xbx + xbw * lpShown * 0.5f, 89f * us, xbw * lpShown - 4f * us, 4f * us, 2f * us, 0.7f, 0.95f, 1f, 0.6f);
        }
        drawSheen(xbx + xbw * 0.5f, 92f * us, xbw, 14f * us, 7f * us, 4.5f, 0f, 0.18f);   // periodic shine

        // tabs (rounded pills; selected one glows)
        String[] tabs = {"WEAPONS", "UPGRADES", "ABILITIES"};
        float tabH = 80f * us, gap = 16f * us;
        float tabW = Math.min(322f * us, (width - 70f) / 3f - gap);
        float tabY = hbH + 22f * us + tabH * 0.5f;
        float totalW = tabW * 3 + gap * 2, x0 = cx - totalW * 0.5f + tabW * 0.5f;
        float trad = tabH * 0.5f;
        // base (unselected) pills + tap handling
        for (int i = 0; i < 3; i++) {
            float txp = x0 + i * (tabW + gap);
            if (tapped && hitRect(txp, tabY, tabW, tabH, tap[0], tap[1])) { hubTab = i; sfx.swap(); tapped = false; }
            drawRoundRect(txp, tabY, tabW, tabH, trad, 0.10f, 0.12f, 0.165f, 0.92f);
            drawRoundRect(txp, tabY - tabH * 0.5f + 2.4f * us, tabW - 2f * trad, 2.4f * us, 1.2f * us, 0.20f, 0.23f, 0.30f, 0.5f);
        }
        // sliding selection highlight: lerp toward the active tab's centre
        float tabTargetX = x0 + hubTab * (tabW + gap);
        if (tabIndX < 0f) tabIndX = tabTargetX;
        tabIndX += (tabTargetX - tabIndX) * 0.28f;
        float tb = pulse01(2.4f, 0f), tix = tabIndX;
        drawRoundRect(tix, tabY, tabW + (16f + 8f * tb) * us, tabH + (16f + 8f * tb) * us, trad + (8f + 4f * tb) * us, AC_CYAN[0], AC_CYAN[1], AC_CYAN[2], 0.07f + 0.06f * tb);
        drawRoundRect(tix, tabY, tabW + 8f * us, tabH + 8f * us, trad + 4f * us, AC_CYAN[0], AC_CYAN[1], AC_CYAN[2], 0.18f + 0.10f * tb);
        drawRoundRect(tix, tabY, tabW, tabH, trad, 0.13f, 0.30f, 0.48f, 0.99f);
        drawRectPx(tix, tabY - tabH * 0.24f, tabW - 2f * trad, tabH * 0.40f, 0.17f, 0.38f, 0.57f, 0.75f);
        drawRoundRect(tix, tabY - tabH * 0.5f + 3f * us, tabW - 2f * trad, 3f * us, 1.5f * us, 0.7f, 0.92f, 1f, 0.55f);
        drawRoundRect(tix, tabY + tabH * 0.5f - 8f * us, tabW * 0.40f, 5f * us, 2.5f * us, 0.75f, 0.94f, 1f, 0.97f);
        // labels on top, brightening as the highlight passes under them
        for (int i = 0; i < 3; i++) {
            float txp = x0 + i * (tabW + gap);
            float prox = clamp(1f - Math.abs(txp - tabIndX) / (tabW + gap), 0f, 1f);
            drawTextCenteredShadow(tabs[i], txp, tabY, 21f, 1f, 1f, 1f, 0.5f + 0.5f * prox);
        }

        // PLAY button (big, glossy, with a breathing glow + a sweeping shine)
        float playH = 122f * us, playW = Math.min(520f * us, width * 0.88f), playRad = playH * 0.5f;
        float playY = height - 26f * us - playH * 0.5f;
        if (tapped && hitRect(cx, playY, playW, playH, tap[0], tap[1])) { startRun(); return; }
        float pb = pulse01(2.8f, 0f);
        drawRoundRect(cx, playY, playW + (20f + 16f * pb) * us, playH + (20f + 16f * pb) * us, playRad + (10f + 8f * pb) * us, 0.24f, 0.85f, 0.40f, 0.07f + 0.09f * pb);
        drawButton(cx, playY, playW, playH, playRad, 0.22f, 0.80f, 0.38f, 1f, true);
        drawSheen(cx, playY, playW, playH, playRad, 3.0f, 0f, 0.22f);
        drawTextCenteredShadow("PLAY", cx, playY, 48f, 1f, 1f, 1f, 1f);
        drawText("BUILD " + buildNumber, 18f * us, height - 16f * us, 13f, 0.45f, 0.5f, 0.58f, 0.65f);
        if (highScore > 0) drawTextRight("BEST SCORE  " + highScore, width - 18f * us, height - 16f * us, 14f, AC_GOLD[0], AC_GOLD[1], AC_GOLD[2], 0.7f);

        // tab-switch reveal: reset on change, then ease 0→1 — rows stagger in (see rowReveal())
        if (hubTab != hubTabShown) { hubTabShown = hubTab; hubTabAnim = 0f; }
        hubTabAnim = Math.min(1f, hubTabAnim + 0.06f);

        float top = tabY + tabH * 0.5f + 18f * us, bottom = playY - playH * 0.5f - 18f * us;
        if (hubTab == 0) hubWeapons(top, bottom, tapped);
        else if (hubTab == 1) hubUpgrades(top, bottom, tapped);
        else hubAbilities(top, bottom, tapped);
        drawPopups();   // purchase "-$" feedback
        if (hubEnterAnim < 1f) {                                              // whole-hub entrance fade-in
            float e = 1f - (1f - hubEnterAnim) * (1f - hubEnterAnim);
            drawRectPx(cx, height * 0.5f, width, height, 0.012f, 0.016f, 0.038f, (1f - e) * 0.97f);
        }
        if (showSettings) drawSettingsOverlay(settingsTap);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /**
     * One purchasable list row. Returns true exactly when an affordable BUY is tapped this frame.
     * doneText != null shows a grey status (OWNED/MAX) with no button; locked shows a red gate label.
     */
    private boolean hubRow(float y, float rowH, String name, String detail, int cost,
                           boolean afford, boolean locked, String lockMsg, String doneText, boolean tapped,
                           float ar, float ag, float ab, int pipCount, int pipFilled, int iconW, float reveal) {
        float cx = width * 0.5f + (1f - reveal) * 80f * us;   // slide in from the right while revealing
        float rowW = Math.min(width - 48f, 920f);
        float x0 = cx - rowW * 0.5f;
        drawCard(cx, y, rowW, rowH, 18f * us, 0.105f, 0.125f, 0.175f, 0.96f);
        float abH = rowH - 30f * us;
        drawGlow(x0 + 20f * us, y, 9f * us, abH, 4.5f * us, ar, ag, ab, 0.8f);            // accent glow
        drawRoundRect(x0 + 20f * us, y, 9f * us, abH, 4.5f * us, ar, ag, ab, 0.98f);      // rounded accent bar
        float textX = x0 + 40f * us;
        if (iconW >= 0) {                                                                 // weapon glyph + indent text
            drawWeaponIcon(iconW, x0 + 86f * us, y, Math.min(4.0f, rowH * 0.026f), 0.65f);
            textX = x0 + 140f * us;
        }
        drawTextShadow(name, textX, y - rowH * 0.18f, Math.min(28f, rowH * 0.32f), 1f, 1f, 1f, 0.98f);
        drawTextShadow(detail, textX, y + rowH * 0.24f, Math.min(18f, rowH * 0.21f),
                0.55f + 0.45f * ar, 0.55f + 0.45f * ag, 0.55f + 0.45f * ab, 0.85f);

        float btnW = 200f * us, btnH = rowH - 30f * us, bcx = x0 + rowW - btnW * 0.5f - 18f * us;
        if (pipCount > 0) drawPips(bcx - btnW * 0.5f - pipCount * 17f * us - 26f * us, y, pipCount, pipFilled, ar, ag, ab);

        boolean buy = false;
        float brad = btnH * 0.5f;
        if (doneText != null) {
            drawRoundRect(bcx, y, btnW, btnH, brad, 0.13f, 0.22f, 0.16f, 0.92f);
            drawTextCenteredShadow(doneText, bcx, y, 22f, AC_GREEN[0], AC_GREEN[1], AC_GREEN[2], 1f);
        } else if (locked) {
            drawRoundRect(bcx, y, btnW, btnH, brad, 0.26f, 0.13f, 0.13f, 0.92f);
            drawTextCenteredShadow(lockMsg, bcx, y, 17f, AC_RED[0], AC_RED[1], AC_RED[2], 1f);
        } else {
            if (afford) {
                drawButton(bcx, y, btnW, btnH, brad, 0.20f, 0.74f, 0.34f, 1f, false);
                drawSheen(bcx, y, btnW, btnH, brad, 3.6f, y * 0.012f, 0.16f);   // staggered shine
            } else {
                drawRoundRect(bcx, y, btnW, btnH, brad, 0.16f, 0.17f, 0.20f, 0.92f);
            }
            drawTextCenteredShadow("$" + cost, bcx, y, 23f, 1f, 1f, 1f, afford ? 1f : 0.45f);
            if (tapped && afford && reveal > 0.92f && hitRect(bcx, y, btnW, btnH, tap[0], tap[1])) buy = true;
        }
        // per-row fade-in from the dark backdrop (staggered by reveal)
        if (reveal < 1f)
            drawRoundRect(cx, y, rowW + 10f * us, rowH + 10f * us, 22f * us, 0.020f, 0.028f, 0.058f, (1f - reveal) * 0.96f);
        return buy;
    }

    /** Staggered 0..1 entrance progress for content row `idx` (hub tab-switch reveal). */
    private float rowReveal(int idx) {
        return clamp((hubTabAnim - idx * 0.075f) * 2.8f, 0f, 1f);
    }

    private void hubWeapons(float top, float bottom, boolean tapped) {
        int n = W_COUNT;
        float gap = 12f * us, rowH = Math.min(168f, (bottom - top - gap * (n - 1)) / n);
        float y = top + rowH * 0.5f;
        for (int w = 0; w < n; w++) {
            boolean owned = wOwned[w] != 0;
            boolean locked = !owned && playerLevel < WEAPON_REQ_LVL[w];
            int cost = WEAPON_COST[w];
            String detail = "DMG " + Math.round(W_BODYDMG[w]) + "   MAG " + W_MAG[w]
                    + (W_PELLETS[w] > 1 ? "   x" + W_PELLETS[w] : "") + (W_AUTO[w] ? "   AUTO" : "");
            if (hubRow(y, rowH, WEAPON_NAME[w], detail, cost, cash >= cost, locked,
                    "REACH LV " + WEAPON_REQ_LVL[w], owned ? "OWNED" : null, tapped,
                    weaponR(w), weaponG(w), weaponB(w), 0, 0, w, rowReveal(w))) {
                cash -= cost; wOwned[w] = 1; sfx.swap(); saveMeta();
                spawnPopup("UNLOCKED", width * 0.5f + 120f * us, y - 24f * us, AC_GREEN[0], AC_GREEN[1], AC_GREEN[2], 22f);
                spawnPopup("-$" + cost, width * 0.5f + 120f * us, y + 6f * us, 1f, 0.6f, 0.3f, 22f);
            }
            y += rowH + gap;
        }
    }

    private void hubUpgrades(float top, float bottom, boolean tapped) {
        float cx = width * 0.5f;
        // weapon sub-selector (owned weapons only)
        float selH = 60f * us, gap = 10f * us, selW = Math.min(250f * us, (width - 70f) / W_COUNT - gap);
        float totalW = selW * W_COUNT + gap * (W_COUNT - 1), x0 = cx - totalW * 0.5f + selW * 0.5f;
        if (wOwned[upgradeSel] == 0) upgradeSel = firstOwnedWeapon();
        for (int w = 0; w < W_COUNT; w++) {
            float sx = x0 + w * (selW + gap);
            boolean owned = wOwned[w] != 0;
            if (tapped && owned && hitRect(sx, top + selH * 0.5f, selW, selH, tap[0], tap[1])) {
                upgradeSel = w; sfx.swap(); tapped = false;
            }
            boolean sel = upgradeSel == w;
            float syc = top + selH * 0.5f;
            if (sel) drawGlow(sx, syc, selW, selH, selH * 0.5f, weaponR(w), weaponG(w), weaponB(w), 0.85f);
            drawRoundRect(sx, syc, selW, selH, selH * 0.5f, sel ? 0.20f : 0.10f, sel ? 0.36f : 0.13f, sel ? 0.50f : 0.17f, owned ? 0.95f : 0.4f);
            if (sel) drawRoundRect(sx, syc + selH * 0.5f - 5f * us, selW - 26f * us, 5f * us, 2.5f * us, weaponR(w), weaponG(w), weaponB(w), 0.97f);
            drawTextCenteredShadow(owned ? WEAPON_NAME[w] : "LOCK", sx, syc, 18f, 1f, 1f, 1f, owned ? (sel ? 1f : 0.65f) : 0.4f);
        }

        float listTop = top + selH + 16f * us;
        int n = UPG_COUNT;
        float rgap = 10f * us, rowH = Math.min(140f, (bottom - listTop - rgap * (n - 1)) / n);
        float y = listTop + rowH * 0.5f;
        for (int c = 0; c < n; c++) {
            int tier = upgTier[upgradeSel][c];
            boolean maxed = tier >= UPG_MAX_TIER;
            int cost = maxed ? 0 : UPG_COST[tier + 1];
            float[] col = UPG_COL[c];
            if (hubRow(y, rowH, UPG_NAME[c], upgDetail(upgradeSel, c), cost, cash >= cost, false, "",
                    maxed ? "MAX" : null, tapped, col[0], col[1], col[2], UPG_MAX_TIER, tier, -1, rowReveal(c))) {
                cash -= cost; upgTier[upgradeSel][c] = tier + 1; sfx.swap(); saveMeta();
                spawnPopup("-$" + cost, width * 0.5f + 130f * us, y, 1f, 0.6f, 0.3f, 22f);
            }
            y += rowH + rgap;
        }
    }

    private void hubAbilities(float top, float bottom, boolean tapped) {
        int n = AB_COUNT;
        float gap = 9f * us, rowH = Math.min(126f, (bottom - top - gap * (n - 1)) / n);
        float y = top + rowH * 0.5f;
        for (int ab = 0; ab < n; ab++) {
            int lv = abLevel[ab], mx = AB_MAX_LEVEL[ab];
            boolean maxed = lv >= mx;
            int cost = maxed ? 0 : AB_COST[ab][lv];
            float[] col = AB_COL[ab];
            if (hubRow(y, rowH, AB_NAME[ab], abDetail(ab), cost, cash >= cost, false, "",
                    maxed ? "MAX" : null, tapped, col[0], col[1], col[2], mx, lv, -1, rowReveal(ab))) {
                cash -= cost; abLevel[ab] = lv + 1; sfx.swap(); saveMeta();
                spawnPopup("-$" + cost, width * 0.5f + 130f * us, y, 1f, 0.6f, 0.3f, 22f);
            }
            y += rowH + gap;
        }
    }

    private String upgDetail(int w, int c) {
        int t = upgTier[w][c];
        String eff;
        switch (c) {
            case UPG_DMG:    eff = "DMG " + Math.round(effBodyDmg(w)); break;
            case UPG_RATE:   eff = "RPM " + Math.round(60f / effInterval(w)); break;
            case UPG_MAG:    eff = "MAG " + effMag(w); break;
            default:         eff = "RELOAD " + Math.round(effReload(w) * 1000f) + "MS"; break;
        }
        return "TIER " + t + "/" + UPG_MAX_TIER + "   " + eff;
    }

    private String abDetail(int ab) {
        int lv = abLevel[ab], mx = AB_MAX_LEVEL[ab];
        String eff;
        switch (ab) {
            case AB_MAXHP:       eff = "+" + (20 * lv) + " HP"; break;
            case AB_FASTRELOAD:  eff = lv > 0 ? "-15% RELOAD" : "RELOAD -15%"; break;
            case AB_DMGBOOST:    eff = "+" + (6 * lv) + "% DMG"; break;
            case AB_HEADMASTERY: eff = "+" + (15 * lv) + "% HEAD"; break;
            case AB_ADRENALINE:  eff = "+" + (6 * lv) + " HP/KILL"; break;
            case AB_MOVESPEED:   eff = "+" + (6 * lv) + "% SPEED"; break;
            case AB_DOUBLEJUMP:  eff = lv > 0 ? "ENABLED" : "AIR JUMP"; break;
            default:             eff = "+" + (10 * lv) + "% CASH"; break;
        }
        return "LV " + lv + "/" + mx + "   " + eff;
    }

    private static final float[] WCOL_R = {0.30f, 0.40f, 1.00f, 0.72f};
    private static final float[] WCOL_G = {0.85f, 1.00f, 0.62f, 0.50f};
    private static final float[] WCOL_B = {1.00f, 0.45f, 0.20f, 1.00f};
    private static float weaponR(int w) { return WCOL_R[w]; }
    private static float weaponG(int w) { return WCOL_G[w]; }
    private static float weaponB(int w) { return WCOL_B[w]; }

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

    // --- text (bitmap-font atlas) ---
    // Monospace ASCII 32..127 in a FONT_COLS x FONT_ROWS grid, white-on-transparent.
    private static final int FONT_COLS = 16, FONT_ROWS = 6;
    // Proportional font metrics, filled by makeFontAtlas(): per-glyph advance (em units) and atlas-cell
    // width fraction (for UV). 96 glyphs = ASCII 32..127.
    private final float[] glyphAdv = new float[96];
    private final float[] glyphUVW = new float[96];
    private float fontBoxH = 1.5f;                  // glyph box height / text size (set at bake time)

    /** Width in px a string would occupy at the given size (for centring / right-align). */
    private float measureText(int len, float sizePx) { return len * 0.5f * sizePx * us; }   // rough; real layout uses the String form
    private float measureText(String s, float sizePx) {
        float sz = sizePx * us, w = 0f;
        for (int i = 0; i < s.length(); i++) {
            int gi = s.charAt(i) - 32;
            w += (gi >= 0 && gi < 96 ? glyphAdv[gi] : 0.5f) * sz;
        }
        return w;
    }

    /** Draw text with its LEFT edge at xPx and vertical CENTRE at yPx. Restores prog2 afterwards. */
    private void drawText(String s, float xPx, float yPx, float sizePx, float r, float g, float b, float a) {
        GLES20.glUseProgram(progText);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fontTex);
        GLES20.glUniform1i(uFontTex, 0);
        GLES20.glUniform4f(uColT, r, g, b, a);
        textQuad.position(0);
        GLES20.glEnableVertexAttribArray(aPText);
        GLES20.glVertexAttribPointer(aPText, 2, GLES20.GL_FLOAT, false, 16, textQuad);
        textQuad.position(2);
        GLES20.glEnableVertexAttribArray(aUVText);
        GLES20.glVertexAttribPointer(aUVText, 2, GLES20.GL_FLOAT, false, 16, textQuad);

        float sz = sizePx * us;
        float hsy = (sz * fontBoxH * 0.5f) / height * 2f;
        float oy = 1f - yPx / height * 2f;
        float penX = xPx;
        for (int i = 0; i < s.length(); i++) {
            int gi = s.charAt(i) - 32;
            if (gi < 0 || gi >= FONT_COLS * FONT_ROWS) { penX += 0.5f * sz; continue; }
            float aw = glyphAdv[gi] * sz;                       // advance = glyph box width (proportional)
            if (s.charAt(i) != ' ') {
                int col = gi % FONT_COLS, row = gi / FONT_COLS;
                GLES20.glUniform2f(uUVoffT, (float) col / FONT_COLS, (float) row / FONT_ROWS);
                GLES20.glUniform2f(uUVscaleT, glyphUVW[gi] / FONT_COLS, 1f / FONT_ROWS);
                GLES20.glUniform2f(uScaleT, (aw * 0.5f) / width * 2f, hsy);
                GLES20.glUniform2f(uOffT, (penX + aw * 0.5f) / width * 2f - 1f, oy);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
            }
            penX += aw;
        }
        GLES20.glUseProgram(prog2);   // HUD/menu code assumes prog2 is active
    }

    /** Draw text horizontally centred on cxPx, vertical centre at yPx. */
    private void drawTextCentered(String s, float cxPx, float yPx, float sizePx, float r, float g, float b, float a) {
        drawText(s, cxPx - measureText(s, sizePx) * 0.5f, yPx, sizePx, r, g, b, a);
    }

    /** Draw text right-aligned so it ENDS at xPx. */
    private void drawTextRight(String s, float xPx, float yPx, float sizePx, float r, float g, float b, float a) {
        drawText(s, xPx - measureText(s, sizePx), yPx, sizePx, r, g, b, a);
    }

    // --- modern UI helpers (accent palette + beveled cards + shadowed text + progress pips) ---
    private static final float[] AC_GOLD  = {1.00f, 0.82f, 0.26f};
    private static final float[] AC_CYAN  = {0.32f, 0.80f, 1.00f};
    private static final float[] AC_GREEN = {0.30f, 0.86f, 0.42f};
    private static final float[] AC_RED   = {1.00f, 0.42f, 0.42f};
    // Per-category / per-ability accent colours for the shop rows.
    private static final float[][] UPG_COL = {{1f,0.42f,0.42f}, {1f,0.6f,0.22f}, {0.32f,0.8f,1f}, {0.3f,0.86f,0.42f}};
    private static final float[][] AB_COL = {
        {0.3f,0.86f,0.42f}, {0.32f,0.8f,1f}, {1f,0.42f,0.42f}, {1f,0.82f,0.26f},
        {1f,0.45f,0.62f},   {0.5f,0.9f,1f},  {0.72f,0.52f,1f}, {1f,0.6f,0.22f},
    };

    /** A beveled card: drop shadow + base fill + a brighter top highlight strip. */
    private void drawPanel(float cx, float cy, float w, float h, float r, float g, float b, float a) {
        drawRectPx(cx, cy + 5f, w, h, 0f, 0f, 0f, 0.35f * a);                                 // drop shadow
        drawRectPx(cx, cy, w, h, r, g, b, a);                                                 // base
        drawRectPx(cx, cy - h * 0.5f + h * 0.11f, w, h * 0.22f, r + 0.13f, g + 0.13f, b + 0.13f, 0.5f * a); // top sheen
    }

    /** Left-anchored text with a dark drop shadow so it stays readable over any background. */
    private void drawTextShadow(String s, float x, float y, float size, float r, float g, float b, float a) {
        drawText(s, x + 2.5f, y + 2.5f, size, 0f, 0f, 0f, 0.65f * a);
        drawText(s, x, y, size, r, g, b, a);
    }
    private void drawTextCenteredShadow(String s, float cx, float y, float size, float r, float g, float b, float a) {
        drawTextShadow(s, cx - measureText(s, size) * 0.5f, y, size, r, g, b, a);
    }

    /** A left-to-right row of small rounded pips; the first `filled` are bright accent, the rest dim. */
    private void drawPips(float x, float y, int count, int filled, float r, float g, float b) {
        float ps = 12f * us, gap = 5f * us;
        for (int i = 0; i < count; i++) {
            float px2 = x + i * (ps + gap) + ps * 0.5f;
            if (i < filled) drawRoundRect(px2, y, ps, ps, ps * 0.32f, r, g, b, 0.97f);
            else drawRoundRect(px2, y, ps, ps, ps * 0.32f, 0.26f, 0.29f, 0.34f, 0.85f);
        }
    }

    // --- rounded-corner UI kit (composited from rects + corner discs over the flat-colour pipeline) ---

    /** Filled rounded rectangle: a cross of two rects + four corner discs. Seam-free at a==1. */
    private void drawRoundRect(float cx, float cy, float w, float h, float rad,
                               float r, float g, float b, float a) {
        if (rad < 0.5f) { drawRectPx(cx, cy, w, h, r, g, b, a); return; }
        rad = Math.min(rad, Math.min(w, h) * 0.5f);
        drawRectPx(cx, cy, w - 2f * rad, h, r, g, b, a);     // full-height middle band
        drawRectPx(cx, cy, w, h - 2f * rad, r, g, b, a);     // full-width middle band
        float hx = w * 0.5f - rad, hy = h * 0.5f - rad;
        drawCircle(cx - hx, cy - hy, rad, r, g, b, a);
        drawCircle(cx + hx, cy - hy, rad, r, g, b, a);
        drawCircle(cx - hx, cy + hy, rad, r, g, b, a);
        drawCircle(cx + hx, cy + hy, rad, r, g, b, a);
    }

    /** Soft outer glow: concentric rounded rects grown uniformly (corner radius held constant so the
     *  halo stays an even thickness instead of bunching into lobes at a pill's rounded ends). */
    private void drawGlow(float cx, float cy, float w, float h, float rad,
                          float r, float g, float b, float a) {
        for (int i = 6; i >= 1; i--) {
            float e = i * 3.2f * us;
            drawRoundRect(cx, cy, w + 2f * e, h + 2f * e, rad, r, g, b, a * 0.038f);
        }
    }

    /** Vertical two-stop gradient fill of the whole screen (opaque menu backdrop). */
    private void fillVGradient(float tr, float tg, float tb, float br, float bg, float bb) {
        int n = 40;
        float strip = (float) height / n + 1.5f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) (n - 1);
            drawRectPx(width * 0.5f, (i + 0.5f) / n * height, width, strip,
                       tr + (br - tr) * t, tg + (bg - tg) * t, tb + (bb - tb) * t, 1f);
        }
    }

    /** Slow drifting light motes for the menu backdrop — deterministic positions, looping upward. */
    private void drawMenuMotes() {
        for (int i = 0; i < 30; i++) {
            float fx = ((i * 79 + 17) % 100) / 100f * width;
            float spd = 10f + (i % 6) * 5f;
            float fy = height - (((timeAcc * spd) + i * 151f) % (height + 100f));
            float sz = (1.4f + (i % 3) * 0.9f) * us;
            float aa = (0.04f + 0.05f * pulse01(1.3f, i * 0.7f)) * (0.5f + 0.5f * (i % 2));
            drawCircle(fx, fy, sz, 0.55f, 0.78f, 1f, aa);
        }
    }

    /** A tinted gradient vignette around all four screen edges (used for the low-health warning). */
    private void drawEdgeVignette(float r, float g, float b, float maxA) {
        int n = 8; float band = 180f * us, step = band / n;
        for (int i = 0; i < n; i++) {
            float a = maxA * (1f - i / (float) n);
            float off = i * step + step * 0.5f;
            drawRectPx(off, height * 0.5f, step + 1f, height, r, g, b, a);
            drawRectPx(width - off, height * 0.5f, step + 1f, height, r, g, b, a);
            drawRectPx(width * 0.5f, off, width, step + 1f, r, g, b, a);
            drawRectPx(width * 0.5f, height - off, width, step + 1f, r, g, b, a);
        }
    }

    /** A subtle edge vignette (left/right/bottom) to add depth to the menu. */
    private void drawVignette() {
        int n = 7; float band = 150f * us, step = band / n;
        for (int i = 0; i < n; i++) {
            float a = 0.045f * (1f - i / (float) n);
            float off = i * step + step * 0.5f;
            drawRectPx(off, height * 0.5f, step + 1f, height, 0f, 0f, 0f, a);                 // left
            drawRectPx(width - off, height * 0.5f, step + 1f, height, 0f, 0f, 0f, a);          // right
            drawRectPx(width * 0.5f, height - off, width, step + 1f, 0f, 0f, 0f, a);           // bottom
        }
    }

    /** A rounded card with a crisp top-edge bevel: soft drop shadow, flat base, a gentle top
     *  lightening (inset so it never protrudes), and a hairline highlight along the top edge. */
    private void drawCard(float cx, float cy, float w, float h, float rad,
                          float r, float g, float b, float a) {
        drawRoundRect(cx, cy + 9f * us, w, h, rad, 0f, 0f, 0f, 0.42f * a);                  // drop shadow
        drawRoundRect(cx, cy, w, h, rad, r, g, b, a);                                       // base
        // gentle top-half lightening — an inset plain rect, so no rounded corners poke out
        drawRectPx(cx, cy - h * 0.26f, w - 2f * rad, h * 0.46f, r + 0.028f, g + 0.032f, b + 0.040f, 0.6f * a);
        // hairline highlight tucked just inside the top edge
        drawRoundRect(cx, cy - h * 0.5f + 2.4f * us, w - 2f * rad - 4f * us, 2.6f * us, 1.2f * us,
                      Math.min(1f, r + 0.20f), Math.min(1f, g + 0.22f), Math.min(1f, b + 0.26f), 0.5f * a);
    }

    /** A translucent rounded chip used to seat HUD/header values (cash, level, …) over any background. */
    private void drawChip(float cx, float cy, float w, float h,
                          float r, float g, float b, float a) {
        drawRoundRect(cx, cy + 3f * us, w, h, h * 0.5f, 0f, 0f, 0f, 0.30f * a);             // soft shadow
        drawRoundRect(cx, cy, w, h, h * 0.5f, r, g, b, a);                                  // base
        drawRoundRect(cx, cy - h * 0.5f + 2.2f * us, w - h, 2.4f * us, 1.2f * us,
                      r + 0.10f, g + 0.12f, b + 0.14f, 0.45f * a);                          // top hairline
    }

    /** A tiny gold coin that slowly flips (horizontal squash fakes a 3D spin). */
    private void drawCoin(float cx, float cy, float rPx) {
        float flip = (float) Math.cos(timeAcc * 1.3f);                       // -1..1
        float fa = Math.max(0.22f, Math.abs(flip));                          // never fully edge-on
        float hw = rPx * fa;                                                 // half-width squashes
        boolean back = flip < 0f;
        drawRoundRect(cx, cy, hw * 2f + 3f * us, rPx * 2f + 3f * us, Math.min(hw, rPx) + 1.5f * us, 0.5f, 0.36f, 0.09f, 0.92f); // rim
        drawRoundRect(cx, cy, hw * 2f, rPx * 2f, Math.min(hw, rPx),
                      back ? 0.86f : AC_GOLD[0], back ? 0.66f : AC_GOLD[1], back ? 0.20f : AC_GOLD[2], 1f);                     // face
        float gl = (fa - 0.22f) / 0.78f;                                     // glint strongest when face-on
        drawCircle(cx - hw * 0.3f, cy - rPx * 0.28f, rPx * 0.4f * fa, 1f, 0.98f, 0.78f, 0.22f * gl);
    }

    /** 0..1 sine pulse on the global clock; phase shifts the wave. */
    private float pulse01(float speed, float phase) {
        return 0.5f + 0.5f * (float) Math.sin(timeAcc * speed + phase);
    }

    /** A soft vertical highlight band that periodically sweeps left→right across a pill (premium shine).
     *  Masked to the straight inner span so it never spills past the rounded ends. */
    private void drawSheen(float cx, float cy, float w, float h, float rad, float period, float phase, float a) {
        float cyc = ((timeAcc + phase) % period) / period;          // 0..1 over the period
        if (cyc > 0.42f) return;                                    // a brief sweep, then idle
        float p = cyc / 0.42f;                                      // 0..1 during the sweep
        float innerHalf = Math.max(0f, (w - 2f * rad) * 0.5f);
        if (innerHalf < 1f) return;
        float sx = cx - innerHalf + p * innerHalf * 2f;             // travels across the straight span
        float fade = (float) Math.sin(p * Math.PI);                 // 0→1→0
        float bw = Math.max(7f * us, w * 0.045f), bh = h - 6f * us;
        for (int i = -2; i <= 2; i++) {
            float bx = sx + i * bw;
            if (bx < cx - innerHalf || bx > cx + innerHalf) continue;
            drawRectPx(bx, cy, bw * 0.9f, bh, 1f, 1f, 1f, a * fade * (1f - Math.abs(i) / 3f));
        }
    }

    /** A small stylised side-on weapon silhouette (rounded-rect parts), tinted with an accent muzzle. */
    private void drawWeaponIcon(int w, float cx, float cy, float s, float a) {
        float mr = 0.80f, mg = 0.83f, mb = 0.90f;      // light metal
        float dr = 0.34f, dg = 0.37f, db = 0.44f;      // dark metal
        switch (w) {
            case 0: // PISTOL — slide + grip + muzzle
                drawRoundRect(cx, cy - 1.5f * s, 13f * s, 4.6f * s, 1.4f * s, mr, mg, mb, a);
                drawRoundRect(cx - 3.5f * s, cy + 3f * s, 5f * s, 7f * s, 1.4f * s, dr, dg, db, a);
                break;
            case 1: // SMG — receiver + magazine + stock
                drawRoundRect(cx, cy - 1.5f * s, 18f * s, 4.6f * s, 1.4f * s, mr, mg, mb, a);
                drawRoundRect(cx - 1f * s, cy + 4f * s, 4.2f * s, 8f * s, 1.2f * s, dr, dg, db, a);
                drawRoundRect(cx - 9f * s, cy - 1.5f * s, 4.5f * s, 4.6f * s, 1.2f * s, dr, dg, db, a);
                break;
            case 2: // SHOTGUN — long barrel + pump + stock
                drawRoundRect(cx + 1f * s, cy - 2f * s, 22f * s, 4f * s, 1.2f * s, mr, mg, mb, a);
                drawRoundRect(cx + 1f * s, cy + 2.6f * s, 12f * s, 3f * s, 1.2f * s, dr, dg, db, a);
                drawRoundRect(cx - 11.5f * s, cy - 0.5f * s, 5f * s, 6.5f * s, 1.4f * s, dr, dg, db, a);
                break;
            default: // SNIPER — long barrel + scope + stock
                drawRoundRect(cx, cy - 1.5f * s, 25f * s, 3.6f * s, 1.2f * s, mr, mg, mb, a);
                drawRoundRect(cx + 2f * s, cy - 5.5f * s, 10f * s, 3f * s, 1.2f * s, dr, dg, db, a);
                drawRoundRect(cx - 12.5f * s, cy - 0.5f * s, 5f * s, 6.5f * s, 1.4f * s, dr, dg, db, a);
                break;
        }
        drawCircle(cx + 11f * s, cy - 1.5f * s, 1.8f * s, weaponR(w), weaponG(w), weaponB(w), a);  // accent muzzle
    }

    /** A polished round touch button: edge shadow, bright thin ring, tinted fill, inner top gloss. */
    private void drawPad(float cx, float cy, float rPx, float r, float g, float b, float fillA) {
        drawCircle(cx, cy + 3f * us, rPx + 3f * us, 0f, 0f, 0f, 0.20f);                      // drop shadow
        drawCircle(cx, cy, rPx + 2.5f * us, Math.min(1f, r + 0.32f), Math.min(1f, g + 0.32f), Math.min(1f, b + 0.32f), fillA * 0.85f); // ring
        drawCircle(cx, cy, rPx, r, g, b, fillA);                                            // fill
        drawCircle(cx, cy - rPx * 0.33f, rPx * 0.55f, 1f, 1f, 1f, 0.10f);                   // inner top gloss
    }

    /** A glossy rounded button: faked vertical gradient + top rim highlight; optional outer glow. */
    private void drawButton(float cx, float cy, float w, float h, float rad,
                            float r, float g, float b, float a, boolean glow) {
        if (glow) drawGlow(cx, cy, w, h, rad, r, g, b, a);
        drawRoundRect(cx, cy + 5f * us, w, h, rad, 0f, 0f, 0f, 0.35f * a);                  // shadow
        drawRoundRect(cx, cy, w, h, rad, r * 0.72f, g * 0.72f, b * 0.72f, a);               // darker bottom base
        drawRoundRect(cx, cy - h * 0.23f, w, h * 0.56f, rad, r, g, b, a);                   // brighter top half
        drawRoundRect(cx, cy - h * 0.5f + 4f * us, w - 12f * us, 4f * us, 2f * us,
                      Math.min(1f, r + 0.30f), Math.min(1f, g + 0.30f), Math.min(1f, b + 0.30f), 0.55f * a); // rim
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

    /** Font atlas: clamp + linear, NO mipmap, so glyph cells never bleed into each other. */
    private static int uploadFontTexture(Bitmap bmp) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        bmp.recycle();
        return id[0];
    }

    /** Bake Roboto Bold (proportional) ASCII 32..127 white-on-transparent, recording per-glyph advances. */
    private Bitmap makeFontAtlas() {
        int cw = 80, ch = 92;
        Bitmap b = Bitmap.createBitmap(FONT_COLS * cw, FONT_ROWS * ch, Bitmap.Config.ARGB_8888);  // transparent
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));     // Roboto Bold
        float ts = ch * 0.62f;
        p.setTextSize(ts);
        p.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseY = (ch - (fm.descent - fm.ascent)) * 0.5f - fm.ascent;   // centre the line box in the cell
        fontBoxH = ch / ts;
        char[] one = new char[1];
        int cells = FONT_COLS * FONT_ROWS;
        for (int i = 0; i < cells; i++) {
            one[0] = (char) (32 + i);
            int col = i % FONT_COLS, row = i / FONT_COLS;
            float adv = p.measureText(one, 0, 1);
            glyphAdv[i] = adv / ts;
            glyphUVW[i] = Math.min(adv, (float) cw) / cw;
            c.drawText(one, 0, 1, col * cw, row * ch + baseY, p);
        }
        return b;
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
        float amp = smoothstep(35f, 60f, r);
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
    private static final float[][] PALETTE = {     // muted realistic facades — neutrals dominate, with some painted houses
        {0.85f, 0.82f, 0.76f},   // warm off-white stucco
        {0.79f, 0.75f, 0.67f},   // beige
        {0.74f, 0.72f, 0.69f},   // light warm grey
        {0.81f, 0.74f, 0.62f},   // sandstone
        {0.70f, 0.67f, 0.62f},   // taupe
        {0.76f, 0.71f, 0.64f},   // pale ochre
        {0.83f, 0.79f, 0.73f},   // cream
        {0.71f, 0.65f, 0.59f},   // muted clay / brick
        {0.75f, 0.76f, 0.76f},   // cool stone grey
        // painted houses (still low-saturation, period-realistic)
        {0.64f, 0.70f, 0.61f},   // sage green
        {0.60f, 0.67f, 0.72f},   // dusty blue
        {0.82f, 0.63f, 0.53f},   // soft terracotta
        {0.85f, 0.79f, 0.58f},   // buttery yellow
        {0.76f, 0.64f, 0.63f},   // muted rose
        {0.66f, 0.47f, 0.41f},   // warm brick red
    };
    private static final float[][] ROOFS = {       // realistic roof tints (terracotta / slate / weathered)
        {0.52f, 0.30f, 0.22f},   // muted terracotta
        {0.40f, 0.40f, 0.43f},   // slate grey
        {0.33f, 0.29f, 0.27f},   // dark charcoal-brown
        {0.47f, 0.41f, 0.35f},   // weathered brown
        {0.50f, 0.33f, 0.26f},   // clay
        {0.43f, 0.46f, 0.42f},   // mossy slate-green
        {0.36f, 0.34f, 0.40f},   // blue-slate
        {0.58f, 0.36f, 0.27f},   // warm red tile
    };
    private static final float[][] DOORS = {       // realistic door colours (wood / painted)
        {0.42f, 0.28f, 0.17f},   // dark wood
        {0.30f, 0.26f, 0.22f},   // weathered brown
        {0.22f, 0.30f, 0.28f},   // muted green
        {0.34f, 0.34f, 0.36f},   // grey
        {0.45f, 0.22f, 0.18f},   // dark red
    };
    private static final float[] GLASS_DEF = {0.50f, 0.57f, 0.66f};   // muted grey-blue glazing

    /** A small city: a grid of varied buildings around an open central plaza + spawn lane. */
    // --- vegetation (one baked mesh, coloured via a tiny palette texture) ---

    private static float cell(int i) { return (i + 0.5f) / 16f; }   // palette column centre

    /** Scatter grass tufts, flowers and bushes over the flat core + surrounding meadow,
     *  skipping roads and building footprints. Baked into one mesh; sets vegVerts. */
    /** Level-placed trees baked into one mesh (reuses the village tree model + foliage atlas). */
    private float[] makePlacedTrees() {
        float[] d = new float[treeList.length * 6000 + 256];
        int o = 0;
        Random r = new Random(303);
        for (float[] tr : treeList) o = vTree(d, o, tr[0], terrainH(tr[0], tr[1]), tr[1], Math.max(0.4f, tr[2]), true, r);
        placedTreeVerts = o / 8;
        return java.util.Arrays.copyOf(d, o);
    }

    private float[] makeVegetation() {
        float[] d = new float[6000000];
        int o = 0;
        Random r = new Random(404);
        float ug0 = cell(0), ug1 = cell(1), ug2 = cell(2), uStem = cell(3), uCtr = cell(11), uBush = cell(12);
        int[] flowerCells = {4, 5, 6, 7, 8, 9, 10};

        for (int n = 0, tries = 0; n < 900 && tries < 12000; tries++) {     // grass tufts
            if (o > d.length - 9000) break;
            float a = r.nextFloat() * 6.2832f, rad = r.nextFloat() * 40f;
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
        for (int n = 0, tries = 0; n < 450 && tries < 9000; tries++) {     // flowers (5 varied types)
            if (o > d.length - 9000) break;
            float a = r.nextFloat() * 6.2832f, rad = r.nextFloat() * 40f;
            float x = (float) Math.cos(a) * rad, z = (float) Math.sin(a) * rad;
            if (inBuildingXZ(x, z, 0.25f) || onRoadXZ(x, z)) continue;
            o = vFlower(d, o, x, terrainH(x, z), z, r.nextInt(5), r, uStem, uCtr, flowerCells);
            n++;
        }
        for (int n = 0, tries = 0; n < 28 && tries < 3000; tries++) {      // bouquets (gathered bunches)
            if (o > d.length - 9000) break;
            float a = r.nextFloat() * 6.2832f, rad = r.nextFloat() * 38f;
            float x = (float) Math.cos(a) * rad, z = (float) Math.sin(a) * rad;
            if (inBuildingXZ(x, z, 0.35f) || onRoadXZ(x, z)) continue;
            o = vBouquet(d, o, x, terrainH(x, z), z, r, uStem, uCtr, flowerCells);
            n++;
        }
        // a few rounded feature bushes scattered on the lawns/yards (street trees already line the sidewalks,
        // so no more street hedges — and only the rounded dome variants, not the blocky clump)
        int[] roundBush = {0, 1, 2, 3};
        for (int n = 0, tries = 0; n < 26 && tries < 6000; tries++) {
            if (o > d.length - 6000) break;
            float a = r.nextFloat() * 6.2832f, rad = 4f + r.nextFloat() * 32f;
            float x = (float) Math.cos(a) * rad, z = (float) Math.sin(a) * rad;
            if (inBuildingXZ(x, z, 0.5f) || onStreetXZ(x, z)) continue;
            o = vBush(d, o, x, terrainH(x, z), z, roundBush[r.nextInt(roundBush.length)], r, flowerCells);
            n++;
        }
        // detailed trees dotted around the meadow / arena edge
        for (int n = 0, tries = 0; n < 55 && tries < 4000; tries++) {
            if (o > d.length - 9000) break;
            float a = r.nextFloat() * 6.2832f, rad = 20f + r.nextFloat() * 18f;
            float x = (float) Math.cos(a) * rad, z = (float) Math.sin(a) * rad;
            if (inBuildingXZ(x, z, 0.6f) || onRoadXZ(x, z)) continue;
            o = vTree(d, o, x, terrainH(x, z), z, 1.0f + r.nextFloat() * 0.5f, true, r);
            n++;
        }
        // forest ring around the level — hides the map edge / horizon
        float[] ringR = {44f, 48f, 52f, 56f, 60f};
        for (int ri = 0; ri < ringR.length; ri++) {
            int count = (int) (ringR[ri] * 1.7f);
            for (int i = 0; i < count; i++) {
                if (o > d.length - 9000) break;
                float a = (float) i / count * 6.2832f + (r.nextFloat() - 0.5f) * 0.10f;
                float rr = ringR[ri] + (r.nextFloat() - 0.5f) * 2.4f;
                float x = (float) Math.cos(a) * rr, z = (float) Math.sin(a) * rr;
                o = vTree(d, o, x, terrainH(x, z), z, 1.2f + r.nextFloat() * 0.8f, false, r);
            }
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

    private static final float[] STREETS = {-25f, -15f, -5f, 5f, 15f, 25f};   // road centrelines (10 m block pitch)
    private static final float ROAD_HALF = 1.8f;     // asphalt half-width (4 m carriageway)
    private static final float WALK_OUT = 2.9f;      // outer edge of the concrete sidewalk band
    private static final float WALK_MID = 2.35f;     // centre of the sidewalk strip (where lamps/trees stand)

    /** True on the asphalt carriageway (NOT the sidewalk) — keeps the traffic lane clear, allows kerbside furniture. */
    private static boolean onRoadXZ(float x, float z) {
        for (float r : STREETS)
            if (Math.abs(x - r) < ROAD_HALF || Math.abs(z - r) < ROAD_HALF) return true;
        return false;
    }
    /** True anywhere on a street footprint incl. its sidewalks — used to keep buildings off the streets. */
    private static boolean onStreetXZ(float x, float z) {
        for (float r : STREETS)
            if (Math.abs(x - r) < WALK_OUT || Math.abs(z - r) < WALK_OUT) return true;
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

    /** A flower of one of five types on a stem. */
    private static int vFlower(float[] d, int o, float x, float by, float z, int type, Random r,
                               float uStem, float uCtr, int[] flowerCells) {
        float petU = cell(flowerCells[r.nextInt(flowerCells.length)]);
        float stemH = 0.22f + r.nextFloat() * 0.18f, topY = by + stemH;
        switch (type) {
            case 1: {  // daisy: many thin white petals, orange centre
                o = vBox(d, o, x, by + stemH * 0.5f, z, 0.016f, stemH, 0.016f, uStem);
                int petals = 9 + r.nextInt(3);
                float pa0 = r.nextFloat() * 6.2832f, uw = cell(6);
                for (int p = 0; p < petals; p++)
                    o = vPetal(d, o, x, topY, z, pa0 + p * (6.2832f / petals), 0.075f, 0.012f, 0.024f, uw);
                o = vBox(d, o, x, topY + 0.008f, z, 0.04f, 0.025f, 0.04f, cell(10));
                break;
            }
            case 2: {  // tulip: taller, four cupped petals pointing up + a leaf
                float th = stemH + 0.12f;
                o = vBox(d, o, x, by + th * 0.5f, z, 0.02f, th, 0.02f, uStem);
                o = vBlade(d, o, x, by, z, r.nextFloat() * 6.2832f, th * 0.7f, 0.05f, 0.05f, cell(1));
                float pa0 = r.nextFloat() * 6.2832f, ty = by + th - 0.02f;
                for (int p = 0; p < 4; p++)
                    o = vPetal(d, o, x, ty, z, pa0 + p * 1.5708f, 0.03f, 0.11f, 0.06f, petU);
                break;
            }
            case 3: {  // pompom: a ball of small colour cubes
                o = vBox(d, o, x, by + stemH * 0.5f, z, 0.018f, stemH, 0.018f, uStem);
                int lobes = 6 + r.nextInt(3);
                for (int l = 0; l < lobes; l++) {
                    float la = r.nextFloat() * 6.2832f, lr = r.nextFloat() * 0.06f;
                    o = vBox(d, o, x + (float) Math.cos(la) * lr, topY + (r.nextFloat() - 0.35f) * 0.06f,
                            z + (float) Math.sin(la) * lr, 0.05f, 0.05f, 0.05f, petU);
                }
                break;
            }
            case 4: {  // big two-tone: six large petals + centre
                o = vBox(d, o, x, by + stemH * 0.5f, z, 0.02f, stemH, 0.02f, uStem);
                float pa0 = r.nextFloat() * 6.2832f;
                for (int p = 0; p < 6; p++)
                    o = vPetal(d, o, x, topY, z, pa0 + p * 1.0472f, 0.10f, 0.03f, 0.08f, petU);
                o = vBox(d, o, x, topY + 0.012f, z, 0.06f, 0.035f, 0.06f, uCtr);
                break;
            }
            default: {  // classic five/six petals
                o = vBlade(d, o, x - 0.05f, by, z, r.nextFloat() * 6.2832f, 0.14f, 0.02f, 0.05f, cell(1));
                o = vBox(d, o, x, by + stemH * 0.5f, z, 0.018f, stemH, 0.018f, uStem);
                int petals = 5 + r.nextInt(2);
                float pa0 = r.nextFloat() * 6.2832f;
                for (int p = 0; p < petals; p++)
                    o = vPetal(d, o, x, topY, z, pa0 + p * (6.2832f / petals), 0.08f, 0.02f, 0.055f, petU);
                o = vBox(d, o, x, topY + 0.012f, z, 0.045f, 0.03f, 0.045f, uCtr);
                break;
            }
        }
        return o;
    }

    /** A gathered bunch: several flowers with stems converging into a paper wrap. */
    private static int vBouquet(float[] d, int o, float x, float by, float z, Random r,
                                float uStem, float uCtr, int[] flowerCells) {
        o = vBox(d, o, x, by + 0.10f, z, 0.13f, 0.20f, 0.13f, cell(6));   // wrap
        int n = 6 + r.nextInt(4);
        float baseY = by + 0.18f;
        for (int i = 0; i < n; i++) {
            float a = r.nextFloat() * 6.2832f, rr = 0.04f + r.nextFloat() * 0.12f;
            float hx = x + (float) Math.cos(a) * rr, hz = z + (float) Math.sin(a) * rr;
            float headY = by + 0.36f + r.nextFloat() * 0.16f;
            float dxh = hx - x, dzh = hz - z, hb = (float) Math.sqrt(dxh * dxh + dzh * dzh);
            o = vBlade(d, o, x, baseY, z, (float) Math.atan2(dzh, dxh), headY - baseY, 0.02f, hb, cell(3));
            float petU = cell(flowerCells[r.nextInt(flowerCells.length)]);
            float pa0 = r.nextFloat() * 6.2832f;
            for (int p = 0; p < 5; p++)
                o = vPetal(d, o, hx, headY, hz, pa0 + p * 1.2566f, 0.055f, 0.015f, 0.04f, petU);
            o = vBox(d, o, hx, headY + 0.008f, hz, 0.035f, 0.022f, 0.035f, uCtr);
        }
        return o;
    }

    /** A full, wide, rounded mound of green lobes (always wider than tall — a bush, never a tree). */
    private static int bushDome(float[] d, int o, float x, float by, float z, float rad, float height,
                                int lobes, Random r, float gA, float gB) {
        float coreW = rad * 1.5f, coreH = height + 0.20f;
        o = vBox(d, o, x, by + 0.10f + coreH * 0.5f, z, coreW, coreH, coreW, gA);     // wide, low core
        for (int l = 0; l < lobes; l++) {                                             // side lobes (kept low)
            float la = r.nextFloat() * 6.2832f, lr = rad * (0.5f + r.nextFloat() * 0.55f);
            float s = 0.30f + r.nextFloat() * 0.22f;
            o = vBox(d, o, x + (float) Math.cos(la) * lr, by + 0.16f + r.nextFloat() * height * 0.5f,
                    z + (float) Math.sin(la) * lr, s, s, s, r.nextBoolean() ? gA : gB);
        }
        for (int k = 0; k < 3; k++) {                                                 // crown lobes round the top
            float s = 0.30f + r.nextFloat() * 0.16f;
            o = vBox(d, o, x + (r.nextFloat() - 0.5f) * rad * 0.7f, by + 0.10f + coreH,
                    z + (r.nextFloat() - 0.5f) * rad * 0.7f, s, s * 0.82f, s, gA);
        }
        return o;
    }

    /** A bush — six rounded variants, all wider than tall (no spiky / stepped shapes). */
    private static int vBush(float[] d, int o, float x, float by, float z, int type, Random r, int[] flowerCells) {
        float gA = cell(12), gB = cell(13), gC = cell(0);
        switch (type) {
            case 1:    // big full shrub
                o = bushDome(d, o, x, by, z, 0.92f, 0.52f, 12, r, gA, gB);
                break;
            case 2: {  // flowering bush: green mound + colour dots on top
                o = bushDome(d, o, x, by, z, 0.64f, 0.46f, 9, r, gA, gB);
                int blooms = 8 + r.nextInt(5);
                for (int b = 0; b < blooms; b++) {
                    float ba = r.nextFloat() * 6.2832f, brr = r.nextFloat() * 0.6f;
                    o = vBox(d, o, x + (float) Math.cos(ba) * brr, by + 0.42f + r.nextFloat() * 0.22f,
                            z + (float) Math.sin(ba) * brr, 0.075f, 0.075f, 0.075f,
                            cell(flowerCells[r.nextInt(flowerCells.length)]));
                }
                break;
            }
            case 3:    // low wide mound
                o = bushDome(d, o, x, by, z, 0.85f, 0.32f, 11, r, gA, gC);
                break;
            case 4: {  // wide low hedge clump
                float w = 0.95f + r.nextFloat() * 0.7f, dd = 0.5f + r.nextFloat() * 0.3f, hh = 0.45f + r.nextFloat() * 0.18f;
                o = vBox(d, o, x, by + hh * 0.5f, z, w, hh, dd, gA);
                o = vBox(d, o, x + (r.nextFloat() - 0.5f) * w * 0.4f, by + hh * 0.5f + 0.08f, z, w * 0.7f, hh * 0.85f, dd * 0.9f, gB);
                break;
            }
            case 5: {  // berry bush: green mound + dark-red berries
                o = bushDome(d, o, x, by, z, 0.60f, 0.42f, 9, r, gA, gC);
                for (int b = 0; b < 9; b++) {
                    float ba = r.nextFloat() * 6.2832f, brr = r.nextFloat() * 0.55f;
                    o = vBox(d, o, x + (float) Math.cos(ba) * brr, by + 0.36f + r.nextFloat() * 0.20f,
                            z + (float) Math.sin(ba) * brr, 0.05f, 0.05f, 0.05f, cell(4));
                }
                break;
            }
            default:   // round shrub
                o = bushDome(d, o, x, by, z, 0.66f, 0.46f, 9, r, gA, gB);
                break;
        }
        return o;
    }

    /** A tree: a brown trunk with a rounded green crown (detailed up close, simpler far away). */
    private static int vTree(float[] d, int o, float x, float by, float z, float scale, boolean detail, Random r) {
        float uTrunk = cell(14), gA = cell(12), gB = cell(13);
        float trunkH = (1.1f + r.nextFloat() * 0.6f) * scale, tw = 0.18f * scale;
        o = vBox(d, o, x, by + trunkH * 0.5f, z, tw, trunkH, tw, uTrunk);
        o = vBox(d, o, x, by + trunkH * 0.88f, z, tw * 0.78f, trunkH * 0.5f, tw * 0.78f, uTrunk);
        float cy = by + trunkH + 0.30f * scale, crad = (0.9f + r.nextFloat() * 0.5f) * scale;
        if (detail) {
            o = vBox(d, o, x, cy, z, crad * 1.4f, crad * 1.3f, crad * 1.4f, gA);     // crown core
            int lobes = 10 + r.nextInt(5);
            for (int l = 0; l < lobes; l++) {
                float la = r.nextFloat() * 6.2832f, lr = crad * (0.4f + r.nextFloat() * 0.7f);
                float ly = cy + (r.nextFloat() - 0.4f) * crad * 0.9f, s = (0.40f + r.nextFloat() * 0.4f) * scale;
                o = vBox(d, o, x + (float) Math.cos(la) * lr, ly, z + (float) Math.sin(la) * lr, s, s, s, r.nextBoolean() ? gA : gB);
            }
        } else {                                                                     // distant: a few big lobes
            o = vBox(d, o, x, cy, z, crad * 1.7f, crad * 1.5f, crad * 1.7f, gA);
            o = vBox(d, o, x + crad * 0.5f, cy + crad * 0.3f, z, crad * 1.1f, crad * 1.1f, crad * 1.1f, gB);
            o = vBox(d, o, x - crad * 0.4f, cy + crad * 0.2f, z + crad * 0.4f, crad * 1.1f, crad * 1.1f, crad * 1.1f, gA);
            o = vBox(d, o, x, cy + crad * 0.55f, z - crad * 0.3f, crad * 0.95f, crad * 0.95f, crad * 0.95f, gB);
        }
        return o;
    }

    private static Bitmap makeVegPalette() {
        int W = 16, H = 4;
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        int[] c = {
            0xFF2F5A20, 0xFF3C6E26, 0xFF4C7E32, 0xFF335E1E,   // grass dark/mid/light, stem (muted, less lime)
            0xFFC24A42, 0xFFE0C04A, 0xFFE8E2D2, 0xFFD080A0,   // red, yellow, white, pink (softer)
            0xFF8A5FC0, 0xFF5273C0, 0xFFD98640, 0xFFE0BE48,   // purple, blue, orange, centre (softer)
            0xFF2A5020, 0xFF3E6E2A, 0xFF6B4A2A, 0xFF356326,   // 12 bush, 13 green, 14 trunk brown, 15 green (muted)
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

    /** Custom streets from the level: one flat textured quad per segment, laid on the ground. */
    private float[] makeRoadMesh() {
        float[] d = new float[roadSegs.length * 6 * 8];
        int o = 0;
        for (float[] s : roadSegs) {
            float x1 = s[0], z1 = s[1], x2 = s[2], z2 = s[3], width = Math.max(0.6f, s[4]);
            float dx = x2 - x1, dz = z2 - z1, len = (float) Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-3f) continue;
            float ux = dx / len, uz = dz / len, px = -uz, pz = ux, hw = width * 0.5f, y = 0.03f;
            float ax = x1 + px * hw, az = z1 + pz * hw, bx = x1 - px * hw, bz = z1 - pz * hw;
            float cx = x2 - px * hw, cz = z2 - pz * hw, ex = x2 + px * hw, ez = z2 + pz * hw;
            float v = len / width;                          // tile dashes along the length
            o = put(d, o, ax, y, az, 0, 1, 0, 0f, 0f);
            o = put(d, o, bx, y, bz, 0, 1, 0, 1f, 0f);
            o = put(d, o, cx, y, cz, 0, 1, 0, 1f, v);
            o = put(d, o, ax, y, az, 0, 1, 0, 0f, 0f);
            o = put(d, o, cx, y, cz, 0, 1, 0, 1f, v);
            o = put(d, o, ex, y, ez, 0, 1, 0, 0f, v);
        }
        roadVerts = o / 8;
        return java.util.Arrays.copyOf(d, o);
    }

    private static Bitmap makeRoadBitmap() {
        int N = 128;
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF4A4D50);                            // asphalt
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(91);
        for (int k = 0; k < 700; k++) {                     // grain
            int v = rnd.nextInt(26), s = 0x42 + v;
            p.setColor((0x44 << 24) | (s << 16) | (s << 8) | s);
            float x = rnd.nextInt(N), y = rnd.nextInt(N);
            c.drawRect(x, y, x + 2, y + 2, p);
        }
        p.setColor(0xFFB9B2A6);                              // light kerb borders along both long edges (U)
        c.drawRect(0, 0, N * 0.09f, N, p);
        c.drawRect(N * 0.91f, 0, N, N, p);
        p.setColor(0xFFE8C23A);                              // dashed yellow centre line (tiles along V)
        for (float y = 0; y < N; y += N / 4f) c.drawRect(N * 0.47f, y, N * 0.53f, y + N / 8f, p);
        return b;
    }

    /** Top-down city ground: concrete blocks, asphalt streets with sidewalk borders + lane dashes. */
    private static Bitmap makeCityBitmap() {
        int N = 1536;
        float half = 35f;
        Bitmap bmp = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF3C6E24);                           // lawn / grass base
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(31);
        for (int k = 0; k < 420; k++) {                    // mottled green so the lawn isn't flat
            int g = rnd.nextInt(3);
            int col = g == 0 ? 0x2E6B1F : (g == 1 ? 0x4A8E2A : 0x356016);
            p.setColor(0x66000000 | col);
            c.drawCircle(rnd.nextInt(N), rnd.nextInt(N), 9 + rnd.nextInt(48), p);
        }
        for (float r : STREETS) {                          // sidewalk slabs (light concrete)
            cityBand(c, p, r, WALK_OUT, half, N, true, 0xFFB3B5A8);
            cityBand(c, p, r, WALK_OUT, half, N, false, 0xFFB3B5A8);
        }
        for (float r : STREETS) {                          // dark kerb line at the sidewalk/road edge
            cityBand(c, p, r, ROAD_HALF + 0.12f, half, N, true, 0xFF82847A);
            cityBand(c, p, r, ROAD_HALF + 0.12f, half, N, false, 0xFF82847A);
        }
        for (float r : STREETS) {                          // asphalt carriageway
            cityBand(c, p, r, ROAD_HALF, half, N, true, 0xFF34373A);
            cityBand(c, p, r, ROAD_HALF, half, N, false, 0xFF34373A);
        }
        p.setColor(0xFFD9C24E);                            // dashed centre lines
        for (float r : STREETS) {
            cityDashes(c, p, r, half, N, true);
            cityDashes(c, p, r, half, N, false);
        }
        int[] bed = {0xE03A3A, 0xF7D43A, 0xF06FB0, 0x9B4FE0, 0x4F7BF0, 0xF58A20, 0xF4F0E6};
        for (int k = 0; k < 70; k++) {                     // painted flower beds on the lawn (off the roads)
            float wx = rnd.nextInt(N), wy = rnd.nextInt(N);
            float worldX = wx / N * (2f * half) - half, worldZ = wy / N * (2f * half) - half;
            if (onStreetXZ(worldX, worldZ)) continue;
            int col = bed[rnd.nextInt(bed.length)];
            for (int j = 0; j < 9; j++) {
                p.setColor(0xCC000000 | col);
                c.drawCircle(wx + rnd.nextInt(24) - 12, wy + rnd.nextInt(24) - 12, 2 + rnd.nextInt(3), p);
            }
        }
        for (int k = 0; k < 4200; k++) {                   // fine grass grain
            int v = rnd.nextInt(40);
            p.setColor((0x22 << 24) | ((0x22 + v) << 16) | ((0x44 + v) << 8) | (0x18 + v / 2));
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

    /**
     * Optional custom level produced by the web editor (tools/editor/level-editor.html) and pushed to
     * the device at  <app-external-files>/level.lvl  (adb push, no rebuild needed). Plain-text lines:
     *   H x z w d h roof door r g b     a house (roof 0-2, door 0=+z 1=-z 2=+x 3=-x, colour 0-1)
     *   B x z w h d r g b               a ground box / prop (crate, pillar, cover)
     *   # ...                           comment
     * Returns false (-> procedural village) if the file is missing, empty or unparseable.
     */
    private boolean buildWorldFromFile(Context ctx, List<float[]> L, List<float[]> doors, List<float[]> houses) {
        java.io.BufferedReader br = null;
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return false;
            java.io.File f = new java.io.File(dir, "level.lvl");
            if (!f.exists()) return false;
            java.util.List<float[]> props = new java.util.ArrayList<float[]>();
            java.util.List<float[]> hs = new java.util.ArrayList<float[]>();
            java.util.List<float[]> roadList = new java.util.ArrayList<float[]>();
            java.util.List<float[]> trees = new java.util.ArrayList<float[]>();
            br = new java.io.BufferedReader(new java.io.FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                String[] t = line.split("\\s+");
                if (t[0].equals("SET") && t.length >= 2) {
                    applySet(t);                                   // global world look (mutates fields immediately)
                } else if (t[0].equals("H") && t.length >= 11) {
                    // base: x z w d h roof door r g b  | v2: rr rg rb pitch overhang windows winSize chimney
                    // | v4 (per-house detail): glassR glassG glassB  doorW doorH  doorR doorG doorB
                    //                          foundH foundR foundG foundB  trimR trimG trimB  storeys
                    hs.add(new float[]{pf(t[1]), pf(t[2]), pf(t[3]), pf(t[4]), pf(t[5]),
                                       pf(t[6]), pf(t[7]), pf(t[8]), pf(t[9]), pf(t[10]),
                                       pfDef(t,11,-1f), pfDef(t,12,-1f), pfDef(t,13,-1f),   // [10-12] roof colour
                                       pfDef(t,14,-1f), pfDef(t,15,-1f),                    // [13-14] pitch, overhang
                                       pfDef(t,16,2f),  pfDef(t,17,1f),                     // [15-16] windows density, window size
                                       pfDef(t,18,-1f),                                     // [17] chimney (-1 = auto)
                                       pfDef(t,19,-1f), pfDef(t,20,-1f), pfDef(t,21,-1f),   // [18-20] glass tint (-1 = default)
                                       pfDef(t,22,1.9f), pfDef(t,23,2.3f),                  // [21-22] door width, height
                                       pfDef(t,24,0.46f), pfDef(t,25,0.30f), pfDef(t,26,0.17f), // [23-25] door colour
                                       pfDef(t,27,0.30f),                                   // [26] foundation height (0 = none)
                                       pfDef(t,28,0.42f), pfDef(t,29,0.40f), pfDef(t,30,0.40f), // [27-29] foundation colour
                                       pfDef(t,31,-1f), pfDef(t,32,-1f), pfDef(t,33,-1f),   // [30-32] trim band colour (-1 = none)
                                       pfDef(t,34,1f)});                                    // [33] storeys (1 = no dividers)
                } else if (t[0].equals("B") && t.length >= 9) {
                    // cx cz w h d r g b [yawDeg]  -> ground box centred at y = h/2 (yaw = visual rotation)
                    float w = pf(t[3]), h = pf(t[4]), d = pf(t[5]);
                    props.add(new float[]{pf(t[1]), h * 0.5f, pf(t[2]), w, h, d, pf(t[6]), pf(t[7]), pf(t[8]), pfDef(t, 9, 0f)});
                } else if (t[0].equals("R") && t.length >= 5) {
                    // R x1 z1 x2 z2 [width]  -> a street segment (visual only, no collision)
                    roadList.add(new float[]{pf(t[1]), pf(t[2]), pf(t[3]), pf(t[4]), pfDef(t, 5, 3.4f)});
                } else if (t[0].equals("T") && t.length >= 3) {
                    // T x z [scale]  -> a placed tree
                    trees.add(new float[]{pf(t[1]), pf(t[2]), pfDef(t, 3, 1f)});
                } else if (t[0].equals("F") && t.length >= 5) {
                    // F x1 z1 x2 z2 [r g b]  -> a fence: one thin box rotated along the segment
                    float x1 = pf(t[1]), z1 = pf(t[2]), x2 = pf(t[3]), z2 = pf(t[4]);
                    float ddx = x2 - x1, ddz = z2 - z1, len = (float) Math.sqrt(ddx * ddx + ddz * ddz);
                    if (len >= 0.3f) {
                        float yaw = (float) Math.toDegrees(Math.atan2(ddx, ddz));
                        props.add(new float[]{(x1 + x2) * 0.5f, 0.6f, (z1 + z2) * 0.5f, 0.14f, 1.2f, len,
                                pfDef(t, 5, 0.62f), pfDef(t, 6, 0.45f), pfDef(t, 7, 0.30f), yaw});
                    }
                }
            }
            this.roadSegs = roadList.isEmpty() ? null : roadList.toArray(new float[0][]);
            this.hasCustomRoads = this.roadSegs != null;
            this.treeList = trees.isEmpty() ? null : trees.toArray(new float[0][]);
            if (hs.isEmpty() && props.isEmpty() && roadList.isEmpty() && trees.isEmpty()) return false;
            for (float[] p : props) L.add(p);                       // props first -> they get the shadow blobs
            numCover = Math.min(COVER_BOXES, props.size());
            for (float[] hh : hs) {
                int roof = (int) hh[5], door = (int) hh[6], chim = (int) hh[17];
                float rr = hh[10], rg = hh[11], rb = hh[12];
                if (rr < 0f) { rr = 0.69f; rg = 0.31f; rb = 0.16f; }     // default terracotta roof
                float gr = hh[18], gg = hh[19], gb = hh[20];
                if (gr < 0f) { gr = GLASS_DEF[0]; gg = GLASS_DEF[1]; gb = GLASS_DEF[2]; }   // default muted glass
                addBuilding(L, doors, hh[0], hh[1], hh[2], hh[3], hh[4], door, roof, hh[7], hh[8], hh[9], chim,
                            hh[21], hh[22], hh[23], hh[24], hh[25]);      // doorW doorH doorR doorG doorB
                houses.add(new float[]{hh[0], hh[1], hh[2], hh[3], hh[4], door, rr, rg, rb, hh[13], hh[14], hh[15], hh[16],
                            gr, gg, gb, hh[26], hh[27], hh[28], hh[29], hh[30], hh[31], hh[32], hh[33]});
            }
            return true;
        } catch (Exception e) {
            return false;                                           // any trouble -> fall back to procedural
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) { }
        }
    }

    private static float pf(String s) { try { return Float.parseFloat(s); } catch (Exception e) { return 0f; } }
    private static float pfDef(String[] t, int i, float def) { return i < t.length ? pf(t[i]) : def; }

    /** A "SET key v..." line from a level overrides the global world look. Unknown keys are ignored. */
    private void applySet(String[] t) {
        String k = t[1];
        if      (k.equals("skyHorizon") && t.length >= 5) { skyHorizon[0]=pf(t[2]); skyHorizon[1]=pf(t[3]); skyHorizon[2]=pf(t[4]); }
        else if (k.equals("skyZenith")  && t.length >= 5) { skyZenith[0]=pf(t[2]);  skyZenith[1]=pf(t[3]);  skyZenith[2]=pf(t[4]); }
        else if (k.equals("sun")        && t.length >= 5) { sunDir[0]=pf(t[2]);     sunDir[1]=pf(t[3]);     sunDir[2]=pf(t[4]); }
        else if (k.equals("sunIntensity") && t.length >= 3) sunInt = pf(t[2]);
        else if (k.equals("ambient")      && t.length >= 3) ambient = pf(t[2]);
        else if (k.equals("fog")        && t.length >= 5) { fog[0]=pf(t[2]); fog[1]=pf(t[3]); fog[2]=pf(t[4]); }
        else if (k.equals("fogRange")   && t.length >= 4) { fogStart=pf(t[2]); fogEnd=pf(t[3]); }
        else if (k.equals("ground")     && t.length >= 5) { ground[0]=pf(t[2]); ground[1]=pf(t[3]); ground[2]=pf(t[4]); }
    }

    private void buildWorldInto(List<float[]> L, List<float[]> doors, List<float[]> houses) {
        // plaza cover (first COVER_BOXES entries get a shadow blob): two climbable crates, two stone pillars
        L.add(new float[]{-2.0f, 0.75f, 2.5f, 1.5f, 1.5f, 1.5f, 0.55f, 0.42f, 0.28f});
        L.add(new float[]{ 2.0f, 0.75f, 2.5f, 1.5f, 1.5f, 1.5f, 0.55f, 0.42f, 0.28f});
        L.add(new float[]{-2.2f, 1.5f, -2.2f, 1.2f, 3.0f, 1.2f, 0.72f, 0.72f, 0.70f});
        L.add(new float[]{ 2.2f, 1.5f, -2.2f, 1.2f, 3.0f, 1.2f, 0.72f, 0.72f, 0.70f});
        // Houses on a 10 m block grid, each set back behind the sidewalks so the streets + kerbs stay clear.
        Random rc = new Random(73);
        float[] gs = {-30f, -20f, -10f, 0f, 10f, 20f, 30f};   // block centres; roads run between them at +/-5,15,25
        for (int ix = 0; ix < gs.length; ix++) {
            for (int iz = 0; iz < gs.length; iz++) {
                float gx = gs[ix], gz = gs[iz];
                if (gx * gx + gz * gz > 31f * 31f) continue;       // stay on the flat core (disc)
                if (gx == 0f && gz >= 0f && gz < 15f) continue;    // keep the spawn plaza (rows 0 & 10) open
                if (isMarketLot(gx, gz)) continue;                 // reserved open lots for the market square
                if (rc.nextFloat() < 0.10f) continue;              // random empty lot -> irregular spacing

                // Pick a building archetype by where it sits: shops + townhouses near the centre,
                // cottages in the mid ring, bigger flats/blocks on the outskirts -> a believable town gradient.
                float dist2 = gx * gx + gz * gz, roll = rc.nextFloat();
                int arch;
                if (dist2 > 26f * 26f)      arch = roll < 0.45f ? 2 : (roll < 0.80f ? 1 : 0);   // outskirts
                else if (dist2 < 16f * 16f) arch = roll < 0.40f ? 3 : (roll < 0.75f ? 1 : 0);   // centre
                else                        arch = roll < 0.60f ? 0 : (roll < 0.85f ? 1 : 3);   // mid ring

                float w, d, h, pitch = -1f, winSize = 1f, foundH, doorW = 1.9f, doorH = 2.3f;
                int storeys, winDens; boolean chimney, trim; float[] rf;
                if (arch == 0) {                                   // cottage: low, gabled
                    w = 3.0f + rc.nextFloat() * 0.5f; d = 3.0f + rc.nextFloat() * 0.45f; h = 2.8f + rc.nextFloat() * 0.7f;
                    storeys = 1; foundH = 0.22f + rc.nextFloat() * 0.10f; winDens = 2; chimney = true; trim = false;
                    rf = ROOFS[new int[]{0, 0, 4, 7}[rc.nextInt(4)]];   // terracotta / clay / warm red tile
                } else if (arch == 1) {                            // townhouse: tall, narrow, terraced
                    w = 2.8f + rc.nextFloat() * 0.5f; d = 3.0f + rc.nextFloat() * 0.4f; h = 4.8f + rc.nextFloat() * 1.4f;
                    storeys = 2 + (rc.nextFloat() < 0.6f ? 1 : 0); foundH = 0.28f + rc.nextFloat() * 0.10f; winDens = 3;
                    chimney = true; trim = rc.nextFloat() < 0.45f; pitch = 1.3f + rc.nextFloat() * 0.5f;
                    rf = ROOFS[new int[]{0, 1, 1, 7}[rc.nextInt(4)]];   // terracotta / slate / warm red tile
                } else if (arch == 2) {                            // apartment block: tall, flat slate roof
                    w = 3.1f + rc.nextFloat() * 0.45f; d = 3.1f + rc.nextFloat() * 0.45f; h = 5.4f + rc.nextFloat() * 1.6f;
                    storeys = 3 + (rc.nextFloat() < 0.5f ? 1 : 0); foundH = 0.30f + rc.nextFloat() * 0.10f; winDens = 3;
                    chimney = false; trim = rc.nextFloat() < 0.5f; pitch = 0.5f + rc.nextFloat() * 0.4f;
                    rf = ROOFS[new int[]{1, 2, 5, 6}[rc.nextInt(4)]];   // slate / charcoal / mossy / blue-slate
                } else {                                           // shop: low front, big door + windows, awning band
                    w = 3.2f + rc.nextFloat() * 0.4f; d = 3.0f + rc.nextFloat() * 0.4f; h = 3.2f + rc.nextFloat() * 0.7f;
                    storeys = 1; foundH = 0.24f + rc.nextFloat() * 0.10f; winDens = 3; winSize = 1.2f; chimney = false;
                    trim = true; doorW = 2.2f + rc.nextFloat() * 0.4f; rf = ROOFS[rc.nextFloat() < 0.6f ? 4 : 3];
                }
                // set the house back from the streets: keep its footprint within +/- 2.0 m of the block centre
                float cx = gx + clamp((rc.nextFloat() - 0.5f) * 1.5f, -Math.max(0f, 2.1f - w * 0.5f), Math.max(0f, 2.1f - w * 0.5f));
                float cz = gz + clamp((rc.nextFloat() - 0.5f) * 1.5f, -Math.max(0f, 2.1f - d * 0.5f), Math.max(0f, 2.1f - d * 0.5f));
                int doorSide = rc.nextInt(4);
                float[] p = PALETTE[rc.nextInt(PALETTE.length)];
                float[] dc = DOORS[rc.nextInt(DOORS.length)];
                float fR = 0.40f + rc.nextFloat() * 0.06f, fG = 0.38f + rc.nextFloat() * 0.06f, fB = 0.36f + rc.nextFloat() * 0.06f;
                float tR = trim ? 0.86f : -1f, tG = trim ? 0.84f : -1f, tB = trim ? 0.78f : -1f;
                int doorStyle = arch == 0 ? 1 : (arch == 1 ? 0 : (arch == 2 ? 2 : 3));   // plank / panel / glazed / shop
                addBuilding(L, doors, cx, cz, w, d, h, doorSide, 1, p[0], p[1], p[2], chimney ? 1 : 0,
                            doorW, doorH, dc[0], dc[1], dc[2], doorStyle, arch);   // arch -> facade material: 0 plaster·1 brick·2 stone·3 timber
                houses.add(new float[]{cx, cz, w, d, h, doorSide, rf[0], rf[1], rf[2], pitch, -1f, (float) winDens, winSize,
                        GLASS_DEF[0], GLASS_DEF[1], GLASS_DEF[2], foundH, fR, fG, fB, tR, tG, tB, (float) storeys});
                if (rc.nextFloat() < 0.45f) {                      // a barrel / crate / planter tucked against a wall
                    float ax = cx + (rc.nextBoolean() ? 1f : -1f) * (w * 0.5f + 0.3f);
                    float az = cz + (rc.nextBoolean() ? 1f : -1f) * (d * 0.5f + 0.3f);
                    if (!onStreetXZ(ax, az) && !(Math.abs(ax) < 3f && az > 2f && az < 13f)
                        && !blocksDoor(new float[]{cx, cz, w, d, 0f, doorSide}, ax, az)) addClutter(L, rc, ax, az);
                }
            }
        }
        addAccessories(L, houses, rc);                             // lamp posts, benches, a market + well, scattered trees
    }

    /** Street furniture for the laid-out town: lamps + trees lining the sidewalks (now that the streets have
     *  kerbs with room), benches on the sidewalk in front of houses, and a market square on the open lots. */
    private void addAccessories(List<float[]> L, List<float[]> houses, Random rc) {
        java.util.List<float[]> placed = new java.util.ArrayList<float[]>();   // prop positions, for spacing
        java.util.List<float[]> trees = new java.util.ArrayList<float[]>();
        // 1. lamps + street trees along the sidewalk strip of every street (kerbside, off the carriageway)
        for (float r : STREETS) {
            for (float t = -27f; t <= 27f; t += 6.5f) {
                float[][] slots = {{r - WALK_MID, t}, {r + WALK_MID, t}, {t, r - WALK_MID}, {t, r + WALK_MID}};
                for (float[] sl : slots) {
                    float sx = sl[0], sz = sl[1];
                    if (sx * sx + sz * sz > 30f * 30f) continue;
                    if (Math.abs(sx) < 3.2f && sz > 1.5f && sz < 13f) continue;     // spawn lane
                    if (onRoadXZ(sx, sz) || !clearOfHouses(houses, sx, sz, 0.15f)) continue;
                    if (blocksAnyDoor(houses, sx, sz)) continue;                    // never plant in front of a doorway
                    boolean near = false;
                    for (float[] pp : placed) if (Math.abs(pp[0] - sx) < 3f && Math.abs(pp[1] - sz) < 3f) { near = true; break; }
                    if (near) continue;
                    placed.add(new float[]{sx, sz});
                    float pick = rc.nextFloat();
                    if (pick < 0.26f)      addLamp(L, sx, sz);                       // a lamp every few slots
                    else if (pick < 0.86f) trees.add(new float[]{sx, sz, 0.8f + rc.nextFloat() * 0.7f});   // street tree (varied)
                    // else: leave a gap so it isn't a solid wall of furniture
                }
            }
        }
        // 1b. front-garden hedges + a few shrubs around each house (vegetation mesh → non-colliding)
        for (float[] h : houses) {
            float cx = h[0], cz = h[1], hw = h[2] * 0.5f, hd = h[3] * 0.5f;
            int ds = (int) h[5];
            // a tidy hedge along the street-facing (door) edge for ~40% of houses, with a gap for the path
            if (rc.nextFloat() < 0.4f) {
                float nx = ds == 2 ? 1 : (ds == 3 ? -1 : 0), nz = ds == 0 ? 1 : (ds == 1 ? -1 : 0);
                float tx = nz, tz = nx;
                float front = (nx != 0 ? hw : hd) + 1.1f, span = (nx != 0 ? hd : hw) + 0.5f;
                for (float s = -span; s <= span + 0.01f; s += 0.7f) {
                    if (Math.abs(s) < 1.4f) continue;                           // wide path gap so even shop doors stay clear
                    float bx = cx + nx * front + tx * s, bz = cz + nz * front + tz * s;
                    if (bx * bx + bz * bz > 30f * 30f || onRoadXZ(bx, bz)) continue;
                    if (Math.abs(bx) < 3.4f && bz > 1.0f && bz < 13f) continue;
                    if (!clearOfHouses(houses, bx, bz, 0f)) continue;
                    trees.add(new float[]{bx, bz, 0.34f});                       // low hedge unit
                }
            }
            // a couple of scattered shrubs for the rest of the lot
            int n = 1 + rc.nextInt(2);
            for (int b = 0; b < n; b++) {
                float ang = rc.nextFloat() * 6.2832f, rr = Math.max(hw, hd) + 0.6f + rc.nextFloat() * 1.0f;
                float bx = cx + (float) Math.cos(ang) * rr, bz = cz + (float) Math.sin(ang) * rr;
                if (bx * bx + bz * bz > 30f * 30f || onRoadXZ(bx, bz)) continue;
                if (Math.abs(bx) < 3.4f && bz > 1.0f && bz < 13f) continue;
                if (!clearOfHouses(houses, bx, bz, 0.18f)) continue;
                if (blocksDoor(h, bx, bz)) continue;                        // keep the doorway clear
                trees.add(new float[]{bx, bz, 0.42f + rc.nextFloat() * 0.38f});
            }
        }
        this.treeList = trees.isEmpty() ? null : trees.toArray(new float[0][]);
        // 2. a bench out on the sidewalk in front of ~1 in 6 houses, backed onto the front (door) wall
        for (float[] h : houses) {
            if (rc.nextFloat() >= 0.16f) continue;
            int ds = (int) h[5]; float cx = h[0], cz = h[1], hw = h[2] * 0.5f, hd = h[3] * 0.5f, bx, bz;
            if (ds == 0)      { bx = cx; bz = cz + hd + 0.7f; }
            else if (ds == 1) { bx = cx; bz = cz - hd - 0.7f; }
            else if (ds == 2) { bx = cx + hw + 0.7f; bz = cz; }
            else              { bx = cx - hw - 0.7f; bz = cz; }
            float tnx = (ds == 0 || ds == 1) ? 1f : 0f, tnz = (ds == 2 || ds == 3) ? 1f : 0f;   // tangent along the wall
            for (float sgn : new float[]{1f, -1f}) {                        // sit the bench BESIDE the door, never across it
                float bxx = bx + tnx * sgn * 1.7f, bzz = bz + tnz * sgn * 1.7f;
                if (Math.abs(bxx) < 3.2f && bzz > 1.5f && bzz < 13f) continue;
                if (onRoadXZ(bxx, bzz) || !clearOfHouses(houses, bxx, bzz, 0.5f) || blocksDoor(h, bxx, bzz)) continue;
                addBench(L, bxx, bzz, ds); break;
            }
        }
        // 3. the reserved open lots form a market square: two stalls flanking a well
        addStall(L, MARKET_LOTS[0][0], MARKET_LOTS[0][1], 0.78f, 0.32f, 0.27f);   // red awning
        addWell (L, MARKET_LOTS[1][0], MARKET_LOTS[1][1]);
        addStall(L, MARKET_LOTS[2][0], MARKET_LOTS[2][1], 0.34f, 0.46f, 0.32f);   // green awning
        addFountain(L, 0f, 0f);                                                   // central plaza landmark
    }

    /** A stone fountain: an octagonal basin with water, a central pedestal, bowl and spout. */
    private static void addFountain(List<float[]> L, float x, float z) {
        float sr = 0.73f, sg = 0.72f, sb = 0.68f;
        L.add(new float[]{x, 0.27f, z, 2.4f, 0.54f, 2.4f, sr, sg, sb, 0f});              // basin (octagon)
        L.add(new float[]{x, 0.27f, z, 2.4f, 0.54f, 2.4f, sr - 0.05f, sg - 0.05f, sb - 0.05f, 45f});
        L.add(new float[]{x, 0.50f, z, 1.9f, 0.08f, 1.9f, 0.34f, 0.52f, 0.62f, 0f});     // water surface
        L.add(new float[]{x, 0.50f, z, 1.9f, 0.08f, 1.9f, 0.30f, 0.49f, 0.61f, 45f});
        L.add(new float[]{x, 0.86f, z, 0.46f, 1.1f, 0.46f, sr, sg, sb, 0f});             // pedestal
        L.add(new float[]{x, 1.42f, z, 0.95f, 0.16f, 0.95f, sr, sg, sb, 0f});            // upper bowl
        L.add(new float[]{x, 1.42f, z, 0.95f, 0.16f, 0.95f, sr - 0.04f, sg - 0.04f, sb - 0.04f, 45f});
        L.add(new float[]{x, 1.50f, z, 0.78f, 0.05f, 0.78f, 0.34f, 0.52f, 0.62f, 0f});   // bowl water
        L.add(new float[]{x, 1.74f, z, 0.16f, 0.55f, 0.16f, sr, sg, sb, 0f});            // spout
    }

    private static final float[][] MARKET_LOTS = {{-10f, -20f}, {0f, -20f}, {10f, -20f}};
    private static boolean isMarketLot(float gx, float gz) {
        for (float[] mlt : MARKET_LOTS) if (mlt[0] == gx && mlt[1] == gz) return true;
        return false;
    }

    /** True if (x,z) is well clear of every house footprint (plus margin) — used to keep props off buildings. */
    /** True if (x,z) sits in the approach + swing corridor in front of a house's door — i.e. it would block the entrance. */
    private static boolean blocksDoor(float[] h, float x, float z) {
        float cx = h[0], cz = h[1], hw = h[2] * 0.5f, hd = h[3] * 0.5f; int ds = (int) h[5];
        float nx = ds == 2 ? 1f : (ds == 3 ? -1f : 0f), nz = ds == 0 ? 1f : (ds == 1 ? -1f : 0f);
        float fx = cx + nx * hw, fz = cz + nz * hd;              // door-wall face centre
        float outD = (x - fx) * nx + (z - fz) * nz;             // metres out from the wall face (covers the swing arc)
        float lat  = (x - fx) * (-nz) + (z - fz) * nx;          // metres sideways along the wall
        return outD > -0.25f && outD < 2.3f && Math.abs(lat) < 1.4f;
    }
    private static boolean blocksAnyDoor(List<float[]> houses, float x, float z) {
        for (float[] h : houses) if (blocksDoor(h, x, z)) return true;
        return false;
    }

    private static boolean clearOfHouses(List<float[]> houses, float x, float z, float margin) {
        for (float[] h : houses)
            if (Math.abs(x - h[0]) < h[2] * 0.5f + margin && Math.abs(z - h[1]) < h[3] * 0.5f + margin) return false;
        return true;
    }

    private static void addClutter(List<float[]> L, Random r, float x, float z) {
        int k = r.nextInt(4);
        if (k == 0)      addBarrel(L, x, z);
        else if (k == 1) addCrate(L, r, x, z);
        else if (k == 2) addPlanter(L, x, z);
        else             addWoodpile(L, r, x, z);
    }

    // A wooden barrel: a square + a 45°-rotated twin make a round-ish 8-sided body, plus two iron hoops + a lid.
    private static void addBarrel(List<float[]> L, float x, float z) {
        L.add(new float[]{x, 0.42f, z, 0.42f, 0.66f, 0.42f, 0.46f, 0.33f, 0.21f, 0f});
        L.add(new float[]{x, 0.42f, z, 0.42f, 0.66f, 0.42f, 0.44f, 0.31f, 0.19f, 45f});
        L.add(new float[]{x, 0.25f, z, 0.50f, 0.07f, 0.50f, 0.24f, 0.22f, 0.20f, 0f});   // lower hoop
        L.add(new float[]{x, 0.60f, z, 0.50f, 0.07f, 0.50f, 0.24f, 0.22f, 0.20f, 0f});   // upper hoop
        L.add(new float[]{x, 0.78f, z, 0.34f, 0.05f, 0.34f, 0.40f, 0.28f, 0.18f, 0f});   // lid
    }

    private static void addCrate(List<float[]> L, Random r, float x, float z) {
        float yaw = r.nextInt(40) - 20f;
        L.add(new float[]{x, 0.28f, z, 0.54f, 0.54f, 0.54f, 0.52f, 0.40f, 0.26f, yaw});  // body
        L.add(new float[]{x, 0.56f, z, 0.58f, 0.06f, 0.58f, 0.40f, 0.30f, 0.20f, yaw});  // top rim
    }

    private static void addPlanter(List<float[]> L, float x, float z) {
        L.add(new float[]{x, 0.24f, z, 0.56f, 0.44f, 0.50f, 0.50f, 0.47f, 0.43f, 0f});   // stone box
        L.add(new float[]{x, 0.46f, z, 0.62f, 0.07f, 0.56f, 0.57f, 0.54f, 0.50f, 0f});   // rim
        L.add(new float[]{x, 0.60f, z, 0.50f, 0.22f, 0.44f, 0.30f, 0.45f, 0.28f, 0f});   // greenery
    }

    private static void addWoodpile(List<float[]> L, Random r, float x, float z) {
        float yaw = r.nextInt(30) - 15f;
        L.add(new float[]{x, 0.16f, z, 0.95f, 0.20f, 0.24f, 0.50f, 0.36f, 0.22f, yaw});
        L.add(new float[]{x, 0.37f, z, 0.95f, 0.20f, 0.24f, 0.46f, 0.33f, 0.20f, yaw});
        L.add(new float[]{x, 0.30f, z + 0.26f, 0.95f, 0.20f, 0.24f, 0.48f, 0.35f, 0.21f, yaw});
    }

    // A street lamp: stone base, slim post, a glowing lantern head with a little cap (head + cap sit overhead -> no collision).
    private static void addLamp(List<float[]> L, float x, float z) {
        L.add(new float[]{x, 0.13f, z, 0.34f, 0.26f, 0.34f, 0.20f, 0.20f, 0.22f, 0f});   // base
        L.add(new float[]{x, 1.55f, z, 0.12f, 2.60f, 0.12f, 0.23f, 0.23f, 0.26f, 0f});   // post
        L.add(new float[]{x, 2.92f, z, 0.30f, 0.10f, 0.30f, 0.20f, 0.20f, 0.22f, 0f});   // collar
        L.add(new float[]{x, 3.14f, z, 0.26f, 0.34f, 0.26f, 0.99f, 0.90f, 0.62f, 0f});   // warm lantern glass
        L.add(new float[]{x, 3.38f, z, 0.36f, 0.12f, 0.36f, 0.19f, 0.19f, 0.21f, 0f});   // cap
    }

    // A park bench: two solid end frames, a slatted seat and a two-rail backrest.
    private static void addBench(List<float[]> L, float x, float z, int dir) {
        boolean ax = (dir == 0 || dir == 1);                    // bench length runs along X for +z/-z fronts
        float ex = ax ? 0.62f : 0f, ez = ax ? 0f : 0.62f;       // end-frame offset along the length
        float esx = ax ? 0.10f : 0.50f, esz = ax ? 0.50f : 0.10f;
        L.add(new float[]{x - ex, 0.24f, z - ez, esx, 0.48f, esz, 0.34f, 0.25f, 0.16f, 0f});    // end frames
        L.add(new float[]{x + ex, 0.24f, z + ez, esx, 0.48f, esz, 0.34f, 0.25f, 0.16f, 0f});
        L.add(new float[]{x, 0.46f, z, ax ? 1.45f : 0.48f, 0.09f, ax ? 0.48f : 1.45f, 0.48f, 0.34f, 0.22f, 0f});  // seat
        float bX = 0f, bZ = 0f;                                 // backrest sits behind the seat (toward the wall)
        if (dir == 0) bZ = -0.18f; else if (dir == 1) bZ = 0.18f; else if (dir == 2) bX = -0.18f; else bX = 0.18f;
        float rw = ax ? 1.45f : 0.06f, rd = ax ? 0.06f : 1.45f;
        L.add(new float[]{x + bX, 0.68f, z + bZ, rw, 0.10f, rd, 0.48f, 0.34f, 0.22f, 0f});       // back rails
        L.add(new float[]{x + bX, 0.88f, z + bZ, rw, 0.10f, rd, 0.48f, 0.34f, 0.22f, 0f});
    }

    // A market stall: a wooden counter with four posts and a striped awning roof (awning overhead -> no collision).
    private static void addStall(List<float[]> L, float x, float z, float ar, float ag, float ab) {
        L.add(new float[]{x, 0.55f, z, 1.90f, 1.00f, 1.00f, 0.50f, 0.38f, 0.24f, 0f});   // counter
        L.add(new float[]{x, 1.08f, z, 2.00f, 0.08f, 1.10f, 0.42f, 0.32f, 0.20f, 0f});   // counter top
        L.add(new float[]{x - 0.9f, 1.55f, z + 0.45f, 0.08f, 1.50f, 0.08f, 0.40f, 0.30f, 0.20f, 0f});  // posts
        L.add(new float[]{x + 0.9f, 1.55f, z + 0.45f, 0.08f, 1.50f, 0.08f, 0.40f, 0.30f, 0.20f, 0f});
        L.add(new float[]{x - 0.9f, 1.55f, z - 0.45f, 0.08f, 1.50f, 0.08f, 0.40f, 0.30f, 0.20f, 0f});
        L.add(new float[]{x + 0.9f, 1.55f, z - 0.45f, 0.08f, 1.50f, 0.08f, 0.40f, 0.30f, 0.20f, 0f});
        L.add(new float[]{x, 2.18f, z, 2.10f, 0.10f, 1.30f, ar, ag, ab, 0f});            // awning
        L.add(new float[]{x, 2.30f, z, 0.55f, 0.12f, 1.30f, ar * 1.12f, ag * 1.12f, ab * 1.12f, 0f});  // awning crest
    }

    // A roofed stone well: a square stone kerb ring, two posts and a little gable roof (roof overhead -> no collision).
    private static void addWell(List<float[]> L, float x, float z) {
        L.add(new float[]{x, 0.40f, z + 0.55f, 1.20f, 0.80f, 0.18f, 0.55f, 0.54f, 0.51f, 0f});  // kerb +z
        L.add(new float[]{x, 0.40f, z - 0.55f, 1.20f, 0.80f, 0.18f, 0.55f, 0.54f, 0.51f, 0f});  // kerb -z
        L.add(new float[]{x + 0.55f, 0.40f, z, 0.18f, 0.80f, 1.20f, 0.52f, 0.51f, 0.48f, 0f});  // kerb +x
        L.add(new float[]{x - 0.55f, 0.40f, z, 0.18f, 0.80f, 1.20f, 0.52f, 0.51f, 0.48f, 0f});  // kerb -x
        L.add(new float[]{x - 0.50f, 1.50f, z, 0.10f, 1.40f, 0.10f, 0.46f, 0.33f, 0.21f, 0f});  // post
        L.add(new float[]{x + 0.50f, 1.50f, z, 0.10f, 1.40f, 0.10f, 0.46f, 0.33f, 0.21f, 0f});  // post
        L.add(new float[]{x, 1.95f, z, 0.10f, 0.10f, 0.70f, 0.30f, 0.24f, 0.20f, 0f});          // crossbeam
        L.add(new float[]{x, 2.25f, z, 1.50f, 0.12f, 0.95f, 0.34f, 0.27f, 0.22f, 0f});          // roof
    }

    /** A four-walled building with one doorway (doorSide 0=+z,1=-z,2=+x,3=-x), a roof, and a
     *  roof style: 0 = flat, 1 = parapet wall, 2 = rooftop unit + antenna. Records an openable door. */
    private static void addBuilding(List<float[]> L, List<float[]> doors, float cx, float cz, float w, float d, float h,
                                    int doorSide, int roofStyle, float r, float g, float b) {
        addBuilding(L, doors, cx, cz, w, d, h, doorSide, roofStyle, r, g, b, -1);   // -1 chimney = auto (roofStyle!=0)
    }

    private static void addBuilding(List<float[]> L, List<float[]> doors, float cx, float cz, float w, float d, float h,
                                    int doorSide, int roofStyle, float r, float g, float b, int chimney) {
        addBuilding(L, doors, cx, cz, w, d, h, doorSide, roofStyle, r, g, b, chimney,
                    1.9f, 2.3f, 0.46f, 0.30f, 0.17f);   // default door size + brown leaf
    }

    private static void addBuilding(List<float[]> L, List<float[]> doors, float cx, float cz, float w, float d, float h,
                                    int doorSide, int roofStyle, float r, float g, float b, int chimney,
                                    float doorW, float doorH, float dr, float dg, float db) {
        addBuilding(L, doors, cx, cz, w, d, h, doorSide, roofStyle, r, g, b, chimney, doorW, doorH, dr, dg, db, 0, 0);
    }

    private static void addBuilding(List<float[]> L, List<float[]> doors, float cx, float cz, float w, float d, float h,
                                    int doorSide, int roofStyle, float r, float g, float b, int chimney,
                                    float doorW, float doorH, float dr, float dg, float db, int doorStyle, int material) {
        float t = 0.3f;
        doorW = clamp(doorW, 0.8f, Math.min(w, d) - 0.6f);     // keep the gap inside the wall
        doorH = clamp(doorH, 1.6f, h - 0.3f);                  // keep the lintel under the eave
        addWall(L, cx, cz + d * 0.5f, w, t, h, doorSide == 0, doorW, doorH, true, r, g, b, material);   // +z
        addWall(L, cx, cz - d * 0.5f, w, t, h, doorSide == 1, doorW, doorH, true, r, g, b, material);   // -z
        addWall(L, cx + w * 0.5f, cz, t, d, h, doorSide == 2, doorW, doorH, false, r, g, b, material);  // +x
        addWall(L, cx - w * 0.5f, cz, t, d, h, doorSide == 3, doorW, doorH, false, r, g, b, material);  // -x
        L.add(new float[]{cx, h + 0.15f, cz, w + t, 0.3f, d + t, r * 0.9f, g * 0.9f, b * 0.95f}); // roof
        float dcx, dcz; int axis;                          // openable door leaf in the gap
        if (doorSide == 0)      { dcx = cx; dcz = cz + d * 0.5f; axis = 0; }
        else if (doorSide == 1) { dcx = cx; dcz = cz - d * 0.5f; axis = 0; }
        else if (doorSide == 2) { dcx = cx + w * 0.5f; dcz = cz; axis = 1; }
        else                    { dcx = cx - w * 0.5f; dcz = cz; axis = 1; }
        float swing = (doorSide == 1 || doorSide == 2) ? -1f : 1f;   // sign that makes the leaf swing OUTWARD (away from interior furniture)
        doors.add(new float[]{dcx, doorH * 0.5f, dcz, doorW * 0.5f - 0.05f, doorH * 0.5f, 0.07f, axis, 1f,
                dr, dg, db, doorStyle, swing});
        // door surround (architrave: jambs + lintel), slightly proud, in a light stone trim — frames the opening
        float fr = 0.84f, fg = 0.82f, fb = 0.76f, jb = 0.13f, pr = 0.10f, fy = doorH * 0.5f;
        float nnx = doorSide == 2 ? 1 : (doorSide == 3 ? -1 : 0), nnz = doorSide == 0 ? 1 : (doorSide == 1 ? -1 : 0);
        if (axis == 0) {
            float fcz = dcz + nnz * (t * 0.5f - 0.02f);
            L.add(new float[]{dcx - (doorW * 0.5f + jb * 0.5f), fy, fcz, jb, doorH + 0.06f, t + pr, fr, fg, fb});
            L.add(new float[]{dcx + (doorW * 0.5f + jb * 0.5f), fy, fcz, jb, doorH + 0.06f, t + pr, fr, fg, fb});
            L.add(new float[]{dcx, doorH + 0.09f, fcz, doorW + jb * 2f, 0.18f, t + pr, fr, fg, fb});
            L.add(new float[]{dcx, 0.02f, fcz, doorW + jb * 2f, 0.04f, t + pr + 0.10f, 0.55f, 0.53f, 0.49f}); // low stone doorstep
        } else {
            float fcx = dcx + nnx * (t * 0.5f - 0.02f);
            L.add(new float[]{fcx, fy, dcz - (doorW * 0.5f + jb * 0.5f), t + pr, doorH + 0.06f, jb, fr, fg, fb});
            L.add(new float[]{fcx, fy, dcz + (doorW * 0.5f + jb * 0.5f), t + pr, doorH + 0.06f, jb, fr, fg, fb});
            L.add(new float[]{fcx, doorH + 0.09f, dcz, t + pr, 0.18f, doorW + jb * 2f, fr, fg, fb});
            L.add(new float[]{fcx, 0.02f, dcz, t + pr + 0.10f, 0.04f, doorW + jb * 2f, 0.55f, 0.53f, 0.49f}); // low stone doorstep
        }
        if (doorStyle == 3) {                              // striped shop awning over the storefront door
            float awY = Math.min(doorH + 0.22f, h - 0.15f), aw = doorW + 0.5f, proj = 0.85f;
            for (int s = 0; s < 3; s++) {
                boolean red = (s % 2 == 0);
                float cr = red ? 0.72f : 0.90f, cg = red ? 0.27f : 0.86f, cb = red ? 0.23f : 0.78f;
                float out = t * 0.5f + (s + 0.5f) * proj / 3f, yy = awY - s * 0.05f;
                if (axis == 0) L.add(new float[]{dcx, yy, dcz + nnz * out, aw, 0.07f, proj / 3f + 0.02f, cr, cg, cb});
                else           L.add(new float[]{dcx + nnx * out, yy, dcz, proj / 3f + 0.02f, 0.07f, aw, cr, cg, cb});
            }
            float vy = awY - 0.16f, vo = t * 0.5f + proj;   // front valance (hanging edge)
            if (axis == 0) L.add(new float[]{dcx, vy, dcz + nnz * vo, aw, 0.20f, 0.05f, 0.72f, 0.27f, 0.23f});
            else           L.add(new float[]{dcx + nnx * vo, vy, dcz, 0.05f, 0.20f, aw, 0.72f, 0.27f, 0.23f});
        }
        boolean chim = chimney < 0 ? (roofStyle != 0) : (chimney != 0);
        if (chim) {                                        // brick chimney poking clearly above the gable ridge
            L.add(new float[]{cx + w * 0.28f, h + 1.5f, cz - d * 0.28f, 0.34f, 2.2f, 0.34f, 0.55f, 0.30f, 0.22f, 0f, 0f, 1f}); // brick chimney
        }
    }

    private static void addWall(List<float[]> L, float cx, float cz, float sx, float sz, float h,
                                boolean door, float doorW, float doorH, boolean spanX,
                                float r, float g, float b, int material) {
        float m = material;                            // facade-material tag travels in slot 11 (yaw=0, render-skip=0 first)
        if (!door) {
            L.add(new float[]{cx, h * 0.5f, cz, sx, h, sz, r, g, b, 0f, 0f, m});
            return;
        }
        if (spanX) {                                   // wall runs along X; gap in the middle
            float segW = (sx - doorW) * 0.5f;
            L.add(new float[]{cx - (doorW * 0.5f + segW * 0.5f), h * 0.5f, cz, segW, h, sz, r, g, b, 0f, 0f, m});
            L.add(new float[]{cx + (doorW * 0.5f + segW * 0.5f), h * 0.5f, cz, segW, h, sz, r, g, b, 0f, 0f, m});
            L.add(new float[]{cx, doorH + (h - doorH) * 0.5f, cz, doorW, h - doorH, sz, r, g, b, 0f, 0f, m}); // lintel
        } else {                                       // wall runs along Z
            float segD = (sz - doorW) * 0.5f;
            L.add(new float[]{cx, h * 0.5f, cz - (doorW * 0.5f + segD * 0.5f), sx, h, segD, r, g, b, 0f, 0f, m});
            L.add(new float[]{cx, h * 0.5f, cz + (doorW * 0.5f + segD * 0.5f), sx, h, segD, r, g, b, 0f, 0f, m});
            L.add(new float[]{cx, doorH + (h - doorH) * 0.5f, cz, sx, h - doorH, doorW, r, g, b, 0f, 0f, m}); // lintel
        }
    }

    // --- gable roofs + windows (better-modelled houses) ---

    /** Baked pitched gable roof over every house (one draw call). */
    private float[] makeRoofMesh() {
        float[] d = new float[houseRects.size() * 6 * 3 * 8 + 64];
        int o = 0, gi = 0;
        roofGroups = new float[houseRects.size()][];
        for (float[] hh : houseRects) {
            int start = o / 8;
            o = roofPrism(d, o, hh[0], hh[1], hh[2], hh[3], hh[4], hh[9], hh[10]);     // cx cz w d h pitch overhang
            roofGroups[gi++] = new float[]{start, o / 8 - start, hh[6], hh[7], hh[8]}; // firstVert, count, roof rgb
        }
        roofVerts = o / 8;
        return java.util.Arrays.copyOf(d, o);
    }

    /** A closed gable roof: two sloped slabs + two triangular gable ends, ridge along the longer axis. */
    private static int roofPrism(float[] d, int o, float cx, float cz, float w, float dd, float h, float pitch, float overhang) {
        float ov = overhang < 0f ? 0.32f : overhang, baseY = h + 0.30f;
        float rh = pitch < 0f ? clamp(0.5f * Math.min(w, dd), 0.8f, 1.7f) : Math.max(0.05f, pitch);
        float ridgeY = baseY + rh;
        if (w >= dd) {                                   // ridge along X
            float hw = w * 0.5f + ov, hd = dd * 0.5f + ov;
            float[] R1 = {cx - hw, ridgeY, cz},     R2 = {cx + hw, ridgeY, cz};
            float[] E1 = {cx - hw, baseY, cz + hd}, E2 = {cx + hw, baseY, cz + hd};
            float[] F1 = {cx - hw, baseY, cz - hd}, F2 = {cx + hw, baseY, cz - hd};
            float inv = 1f / (float) Math.sqrt(hd * hd + rh * rh);
            o = tri(d, o, R1, E1, E2, 0f, hd * inv, rh * inv);
            o = tri(d, o, R1, E2, R2, 0f, hd * inv, rh * inv);
            o = tri(d, o, R1, R2, F2, 0f, hd * inv, -rh * inv);
            o = tri(d, o, R1, F2, F1, 0f, hd * inv, -rh * inv);
            o = tri(d, o, R2, E2, F2, 1f, 0f, 0f);
            o = tri(d, o, R1, F1, E1, -1f, 0f, 0f);
        } else {                                         // ridge along Z
            float hw = w * 0.5f + ov, hd = dd * 0.5f + ov;
            float[] R1 = {cx, ridgeY, cz - hd},     R2 = {cx, ridgeY, cz + hd};
            float[] E1 = {cx + hw, baseY, cz - hd}, E2 = {cx + hw, baseY, cz + hd};
            float[] F1 = {cx - hw, baseY, cz - hd}, F2 = {cx - hw, baseY, cz + hd};
            float inv = 1f / (float) Math.sqrt(hw * hw + rh * rh);
            o = tri(d, o, R1, E1, E2, hw * inv, rh * inv, 0f);
            o = tri(d, o, R1, E2, R2, hw * inv, rh * inv, 0f);
            o = tri(d, o, R1, R2, F2, -hw * inv, rh * inv, 0f);
            o = tri(d, o, R1, F2, F1, -hw * inv, rh * inv, 0f);
            o = tri(d, o, R2, E2, F2, 0f, 0f, 1f);
            o = tri(d, o, R1, F1, E1, 0f, 0f, -1f);
        }
        return o;
    }

    private static int tri(float[] d, int o, float[] a, float[] b, float[] c, float nx, float ny, float nz) {
        o = put(d, o, a[0], a[1], a[2], nx, ny, nz, a[0] * 0.35f, a[2] * 0.35f);
        o = put(d, o, b[0], b[1], b[2], nx, ny, nz, b[0] * 0.35f, b[2] * 0.35f);
        o = put(d, o, c[0], c[1], c[2], nx, ny, nz, c[0] * 0.35f, c[2] * 0.35f);
        return o;
    }

    /** Baked window panes + sill ledges on every wall (one draw call each); skips the doorway. */
    private float[] makeWindowMesh() {
        float[] wd = new float[1200000];   // headroom for dense windows on a large custom village
        float[] td = new float[1600000];
        int[] off = {0, 0};
        Random rng = new Random(202);   // irregular per-house omission so no two facades match
        java.util.List<float[]> winG = new java.util.ArrayList<float[]>();
        for (float[] hh : houseRects) {
            float cx = hh[0], cz = hh[1], w = hh[2], dd = hh[3], h = hh[4];
            int ds = (int) hh[5], dens = (int) hh[11], storeys = Math.round(hh[23]);
            float wsc = hh[12], foundH = hh[16];
            if (dens == 0) continue;                       // "none" -> this house has no windows
            int wstart = off[0] / 8;
            wallWindows(wd, td, off, cx, cz + dd * 0.5f, w, h, 0, 1, ds == 0, rng, dens, wsc, storeys, foundH);
            wallWindows(wd, td, off, cx, cz - dd * 0.5f, w, h, 0, -1, ds == 1, rng, dens, wsc, storeys, foundH);
            wallWindows(wd, td, off, cx + w * 0.5f, cz, dd, h, 1, 1, ds == 2, rng, dens, wsc, storeys, foundH);
            wallWindows(wd, td, off, cx - w * 0.5f, cz, dd, h, 1, -1, ds == 3, rng, dens, wsc, storeys, foundH);
            int wcount = off[0] / 8 - wstart;
            if (wcount > 0) winG.add(new float[]{wstart, wcount, hh[13], hh[14], hh[15]});  // glass tint
        }
        winGroups = winG.toArray(new float[0][]);
        windowVerts = off[0] / 8;
        trimVerts = off[1] / 8;
        trimData = java.util.Arrays.copyOf(td, off[1]);
        return java.util.Arrays.copyOf(wd, off[0]);
    }

    /** Per-house foundation plinth, eave trim band and storey divider bands (baked, world-space, no collision).
     *  Each band is a perimeter ring hugging the four walls (never a solid slab) so house interiors stay clear. */
    private float[] makeBandMesh() {
        float[] d = new float[houseRects.size() * 40 * 36 * 8 + 1024];
        int o = 0;
        java.util.List<float[]> bandG = new java.util.ArrayList<float[]>();
        for (float[] hh : houseRects) {
            float cx = hh[0], cz = hh[1], w = hh[2], dd = hh[3], h = hh[4];
            float foundH = hh[16];
            float tr = hh[20], tg = hh[21], tb = hh[22];
            int storeys = Math.round(hh[23]);
            if (storeys < 1) storeys = 1;
            if (storeys > 8) storeys = 8;
            if (foundH > 0.01f) {                              // stone plinth poking proud of the walls
                int s = o / 8;
                o = bandRing(d, o, cx, foundH * 0.5f, cz, w, dd, foundH * 0.5f, 0.07f);
                bandG.add(new float[]{s, o / 8 - s, hh[17], hh[18], hh[19]});
            }
            if (tr >= 0f) {                                    // painted eave trim band just under the roof
                int s = o / 8;
                o = bandRing(d, o, cx, Math.max(0.2f, h - 0.14f), cz, w, dd, 0.11f, 0.05f);
                bandG.add(new float[]{s, o / 8 - s, tr, tg, tb});
            }
            if (storeys >= 2) {                                // horizontal storey divider lines
                int s = o / 8;
                for (int k = 1; k < storeys; k++) o = bandRing(d, o, cx, h * k / (float) storeys, cz, w, dd, 0.06f, 0.04f);
                float br = tr >= 0f ? tr : 0.86f, bg = tr >= 0f ? tg : 0.83f, bb = tr >= 0f ? tb : 0.77f;
                bandG.add(new float[]{s, o / 8 - s, br, bg, bb});
            }
        }
        bandGroups = bandG.isEmpty() ? null : bandG.toArray(new float[0][]);
        bandVerts = o / 8;
        return java.util.Arrays.copyOf(d, o);
    }

    /** Four thin boxes wrapping the 0.30 m walls of a w x dd house at height cy, sticking `proud` past each face. */
    private static int bandRing(float[] d, int o, float cx, float cy, float cz, float w, float dd, float halfH, float proud) {
        float hw = w * 0.5f, hd = dd * 0.5f, perp = 0.15f + proud;
        o = box6(d, o, cx, cy, cz + hd, hw + proud, halfH, perp);    // +z face
        o = box6(d, o, cx, cy, cz - hd, hw + proud, halfH, perp);    // -z face
        o = box6(d, o, cx + hw, cy, cz, perp, halfH, hd + proud);    // +x face
        o = box6(d, o, cx - hw, cy, cz, perp, halfH, hd + proud);    // -x face
        return o;
    }

    // --- walk-in interiors: a furnished ground-floor room per house, baked into one mesh + collision proxies ---

    /** Furnish every house once: store visual pieces for the baked mesh, add solid colliders to L. */
    private void furnishAll(List<float[]> L, List<float[]> houses) {
        interiorPieces = new java.util.ArrayList<float[]>();
        for (float[] h : houses) furnishHouse(L, h[0], h[1], h[2], h[3], h[4], (int) h[5]);
    }

    /** A cosy ground-floor room: wood floor, ceiling, rug, hanging lamp, a bed, a wardrobe and a table + chairs,
     *  laid out away from the doorway. Solid pieces also get a non-rendered collision box in L. */
    private void furnishHouse(List<float[]> L, float cx, float cz, float w, float dd, float h, int ds) {
        float inx = w * 0.5f - 0.28f, inz = dd * 0.5f - 0.28f;       // interior half-extents (for furniture)
        if (inx < 0.7f || inz < 0.7f) return;                        // too small to furnish
        float ceilH = Math.min(2.55f, h - 0.22f);
        if (ceilH < 2.0f) return;                                    // too low to furnish sanely
        long seed = ((long) Float.floatToIntBits(cx)) * 73856093L ^ ((long) Float.floatToIntBits(cz)) * 19349663L;
        Random r = new Random(seed);
        float dox = (ds == 2 ? 1 : (ds == 3 ? -1 : 0)), doz = (ds == 0 ? 1 : (ds == 1 ? -1 : 0));   // door points OUT
        // floor + ceiling sized to the wall inner face (close the seam to the walls)
        float slx = w * 0.5f - 0.13f, slz = dd * 0.5f - 0.13f;
        addPiece(L, cx, 0.012f, cz, slx * 2f, 0.05f, slz * 2f, 0, false);                      // wood floor (top ~0.037)
        addPiece(L, cx, ceilH, cz, slx * 2f, 0.10f, slz * 2f, 1, false);                       // ceiling
        addPiece(L, cx, 0.058f, cz, Math.min(1.6f, inx * 1.5f), 0.03f, Math.min(1.1f, inz * 1.5f), 4, false); // rug
        addPiece(L, cx, ceilH - 0.18f, cz, 0.06f, 0.30f, 0.06f, 6, false);                     // lamp cord
        addPiece(L, cx, ceilH - 0.40f, cz, 0.34f, 0.16f, 0.34f, 7, false);                     // lampshade
        // small entry passage just inside the doorway that furniture must keep clear (so the player can always pass)
        float lane = 1.3f, pass = 1.05f;
        float koX = cx + dox * (inx - lane * 0.5f), koZ = cz + doz * (inz - lane * 0.5f);
        float koW = dox != 0 ? lane : pass, koD = doz != 0 ? lane : pass;
        java.util.List<float[]> occ = new java.util.ArrayList<float[]>();        // placed footprints, for later furniture
        occ.add(new float[]{koX, koZ, koW, koD});
        float[] sgx = {-1, 1, -1, 1}, sgz = {-1, -1, 1, 1};         // corners: 0=(-,-) 1=(+,-) 2=(-,+) 3=(+,+)
        // BED: corner farthest from the door
        int bedC = 0; float bestB = -1e9f;
        for (int ci = 0; ci < 4; ci++) { float sc = -(sgx[ci] * dox + sgz[ci] * doz) + (r.nextFloat() - 0.5f) * 0.3f; if (sc > bestB) { bestB = sc; bedC = ci; } }
        boolean bedAlongX = w >= dd;
        float bedW = bedAlongX ? Math.min(1.7f, inx * 1.35f) : 0.9f;
        float bedD = bedAlongX ? 0.9f : Math.min(1.7f, inz * 1.35f);
        float bx = cx + sgx[bedC] * (inx - bedW * 0.5f - 0.04f), bz = cz + sgz[bedC] * (inz - bedD * 0.5f - 0.04f);
        boolean bedPlaced = !aabbOverlap(bx, bz, bedW, bedD, koX, koZ, koW, koD);
        if (bedPlaced) {
            addPiece(L, bx, 0.22f, bz, bedW, 0.30f, bedD, 2, true);                            // frame (solid)
            addPiece(L, bx, 0.42f, bz, bedW - 0.08f, 0.14f, bedD - 0.08f, 4, false);           // duvet
            addPiece(L, bx - sgx[bedC] * bedW * 0.30f, 0.46f, bz - sgz[bedC] * bedD * 0.30f, 0.40f, 0.12f, 0.28f, 5, false); // pillow
            occ.add(new float[]{bx, bz, bedW, bedD});
            // nightstand tucked beside the head of the bed (toward room centre), with a tiny lamp
            float nsx = bx - sgx[bedC] * (bedW * 0.5f + 0.22f), nsz = bz - sgz[bedC] * (bedD * 0.5f - 0.10f);
            if (Math.abs(nsx - cx) < inx - 0.2f && Math.abs(nsz - cz) < inz - 0.2f && clearSpot(occ, nsx, nsz, 0.34f, 0.30f)) {
                addPiece(L, nsx, 0.22f, nsz, 0.34f, 0.40f, 0.30f, 2, true);
                addPiece(L, nsx, 0.50f, nsz, 0.16f, 0.18f, 0.16f, 7, false);     // little lamp
                occ.add(new float[]{nsx, nsz, 0.34f, 0.30f});
            }
        }
        // WARDROBE: prefer the diagonal corner, else an adjacent one — never blocking the entry, never into the bed
        float wardH = Math.min(1.70f, ceilH - 0.15f);
        for (int cand : new int[]{bedC ^ 3, bedC ^ 1, bedC ^ 2}) {
            float wx = cx + sgx[cand] * (inx - 0.30f), wz = cz + sgz[cand] * (inz - 0.24f);
            if (aabbOverlap(wx, wz, 0.70f, 0.50f, koX, koZ, koW, koD)) continue;               // blocks the door lane
            if (bedPlaced && aabbOverlap(wx, wz, 0.70f, 0.50f, bx, bz, bedW, bedD)) continue;  // into the bed
            addPiece(L, wx, wardH * 0.5f + 0.04f, wz, 0.62f, wardH, 0.42f, 3, true);
            occ.add(new float[]{wx, wz, 0.62f, 0.42f});
            break;
        }
        // TABLE + chairs: centred, pushed away from the door, only if the room is roomy and the spot is clear
        if (inx > 1.1f && inz > 1.05f) {
            float tx = cx - dox * (inx * 0.35f), tz = cz - doz * (inz * 0.35f);
            if (!aabbOverlap(tx, tz, 0.95f, 0.75f, koX, koZ, koW, koD)
             && !(bedPlaced && aabbOverlap(tx, tz, 0.95f, 0.75f, bx, bz, bedW, bedD))) {
                addPiece(L, tx, 0.70f, tz, 0.80f, 0.07f, 0.60f, 2, true);                       // table top (solid)
                for (int lg = 0; lg < 4; lg++)
                    addPiece(L, tx + ((lg & 1) == 0 ? -0.32f : 0.32f), 0.35f, tz + ((lg & 2) == 0 ? -0.24f : 0.24f), 0.06f, 0.66f, 0.06f, 3, false);
                addPiece(L, tx - 0.52f, 0.27f, tz, 0.38f, 0.46f, 0.38f, 2, false);             // chair (sits on the floor)
                addPiece(L, tx + 0.52f, 0.27f, tz, 0.38f, 0.46f, 0.38f, 2, false);
                occ.add(new float[]{tx, tz, 1.65f, 0.75f});                                     // table + both chairs
            }
        }

        // --- extra furnishings: sofa, bookshelf, potted plant, wall pictures ---
        // SOFA: against a wall, facing the room, low and upholstered
        for (int side = 0; side < 4 && inx > 1.0f && inz > 1.0f; side++) {
            boolean alongX = (side < 2);
            float sw = alongX ? Math.min(1.5f, inx * 1.3f) : 0.5f;
            float sd = alongX ? 0.5f : Math.min(1.5f, inz * 1.3f);
            float sx2 = cx + (alongX ? 0f : (side == 2 ? -1f : 1f) * (inx - sd * 0.5f - 0.02f));
            float sz2 = cz + (alongX ? (side == 0 ? -1f : 1f) * (inz - sd * 0.5f - 0.02f) : 0f);
            if (!clearSpot(occ, sx2, sz2, sw, sd)) continue;
            addPiece(L, sx2, 0.22f, sz2, sw, 0.30f, sd, 8, true);                               // seat
            float bdx2 = alongX ? 0f : (side == 2 ? -1f : 1f), bdz2 = alongX ? (side == 0 ? -1f : 1f) : 0f;
            addPiece(L, sx2 + bdx2 * (sd * 0.5f - 0.08f), 0.46f, sz2 + bdz2 * (sd * 0.5f - 0.08f),
                    alongX ? sw : 0.16f, 0.34f, alongX ? 0.16f : sd, 8, false);                 // backrest
            occ.add(new float[]{sx2, sz2, sw, sd});
            break;
        }
        // BOOKSHELF: a tall thin unit in a free corner, with a colourful book band
        for (int ci = 0; ci < 4; ci++) {
            float shx = cx + sgx[ci] * (inx - 0.22f), shz = cz + sgz[ci] * (inz - 0.16f);
            if (!clearSpot(occ, shx, shz, 0.6f, 0.34f)) continue;
            float shH = Math.min(1.85f, ceilH - 0.2f);
            addPiece(L, shx, shH * 0.5f + 0.04f, shz, 0.56f, shH, 0.3f, 3, true);               // case
            addPiece(L, shx, shH * 0.62f, shz, 0.48f, 0.16f, 0.24f, 9, false);                  // books
            addPiece(L, shx, shH * 0.40f, shz, 0.48f, 0.16f, 0.24f, 9, false);
            occ.add(new float[]{shx, shz, 0.6f, 0.34f});
            break;
        }
        // POTTED PLANT: a small green accent in any remaining corner
        for (int ci = 3; ci >= 0; ci--) {
            float pxp = cx + sgx[ci] * (inx - 0.20f), pzp = cz + sgz[ci] * (inz - 0.20f);
            if (!clearSpot(occ, pxp, pzp, 0.32f, 0.32f)) continue;
            addPiece(L, pxp, 0.15f, pzp, 0.26f, 0.30f, 0.26f, 2, false);                        // pot
            addPiece(L, pxp, 0.46f, pzp, 0.40f, 0.42f, 0.40f, 10, false);                       // foliage
            occ.add(new float[]{pxp, pzp, 0.32f, 0.32f});
            break;
        }
        // WALL PICTURES: two framed pictures flat on the back wall (decor only, no collision)
        float pwx = cx - dox * (inx - 0.03f), pwz = cz - doz * (inz - 0.03f);
        boolean wallAlongX = (doz != 0);   // back wall runs along X when the door faces +/-Z
        for (int pi = -1; pi <= 1; pi += 2) {
            float ox = wallAlongX ? pi * inx * 0.4f : 0f, oz = wallAlongX ? 0f : pi * inz * 0.4f;
            addPiece(L, pwx + ox, ceilH * 0.62f, pwz + oz, wallAlongX ? 0.34f : 0.04f, 0.28f, wallAlongX ? 0.04f : 0.34f, 11, false);
        }
    }

    /** True if two XZ footprints (centre + full extents) overlap — used to keep furniture off the door + each other. */
    private static boolean aabbOverlap(float ax, float az, float aw, float ad, float bx, float bz, float bw, float bd) {
        return Math.abs(ax - bx) < (aw + bw) * 0.5f && Math.abs(az - bz) < (ad + bd) * 0.5f;
    }

    /** True if a footprint clears every already-placed furniture footprint (with a small breathing margin). */
    private static boolean clearSpot(java.util.List<float[]> occ, float x, float z, float w, float d) {
        for (float[] o : occ) if (aabbOverlap(x, z, w + 0.1f, d + 0.1f, o[0], o[1], o[2], o[3])) return false;
        return true;
    }

    private void addPiece(List<float[]> L, float x, float y, float z, float w, float h, float d, int colorIdx, boolean solid) {
        interiorPieces.add(new float[]{x, y, z, w, h, d, colorIdx});
        if (solid) L.add(new float[]{x, y, z, w, h, d, 0.5f, 0.5f, 0.5f, 0f, 1f});   // collision-only proxy (idx 10 = no-render)
    }

    /** Bake every interior piece into one mesh, grouped by material colour for cheap batched draws (~8 calls total). */
    private float[] makeInteriorMesh() {
        if (interiorPieces == null || interiorPieces.isEmpty()) { interiorGroups = null; interiorVerts = 0; return new float[0]; }
        float[] d = new float[interiorPieces.size() * 36 * 8 + 64];
        int o = 0;
        java.util.List<float[]> groups = new java.util.ArrayList<float[]>();
        for (int ci = 0; ci < INT_COLS.length; ci++) {
            int start = o / 8;
            for (float[] p : interiorPieces) {
                if ((int) p[6] != ci) continue;
                o = box6(d, o, p[0], p[1], p[2], p[3] * 0.5f, p[4] * 0.5f, p[5] * 0.5f);
            }
            int cnt = o / 8 - start;
            if (cnt > 0) groups.add(new float[]{start, cnt, INT_COLS[ci][0], INT_COLS[ci][1], INT_COLS[ci][2]});
        }
        interiorGroups = groups.toArray(new float[0][]);
        interiorVerts = o / 8;
        return java.util.Arrays.copyOf(d, o);
    }

    /** Warm wood planks with grain — used for floors + furniture (tinted per material via uColor). */
    private static Bitmap makeWoodBitmap() {
        int N = 256;
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFF2EEE6);                                    // light near-white base so uColor sets the real tone (incl. white ceiling/fabric)
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(91);
        for (int plank = 1; plank < 6; plank++) {                  // plank seams (subtle)
            float y = plank * N / 6f;
            p.setColor(0x22000000); c.drawRect(0, y, N, y + 2.0f, p);
        }
        for (int k = 0; k < 1100; k++) {                           // fine grain streaks (subtle)
            int v = rnd.nextInt(30);
            p.setColor((0x16 << 24) | ((0xCC - v) << 16) | ((0xBE - v) << 8) | (0xA6 - v));
            float x = rnd.nextInt(N), y = rnd.nextInt(N);
            c.drawRect(x, y, x + 8 + rnd.nextInt(20), y + 1, p);
        }
        return b;
    }

    // off[0] = window-mesh cursor, off[1] = sill-mesh cursor.
    private static void wallWindows(float[] wd, float[] td, int[] off, float a, float b, float span, float h, int axis, int sign, boolean doorWall, Random rng, int dens, float wsc, int storeys, float foundH) {
        // One row of windows per FLOOR, centred in its storey -> the building visibly reads as having Etagen.
        wsc = clamp(wsc <= 0f ? 1f : wsc, 0.5f, 1.6f);
        if (storeys < 1) storeys = 1;
        if (storeys > 8) storeys = 8;
        float floorH = h / storeys;
        float winW = 0.85f * wsc, winH = Math.min(1.05f * wsc, floorH * 0.62f), proud = 0.18f;
        float colDiv, omit;                                          // density: 1 sparse, 2 normal, 3 dense
        if (dens == 1)      { colDiv = 2.3f; omit = 0.10f; }
        else if (dens >= 3) { colDiv = 1.25f; omit = 0.04f; }
        else                { colDiv = 1.7f; omit = 0.10f; }
        int cols = Math.max(1, (int) ((span - 0.6f) / colDiv));
        float colGap = span / cols;
        for (int fi = 0; fi < storeys; fi++) {
            float wy = floorH * (fi + 0.5f);                         // centre of this floor
            if (wy + winH * 0.5f > h - 0.2f) continue;               // top floor clears the eave
            if (wy - winH * 0.5f < foundH + 0.12f) wy = foundH + 0.12f + winH * 0.5f;   // clear the plinth
            for (int ci = 0; ci < cols; ci++) {
                float t = -span * 0.5f + colGap * (ci + 0.5f);       // offset along the wall
                if (doorWall && fi == 0 && Math.abs(t) < 1.4f * Math.max(1f, wsc)) continue;   // ground-floor doorway
                if (rng.nextFloat() < omit) continue;                // drop a few so it isn't stamped
                off[0] = windowQuad(wd, off[0], a, b, t, wy, winW, winH, axis, sign, proud);
                float sy = wy - winH * 0.5f - 0.04f;                 // sill ledge just below the pane
                if (axis == 0) off[1] = box6(td, off[1], a + t, sy, b + sign * 0.11f, winW * 0.5f + 0.1f, 0.05f, 0.11f);
                else           off[1] = box6(td, off[1], a + sign * 0.11f, sy, b + t, 0.11f, 0.05f, winW * 0.5f + 0.1f);
            }
        }
    }

    private static int windowQuad(float[] d, int o, float a, float b, float t, float wy, float winW, float winH, int axis, int sign, float proud) {
        float y0 = wy - winH * 0.5f, y1 = wy + winH * 0.5f;
        if (axis == 0) {
            float zf = b + sign * proud, x0 = a + t - winW * 0.5f, x1 = a + t + winW * 0.5f;
            o = put(d, o, x0, y0, zf, 0, 0, sign, 0, 1); o = put(d, o, x1, y0, zf, 0, 0, sign, 1, 1);
            o = put(d, o, x1, y1, zf, 0, 0, sign, 1, 0); o = put(d, o, x0, y0, zf, 0, 0, sign, 0, 1);
            o = put(d, o, x1, y1, zf, 0, 0, sign, 1, 0); o = put(d, o, x0, y1, zf, 0, 0, sign, 0, 0);
        } else {
            float xf = a + sign * proud, z0 = b + t - winW * 0.5f, z1 = b + t + winW * 0.5f;
            o = put(d, o, xf, y0, z0, sign, 0, 0, 0, 1); o = put(d, o, xf, y0, z1, sign, 0, 0, 1, 1);
            o = put(d, o, xf, y1, z1, sign, 0, 0, 1, 0); o = put(d, o, xf, y0, z0, sign, 0, 0, 0, 1);
            o = put(d, o, xf, y1, z1, sign, 0, 0, 1, 0); o = put(d, o, xf, y1, z0, sign, 0, 0, 0, 0);
        }
        return o;
    }

    /** A solid box (6 faces) centred at (cx,cy,cz) with the given half-extents — used for window sills. */
    private static int box6(float[] d, int o, float cx, float cy, float cz, float hx, float hy, float hz) {
        o = boxQuad(d, o, cx + hx, cy - hy, cz - hz, cx + hx, cy - hy, cz + hz, cx + hx, cy + hy, cz + hz, cx + hx, cy + hy, cz - hz, 1, 0, 0);
        o = boxQuad(d, o, cx - hx, cy - hy, cz + hz, cx - hx, cy - hy, cz - hz, cx - hx, cy + hy, cz - hz, cx - hx, cy + hy, cz + hz, -1, 0, 0);
        o = boxQuad(d, o, cx - hx, cy + hy, cz - hz, cx + hx, cy + hy, cz - hz, cx + hx, cy + hy, cz + hz, cx - hx, cy + hy, cz + hz, 0, 1, 0);
        o = boxQuad(d, o, cx - hx, cy - hy, cz + hz, cx + hx, cy - hy, cz + hz, cx + hx, cy - hy, cz - hz, cx - hx, cy - hy, cz - hz, 0, -1, 0);
        o = boxQuad(d, o, cx - hx, cy - hy, cz + hz, cx + hx, cy - hy, cz + hz, cx + hx, cy + hy, cz + hz, cx - hx, cy + hy, cz + hz, 0, 0, 1);
        o = boxQuad(d, o, cx + hx, cy - hy, cz - hz, cx - hx, cy - hy, cz - hz, cx - hx, cy + hy, cz - hz, cx + hx, cy + hy, cz - hz, 0, 0, -1);
        return o;
    }

    private static int boxQuad(float[] d, int o, float ax, float ay, float az, float bx, float by, float bz,
                               float cx, float cy, float cz, float dx, float dy, float dz, float nx, float ny, float nz) {
        o = put(d, o, ax, ay, az, nx, ny, nz, 0, 0); o = put(d, o, bx, by, bz, nx, ny, nz, 1, 0);
        o = put(d, o, cx, cy, cz, nx, ny, nz, 1, 1); o = put(d, o, ax, ay, az, nx, ny, nz, 0, 0);
        o = put(d, o, cx, cy, cz, nx, ny, nz, 1, 1); o = put(d, o, dx, dy, dz, nx, ny, nz, 0, 1);
        return o;
    }

    private static Bitmap makeRoofBitmap() {
        int N = 256;
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFEDEDED);                                    // near-white grey base -> uColor sets the hue
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(77);
        for (int k = 0; k < 1500; k++) {                            // neutral tile grain (no hue)
            int v = rnd.nextInt(40), s = 0xC4 + v;
            p.setColor((0x33 << 24) | (s << 16) | (s << 8) | s);
            float x = rnd.nextInt(N), y = rnd.nextInt(N);
            c.drawRect(x, y, x + 2, y + 2, p);
        }
        p.setColor(0x55000000);                                     // horizontal tile rows
        for (int row = 0; row <= 12; row++) { float y = row * N / 12f; c.drawRect(0, y, N, y + 2.5f, p); }
        p.setColor(0x33000000);                                     // staggered vertical seams
        for (int row = 0; row < 12; row++) {
            float y = row * N / 12f, off = (row % 2) * (N / 16f);
            for (int col = 0; col <= 8; col++) { float x = col * N / 8f + off; c.drawRect(x, y, x + 1.5f, y + N / 12f, p); }
        }
        return b;
    }

    private static Bitmap makeWindowBitmap() {
        int N = 128;
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFF2F0EA);                                    // bright frame (tints lightly with the glass colour)
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float m = N * 0.17f, gl = m, gr = N - m, gt = m, gb = N - m, gw = gr - gl;
        // a window with a ROOM behind it, not flat glass: light glass top, darker interior lower down
        p.setColor(0xFFC6CED6); c.drawRect(gl, gt, gr, gb, p);      // neutral glass (per-house uColor sets the hue)
        p.setColor(0x55FFFFFF); c.drawRect(gl, gt, gr, N * 0.42f, p);   // sky sheen on the upper panes
        p.setColor(0x4D1A2230); c.drawRect(gl, N * 0.5f, gr, gb, p);    // dim interior depth (lower half)
        p.setColor(0x884A3326); c.drawRect(gl, N * 0.78f, gr, gb, p);   // furniture/sill silhouette at the bottom
        p.setColor(0x66FFD9A0); c.drawRect(gl + gw * 0.30f, N * 0.6f, gr - gw * 0.30f, N * 0.74f, p);  // warm lamp glow inside
        p.setColor(0xCCEDE6D6);                                     // light curtains down the two sides
        c.drawRect(gl, gt, gl + gw * 0.20f, gb, p);
        c.drawRect(gr - gw * 0.20f, gt, gr, gb, p);
        p.setColor(0xFFF2F0EA);                                     // frame border + cross mullion (window bars)
        c.drawRect(N * 0.5f - 3f, gt, N * 0.5f + 3f, gb, p);
        c.drawRect(gl, N * 0.5f - 3f, gr, N * 0.5f + 3f, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(5f); c.drawRect(gl, gt, gr, gb, p);
        return b;
    }

    private static int cl255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /** Light stucco/plaster facade so houses read as houses (tinted by the per-house colour). */
    private static Bitmap makeHouseBitmap() {
        int N = 256;
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFEDE7DA);                                    // warm light plaster
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(55);
        for (int k = 0; k < 30; k++) {                              // soft low-frequency mottle so big walls aren't dead flat
            int v = rnd.nextInt(18);
            p.setColor((0x12 << 24) | ((0xD8 - v) << 16) | ((0xD2 - v) << 8) | (0xC6 - v));
            c.drawCircle(rnd.nextInt(N), rnd.nextInt(N), 18 + rnd.nextInt(46), p);
        }
        for (int k = 0; k < 2600; k++) {                            // fine stucco speckle
            int v = rnd.nextInt(28);
            p.setColor((0x2A << 24) | ((0xD6 - v) << 16) | ((0xD0 - v) << 8) | (0xC2 - v));
            float x = rnd.nextInt(N), y = rnd.nextInt(N);
            c.drawRect(x, y, x + 2, y + 2, p);
        }
        p.setColor(0x10000000);                                     // faint horizontal coat lines
        for (int row = 1; row < 6; row++) { float y = row * N / 6f; c.drawRect(0, y, N, y + 1.2f, p); }
        p.setColor(0x0E000000);                                     // a few faint vertical weather streaks
        for (int k = 0; k < 16; k++) { float x = rnd.nextInt(N); c.drawRect(x, 0, x + 1.4f, N, p); }
        return b;
    }

    /** Red-brown running-bond brick (carries its own colour; drawn with white tint). */
    private static Bitmap makeBrickBitmap() {
        int N = 256;
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFB9AE9C);                                    // mortar
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(91);
        int rows = 12; float rh = (float) N / rows, gap = 2.2f, bw = N / 4f;
        for (int row = 0; row < rows; row++) {
            float y0 = row * rh + gap, y1 = (row + 1) * rh - gap, off = (row % 2) * (bw * 0.5f);
            for (int col = -1; col <= 4; col++) {
                float x0 = col * bw + off + gap, x1 = (col + 1) * bw + off - gap;
                int v = rnd.nextInt(34);                            // per-brick tone variation
                p.setColor(0xFF000000 | (cl255(0x9a - v + rnd.nextInt(18)) << 16) | (cl255(0x55 - v / 2) << 8) | cl255(0x42 - v / 2));
                c.drawRect(x0, y0, x1, y1, p);
            }
        }
        p.setColor(0x22000000);                                     // weathering speckle
        for (int k = 0; k < 700; k++) { float x = rnd.nextInt(N), y = rnd.nextInt(N); c.drawRect(x, y, x + 2, y + 2, p); }
        return b;
    }

    /** Ashlar grey stone / rendered-concrete blocks (carries its own colour). */
    private static Bitmap makeStoneBitmap() {
        int N = 256;
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF6E6E70);                                    // dark mortar
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(43);
        int rows = 5; float rh = (float) N / rows, gap = 3.0f, bw = N / 3f;
        for (int row = 0; row < rows; row++) {
            float y0 = row * rh + gap, y1 = (row + 1) * rh - gap, off = (row % 2) * (bw * 0.5f);
            for (int col = -1; col <= 3; col++) {
                float x0 = col * bw + off + gap, x1 = (col + 1) * bw + off - gap;
                int s = 0x9c - rnd.nextInt(26) + rnd.nextInt(14);   // slightly warm grey
                p.setColor(0xFF000000 | (cl255(s) << 16) | (cl255(s - 2) << 8) | cl255(s - 6));
                c.drawRect(x0, y0, x1, y1, p);
            }
        }
        p.setColor(0x18FFFFFF);                                     // aggregate highlight speckle
        for (int k = 0; k < 900; k++) { float x = rnd.nextInt(N), y = rnd.nextInt(N); c.drawRect(x, y, x + 1.5f, y + 1.5f, p); }
        return b;
    }

    /** Warm horizontal timber siding for shops (carries its own colour). */
    private static Bitmap makeSidingBitmap() {
        int N = 256;
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF9B6A3C);                                    // warm timber base
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(64);
        int planks = 9; float ph = (float) N / planks;
        for (int row = 0; row < planks; row++) {
            float y0 = row * ph, y1 = (row + 1) * ph;
            int v = rnd.nextInt(26);                                // per-plank tone
            p.setColor(0xFF000000 | (cl255(0x9b - v + rnd.nextInt(16)) << 16) | (cl255(0x6a - v / 2) << 8) | cl255(0x3c - v / 3));
            c.drawRect(0, y0, N, y1, p);
            p.setColor(0x55000000); c.drawRect(0, y1 - 2f, N, y1, p);       // shadow line between boards
            p.setColor(0x22FFFFFF); c.drawRect(0, y1, N, y1 + 1.5f, p);     // lit top edge of next board
        }
        p.setColor(0x1C000000);                                     // vertical grain streaks
        for (int k = 0; k < 220; k++) { float x = rnd.nextInt(N), y = rnd.nextInt(N), len = 8 + rnd.nextInt(26); c.drawRect(x, y, x + 1.2f, y + len, p); }
        return b;
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

    // Interleaved pos(2)+uv(2). UV origin top-left (v=0 at top, matching the baked atlas).
    private static final float[] TEXTQUAD_DATA = {
        -1f, -1f, 0f, 1f,   1f, -1f, 1f, 1f,   1f, 1f, 1f, 0f,
        -1f, -1f, 0f, 1f,   1f,  1f, 1f, 0f,  -1f, 1f, 0f, 0f,
    };

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
        "uniform float uSunInt; uniform float uAmbient; uniform vec2 uFogRange; uniform float uWorldUV;" +
        "vec3 aces(vec3 x){ return clamp((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14),0.0,1.0); }" +
        "void main(){" +
        "  vec3 N=normalize(vNormal); vec3 col;" +
        "  if(uMode<0.5){" +
        "    vec2 uv=vUV; float ao=1.0;" +
        "    if(uWorldUV>0.5){" +                                        // facades: tile by world position (not the stretched 0..1 cube UV)
        "      vec3 an=abs(N);" +
        "      if(an.y>an.x&&an.y>an.z) uv=vWorld.xz;" +                 // top/bottom
        "      else if(an.x>an.z)       uv=vWorld.zy;" +                 // +/-X wall
        "      else                     uv=vWorld.xy;" +                 // +/-Z wall
        "      uv*=0.5;" +                                               // ~2 m per texture tile
        "      ao=mix(0.55,1.0,smoothstep(0.0,0.6,vWorld.y));" +         // ground-contact darkening so houses sit in the world
        "    }" +
        "    vec3 tex=texture2D(uTex,uv).rgb;" +
        "    vec3 V=normalize(uCamPos-vWorld); vec3 L=normalize(uLightDir);" +
        "    float df=max(dot(N,L),0.0);" +
        "    float fl=max(dot(N,normalize(vec3(0.5,0.25,0.6))),0.0);" +
        "    float rim=pow(1.0-max(dot(N,V),0.0),3.0);" +
        "    vec3 H=normalize(L+V); float sp=pow(max(dot(N,H),0.0),48.0);" +
        "    vec3 base=tex*uColor;" +
        "    col=(base*(uAmbient*vec3(0.16,0.18,0.24)+uSunInt*df*vec3(1.15,1.05,0.9)+fl*vec3(0.25,0.32,0.45))" +
        "        +sp*vec3(0.9)*tex.r+rim*vec3(0.18,0.22,0.30))*ao;" +
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
        "  float dist=length(uCamPos-vWorld); float fog=clamp((dist-uFogRange.x)/max(uFogRange.y-uFogRange.x,0.001),0.0,0.82);" +
        "  col=mix(col,uFogColor,fog);" +
        "  gl_FragColor=vec4(pow(aces(col),vec3(0.4545)),1.0);" +
        "}";

    private static final String VERT_SKY_SRC =
        "attribute vec2 aP; varying vec2 vP; void main(){ vP=aP; gl_Position=vec4(aP,0.999,1.0); }";

    // Daytime sky: rebuild a per-pixel view ray from the camera basis, then paint a blue gradient,
    // a warm sun disc + halo, and animated FBM clouds projected onto a sky plane.
    private static final String FRAG_SKY_SRC =
        "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
        "varying vec2 vP;" +
        "uniform vec3 uFwd; uniform vec3 uRight; uniform vec3 uUp; uniform vec3 uSun;" +
        "uniform vec2 uHalfFov; uniform float uTime; uniform vec3 uHorizon; uniform vec3 uZenith;" +
        "float hash(vec2 p){ p=fract(p*vec2(123.34,456.21)); p+=dot(p,p+45.32); return fract(p.x*p.y); }" +
        "float vnoise(vec2 p){ vec2 i=floor(p); vec2 f=fract(p); f=f*f*(3.0-2.0*f);" +
        "  float a=hash(i); float b=hash(i+vec2(1.0,0.0)); float c=hash(i+vec2(0.0,1.0)); float d=hash(i+vec2(1.0,1.0));" +
        "  return mix(mix(a,b,f.x),mix(c,d,f.x),f.y); }" +
        "float fbm(vec2 p){ float v=0.0; float a=0.55; for(int i=0;i<5;i++){ v+=a*vnoise(p); p=p*2.02+vec2(11.3,7.1); a*=0.5; } return v; }" +
        "void main(){" +
        "  vec3 dir=normalize(uFwd + vP.x*uHalfFov.x*uRight + vP.y*uHalfFov.y*uUp);" +
        "  vec3 sun=normalize(uSun); float h=dir.y;" +
        "  float t=clamp(h,0.0,1.0);" +
        "  vec3 col=mix(uHorizon,uZenith,pow(t,0.55));" +                            // horizon -> zenith (level-driven)
        "  col=mix(col,vec3(0.55,0.60,0.63),smoothstep(0.0,-0.18,h));" +             // haze just below horizon
        "  float sd=max(dot(dir,sun),0.0);" +
        "  float halo=pow(sd,5.0)*0.45 + pow(sd,180.0)*0.7;" +
        "  col+=halo*vec3(1.0,0.86,0.58);" +
        "  col=mix(col,vec3(1.05,1.0,0.92),smoothstep(0.9988,0.9994,sd));" +          // sun disc
        "  float fade=smoothstep(0.05,0.25,h);" +
        "  if(fade>0.001){" +
        "    vec2 cp=dir.xz/max(h,0.07);" +
        "    float d=fbm(cp*1.3+vec2(uTime*0.010,uTime*0.004));" +
        "    d=d*1.7-0.45;" +
        "    float cov=smoothstep(0.30,0.95,d)*fade;" +
        "    float shade=smoothstep(0.2,1.0,d);" +
        "    vec3 cloud=mix(vec3(0.72,0.75,0.82),vec3(1.0,1.0,1.0),shade)+halo*0.6*vec3(1.0,0.9,0.7);" +
        "    col=mix(col,cloud,cov*0.92);" +
        "  }" +
        "  gl_FragColor=vec4(pow(min(col,vec3(1.0)),vec3(0.4545)),1.0);" +
        "}";

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

    // Textured 2D text: per-glyph UV rect via uUVoff/uUVscale; white atlas tinted by uCol.
    private static final String VERT_TEXT_SRC =
        "attribute vec2 aP; attribute vec2 aUV; uniform vec2 uScale; uniform vec2 uOff;" +
        "uniform vec2 uUVoff; uniform vec2 uUVscale; varying vec2 vUV;" +
        "void main(){ vUV=aUV*uUVscale+uUVoff; gl_Position=vec4(aP*uScale+uOff,0.0,1.0); }";

    private static final String FRAG_TEXT_SRC =
        "precision mediump float; varying vec2 vUV; uniform sampler2D uFont; uniform vec4 uCol;" +
        "void main(){ gl_FragColor=uCol*texture2D(uFont,vUV); }";
}
