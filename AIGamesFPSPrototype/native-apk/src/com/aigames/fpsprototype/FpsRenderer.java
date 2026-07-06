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

    // Light-travel direction for cast shadows = -normalize(sunDir): shadows now fall exactly opposite the
    // sun that lights the facades (they used to point ~24 deg off, which made buildings look pasted-on).
    private static final float LDX = 0.389f, LDY = -0.611f, LDZ = 0.689f;
    private static final float SHADOW_AZI = (float) Math.toDegrees(Math.atan2(LDX, LDZ));  // sun ground azimuth -> cast-shadow direction
    // Baked directional shadows: every static object projects a flattened hull along the sun ray.
    private java.nio.FloatBuffer shadowMesh; private int shadowVerts;
    private static final float SHX = LDX / -LDY, SHZ = LDZ / -LDY;   // ground offset per metre of caster height

    // Fountain water: real animated surfaces (FRAG3 water mode) in their own translucent mesh —
    // the old look was two coplanar water BOXES z-fighting each other and the basin rim.
    private java.nio.FloatBuffer waterMesh; private int waterVerts;
    private java.util.List<float[]> waterDiscs;     // {cx, y, cz, radius} pools, registered by addFountain
    private java.util.List<float[]> waterStreams;   // {cx, yTop, cz, yBot, halfW} falling overflow streams

    // Post-processing (the AAA pass): the 3D scene renders into an offscreen target, then a bright-pass +
    // quarter-res separable gaussian builds a bloom layer, and one fullscreen composite applies FXAA
    // (replacing MSAA, which offscreen targets lose), adds the bloom, and lays a whisper of film grain.
    // Falls back to direct rendering when the device lacks a 24-bit FBO depth format (16-bit would
    // re-introduce the B199 window moiré).
    private int progBright, aPBr, uBrTex;
    private int progBlur, aPBl, uBlTex, uBlDir;
    private int progPost, aPPo, uPoScene, uPoBloom, uPoInvRes, uPoGrain, uPoSun, uPoFlare;
    private int sceneFboId, sceneColorTex, sceneDepthRb, bloomFboA, bloomTexA, bloomFboB, bloomTexB;
    private int postW = -1, postH = -1;             // built-for size (-1 = build on next frame)
    private boolean postOk = false, postDead = false;   // postDead: device can't do it, stop trying
    private static final int BLOOM_DIV = 4;

    // Dynamic point lights (FRAG3 lit branch): slot 0 = muzzle flash kicking warm light onto the world,
    // remaining slots = the nearest street lanterns during the ABENDROT (dusk) weather.
    private int uPtLoc = -1, uPtCLoc = -1, uSwayLoc = -1, uCharLoc = -1;
    private final float[] ptPos = new float[16];    // 4 x (x,y,z, 1/r^2)
    private final float[] ptCol = new float[12];    // 4 x premultiplied rgb (0 = slot off)
    private float wDusk = 0f;                       // dusk crossfade (like wRain/wSnow)
    private float[][] lampWorld;                    // street-lamp positions {x,z} for lights + glowing bulbs

    private final float[] shotO = new float[3], shotD = new float[3];   // scratch for yaw-aware hitscan
    private float gunFlashAdd = 0f;   // warm light the muzzle flash throws onto the viewmodel this frame
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
    private int progSky, aPSky, uSkyFwd, uSkyRight, uSkyUp, uSkySun, uSkyHalfFov, uSkyTime, uSkyHorizon, uSkyZenith, uSkyOvercast;
    private int progVig, aPVig;
    private int progBlob, aPBlob, uBlobMVP, uBlobA;
    private int prog2, aP2, uScale2, uOff2, uCol2;
    private int progText, aPText, aUVText, uScaleT, uOffT, uColT, uUVoffT, uUVscaleT, uFontTex;

    private int floorTex, metalTex, terrainTex, cityTex, vegTex, fontTex, winTex, roofTex, wallTex, roadTex, woodTex;
    private int brickTex, stoneTex, sidingTex, gravelTex, winBackTex;   // per-archetype facade materials, gravel path + dark window-interior

    private FloatBuffer cube, floor, sphere, quad, circle, terrain, cityGround, vegetation, textQuad, roofMesh, windowMesh, trimMesh, roadMesh, placedTrees, bandMesh, interiorMesh, revealMesh;
    private int sphereVerts, circleVerts, terrainVerts, vegVerts, roofVerts, windowVerts, trimVerts, roadVerts, placedTreeVerts, bandVerts, interiorVerts, revealVerts;
    private float[] revealData;   // dark recessed interior behind each glass pane (built with the window mesh)
    private float[][] roadSegs;                    // custom streets {x1,z1,x2,z2,width,r,g,b} from the level; null = default grid
    private float[][] roadGroups;                  // per-street colour groups {firstVert, vertCount, r,g,b}
    private float[][] treeList;                    // level-placed trees {x,z,scale}; null = none
    private java.util.List<float[]> clutterPts;    // barrel/crate/planter positions, so benches never spawn on top of them
    // Recorded by buildWorldInto for the ground texture + vegetation bake (all null for .lvl worlds):
    private java.util.List<float[]> pathStrokes;   // dirt footpaths {x0,z0,x1,z1,width}
    private java.util.List<float[]> gardenSoil;    // tilled plots {cx,cz,w,d,yawDeg} painted + sprouted
    private java.util.List<float[]> gardenTreePts; // fruit trees inside gardens {x,z,scale}
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
    private java.util.List<float[]> customFurniture;// level-placed FU pieces {kind,x,z,yaw,scale} (editor furniture tool)
    // Editor-placed houses get their OWN small mesh set (pitched roof + windows + bands + interior), baked from
    // the cat-3 editObjs and drawn alongside the base town meshes -- so placed houses look identical to the town,
    // not flat box-shells. Rebuilt only when the house set changes (edHouseMeshDirty), and hidden mid-drag
    // (edDraggingHouse) so a moving house shows its box-shell walls without a detached roof.
    private FloatBuffer ovRoofMesh, ovWinMesh, ovTrimMesh, ovRevealMesh, ovBandMesh, ovIntMesh;
    private float[][] ovRoofGroups, ovWinGroups, ovBandGroups, ovIntGroups;
    private int ovRoofVerts, ovWinVerts, ovTrimVerts, ovRevealVerts, ovBandVerts, ovIntVerts;
    private java.util.List<float[]> ovHouseColliders;   // furniture collision proxies for placed houses (merged into boxes)
    private boolean edHouseMeshDirty = true, edDraggingHouse;   // bake overlay-house meshes on first overlay build
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
        {0.24f, 0.26f, 0.30f},   // 12 dark metal (stove body, chest bands)
        {1.00f, 0.62f, 0.22f},   // 13 ember / bulb glow (warm bright)
        {0.58f, 0.56f, 0.52f},   // 14 hearth stone
        {0.78f, 0.72f, 0.58f},   // 15 linen / paper
    };

    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] model = new float[16];
    private final float[] mvp = new float[16];
    private final float[] doorBase = new float[16];   // hinge transform for detailed swinging doors
    private final float[] winBase = new float[16];    // hinge transform for openable window sashes
    private final float[] tmpA = new float[16];
    private final float[] gunBase = new float[16];
    private final float[] partM = new float[16];

    private float px = 0f, py = 1.6f, pz = 9f;
    private float yaw = 0f, pitch = -0.08f;
    // View basis for the sky shader. Starts as a valid basis (not zeros): the sky is drawn from the very
    // first hub frame, and normalize(0) in the shader is NaN -> undefined sky on some GPUs.
    private final float[] camFwd = {0f, 0f, -1f}, camRight = {1f, 0f, 0f}, camUp = {0f, 1f, 0f};
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

    // Game state machine: HUB (between runs, spend cash) / PLAYING / SUMMARY (run-over recap) / EDIT (in-game
    // editor) / SANDBOX (peaceful first-person build+roam: no enemies, no damage; own separate overlay).
    private static final int ST_HUB = 0, ST_PLAYING = 1, ST_SUMMARY = 2, ST_EDIT = 3, ST_SANDBOX = 4;
    private int state = ST_HUB;

    // ===== in-game touch editor =====
    // Editable overlay objects, each float[]{cat, kind, x, z, yaw(deg), scale}. cat: 0 furniture · 1 plant · 2 prop.
    private final java.util.List<float[]> editObjs = new java.util.ArrayList<float[]>();
    private float[][] baseBoxes;                    // frozen base collision/render boxes (houses + accessories)
    private String baseLevelText = "";              // scenery lines (H/R/F/SET) re-emitted verbatim on save
    private boolean editDirty = true;               // rebuild overlay (boxes + plant mesh) on the GL thread
    private FloatBuffer overlayTrees; private int overlayTreeVerts;
    // Interior editing: double-tap a placed house -> a top-down "look inside" view of THAT house (its roof
    // hidden, ceiling off), where its furniture is editable. -1 = normal town view.
    private int edInsideHouse = -1;
    private float edSavePivotX, edSavePivotZ, edSaveYaw, edSavePitch, edSaveDist;   // town camera, restored on leaving
    private int selObj = -1;                         // selected editObjs index, -1 = none
    private int armedCat = -1, armedKind = -1;       // palette item armed for placement (-1 = none)
    // ===== peaceful first-person SANDBOX mode (state ST_SANDBOX) =====
    // Reuses the editor's editObjs store + rebuildOverlay/bake pipeline, but placement is driven from the
    // FPS reticle ("in front of the player") and persisted to a SEPARATE sandbox.lvl. On enter we snapshot
    // the authored/combat overlay into levelObjsBackup and load the sandbox overlay; on exit we restore it,
    // so sandbox placements NEVER leak into combat and never touch level.lvl.
    private final java.util.List<float[]> levelObjsBackup = new java.util.ArrayList<float[]>();
    private int sbTool = 0;                            // selected sandbox tool (index into SB_TOOLS)
    private final float[] sbVP = new float[16];        // scratch first-person view-projection for reticle picking
    // sandbox tool set -> {editObjs cat, kind}. All reuse the editor's presets/builders.
    private static final int[][] SB_TOOLS = {
        {2,0}, {2,1}, {2,2}, {2,3},   // Kiste, Fass, Saeule, Kuebel (props)
        {0,8}, {1,0}, {1,1}, {3,0},   // Lampe (furniture) · Baum, Busch (plants) · Haus
    };
    private static final String[] SB_TOOL_NAMES = {"KISTE", "FASS", "SAEULE", "KUEBEL", "LAMPE", "BAUM", "BUSCH", "HAUS"};
    // orbit edit camera
    private float edCamYaw = 0.7f, edCamPitch = 0.85f, edCamDist = 26f, edCamPivotX = 0f, edCamPivotY = 0f, edCamPivotZ = 9f;
    private int edFloor = 0;   // interior view: which storey you're looking at / placing on (0 = lowest)
    // gesture state, split by zone like the gameplay controls (left = move, right = look):
    // left-half single finger = pan; right-half two fingers = twist (angle) + pinch (zoom) + vertical
    // midpoint drift (tilt/pitch) all read off the same two fingers, like Google Maps' 2-finger navigation.
    private final float[] edPanArr = new float[3], edCamArr = new float[5], edStickArr = new float[5];
    private boolean edPrevPanActive; private float edPrevPanX, edPrevPanY;
    private int edPrevCamCount = 0; private float edPrevCamGap, edPrevCamAng, edPrevCamMidY;
    private boolean edGrabbed, edGrabMoved; private float edGrabOffX, edGrabOffZ;
    private final float[] edBnd = new float[6];    // scratch bounds for edRayHitsObj
    private int edPaletteScroll = 0;
    private int edCatTab = 0;                         // palette category: 0 furniture · 1 plants · 2 props
    private boolean edPaletteOpen = false;             // collapsed by default so it doesn't cover the world
    private android.content.Context appCtx;           // for getExternalFilesDir on save
    private boolean edSnap = true;
    private final float ED_SNAP_POS = 0.5f, ED_SNAP_YAW = 15f;
    private static final float ED_TILT_SENS = 0.0028f;   // px -> radians for the 2-finger tilt/pitch drag
    private static final float ED_LOOK_SENS = 0.006f;    // px -> radians for the 1-finger orbit (yaw/pitch), like gameplay look
    private String edToast = null; private float edToastT = 0f;
    // undo stack: each entry {String op, Integer index, float[] before}
    private final java.util.List<Object[]> edUndo = new java.util.ArrayList<Object[]>();
    // scratch
    private final float[] edVP = new float[16], edRO = new float[3], edRD = new float[3], edHit = new float[3], edTap = new float[2], edTap2 = new float[2];
    // prop presets {w,h,d, r,g,b}
    private static final float[][] PROP_PRESETS = {
        {1.2f,1.2f,1.2f, 0.55f,0.40f,0.24f},   // 0 Kiste (crate)
        {0.8f,1.05f,0.8f, 0.42f,0.30f,0.22f},  // 1 Fass (barrel)
        {0.8f,3.0f,0.8f, 0.68f,0.66f,0.62f},   // 2 Säule (pillar, stone)
        {1.0f,0.6f,1.0f, 0.62f,0.42f,0.34f},   // 3 Topf/Kübel (planter)
        {1.6f,0.5f,0.7f, 0.5f,0.35f,0.22f},    // 4 Bank-Kiste (low box)
    };
    private static final String[] PROP_NAMES = {"Kiste", "Fass", "Saeule", "Kuebel", "Kiste flach"};
    // editor house presets (cat 3): {w, d, h, doorSide, roofStyle, r,g,b, chimney, doorW, doorH, dr,dg,db, doorStyle, material}
    // -- baked as a box-shell via addBuilding() (walls with a door gap + roof slab + doorstep + chimney/awning),
    //    so a placed house is solid, walk-in, material-textured, and fully movable in the overlay.
    private static final float[][] HOUSE_PRESETS = {
        {5f,5f,3f, 0,0, 0.88f,0.84f,0.74f, 1, 1.6f,2.2f, 0.46f,0.30f,0.17f, 1, 0},   // 0 Cottage (plaster, chimney)
        {5f,6f,6f, 0,0, 0.72f,0.42f,0.35f, 1, 1.7f,2.3f, 0.34f,0.22f,0.15f, 0, 1},   // 1 Stadthaus (brick, tall)
        {7f,7f,7f, 0,1, 0.72f,0.72f,0.68f, 0, 1.9f,2.4f, 0.42f,0.42f,0.45f, 2, 2},   // 2 Block (stone, flat)
        {6f,5f,4f, 0,1, 0.82f,0.74f,0.56f, 0, 2.0f,2.4f, 0.35f,0.30f,0.50f, 3, 0},   // 3 Laden (shop awning)
    };
    private static final String[] HOUSE_NAMES = {"Cottage", "Stadthaus", "Block", "Laden"};
    // palette rows: {cat, kind}. Labels resolved in edLabel().
    private static final int[][] ED_PALETTE = {
        {0,0},{0,1},{0,2},{0,3},{0,4},{0,5},{0,6},{0,7},{0,8},   // furniture kinds 0..8
        {1,0},{1,1},{1,2},                                        // tree / bush / flower
        {2,0},{2,1},{2,2},{2,3},{2,4},                            // props
    };
    private float deathAnim = 0f;      // >0 = playing the death sequence before the summary
    private boolean newBest = false;   // this run beat the high score
    // player-toggleable options (settings panel)
    private boolean showSettings = false;
    private boolean optShake = true, optDmgNum = true, optRadar = true, optKillfeed = true;
    // --- story campaign state ---
    private int levelId = 0;                            // chapter 0..2 (persisted "levelSel")
    private boolean worldDirty = false;                 // chapter switched in the hub -> rebuild world on the GL thread
    private java.util.List<float[]> savedEditObjs;      // chapter-1 editor overlay parked while visiting ch. 2/3
    private float storyTimer = 0f;                      // chapter briefing overlay at run start

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
    private long preRunCash = 0, preRunXp = 0; private int preRunLevel = 1;   // bank snapshot for un-counting an aborted run
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
    private final int[] enType = new int[MAX_ENEMIES];   // 0 = normal, 1 = CRAWLER (fast/weak, legless, low), 2 = brute (slow/tanky)
    private final float[] enVar = new float[MAX_ENEMIES]; // per-zombie 0..1: skin tint, clothing wear, wound layout
    // Radar echoes: a blip is painted only when the sweep line passes the enemy's bearing, then it
    // afterglows and fades where it was painted (even if the enemy has since moved or died).
    private final float[] blipX = new float[MAX_ENEMIES], blipZ = new float[MAX_ENEMIES];
    private final float[] blipAge = new float[MAX_ENEMIES];      // seconds since painted (999 = dark)
    private final float[] blipRel = new float[MAX_ENEMIES];      // last bearing-minus-sweep (-1 = untracked)
    private final boolean[] blipBoss = new boolean[MAX_ENEMIES];
    private float radarPrevT = -1f;                              // timeAcc at the previous radar frame
    private static final float RADAR_RATE = 2.2f;                // sweep speed (rad/s, ~2.9 s per turn)
    private static final float RADAR_GLOW = 2.45f;               // afterglow fade time (most of a turn)
    private final int[] enFaceType = new int[MAX_ENEMIES];
    private final boolean[] enAlive = new boolean[MAX_ENEMIES];
    private final float[] enScale = new float[MAX_ENEMIES];   // 1 = normal, >1 = (mini)boss

    // Flow-field navigation: a walkability grid over the town + a BFS distance field flooded from the
    // player a few times a second. Zombies descend the field instead of greedily probing straight at
    // you, so fences, garden walls, market stalls and house corners no longer wedge them; a per-enemy
    // progress watchdog shakes loose (then quietly relocates) anything that still snags.
    private static final float NAV_CELL = 0.6f, NAV_ORG = -36.9f;   // world x/z of the grid's low edge
    private static final int NAV_N = 123;                            // 123 cells * 0.6 m = 73.8 m span
    private static final int[] NAV_DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] NAV_DZ = {0, 0, 1, -1, 1, -1, 1, -1};
    private boolean[] navBlocked;                                    // static bake from boxes (null = rebake)
    private short[] navDist;                                         // BFS steps from the player (-1 unseen, -2 closed door)
    private int[] navQ;                                              // both allocated with the first bake
    private int[][] navDoorCells;                                    // per-door: cells a CLOSED leaf blocks
    private boolean navFieldValid = false;
    private boolean lmPolice, lmFire, lmChapel;   // landmark one-shots (police / fire station / chapel), per world build
    private float navTimer = 0f, navPx = 1e9f, navPz = 1e9f;
    private final float[] navFlow = new float[2];
    private final float[] enLastX = new float[MAX_ENEMIES], enLastZ = new float[MAX_ENEMIES];
    private final float[] enStuck = new float[MAX_ENEMIES];          // seconds spent making no real progress
    private final float[] enProgT = new float[MAX_ENEMIES];          // progress-check ticker
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
    private float[][] boxes;               // base + editor overlay (rebuilt when the overlay changes)
    private int numCover = COVER_BOXES;              // boxes that cast a shadow (props); set by the level loader

    // Interactive doors. doorData[i] = cx, cy, cz, hw, hh, ht, axis(0=spansX,1=spansZ), hingeSign, r,g,b.
    private static final float INTERACT_DIST = 2.6f;
    private float[][] doorData;                       // non-final: a story-chapter switch rebuilds the town
    private float[] doorOpen, doorTarget;             // 0 = closed, 1 = open
    private int nearDoor = -1;
    private final float[] obb2 = new float[2];         // scratch for oriented-box collision (rotated houses)
    // Openable window sashes (reachable ground-floor windows) — same record layout + hinge maths as doors:
    // {cx,cy,cz, tHalf, vHalf, depthHalf, axis, 1, gr,gg,gb, style, swingSign, houseYaw}. Built from
    // houseRects after the meshes bake; unreachable/upper panes stay baked static glass in windowMesh.
    private float[][] windowData = new float[0][];
    private float[] windowOpen = new float[0], windowTarget = new float[0];
    private int nearWindow = -1;
    private boolean nearWinCloser = false;             // the nearest interactable is a window (drives the HUD prompt)
    private boolean bakingOverlay = false;             // editor overlay bake: keep ALL panes baked, no sashes/holes-only glass

    // World look — defaults match the built-in sunny day; a level's SET lines override these.
    private final float[] skyHorizon = {0.76f, 0.85f, 0.95f}, skyZenith = {0.16f, 0.41f, 0.74f};
    private final float[] sunDir = {-0.35f, 0.55f, -0.62f}, fog = {0.72f, 0.80f, 0.90f}, ground = {1f, 1f, 1f};
    private float sunInt = 1f, ambient = 1f, fogStart = 55f, fogEnd = 130f;   // clear days: the whole village crisp, only the world edge fades

    // --- Weather: auto-cycles Sun -> Rain -> Fog -> Snow with smooth crossfades, plus rain/snow particles ---
    private static final int WX_SUN = 0, WX_FOG = 1, WX_RAIN = 2, WX_SNOW = 3, WX_DUSK = 4;
    // rain & snow never adjacent -> particle pool is only ever one kind; dusk right after rain =
    // golden-hour light over wet streets (the prettiest transition the cycle has)
    private static final int[] WX_ORDER = {WX_SUN, WX_RAIN, WX_DUSK, WX_FOG, WX_SNOW};
    private static final String[] WX_NAME = {"SONNE", "NEBEL", "REGEN", "SCHNEE", "ABENDROT"};
    private static final float WX_HOLD = 55f;          // seconds each weather lingers before the next
    private int wxIdx = 0;                              // index into WX_ORDER
    private float wxTimer = 20f, weatherLabelT = 0f;    // first change comes a bit sooner, then settle into WX_HOLD
    private final float[] effHorizon = {0.76f, 0.85f, 0.95f}, effZenith = {0.16f, 0.41f, 0.74f}, effFog = {0.72f, 0.80f, 0.90f};
    private float effSunInt = 1f, effAmbient = 1f, effFogStart = 10f, effFogEnd = 70f, wOvercast = 0f, wRain = 0f, wSnow = 0f;
    private static final int MAX_WX = 180;
    private final float[] wxX = new float[MAX_WX], wxY = new float[MAX_WX], wxZ = new float[MAX_WX], wxP = new float[MAX_WX];
    private boolean wxInit = false;
    private final java.util.Random wxRnd = new java.util.Random(12345);

    public FpsRenderer(InputState input, int buildNumber, Context ctx) {
        this.input = input;
        this.buildNumber = buildNumber;
        this.appCtx = ctx.getApplicationContext();
        this.prefs = ctx.getSharedPreferences("aigames_fps", Context.MODE_PRIVATE);
        this.highScore = prefs.getInt("highscore", 0);
        this.sfx = new Sfx(ctx);
        loadMeta();
        levelId = Math.max(0, Math.min(2, prefs.getInt("levelSel", 0)));
        if (playerLevel < LV_UNLOCK[levelId]) levelId = 0;   // meta reset since last session: fall back
        if (levelId != 0) buildRoads(LV_ROADS[levelId]);
        applyLevelLook();

        ArrayList<float[]> bl = new ArrayList<float[]>();
        ArrayList<float[]> dl = new ArrayList<float[]>();
        ArrayList<float[]> hl = new ArrayList<float[]>();
        // A custom level (placed via the web editor + adb push) overrides the procedural village —
        // it IS chapter 1's map; chapters 2/3 are always their own procedural towns.
        if (levelId != 0 || !buildWorldFromFile(ctx, bl, dl, hl)) buildWorldInto(bl, dl, hl);
        furnishAll(bl, hl);                                 // walk-in interiors: furniture colliders + visual specs
        if (customFurniture != null)                        // level-authored furniture (editor furniture tool)
            for (float[] fu : customFurniture) addFurniture(bl, (int) fu[0], fu[1], fu[2], fu[3], fu[4]);
        this.boxes = bl.toArray(new float[0][]);
        this.baseBoxes = this.boxes;                        // frozen base; editor overlay = base + editObjs colliders
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
        uSkyOvercast = GLES20.glGetUniformLocation(progSky, "uOvercast");

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

        uPtLoc = GLES20.glGetUniformLocation(prog3, "uPt");        // dynamic point lights
        uPtCLoc = GLES20.glGetUniformLocation(prog3, "uPtC");
        uSwayLoc = GLES20.glGetUniformLocation(prog3, "uSway");    // vegetation wind
        uCharLoc = GLES20.glGetUniformLocation(prog3, "uChar");    // character wrap lighting

        progBright = buildProgram(VERT_POST_SRC, FRAG_BRIGHT_SRC);
        aPBr = GLES20.glGetAttribLocation(progBright, "aP");
        uBrTex = GLES20.glGetUniformLocation(progBright, "uTex");
        progBlur = buildProgram(VERT_POST_SRC, FRAG_BLUR_SRC);
        aPBl = GLES20.glGetAttribLocation(progBlur, "aP");
        uBlTex = GLES20.glGetUniformLocation(progBlur, "uTex");
        uBlDir = GLES20.glGetUniformLocation(progBlur, "uDir");
        progPost = buildProgram(VERT_POST_SRC, FRAG_POST_SRC);
        aPPo = GLES20.glGetAttribLocation(progPost, "aP");
        uPoScene = GLES20.glGetUniformLocation(progPost, "uScene");
        uPoBloom = GLES20.glGetUniformLocation(progPost, "uBloom");
        uPoInvRes = GLES20.glGetUniformLocation(progPost, "uInvRes");
        uPoGrain = GLES20.glGetUniformLocation(progPost, "uGrainT");
        uPoSun = GLES20.glGetUniformLocation(progPost, "uSunUV");
        uPoFlare = GLES20.glGetUniformLocation(progPost, "uFlare");
        // A re-created GL context invalidates every GL object name: forget the old FBOs (do NOT delete
        // stale names — they could collide with fresh ones) and re-probe the device on the next frame.
        sceneFboId = 0; sceneColorTex = 0; sceneDepthRb = 0; bloomFboA = 0; bloomTexA = 0; bloomFboB = 0; bloomTexB = 0;
        postW = -1; postH = -1; postOk = false; postDead = false;

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
        roadTex = uploadTexture(makeRoadBitmap());
        vegTex = uploadPalette(makeVegPalette());
        roofTex = uploadTexture(makeRoofBitmap());
        winTex = uploadTexture(makeWindowBitmap());
        winBackTex = uploadTexture(makeWinBackBitmap());
        wallTex = uploadTexture(makeHouseBitmap());
        brickTex = uploadTexture(makeBrickBitmap());
        stoneTex = uploadTexture(makeStoneBitmap());
        sidingTex = uploadTexture(makeSidingBitmap());
        gravelTex = uploadTexture(makeGravelBitmap());
        woodTex = uploadTexture(makeWoodBitmap());
        bakeWorldMeshes();                               // everything derived from the CURRENT chapter's world

        // The GL context can be re-created mid-session (Home + return, screen off). If that happens while in
        // the SANDBOX, the editObjs snapshot-swap must be unwound FIRST — otherwise the hub opens with the
        // sandbox overlay still swapped in, and a later editor SPEICHERN would write sandbox objects into
        // level.lvl (permanently replacing the authored overlay).
        if (state == ST_SANDBOX) sandboxExit();
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
        Matrix.perspectiveM(proj, 0, 68f, aspect, 0.07f, 300f);   // near up from 0.05: more depth precision (gun stays well past 0.07)
    }

    /** Every GL bake derived from the current world (boxes/houses/roads/trees) — split out of
     *  onSurfaceCreated so a story-chapter switch can rebuild the whole town at runtime. */
    private void bakeWorldMeshes() {
        if (cityTex != 0) GLES20.glDeleteTextures(1, new int[]{cityTex}, 0);   // repainted per chapter
        // Authored natively at 2048 (POT): safe for mipmaps+REPEAT on strict GLES2 drivers, and every kerb
        // line / dash / joint stays pixel-crisp (the old 1536 bitmap needed a blurring POT rescale here).
        cityTex = uploadTexture(makeCityBitmap());
        roadMesh = hasCustomRoads ? makeBuffer(makeRoadMesh()) : null;   // custom streets replace the default paint
        vegetation = makeBuffer(makeVegetation());   // grass, flowers, bushes (sets vegVerts)
        placedTrees = treeList != null ? makeBuffer(makePlacedTrees()) : null;   // level-placed trees
        roofMesh = makeBuffer(makeRoofMesh());       // pitched gable roofs (sets roofVerts)
        windowMesh = makeBuffer(makeWindowMesh());   // wall windows (sets windowVerts + trimData + winGroups + revealData)
        trimMesh = makeBuffer(trimData);             // window sills
        revealMesh = makeBuffer(revealData);         // dark recessed interior behind each pane
        bandMesh = makeBuffer(makeBandMesh());       // per-house foundation / trim / storey bands (sets bandVerts)
        interiorMesh = makeBuffer(makeInteriorMesh());   // baked walk-in interiors (floors/ceilings/furniture)
        buildOpenableWindows();                          // reachable ground-floor sashes (from the same window layout)
        shadowMesh = makeBuffer(makeShadowMeshData());   // projected directional shadows for every static object
        float[] wtr = makeWaterMeshData();               // fountain pools + overflow streams (animated shader water)
        waterMesh = wtr.length > 0 ? makeBuffer(wtr) : null;
    }

    /** Chapter base look: what the WX_SUN weather state reads (the other weathers blend over it). */
    private void applyLevelLook() {
        if (levelId == 1) {          // ASCHENHOF: dry late-summer haze over stubble fields
            skyHorizon[0] = 0.85f; skyHorizon[1] = 0.80f; skyHorizon[2] = 0.68f;
            skyZenith[0] = 0.34f;  skyZenith[1] = 0.48f;  skyZenith[2] = 0.68f;
            fog[0] = 0.76f; fog[1] = 0.72f; fog[2] = 0.62f;
            fogStart = 42f; fogEnd = 105f;
            ground[0] = 1.08f; ground[1] = 1.00f; ground[2] = 0.80f;
        } else if (levelId == 2) {   // STEINMARKT: cold hard light on old stone
            skyHorizon[0] = 0.70f; skyHorizon[1] = 0.74f; skyHorizon[2] = 0.80f;
            skyZenith[0] = 0.14f;  skyZenith[1] = 0.30f;  skyZenith[2] = 0.55f;
            fog[0] = 0.60f; fog[1] = 0.65f; fog[2] = 0.72f;
            fogStart = 45f; fogEnd = 115f;
            ground[0] = 0.90f; ground[1] = 0.94f; ground[2] = 0.97f;
        } else {                     // BRUNNENFELD: the classic clear village day
            skyHorizon[0] = 0.76f; skyHorizon[1] = 0.85f; skyHorizon[2] = 0.95f;
            skyZenith[0] = 0.16f;  skyZenith[1] = 0.41f;  skyZenith[2] = 0.74f;
            fog[0] = 0.72f; fog[1] = 0.80f; fog[2] = 0.90f;
            fogStart = 55f; fogEnd = 130f;
            ground[0] = 1f; ground[1] = 1f; ground[2] = 1f;
        }
    }

    /** Switch to the selected chapter's map at runtime (GL thread): rebuild roads/look/world lists,
     *  park or restore the chapter-1 editor overlay, then rebake every world-derived mesh. */
    private void rebuildWorldForLevel() {
        buildRoads(LV_ROADS[levelId]);
        applyLevelLook();
        hasCustomRoads = false; roadSegs = null;
        customFurniture = null;
        if (levelId != 0 && savedEditObjs == null && !editObjs.isEmpty()) {   // park ch.1 editor objects
            savedEditObjs = new java.util.ArrayList<float[]>(editObjs);
            editObjs.clear();
        } else if (levelId == 0 && savedEditObjs != null) {                    // restore them with ch.1
            editObjs.clear(); editObjs.addAll(savedEditObjs);
            savedEditObjs = null;
        }
        ArrayList<float[]> bl = new ArrayList<float[]>();
        ArrayList<float[]> dl = new ArrayList<float[]>();
        ArrayList<float[]> hl = new ArrayList<float[]>();
        if (levelId != 0 || !buildWorldFromFile(appCtx, bl, dl, hl)) buildWorldInto(bl, dl, hl);
        furnishAll(bl, hl);
        if (customFurniture != null)
            for (float[] fu : customFurniture) addFurniture(bl, (int) fu[0], fu[1], fu[2], fu[3], fu[4]);
        boxes = bl.toArray(new float[0][]);
        baseBoxes = boxes;
        doorData = dl.toArray(new float[0][]);
        houseRects = hl;
        doorOpen = new float[doorData.length];
        doorTarget = new float[doorData.length];
        navBlocked = null;                     // walkability grid rebakes lazily for the new town
        edHouseMeshDirty = true;
        bakeWorldMeshes();
        if (!editObjs.isEmpty()) rebuildOverlay();   // ch.1 overlay colliders/meshes back on top
    }

    /** (Re)build the offscreen scene + bloom targets to match the surface. True = post is usable this
     *  frame. Requires a 24-bit FBO depth (OES_depth24): a 16-bit offscreen depth would re-introduce
     *  the B199 window moiré, so devices without it simply render directly, exactly as before. */
    private boolean ensurePostTargets() {
        if (postDead) return false;
        if (postW == width && postH == height) return postOk;
        String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        if (ext == null || !ext.contains("GL_OES_depth24")) { postDead = true; return false; }
        if (sceneFboId != 0) {                    // same context, size changed: release the old set
            GLES20.glDeleteFramebuffers(3, new int[]{sceneFboId, bloomFboA, bloomFboB}, 0);
            GLES20.glDeleteTextures(3, new int[]{sceneColorTex, bloomTexA, bloomTexB}, 0);
            GLES20.glDeleteRenderbuffers(1, new int[]{sceneDepthRb}, 0);
        }
        int bw = Math.max(1, width / BLOOM_DIV), bh = Math.max(1, height / BLOOM_DIV);
        int[] id = new int[1];
        sceneColorTex = makePostTex(width, height);
        GLES20.glGenRenderbuffers(1, id, 0); sceneDepthRb = id[0];
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, sceneDepthRb);
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, 0x81A6 /* DEPTH_COMPONENT24_OES */, width, height);
        GLES20.glGenFramebuffers(1, id, 0); sceneFboId = id[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, sceneColorTex, 0);
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, sceneDepthRb);
        boolean ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE;
        bloomTexA = makePostTex(bw, bh);
        GLES20.glGenFramebuffers(1, id, 0); bloomFboA = id[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bloomFboA);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, bloomTexA, 0);
        ok &= GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE;
        bloomTexB = makePostTex(bw, bh);
        GLES20.glGenFramebuffers(1, id, 0); bloomFboB = id[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bloomFboB);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, bloomTexB, 0);
        ok &= GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        postW = width; postH = height;
        postOk = ok;
        if (!ok) postDead = true;                 // a broken driver: stop probing, render directly forever
        return postOk;
    }

    private static int makePostTex(int w, int h) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return id[0];
    }

    private void postQuad(int attrib) {
        quad.position(0);
        GLES20.glEnableVertexAttribArray(attrib);
        GLES20.glVertexAttribPointer(attrib, 2, GLES20.GL_FLOAT, false, 8, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
    }

    /** Bright-pass -> quarter-res H blur -> V blur -> one composite (FXAA + bloom + grain) to screen. */
    private void resolvePost() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        int bw = Math.max(1, width / BLOOM_DIV), bh = Math.max(1, height / BLOOM_DIV);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glViewport(0, 0, bw, bh);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bloomFboA);
        GLES20.glUseProgram(progBright);
        GLES20.glUniform1i(uBrTex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneColorTex);
        postQuad(aPBr);
        GLES20.glUseProgram(progBlur);
        GLES20.glUniform1i(uBlTex, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bloomFboB);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bloomTexA);
        GLES20.glUniform2f(uBlDir, 1f / bw, 0f);
        postQuad(aPBl);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bloomFboA);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bloomTexB);
        GLES20.glUniform2f(uBlDir, 0f, 1f / bh);
        postQuad(aPBl);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(progPost);
        GLES20.glUniform1i(uPoScene, 0);
        GLES20.glUniform1i(uPoBloom, 1);
        GLES20.glUniform2f(uPoInvRes, 1f / width, 1f / height);
        GLES20.glUniform1f(uPoGrain, timeAcc % 10f);
        // Sun screen position for the flare/god-ray package: project the (infinite) sun direction with
        // this frame's proj*view; the flare window fades in as the sun enters the screen and dies with
        // overcast weather. Occlusion (a house in front of the sun) is handled in the shader itself.
        Matrix.multiplyMM(tmpA, 0, proj, 0, view, 0);
        float scw = tmpA[3] * sunDir[0] + tmpA[7] * sunDir[1] + tmpA[11] * sunDir[2];
        float flare = 0f, su = 0.5f, sv = 0.5f;
        if (scw > 0.001f) {
            su = (tmpA[0] * sunDir[0] + tmpA[4] * sunDir[1] + tmpA[8] * sunDir[2]) / scw * 0.5f + 0.5f;
            sv = (tmpA[1] * sunDir[0] + tmpA[5] * sunDir[1] + tmpA[9] * sunDir[2]) / scw * 0.5f + 0.5f;
            float m = Math.min(Math.min(su, 1f - su), Math.min(sv, 1f - sv));
            flare = clamp(0.35f + 3.2f * m, 0f, 1f)                    // fades out ~11% past the screen edge
                  * clamp((effSunInt - 0.62f) * 3f, 0f, 1f);           // clear skies only
        }
        GLES20.glUniform2f(uPoSun, su, sv);
        GLES20.glUniform1f(uPoFlare, flare);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bloomTexA);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneColorTex);
        postQuad(aPPo);
        GLES20.glDepthMask(true);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    /** Fill the 4 dynamic light slots: slot 0 = the muzzle flash kicking warm light onto nearby walls,
     *  the rest = the three nearest burning lanterns while the ABENDROT (dusk) weather holds. */
    private void fillPointLights() {
        int ln = 0;
        if (muzzleTimer > 0f && state == ST_PLAYING) {
            float mk = Math.min(1f, muzzleTimer / MUZZLE_TIME);
            ptPos[0] = px + camFwd[0] * 1.3f; ptPos[1] = py - 0.1f; ptPos[2] = pz + camFwd[2] * 1.3f;
            ptPos[3] = 1f / (8.5f * 8.5f);
            ptCol[0] = 2.4f * mk; ptCol[1] = 1.5f * mk; ptCol[2] = 0.55f * mk;
            ln = 1;
        }
        if (wDusk > 0.04f && lampWorld != null) {
            int n1 = -1, n2 = -1, n3 = -1; float d1 = 1e9f, d2 = 1e9f, d3 = 1e9f;
            for (int i = 0; i < lampWorld.length; i++) {
                float dx = lampWorld[i][0] - px, dz = lampWorld[i][1] - pz;
                float d = dx * dx + dz * dz;
                if (d < d1)      { d3 = d2; n3 = n2; d2 = d1; n2 = n1; d1 = d; n1 = i; }
                else if (d < d2) { d3 = d2; n3 = n2; d2 = d;  n2 = i; }
                else if (d < d3) { d3 = d;  n3 = i; }
            }
            float li = 1.15f * Math.min(1f, wDusk);
            for (int s = 0; s < 3 && ln < 4; s++) {
                int pick = s == 0 ? n1 : s == 1 ? n2 : n3;
                if (pick < 0) break;
                ptPos[ln * 4] = lampWorld[pick][0]; ptPos[ln * 4 + 1] = 3.05f; ptPos[ln * 4 + 2] = lampWorld[pick][1];
                ptPos[ln * 4 + 3] = 1f / (7.5f * 7.5f);
                ptCol[ln * 3] = 1.35f * li; ptCol[ln * 3 + 1] = 0.92f * li; ptCol[ln * 3 + 2] = 0.50f * li;
                ln++;
            }
        }
        for (int s = ln; s < 4; s++) {
            ptCol[s * 3] = 0f; ptCol[s * 3 + 1] = 0f; ptCol[s * 3 + 2] = 0f;
            ptPos[s * 4 + 3] = 1f;                    // benign falloff for dead slots
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        float dt = (now - lastNanos) / 1_000_000_000f;
        lastNanos = now;
        if (dt > 0.1f) dt = 0.1f;
        timeAcc += dt;
        updateWeather(dt);

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

        float fov;
        if (state == ST_EDIT) {
            // A much tighter near/far pair than gameplay's 0.05/300 (far:near = 6000:1, fine for a mostly-
            // horizontal FPS view but causes visible depth-buffer z-fighting bands on the editor's steep,
            // distant top-down orbit looking at near-coplanar ground meshes). 0.3/200 (far:near = 667:1) is
            // ample for edCamDist's 4-90m range and removes the banding.
            fov = 62f;
            Matrix.perspectiveM(proj, 0, fov, aspect, 0.3f, 200f);
        } else {
            float adsZoom = (curWeapon == 3) ? 46f : 30f;   // sniper zooms much harder through its scope
            fov = 72f - adsZoom * aim + 6f * sprintAnim;
            Matrix.perspectiveM(proj, 0, fov, aspect, 0.05f, 300f);
        }

        boolean postThis = ensurePostTargets();               // AAA post: the 3D scene renders offscreen...
        if (postThis) GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFboId);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // Sky
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glUseProgram(progSky);
        GLES20.glUniform3f(uSkyFwd, camFwd[0], camFwd[1], camFwd[2]);
        GLES20.glUniform3f(uSkyRight, camRight[0], camRight[1], camRight[2]);
        GLES20.glUniform3f(uSkyUp, camUp[0], camUp[1], camUp[2]);
        GLES20.glUniform3f(uSkySun, sunDir[0], sunDir[1], sunDir[2]);
        GLES20.glUniform3f(uSkyHorizon, effHorizon[0], effHorizon[1], effHorizon[2]);
        GLES20.glUniform3f(uSkyZenith, effZenith[0], effZenith[1], effZenith[2]);
        GLES20.glUniform1f(uSkyOvercast, wOvercast);
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
        GLES20.glUniform3f(uFogColor, effFog[0], effFog[1], effFog[2]);
        GLES20.glUniform1f(uSunInt, effSunInt);
        GLES20.glUniform1f(uAmbient, effAmbient);
        GLES20.glUniform2f(uFogRange, effFogStart, effFogEnd);
        // Wrapped at 10*PI: every FRAG3 animation frequency is a multiple of 0.2 rad/s, so the wrap is
        // seamless (integer cycles) — and uTime stays small enough that mediump/fp16 GPUs keep smooth
        // phase precision instead of the water/pulse animations quantizing as timeAcc grows unbounded.
        GLES20.glUniform1f(uTime, timeAcc % 31.415926f);
        GLES20.glUniform1f(uWorldUV, 0f);                      // ground/roads/veg use their baked mesh UVs
        fillPointLights();                                     // muzzle flash + ABENDROT lanterns
        GLES20.glUniform4fv(uPtLoc, 4, ptPos, 0);
        GLES20.glUniform3fv(uPtCLoc, 4, ptCol, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        // Weather ground grade: rain DARKENS the ground (wet albedo -- asphalt hardest, grass less, blue kept
        // higher so it reads cool-wet not muddy); snow FROSTS everything toward white. Both crossfade with
        // the weather (wRain/wSnow) and are zero on clear days and in the editor.
        float wetG = 1f - 0.30f * wRain, wetGB = 1f - 0.22f * wRain;
        float wetA = 1f - 0.42f * wRain, wetAB = 1f - 0.33f * wRain;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, terrainTex);
        Matrix.setIdentityM(model, 0);
        drawWorld(terrain, terrainVerts, 0f,
                ground[0] * wetG + (1.05f - ground[0]) * 0.55f * wSnow,
                ground[1] * wetG + (1.05f - ground[1]) * 0.55f * wSnow,
                ground[2] * wetGB + (1.05f - ground[2]) * 0.55f * wSnow);

        if (hasCustomRoads) {                                  // level-defined streets replace the default grid
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, roadTex);
            if (roadGroups != null) for (float[] gp : roadGroups) drawWorldRange(roadMesh, (int) gp[0], (int) gp[1], 0f,
                    gp[2] * wetA + (0.90f - gp[2]) * 0.30f * wSnow,
                    gp[3] * wetA + (0.90f - gp[3]) * 0.30f * wSnow,
                    gp[4] * wetAB + (0.90f - gp[4]) * 0.30f * wSnow);
            else drawWorld(roadMesh, roadVerts, 0f, wetA, wetA, wetAB);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cityTex);   // streets + sidewalks over the flat core
            drawWorld(cityGround, 6, 0f, wetA - 0.03f * wSnow, wetA - 0.03f * wSnow, wetAB - 0.03f * wSnow);
        }

        if (worldDirty) { rebuildWorldForLevel(); worldDirty = false; }   // story-chapter switch (GL thread)
        if (editDirty) rebuildOverlay();                       // editor: rebake overlay colliders + plant mesh (GL thread)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vegTex);    // grass / flowers / bushes
        Matrix.setIdentityM(model, 0);
        GLES20.glUniform1f(uSwayLoc, 1f);                      // plants lean into the wind (vertex sway)
        drawWorld(vegetation, vegVerts, 0f, 1f, 1f, 1f);
        if (placedTrees != null) drawWorld(placedTrees, placedTreeVerts, 0f, 1f, 1f, 1f);   // level-placed trees (same atlas)
        if (overlayTrees != null) { Matrix.setIdentityM(model, 0); drawWorld(overlayTrees, overlayTreeVerts, 0f, 1f, 1f, 1f); }   // editor-placed plants
        GLES20.glUniform1f(uSwayLoc, 0f);                      // everything else stands still

        drawShadows();

        GLES20.glUseProgram(prog3);
        GLES20.glUniform1f(uWorldUV, 1f);                      // walls: world-scale facade tiling + ground-contact AO
        int lastMat = -1;
        for (int i = 0; i < boxes.length; i++) {
            float[] b = boxes[i];
            if (b.length > 10 && b[10] != 0f) continue;       // collision-only proxy (interior furniture) — drawn via interiorMesh
            int mat = (b.length > 11) ? (int) b[11] : 0;      // 0 plaster (palette-tinted) · 1 brick · 2 stone · 3 timber
            if (mat != lastMat) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mat == 1 ? brickTex : mat == 2 ? stoneTex : mat == 3 ? sidingTex : mat == 4 ? gravelTex : wallTex);
                lastMat = mat;
            }
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, b[0], b[1], b[2]);
            if (b.length > 9 && b[9] != 0f) Matrix.rotateM(model, 0, b[9], 0f, 1f, 0f);   // props/fences carry a yaw
            Matrix.scaleM(model, 0, b[3], b[4], b[5]);
            float boost = (i == hitBox && hitTimer > 0f) ? 1.6f : 1f;
            // Plaster (mat 0) carries its own pastel colour; deepen it a touch so light facades read richer,
            // not washed-out next to the textured brick/stone walls. Brick/stone/timber stay white x texture.
            float cr = mat == 0 ? b[6] * 0.87f : 1f, cg = mat == 0 ? b[7] * 0.87f : 1f, cb = mat == 0 ? b[8] * 0.87f : 1f;
            if (mat == 4) { cr *= wetA; cg *= wetA; cb *= wetAB; }   // gravel paths darken in the rain too
            drawWorld(cube, 36, 0f, cr * boost, cg * boost, cb * boost);
        }
        GLES20.glUniform1f(uWorldUV, 0f);                      // restore mesh UVs for roofs/windows/bands/interiors

        Matrix.setIdentityM(model, 0);                         // pitched roofs + windows (baked, world-space)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, roofTex);    // neutral-grey shingle; per-house colour via uColor
        float sf = 0.75f * wSnow;                              // snow dusts the rooftops white during the SCHNEE phase
        if (roofGroups != null) {
            for (float[] gp : roofGroups) drawWorldRange(roofMesh, (int) gp[0], (int) gp[1], 0f,
                    gp[2] + (1.25f - gp[2]) * sf, gp[3] + (1.30f - gp[3]) * sf, gp[4] + (1.40f - gp[4]) * sf);
        } else {
            drawWorld(roofMesh, roofVerts, 0f, 0.69f + 0.56f * sf, 0.31f + 0.99f * sf, 0.16f + 1.24f * sf);
        }
        // Cozy inhabited rooms: the dark interior behind every pane glows warm amber exactly when the sky
        // turns grey (rain/fog/snow) -- tungsten ratio, crossfading with the weather.
        float cozy = 1f + 0.55f * wOvercast;
        if (revealVerts > 0) {                                 // dark recessed interior behind each pane, drawn first so it shows through the glass
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, winBackTex);
            drawWorld(revealMesh, revealVerts, 0f, cozy, cozy * 0.93f, cozy * 0.80f);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, winTex);
        GLES20.glEnable(GLES20.GL_BLEND);                       // glass panes: reflective + slightly see-through
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);                             // don't let glass occlude what's drawn after it
        if (winGroups != null) {                               // per-house glass tint
            for (float[] gp : winGroups) drawWorldRange(windowMesh, (int) gp[0], (int) gp[1], 5f, gp[2], gp[3], gp[4]);
        } else {
            drawWorld(windowMesh, windowVerts, 5f, 1f, 1f, 1f);
        }
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wallTex);   // light stone window sills
        drawWorld(trimMesh, trimVerts, 0f, 0.95f, 0.93f, 0.86f);
        if (bandGroups != null) {                              // per-house foundation / trim / storey bands
            for (float[] gp : bandGroups) drawWorldRange(bandMesh, (int) gp[0], (int) gp[1], 0f, gp[2], gp[3], gp[4]);
        }
        if (interiorGroups != null) {                          // baked walk-in interiors (floors/ceilings/furniture)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, woodTex);
            for (float[] gp : interiorGroups) drawWorldRange(interiorMesh, (int) gp[0], (int) gp[1], 0f, gp[2], gp[3], gp[4]);
        }

        // editor-placed houses: their own pitched roofs / windows / bands / interiors, same pipeline as the town.
        // Hidden only while a house is being dragged (its box-shell walls follow the finger instead).
        if (!edDraggingHouse) {
            if (ovRoofGroups != null && edInsideHouse < 0) {   // hide the roof while looking inside a house from above
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, roofTex);
                for (float[] gp : ovRoofGroups) drawWorldRange(ovRoofMesh, (int) gp[0], (int) gp[1], 0f,
                        gp[2] + (1.25f - gp[2]) * sf, gp[3] + (1.30f - gp[3]) * sf, gp[4] + (1.40f - gp[4]) * sf);
            }
            if (ovRevealVerts > 0) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, winBackTex);
                drawWorld(ovRevealMesh, ovRevealVerts, 0f, cozy, cozy * 0.93f, cozy * 0.80f);
            }
            if (ovWinGroups != null) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, winTex);
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                GLES20.glDepthMask(false);
                for (float[] gp : ovWinGroups) drawWorldRange(ovWinMesh, (int) gp[0], (int) gp[1], 5f, gp[2], gp[3], gp[4]);
                GLES20.glDepthMask(true);
                GLES20.glDisable(GLES20.GL_BLEND);
            }
            if (ovTrimVerts > 0) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wallTex);
                drawWorld(ovTrimMesh, ovTrimVerts, 0f, 0.95f, 0.93f, 0.86f);
            }
            if (ovBandGroups != null) {
                for (float[] gp : ovBandGroups) drawWorldRange(ovBandMesh, (int) gp[0], (int) gp[1], 0f, gp[2], gp[3], gp[4]);
            }
            if (ovIntGroups != null) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, woodTex);
                for (float[] gp : ovIntGroups) drawWorldRange(ovIntMesh, (int) gp[0], (int) gp[1], 0f, gp[2], gp[3], gp[4]);
            }
        }

        drawShadowMesh();                                      // projected shadows over ground, paving AND interior floors
        drawOverlayFurniture();                                // editor-placed furniture (drawn every frame, all states)
        if (state == ST_EDIT || state == ST_SANDBOX) drawEditGizmos();   // selection ring around the picked object (3D)

        if (wDusk > 0.04f && lampWorld != null) {              // ABENDROT: the lantern glass burns bright
            GLES20.glUseProgram(prog3);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, metalTex);
            float glow = Math.min(1f, wDusk * 1.4f);
            for (float[] lp : lampWorld) {
                float ldx = lp[0] - px, ldz = lp[1] - pz;
                if (ldx * ldx + ldz * ldz > 48f * 48f) continue;
                Matrix.setIdentityM(model, 0);
                Matrix.translateM(model, 0, lp[0], 3.14f, lp[1]);
                Matrix.scaleM(model, 0, 0.28f, 0.36f, 0.28f);
                drawWorld(cube, 36, 4f, 1.00f * glow, 0.86f * glow, 0.52f * glow);   // unlit-bright -> blooms
            }
        }
        drawDoors();
        drawWindows();
        drawWater();          // before the horde: a zombie between you and the fountain must cover the water
        drawEnemies();
        drawParticles();
        drawWeather();
        drawPickups();

        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
        if (state == ST_PLAYING) drawGun();

        if (postThis) resolvePost();                           // ...and lands on screen with FXAA + bloom + grain

        if (state == ST_PLAYING) drawHud();
        else if (state == ST_SUMMARY) drawSummary();
        else if (state == ST_EDIT) drawEditorHud();
        else if (state == ST_SANDBOX) drawSandboxHud();
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
        float speed = ENEMY_SPEED * Math.min(2.2f, 1f + 0.03f * (wave - 1)) * (1f + 0.07f * levelId);   // faster every wave + chapter
        float dmgMul = Math.min(1.8f, 1f + 0.025f * (wave - 1));                // and they hit harder
        float sinYaw = (float) Math.sin(yaw), cosYaw = (float) Math.cos(yaw);
        navUpdate(dt);                                     // keep the flow field flooded from the player
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
                // 0.50 = only leash beyond ~60 deg off-axis: wide phones render ~57 deg half-FOV, so the old
                // 42-deg cone could visibly teleport a zombie that was still on screen.
                if (facing < d * 0.50f && spawnPointNear(spawnTmp)) { enX[i] = spawnTmp[0]; enZ[i] = spawnTmp[1]; continue; }
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
                // Route: close + clear line -> straight lunge; else descend the flow field (with the
                // greedy probe as a local-clearance polish); else the old greedy steering alone.
                float desired = (float) Math.atan2(dx, dz);
                float a;
                if (d < 3.2f && navLineClear(enX[i], enZ[i], px, pz)) a = desired;
                else if (navFlowDir(enX[i], enZ[i], navFlow)) a = steerDir(enX[i], enZ[i], (float) Math.atan2(navFlow[0], navFlow[1]));
                else a = steerDir(enX[i], enZ[i], desired);
                enFace[i] = a;                            // face the way it actually moves
                float step = es * dt * (enHurt[i] > 0f ? 0.4f : 1f);   // flinch: stagger briefly when shot
                enX[i] += (float) Math.sin(a) * step;
                enZ[i] += (float) Math.cos(a) * step;
                enPhase[i] += step * 9f;                  // advance walk cycle with distance
                colTmp[0] = enX[i]; colTmp[1] = enZ[i];
                collide(0.4f, 0f, false, colTmp);         // backstop: never pass through cover
                enX[i] = colTmp[0]; enZ[i] = colTmp[1];
                // Watchdog: no real ground covered for a while -> a sideways shove to pop it off the
                // snag; still wedged, far away AND out of the player's view -> quiet relocation.
                enProgT[i] += dt;
                if (enProgT[i] >= 1.2f) {
                    if (Math.abs(enX[i] - enLastX[i]) + Math.abs(enZ[i] - enLastZ[i]) < 0.30f) {
                        enStuck[i] += enProgT[i];
                        float facing = -dx * sinYaw + dz * cosYaw;
                        if (enStuck[i] > 4.0f && d > 9f && facing < d * 0.45f && spawnPointNear(spawnTmp)) {
                            enX[i] = spawnTmp[0]; enZ[i] = spawnTmp[1];
                            enStuck[i] = 0f;
                        } else {
                            float ja = rng.nextFloat() * 6.2832f;
                            enKx[i] += (float) Math.sin(ja) * 2.4f;
                            enKz[i] += (float) Math.cos(ja) * 2.4f;
                        }
                    } else enStuck[i] = 0f;
                    enLastX[i] = enX[i]; enLastZ[i] = enZ[i];
                    enProgT[i] = 0f;
                }
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
            if (navFieldValid && tries < 20) {                                // never drop one into an enclosed pocket
                // (soft after 20 tries: with the player sealed indoors the WHOLE outdoors is unreachable,
                //  and a zombie waiting outside beats no zombie at all)
                int cx = navIx(x), cz = navIx(z);
                if (cx >= 0 && cz >= 0 && cx < NAV_N && cz < NAV_N && navDist[cz * NAV_N + cx] < 0) continue;
            }
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
                enStuck[i] = 0f; enProgT[i] = 0f; enLastX[i] = enX[i]; enLastZ[i] = enZ[i];
                enFaceType[i] = rng.nextInt(6);
                enVar[i] = rng.nextFloat();                            // per-zombie skin/wear/wound variety
                float hpMul = Math.min(6f, 1f + 0.09f * (wave - 1)) * (1f + 0.30f * levelId);   // tankier every wave + chapter
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
        int count = Math.round(34 * scale);
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
            pVY[i] = 2.9f + rng.nextFloat() * 4.0f;                   // pop up (taller launch = juicier burst)
            int c = rng.nextInt(4);
            if (c == 0)      { pR[i] = o[0]; pG[i] = o[1]; pB[i] = o[2]; }   // shirt
            else if (c == 1) { pR[i] = o[3]; pG[i] = o[4]; pB[i] = o[5]; }   // pants
            else if (c == 3) { pR[i] = 0.44f; pG[i] = 0.09f; pB[i] = 0.07f; } // dark gore garnish
            else             { pR[i] = 0.70f; pG[i] = 0.73f; pB[i] = 0.50f; } // pale undead skin
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
            if (n < 2) {                                             // two brief white-hot flecks give the impact its snap
                pR[i] = 1f; pG[i] = 0.97f; pB[i] = 0.82f;
                pMaxLife[i] = 0.12f; pLife[i] = 0.12f;
            }
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

    // --- Weather: cycle the look + tick/draw rain or snow ----------------------
    private void updateWeather(float dt) {
        boolean clearForEdit = (state == ST_EDIT);   // full visibility while building; the cycle just pauses, not skips
        if (!clearForEdit) {
            wxTimer -= dt;
            if (wxTimer <= 0f) { wxIdx = (wxIdx + 1) % WX_ORDER.length; wxTimer = WX_HOLD; weatherLabelT = 4.5f; }
            if (weatherLabelT > 0f) weatherLabelT -= dt;
        }
        int w = clearForEdit ? WX_SUN : WX_ORDER[wxIdx];
        // per-weather targets (Sun reads the level's base look so clear days are unchanged)
        float tHr, tHg, tHb, tZr, tZg, tZb, tFr, tFg, tFb, tOver, tFs, tFe, tSun, tAmb, tRain, tSnow, tDusk = 0f;
        if (w == WX_SUN) {
            tHr = skyHorizon[0]; tHg = skyHorizon[1]; tHb = skyHorizon[2];
            tZr = skyZenith[0];  tZg = skyZenith[1];  tZb = skyZenith[2];
            tFr = fog[0]; tFg = fog[1]; tFb = fog[2];
            tOver = 0f; tFs = fogStart; tFe = fogEnd; tSun = sunInt; tAmb = ambient; tRain = 0f; tSnow = 0f;
            if (clearForEdit) { tFs = 6000f; tFe = 14000f; }   // build mode: fog pushed far past the whole town -> no overhead haze
        } else if (w == WX_FOG) {
            tHr = 0.74f; tHg = 0.77f; tHb = 0.80f; tZr = 0.66f; tZg = 0.70f; tZb = 0.74f;
            tFr = 0.78f; tFg = 0.81f; tFb = 0.84f; tOver = 0.88f; tFs = 3f; tFe = 28f; tSun = 0.70f; tAmb = 1.15f; tRain = 0f; tSnow = 0f;
        } else if (w == WX_RAIN) {
            tHr = 0.50f; tHg = 0.53f; tHb = 0.58f; tZr = 0.39f; tZg = 0.43f; tZb = 0.49f;
            tFr = 0.54f; tFg = 0.57f; tFb = 0.62f; tOver = 1f; tFs = 16f; tFe = 72f; tSun = 0.55f; tAmb = 0.90f; tRain = 1f; tSnow = 0f;
        } else if (w == WX_DUSK) {
            // Golden hour: burning horizon under a deepening blue zenith, warm haze, sun still strong but
            // the ambient dropping — and the street lanterns come on (point lights + glowing bulbs + bloom).
            tHr = 0.98f; tHg = 0.60f; tHb = 0.34f; tZr = 0.28f; tZg = 0.31f; tZb = 0.52f;
            tFr = 0.68f; tFg = 0.53f; tFb = 0.46f; tOver = 0.12f; tFs = 38f; tFe = 105f; tSun = 0.95f; tAmb = 0.72f; tRain = 0f; tSnow = 0f;
            tDusk = 1f;
        } else {
            tHr = 0.82f; tHg = 0.84f; tHb = 0.88f; tZr = 0.74f; tZg = 0.78f; tZb = 0.84f;
            tFr = 0.85f; tFg = 0.87f; tFb = 0.90f; tOver = 0.95f; tFs = 12f; tFe = 58f; tSun = 0.78f; tAmb = 1.10f; tRain = 0f; tSnow = 1f;
        }
        float k = Math.min(1f, dt * 0.5f);                  // exponential crossfade (~2 s time constant)
        effHorizon[0] += (tHr - effHorizon[0]) * k; effHorizon[1] += (tHg - effHorizon[1]) * k; effHorizon[2] += (tHb - effHorizon[2]) * k;
        effZenith[0]  += (tZr - effZenith[0]) * k;  effZenith[1]  += (tZg - effZenith[1]) * k;  effZenith[2]  += (tZb - effZenith[2]) * k;
        effFog[0] += (tFr - effFog[0]) * k; effFog[1] += (tFg - effFog[1]) * k; effFog[2] += (tFb - effFog[2]) * k;
        wOvercast += (tOver - wOvercast) * k; effFogStart += (tFs - effFogStart) * k; effFogEnd += (tFe - effFogEnd) * k;
        effSunInt += (tSun - effSunInt) * k; effAmbient += (tAmb - effAmbient) * k;
        wRain += (tRain - wRain) * k; wSnow += (tSnow - wSnow) * k; wDusk += (tDusk - wDusk) * k;
        if (clearForEdit) { effFogStart = tFs; effFogEnd = tFe; wOvercast = 0f; }   // snap: zero haze from the first editor frame
        updateWeatherParticles(dt);
    }

    private void updateWeatherParticles(float dt) {
        float inten = Math.max(wRain, wSnow);
        if (inten < 0.02f) return;
        if (!wxInit) { for (int i = 0; i < MAX_WX; i++) respawnWx(i, true); wxInit = true; }
        boolean rainy = wRain >= wSnow;
        float fall = rainy ? 17f : 1.4f;
        int active = (int) (MAX_WX * inten);
        for (int i = 0; i < active; i++) {
            wxY[i] -= fall * dt;
            if (rainy) { wxX[i] += 1.6f * dt; wxZ[i] += 1.0f * dt; }                       // wind slant
            else { wxP[i] += dt; wxX[i] += (float) Math.sin(wxP[i] * 2f + i) * 0.5f * dt; wxZ[i] += (float) Math.cos(wxP[i] * 1.6f + i) * 0.5f * dt; }
            if (wxY[i] < py - 2.5f || Math.abs(wxX[i] - px) > 17f || Math.abs(wxZ[i] - pz) > 17f) respawnWx(i, false);
        }
    }

    private void respawnWx(int i, boolean anywhere) {
        wxX[i] = px + (wxRnd.nextFloat() - 0.5f) * 32f;
        wxZ[i] = pz + (wxRnd.nextFloat() - 0.5f) * 32f;
        wxY[i] = anywhere ? py + (wxRnd.nextFloat() - 0.3f) * 16f : py + 9f + wxRnd.nextFloat() * 7f;
        wxP[i] = wxRnd.nextFloat() * 6.28f;
    }

    private void drawWeather() {
        float inten = Math.max(wRain, wSnow);
        if (!wxInit || inten < 0.02f) return;
        boolean rainy = wRain >= wSnow;
        GLES20.glUseProgram(prog3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, metalTex);   // mode 3 ignores the texture
        GLES20.glDepthMask(false);                             // flakes/streaks don't occlude pickups or each other
        int active = (int) (MAX_WX * inten);
        float br = 0.7f + 0.6f * inten;
        if (rainy) {                                           // rain: additive translucent streaks (glint on dark
            GLES20.glEnable(GLES20.GL_BLEND);                  //  facades, melt into the bright sky -- like real rain)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        }
        for (int i = 0; i < active; i++) {
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, wxX[i], wxY[i], wxZ[i]);
            if (rainy) {
                // tilt each streak to match its actual motion vector (wind 1.6,1.0 vs fall 17 -> 6.3 deg)
                Matrix.rotateM(model, 0, 6.3f, 0.53f, 0f, -0.848f);
                float len = 0.35f + 0.05f * wxP[i];            // per-drop length jitter (wxP holds 0..6.28)
                Matrix.scaleM(model, 0, 0.012f, len, 0.012f);
                drawWorld(cube, 36, 4f, 0.14f * br, 0.16f * br, 0.20f * br);     // flat emissive, adds ~0.2 luminance
            } else {
                Matrix.scaleM(model, 0, 0.07f, 0.07f, 0.07f);
                drawWorld(cube, 36, 3f, 0.95f * br, 0.96f * br, 1.0f * br);      // white snowflake
            }
        }
        if (rainy) GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDepthMask(true);
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
            Matrix.rotateM(model, 0, pLife[i] * 430f + i * 61f, 0.42f, 1f, 0.31f);   // tumble as it flies
            Matrix.scaleM(model, 0, s, s, s);
            float boost = 1f + 1.5f * Math.max(0f, (lf - 0.65f) / 0.35f);   // smooth spawn flash (was a hard step)
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
            Matrix.setIdentityM(winBase, 0);                       // reuse the window hinge scratch as pickup base
            Matrix.translateM(winBase, 0, pkX[i], pkY[i] + bob, pkZ[i]);
            Matrix.rotateM(winBase, 0, spin, 0f, 1f, 0f);
            Matrix.rotateM(winBase, 0, 24f, 1f, 0f, 0.30f);
            if (pkType[i] == 0) {                                  // HEALTH: a white medkit with a red cross + latch
                pkBit(0f, 0f, 0f, 0.30f, 0.20f, 0.22f, 0.92f * fade, 0.92f * fade, 0.90f * fade);
                pkBit(0f, 0.005f, 0.115f, 0.056f, 0.16f, 0.012f, 0.90f * fade, 0.12f * fade, 0.12f * fade);   // cross |
                pkBit(0f, 0.005f, 0.115f, 0.16f, 0.056f, 0.012f, 0.90f * fade, 0.12f * fade, 0.12f * fade);   // cross -
                pkBit(0f, 0.105f, 0f, 0.31f, 0.02f, 0.23f, 0.70f * fade, 0.70f * fade, 0.68f * fade);         // lid seam
                pkBit(0f, 0.045f, -0.115f, 0.10f, 0.05f, 0.012f, 0.45f * fade, 0.45f * fade, 0.47f * fade);   // latch
            } else {                                               // AMMO: an olive crate with brass tips poking out
                pkBit(0f, -0.02f, 0f, 0.30f, 0.16f, 0.22f, 0.30f * fade, 0.32f * fade, 0.18f * fade);
                pkBit(0f, 0.065f, 0f, 0.31f, 0.025f, 0.23f, 0.20f * fade, 0.215f * fade, 0.12f * fade);       // lid rim
                for (int s2 = -1; s2 <= 1; s2++)                                                              // cartridges
                    pkBit(s2 * 0.085f, 0.105f, 0f, 0.045f, 0.09f, 0.045f, 0.85f * fade, 0.66f * fade, 0.25f * fade);
                pkBit(0f, 0f, 0.115f, 0.20f, 0.05f, 0.012f, 0.75f * fade, 0.62f * fade, 0.22f * fade);        // stencil band
            }
        }
    }

    /** One box of a pickup in its spin frame (offsets/sizes in metres, sizes full). */
    private void pkBit(float lx, float ly, float lz, float sx, float sy, float sz, float r, float g, float b) {
        System.arraycopy(winBase, 0, model, 0, 16);
        Matrix.translateM(model, 0, lx, ly, lz);
        Matrix.scaleM(model, 0, sx, sy, sz);
        drawWorld(cube, 36, 3f, r, g, b);
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

    // --- flow-field navigation ---

    private static int navIx(float w) { return (int) ((w - NAV_ORG) / NAV_CELL); }
    private static float navCx(int i) { return NAV_ORG + (i + 0.5f) * NAV_CELL; }

    /** Bake the static walkability grid: a cell is blocked when a box that would deflect a zombie
     *  (same height-band criteria as clearOfBuildings) covers its centre, inflated by the zombie
     *  radius. Doors are NOT baked — they are overlaid per BFS pass from their live open state. */
    private void navBake() {
        if (navDist == null) { navDist = new short[NAV_N * NAV_N]; navQ = new int[NAV_N * NAV_N]; }
        boolean[] blk = new boolean[NAV_N * NAV_N];
        float[][] bxs = boxes != null ? boxes : new float[0][];
        for (float[] b : bxs) {
            boolean interior = b.length > 10 && b[10] != 0f;
            if (!interior && b[1] + b[4] * 0.5f < 0.5f) continue;   // low enough to shamble over
            if (b[1] - b[4] * 0.5f >= 1.8f) continue;               // overhead: walks under
            navMarkRect(blk, null, b[0], b[2], b[3] * 0.5f, b[5] * 0.5f, b.length > 9 ? b[9] : 0f);
        }
        int nd = doorData == null ? 0 : doorData.length;
        navDoorCells = new int[nd][];
        java.util.List<Integer> cells = new java.util.ArrayList<Integer>();
        for (int i = 0; i < nd; i++) {
            float[] dd = doorData[i];
            float hx = dd[6] == 0f ? dd[3] : dd[5];
            float hz = dd[6] == 0f ? dd[5] : dd[3];
            cells.clear();
            navMarkRect(null, cells, dd[0], dd[2], hx, hz, dd.length > 13 ? dd[13] : 0f);
            int[] arr = new int[cells.size()];
            for (int c = 0; c < arr.length; c++) arr[c] = cells.get(c);
            navDoorCells[i] = arr;
        }
        navBlocked = blk;
        navFieldValid = false;
        navPx = 1e9f;                                                // force a BFS refresh on the next tick
    }

    /** Mark (or collect) every grid cell whose centre lies inside the yawed rect inflated by the
     *  zombie radius. Conservative outer AABB first, exact insideYawXZ per cell. */
    private static void navMarkRect(boolean[] blk, java.util.List<Integer> sink,
                                    float cx, float cz, float hx, float hz, float yawDeg) {
        float ihx = hx + 0.36f, ihz = hz + 0.36f;
        double a = Math.toRadians(yawDeg);
        float ca = Math.abs((float) Math.cos(a)), sa = Math.abs((float) Math.sin(a));
        float ex = ihx * ca + ihz * sa, ez = ihx * sa + ihz * ca;    // world-axis extents of the rotated rect
        int x0 = Math.max(0, navIx(cx - ex)), x1 = Math.min(NAV_N - 1, navIx(cx + ex));
        int z0 = Math.max(0, navIx(cz - ez)), z1 = Math.min(NAV_N - 1, navIx(cz + ez));
        for (int iz = z0; iz <= z1; iz++) for (int ix = x0; ix <= x1; ix++) {
            if (!insideYawXZ(navCx(ix), navCx(iz), cx, cz, ihx, ihz, yawDeg)) continue;
            if (blk != null) blk[iz * NAV_N + ix] = true;
            if (sink != null) sink.add(iz * NAV_N + ix);
        }
    }

    /** Re-flood the distance field from the player: closed doors become temporary walls, then a
     *  plain 8-connected BFS (diagonals may not cut blocked corners) fills steps-to-player. */
    private void navRefresh() {
        java.util.Arrays.fill(navDist, (short) -1);
        for (int i = 0; i < navDoorCells.length; i++) {
            if (doorOpen[i] > 0.5f) continue;
            for (int c : navDoorCells[i]) navDist[c] = -2;
        }
        int scx = Math.max(0, Math.min(NAV_N - 1, navIx(px)));
        int scz = Math.max(0, Math.min(NAV_N - 1, navIx(pz)));
        int start = -1;
        for (int rad = 0; rad <= 3 && start < 0; rad++) {            // player may stand on a blocked cell edge
            for (int dz = -rad; dz <= rad && start < 0; dz++) for (int dx = -rad; dx <= rad; dx++) {
                int nx = scx + dx, nz = scz + dz;
                if (nx < 0 || nz < 0 || nx >= NAV_N || nz >= NAV_N) continue;
                int c = nz * NAV_N + nx;
                if (!navBlocked[c] && navDist[c] == -1) { start = c; break; }
            }
        }
        navFieldValid = start >= 0;
        if (start < 0) return;
        int head = 0, tail = 0;
        navQ[tail++] = start; navDist[start] = 0;
        while (head < tail) {
            int c = navQ[head++];
            int cx = c % NAV_N, cz = c / NAV_N;
            short nd = (short) (navDist[c] + 1);
            for (int k = 0; k < 8; k++) {
                int nx = cx + NAV_DX[k], nz = cz + NAV_DZ[k];
                if (nx < 0 || nz < 0 || nx >= NAV_N || nz >= NAV_N) continue;
                int n = nz * NAV_N + nx;
                if (navDist[n] != -1 || navBlocked[n]) continue;
                if (k >= 4) {                                        // diagonal: both flanking cells must be open
                    int n1 = cz * NAV_N + nx, n2 = nz * NAV_N + cx;
                    if (navBlocked[n1] || navDist[n1] == -2 || navBlocked[n2] || navDist[n2] == -2) continue;
                }
                navDist[n] = nd;
                navQ[tail++] = n;
            }
        }
    }

    /** Rebake/reflood as needed: cheap to call every frame while playing. */
    private void navUpdate(float dt) {
        if (navBlocked == null) navBake();
        navTimer -= dt;
        if (navTimer <= 0f || Math.abs(px - navPx) + Math.abs(pz - navPz) > 0.9f) {
            navRefresh();
            navTimer = 0.45f;
            navPx = px; navPz = pz;
        }
    }

    /** Descend the distance field at (ex,ez): out = normalized flow direction. False when the spot
     *  is off-grid or the field can't see it (caller falls back to greedy steering + leash). */
    private boolean navFlowDir(float ex, float ez, float[] out) {
        if (!navFieldValid) return false;
        int cx = navIx(ex), cz = navIx(ez);
        if (cx < 1 || cz < 1 || cx >= NAV_N - 1 || cz >= NAV_N - 1) return false;
        int c = cz * NAV_N + cx;
        int dc = navDist[c];
        if (dc == 0) return false;                                   // sharing the player's cell: walk straight
        if (dc < 0) {                                                // wedged on a blocked cell: step to any seen neighbour
            int best = -1, bd = Integer.MAX_VALUE;
            for (int k = 0; k < 8; k++) {
                int n = (cz + NAV_DZ[k]) * NAV_N + (cx + NAV_DX[k]);
                if (navDist[n] >= 0 && navDist[n] < bd) { bd = navDist[n]; best = k; }
            }
            if (best < 0) return false;
            float l = best >= 4 ? 0.7071f : 1f;
            out[0] = NAV_DX[best] * l; out[1] = NAV_DZ[best] * l;
            return true;
        }
        float fx = 0f, fz = 0f;
        for (int k = 0; k < 8; k++) {                                // weighted downhill blend = smooth heading
            int n = (cz + NAV_DZ[k]) * NAV_N + (cx + NAV_DX[k]);
            if (navDist[n] < 0) continue;
            float w = dc - navDist[n];
            if (w <= 0f) continue;
            float il = k >= 4 ? 0.7071f : 1f;
            fx += NAV_DX[k] * w * il; fz += NAV_DZ[k] * w * il;
        }
        float l = (float) Math.sqrt(fx * fx + fz * fz);
        if (l < 1e-4f) return false;
        out[0] = fx / l; out[1] = fz / l;
        return true;
    }

    /** A cheap straight-line probe (used to switch from field-following to a direct lunge). */
    private boolean navLineClear(float x0, float z0, float x1, float z1) {
        float dx = x1 - x0, dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        int n = (int) (len / 0.5f) + 1;
        for (int i = 1; i < n; i++) {
            float u = i / (float) n;
            if (!clearOfBuildings(x0 + dx * u, z0 + dz * u, 0.30f)) return false;
        }
        return true;
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
            float yawB = b.length > 9 ? b[9] : 0f;
            if (yawB != 0f) {                             // rotated wall/fence: test in the box's own frame
                if (insideYawXZ(x, z, b[0], b[2], b[3] * 0.5f + r, b[5] * 0.5f + r, yawB)) return false;
                continue;
            }
            if (x > b[0] - b[3] * 0.5f - r && x < b[0] + b[3] * 0.5f + r
             && z > b[2] - b[5] * 0.5f - r && z < b[2] + b[5] * 0.5f + r) return false;
        }
        return true;
    }

    private void drawEnemies() {
        GLES20.glUseProgram(prog3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, metalTex);
        GLES20.glUniform1f(uCharLoc, 1f);                 // wrap-lit characters (reset at the end)
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
            // Per-zombie variety: pale sickly skin graded from greenish to grey, clothing wear, wound layout.
            float sv = enVar[i], wv = (sv * 7.31f) % 1f;
            float wear = 0.76f + 0.34f * wv;
            float skR = 0.64f + 0.16f * sv, skG = 0.74f - 0.04f * sv, skB = 0.44f + 0.14f * sv;
            float[] o = OUTFITS[enOutfit[i]];
            float shR = (o[0] * wear + hf) * (1f - wr) + 1.00f * wr, shG = (o[1] * wear + hf) * (1f - wr) + 0.16f * wr, shB = (o[2] * wear + hf) * (1f - wr) + 0.13f * wr;
            float paR = (o[3] * wear + hf) * (1f - wr) + 1.00f * wr, paG = (o[4] * wear + hf) * (1f - wr) + 0.16f * wr, paB = (o[5] * wear + hf) * (1f - wr) + 0.13f * wr;
            float hdR = (skR + hf) * (1f - wr) + 1.00f * wr, hdG = (skG + hf) * (1f - wr) + 0.16f * wr, hdB = (skB + hf) * (1f - wr) + 0.13f * wr;
            float blR = (0.34f + hf) * (1f - wr) + 1.00f * wr, blG = (0.06f + hf * 0.3f) * (1f - wr) + 0.16f * wr, blB = (0.05f + hf * 0.3f) * (1f - wr) + 0.13f * wr;
            boolean crawler = enType[i] == 1 && enBoss[i] == 0;
            if (crawler) bob *= 0.5f;                          // crawl lurch is flatter than a walk bob
            Matrix.setIdentityM(gunBase, 0);                  // reuse as enemy base
            Matrix.translateM(gunBase, 0, enX[i], terrainH(enX[i], enZ[i]) + bob, enZ[i]);
            Matrix.rotateM(gunBase, 0, (float) Math.toDegrees(enFace[i]), 0f, 1f, 0f);
            if (enScale[i] != 1f) Matrix.scaleM(gunBase, 0, enScale[i], enScale[i], enScale[i]);  // bosses are bigger

            if (crawler) {
                // ===== CRAWLER: no legs -- a torn torso hauling itself on its hands =====
                // chest propped up on the arms, pelvis stump dragging along the ground behind it
                enemyPart(0f, 0.26f, -0.02f, 0.44f, 0.24f, 0.66f, shR, shG, shB);               // prone torso
                enemyPart(0f, 0.17f, -0.44f, 0.38f, 0.20f, 0.26f, paR, paG, paB);               // pelvis stump, dragging
                enemyPart(0f, 0.16f, -0.585f, 0.30f, 0.16f, 0.05f, blR, blG, blB);              // torn-off edge
                enemyPart(0f, 0.40f, 0.38f, 0.34f, 0.34f, 0.34f, hdR, hdG, hdB);                // head, raised in front
                faceDy = -1.10f; faceDz = 0.38f;
                enemyFace(enFaceType[i]);
                faceDy = 0f; faceDz = 0f;
                enemyPart(0f, 0.09f, -0.68f, 0.16f, 0.10f, 0.22f, blR * 0.85f, blG, blB);        // dragged entrails
                enemyPart(0.07f, 0.07f, -0.86f, 0.10f, 0.07f, 0.16f, blR * 0.7f, blG, blB);
                float crAsym = (sv - 0.5f) * 10f;
                float aL = -64f + crAsym + sw * 12f, aR = -64f - crAsym - sw * 12f;             // alternating crawl pull
                enemyLimb(-0.27f, 0.34f, 0.22f, 0.14f, 0.13f, 0.28f, 0.16f, aL, shR, shG, shB); // sleeves
                enemyLimb( 0.27f, 0.34f, 0.22f, 0.14f, 0.13f, 0.28f, 0.16f, aR, shR, shG, shB);
                enemyLimb(-0.27f, 0.34f, 0.22f, 0.38f, 0.12f, 0.24f, 0.15f, aL, hdR, hdG, hdB); // bare forearms
                enemyLimb( 0.27f, 0.34f, 0.22f, 0.38f, 0.12f, 0.24f, 0.15f, aR, hdR, hdG, hdB);
                enemyLimb(-0.27f, 0.34f, 0.22f, 0.50f, 0.13f, 0.11f, 0.16f, aL, hdR * 0.85f, hdG * 0.85f, hdB * 0.85f); // hands
                enemyLimb( 0.27f, 0.34f, 0.22f, 0.50f, 0.13f, 0.11f, 0.16f, aR, hdR * 0.85f, hdG * 0.85f, hdB * 0.85f);
                enemyLimb(-0.27f, 0.34f, 0.22f, 0.575f, 0.11f, 0.05f, 0.13f, aL, hdR * 0.60f, hdG * 0.58f, hdB * 0.52f); // clawing fingertips
                enemyLimb( 0.27f, 0.34f, 0.22f, 0.575f, 0.11f, 0.05f, 0.13f, aR, hdR * 0.60f, hdG * 0.58f, hdB * 0.52f);
                enemyPart(0f, 0.585f, 0.40f, 0.30f, 0.10f, 0.26f, 0.16f + 0.10f * sv, 0.11f, 0.06f);   // matted hair
                enemyPart(0f, 0.335f, -0.02f, 0.10f, 0.06f, 0.62f, shR * 0.62f, shG * 0.62f, shB * 0.62f);  // spine ridge tear
            } else {
                // ===== WALKER / BRUTE: hunched shamble, arms reaching for the player =====
                float tw = enType[i] == 2 ? 0.56f : 0.50f;                                      // brutes are broader
                enemyPart(0f, 0.92f, 0.02f, tw, 0.70f, 0.30f, shR, shG, shB);                   // torso, pitched forward
                enemyPart(0f, 1.24f, -0.09f, tw * 0.88f, 0.26f, 0.24f, shR * 0.86f, shG * 0.86f, shB * 0.86f); // hunched upper back
                enemyPart(0f, 1.33f, 0.04f, 0.16f, 0.10f, 0.16f, hdR * 0.92f, hdG * 0.92f, hdB * 0.92f);       // neck
                enemyPart(0f, 1.47f, 0.10f, 0.34f, 0.34f, 0.34f, hdR, hdG, hdB);                // head, jutting forward
                enemyPart(0f, 1.60f, 0.125f, 0.325f, 0.07f, 0.29f, hdR * 0.84f, hdG * 0.84f, hdB * 0.84f);  // heavy brow ledge
                enemyPart(0f, 1.355f, 0.115f, 0.295f, 0.115f, 0.295f, hdR * 0.90f, hdG * 0.88f, hdB * 0.86f); // sunken jaw step
                faceDy = -0.03f; faceDz = 0.10f;
                enemyFace(enFaceType[i]);
                faceDy = 0f; faceDz = 0f;
                // tattered shirt hem strips + wound patches laid out by the per-zombie seed
                enemyPart(-0.16f, 0.52f, 0.05f, 0.11f, 0.15f, 0.30f, shR * 0.80f, shG * 0.80f, shB * 0.80f);
                enemyPart( 0.06f, 0.49f, 0.02f, 0.10f, 0.13f, 0.30f, shR * 0.74f, shG * 0.74f, shB * 0.74f);
                enemyPart( 0.18f, 0.52f, 0.04f, 0.09f, 0.14f, 0.30f, shR * 0.82f, shG * 0.82f, shB * 0.82f);
                if (sv > 0.35f) enemyPart(0.12f, 1.04f, 0.155f, 0.15f, 0.18f, 0.02f, blR, blG, blB);   // torso wound
                if (sv > 0.70f) enemyPart(-0.15f, 0.86f, 0.155f, 0.11f, 0.13f, 0.02f, blR, blG, blB);  // second gash
                // shambling legs: one drags behind (asymmetric swing), boots track the same angles
                float lL = sw * 20f, lR = -sw * 30f + 7f;
                enemyLimb(-0.14f, 0.70f, 0f, 0.35f, 0.18f, 0.70f, 0.22f, lL, paR, paG, paB);
                enemyLimb( 0.14f, 0.70f, 0f, 0.35f, 0.18f, 0.70f, 0.22f, lR, paR, paG, paB);
                enemyLimb(-0.14f, 0.70f, 0.02f, 0.615f, 0.20f, 0.17f, 0.28f, lL, paR * 0.42f, paG * 0.38f, paB * 0.34f);
                enemyLimb( 0.14f, 0.70f, 0.02f, 0.615f, 0.20f, 0.17f, 0.28f, lR, paR * 0.42f, paG * 0.38f, paB * 0.34f);
                // the classic zombie reach: arms thrust forward, swaying slightly, rising during the telegraph.
                // Per-zombie asymmetry: one arm hangs lower than the other, so the horde stops mirror-cloning.
                float armAsym = (sv - 0.5f) * 16f;
                float aL = -74f + armAsym + sw * 9f - wr * 16f, aR = -74f - armAsym * 0.6f - sw * 9f - wr * 16f;
                enemyLimb(-0.33f, 1.28f, 0.02f, 0.16f, 0.13f, 0.32f, 0.16f, aL, shR, shG, shB); // sleeves
                enemyLimb( 0.33f, 1.28f, 0.02f, 0.16f, 0.13f, 0.32f, 0.16f, aR, shR, shG, shB);
                enemyLimb(-0.33f, 1.28f, 0.02f, 0.42f, 0.12f, 0.26f, 0.15f, aL, hdR, hdG, hdB); // bare forearms
                enemyLimb( 0.33f, 1.28f, 0.02f, 0.42f, 0.12f, 0.26f, 0.15f, aR, hdR, hdG, hdB);
                enemyLimb(-0.33f, 1.28f, 0.02f, 0.585f, 0.13f, 0.11f, 0.16f, aL, hdR * 0.85f, hdG * 0.85f, hdB * 0.85f); // hands
                enemyLimb( 0.33f, 1.28f, 0.02f, 0.585f, 0.13f, 0.11f, 0.16f, aR, hdR * 0.85f, hdG * 0.85f, hdB * 0.85f);
                enemyLimb(-0.33f, 1.28f, 0.02f, 0.660f, 0.11f, 0.045f, 0.10f, aL, hdR * 0.60f, hdG * 0.58f, hdB * 0.52f); // grasping fingertips
                enemyLimb( 0.33f, 1.28f, 0.02f, 0.660f, 0.11f, 0.045f, 0.10f, aR, hdR * 0.60f, hdG * 0.58f, hdB * 0.52f);
                enemyPart(0f, 0.635f, 0.02f, tw * 0.98f, 0.075f, 0.315f, 0.16f, 0.11f, 0.07f);          // leather belt
                enemyPart(0f, 0.635f, 0.175f, 0.09f, 0.055f, 0.02f, 0.55f, 0.50f, 0.34f);               // buckle
                enemyPart(-0.245f, 1.235f, 0.03f, 0.13f, 0.09f, 0.28f, shR * 0.70f, shG * 0.70f, shB * 0.70f);  // torn shoulder seams
                enemyPart( 0.245f, 1.235f, 0.03f, 0.13f, 0.09f, 0.28f, shR * 0.66f, shG * 0.66f, shB * 0.66f);
                if (wv < 0.72f) {                                                                        // most keep matted hair...
                    enemyPart(0f, 1.605f, 0.055f, 0.30f, 0.09f, 0.30f, 0.16f + 0.10f * sv, 0.11f, 0.06f);
                    enemyPart(0f, 1.545f, -0.055f, 0.34f, 0.16f, 0.10f, 0.14f + 0.10f * sv, 0.10f, 0.06f);
                } else {                                                                                 // ...the rest went bald + scalp gash
                    enemyPart(0.06f, 1.625f, 0.02f, 0.16f, 0.03f, 0.20f, blR * 0.8f, blG, blB);
                }
                enemyPart(0f, 1.315f, 0.00f, 0.32f, 0.09f, 0.30f, shR * 0.88f, shG * 0.88f, shB * 0.88f);   // shirt collar
                if (wv > 0.45f) enemyPart(0.115f, 1.335f, 0.075f, 0.10f, 0.10f, 0.12f, blR, blG, blB);      // neck bite
                if (sv > 0.55f) {                                                                        // ribs bared through the shirt
                    enemyPart(-0.03f, 1.005f, 0.158f, 0.26f, 0.30f, 0.015f, 0.10f, 0.045f, 0.04f);       // dark cavity
                    for (int rb = 0; rb < 3; rb++)
                        enemyPart(-0.03f, 1.105f - rb * 0.085f, 0.166f, 0.24f, 0.032f, 0.012f, 0.80f, 0.75f, 0.62f);
                }
                enemyLimb(-0.14f, 0.70f, 0.02f, 0.30f, 0.20f, 0.13f, 0.245f, lL, paR * 0.88f, paG * 0.88f, paB * 0.88f);  // knee wraps
                enemyLimb( 0.14f, 0.70f, 0.02f, 0.30f, 0.20f, 0.13f, 0.245f, lR, paR * 0.88f, paG * 0.88f, paB * 0.88f);
                enemyLimb(-0.14f, 0.70f, 0.075f, 0.655f, 0.19f, 0.09f, 0.20f, lL, paR * 0.36f, paG * 0.33f, paB * 0.30f); // boot toes
                enemyLimb( 0.14f, 0.70f, 0.075f, 0.655f, 0.19f, 0.09f, 0.20f, lR, paR * 0.36f, paG * 0.33f, paB * 0.30f);
                enemyLimb(-0.33f, 1.28f, 0.02f, 0.335f, 0.15f, 0.055f, 0.175f, aL, shR * 0.72f, shG * 0.72f, shB * 0.72f); // sleeve cuffs
                enemyLimb( 0.33f, 1.28f, 0.02f, 0.335f, 0.15f, 0.055f, 0.175f, aR, shR * 0.72f, shG * 0.72f, shB * 0.72f);
            }
        }
        GLES20.glUniform1f(uCharLoc, 0f);                 // back to architectural lighting
    }

    /** Six undead faces (one chosen per enemy), drawn UNLIT (mode 4) for high contrast; local +z always
     *  faces the player. Dark hollow sockets + small emissive red pupils are the shared zombie tell. */
    private void enemyFace(int t) {
        float fz = 0.176f, fzp = fz + 0.010f;
        switch (t) {
            case 0:   // hollow stare, jaw hanging open, chin drip
                enemyFacePart(-0.085f, 1.555f, fz, 0.080f, 0.078f, 0.02f, 0.03f, 0.02f, 0.02f);
                enemyFacePart( 0.085f, 1.555f, fz, 0.080f, 0.078f, 0.02f, 0.03f, 0.02f, 0.02f);
                enemyFacePart(-0.085f, 1.550f, fzp, 0.030f, 0.030f, 0.015f, 0.95f, 0.18f, 0.10f);
                enemyFacePart( 0.085f, 1.550f, fzp, 0.030f, 0.030f, 0.015f, 0.95f, 0.18f, 0.10f);
                enemyFacePart( 0.000f, 1.408f, fz, 0.110f, 0.130f, 0.02f, 0.05f, 0.02f, 0.02f);   // hanging jaw
                enemyFacePart( 0.020f, 1.330f, fz, 0.028f, 0.085f, 0.02f, 0.34f, 0.06f, 0.05f);   // drip
                break;
            case 1:   // rage: bright glowing eyes, gritted teeth
                enemyFacePart(-0.085f, 1.555f, fz, 0.062f, 0.060f, 0.02f, 0.03f, 0.02f, 0.02f);
                enemyFacePart( 0.085f, 1.555f, fz, 0.062f, 0.060f, 0.02f, 0.03f, 0.02f, 0.02f);
                enemyFacePart(-0.085f, 1.552f, fzp, 0.040f, 0.040f, 0.015f, 1.00f, 0.22f, 0.10f);
                enemyFacePart( 0.085f, 1.552f, fzp, 0.040f, 0.040f, 0.015f, 1.00f, 0.22f, 0.10f);
                enemyFacePart( 0.000f, 1.435f, fz, 0.170f, 0.040f, 0.02f, 0.05f, 0.02f, 0.02f);   // gritted line
                enemyFacePart(-0.035f, 1.437f, fzp, 0.028f, 0.030f, 0.012f, 0.82f, 0.80f, 0.72f); // teeth
                enemyFacePart( 0.035f, 1.437f, fzp, 0.028f, 0.030f, 0.012f, 0.82f, 0.80f, 0.72f);
                break;
            case 2:   // one-eyed, scar cross over the lost eye, snarl
                enemyFacePart(-0.085f, 1.555f, fz, 0.075f, 0.072f, 0.02f, 0.03f, 0.02f, 0.02f);
                enemyFacePart(-0.085f, 1.550f, fzp, 0.032f, 0.032f, 0.015f, 0.95f, 0.18f, 0.10f);
                enemyFacePart( 0.085f, 1.555f, fz, 0.028f, 0.120f, 0.02f, 0.34f, 0.06f, 0.05f);   // scar |
                enemyFacePart( 0.085f, 1.555f, fz, 0.100f, 0.026f, 0.02f, 0.34f, 0.06f, 0.05f);   // scar -
                enemyFacePart( 0.020f, 1.432f, fz, 0.120f, 0.038f, 0.02f, 0.05f, 0.02f, 0.02f);   // snarl
                enemyFacePart(-0.070f, 1.448f, fz, 0.045f, 0.030f, 0.02f, 0.05f, 0.02f, 0.02f);
                break;
            case 3:   // moaner: slit eyes, tall open mouth, drip
                enemyFacePart(-0.085f, 1.562f, fz, 0.072f, 0.034f, 0.02f, 0.03f, 0.02f, 0.02f);
                enemyFacePart( 0.085f, 1.562f, fz, 0.072f, 0.034f, 0.02f, 0.03f, 0.02f, 0.02f);
                enemyFacePart(-0.080f, 1.560f, fzp, 0.026f, 0.024f, 0.015f, 0.95f, 0.18f, 0.10f);
                enemyFacePart( 0.080f, 1.560f, fzp, 0.026f, 0.024f, 0.015f, 0.95f, 0.18f, 0.10f);
                enemyFacePart( 0.000f, 1.420f, fz, 0.090f, 0.140f, 0.02f, 0.05f, 0.02f, 0.02f);   // moan mouth
                enemyFacePart(-0.015f, 1.328f, fz, 0.026f, 0.095f, 0.02f, 0.34f, 0.06f, 0.05f);   // drip
                break;
            case 4:   // sunken: heavy brow, uneven slits, downturned mouth
                enemyFacePart( 0.000f, 1.588f, fz, 0.230f, 0.032f, 0.02f, 0.03f, 0.02f, 0.02f);   // brow bar
                enemyFacePart(-0.080f, 1.548f, fz, 0.058f, 0.030f, 0.02f, 0.03f, 0.02f, 0.02f);
                enemyFacePart( 0.088f, 1.542f, fz, 0.042f, 0.028f, 0.02f, 0.03f, 0.02f, 0.02f);
                enemyFacePart(-0.078f, 1.545f, fzp, 0.024f, 0.022f, 0.015f, 0.95f, 0.18f, 0.10f);
                enemyFacePart( 0.086f, 1.540f, fzp, 0.022f, 0.020f, 0.015f, 0.95f, 0.18f, 0.10f);
                enemyFacePart( 0.000f, 1.426f, fz, 0.085f, 0.030f, 0.02f, 0.05f, 0.02f, 0.02f);   // downturned
                enemyFacePart(-0.068f, 1.443f, fz, 0.045f, 0.028f, 0.02f, 0.05f, 0.02f, 0.02f);
                enemyFacePart( 0.068f, 1.443f, fz, 0.045f, 0.028f, 0.02f, 0.05f, 0.02f, 0.02f);
                break;
            default:  // screamer: wide sockets, huge open mouth with a broken tooth row
                enemyFacePart(-0.088f, 1.568f, fz, 0.080f, 0.080f, 0.02f, 0.03f, 0.02f, 0.02f);
                enemyFacePart( 0.088f, 1.568f, fz, 0.080f, 0.080f, 0.02f, 0.03f, 0.02f, 0.02f);
                enemyFacePart(-0.088f, 1.562f, fzp, 0.028f, 0.028f, 0.015f, 0.95f, 0.18f, 0.10f);
                enemyFacePart( 0.088f, 1.562f, fzp, 0.028f, 0.028f, 0.015f, 0.95f, 0.18f, 0.10f);
                enemyFacePart( 0.000f, 1.412f, fz, 0.130f, 0.150f, 0.02f, 0.05f, 0.02f, 0.02f);   // scream
                enemyFacePart(-0.028f, 1.348f, fzp, 0.030f, 0.026f, 0.012f, 0.82f, 0.80f, 0.72f); // broken teeth
                enemyFacePart( 0.024f, 1.348f, fzp, 0.026f, 0.024f, 0.012f, 0.82f, 0.80f, 0.72f);
                break;
        }
    }

    // Face placement offsets: the zombie faces are authored for a head centred at (0, 1.50, 0); the walker's
    // hunched head sits forward and the crawler's head sits at ground level, so the whole face shifts with it.
    private float faceDy = 0f, faceDz = 0f;

    /** Like enemyPart but UNLIT (mode 4) for crisp, high-contrast facial features. */
    private void enemyFacePart(float lx, float ly, float lz, float sx, float sy, float sz,
                               float r, float g, float b) {
        ly += faceDy; lz += faceDz;
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
        int nd = -1, nw = -1;
        float bestD = INTERACT_DIST * INTERACT_DIST, bestW = INTERACT_DIST * INTERACT_DIST;
        if (state == ST_PLAYING) {
            for (int i = 0; i < doorData.length; i++) {
                float dx = px - doorData[i][0], dz = pz - doorData[i][2];
                float d2 = dx * dx + dz * dz;
                if (d2 < bestD) { bestD = d2; nd = i; }
            }
            for (int i = 0; i < windowData.length; i++) {
                float dx = px - windowData[i][0], dz = pz - windowData[i][2];
                float d2 = dx * dx + dz * dz;
                if (d2 < bestW) { bestW = d2; nw = i; }
            }
        }
        nearDoor = nd;
        nearWindow = nw;
        nearWinCloser = nw >= 0 && (nd < 0 || bestW <= bestD);       // which is the single closest interactable
        input.setDoorInRange(nd >= 0 || nw >= 0);
        boolean tap = input.consumeInteract();
        if (tap) {                                                   // one button toggles the single closest thing
            if (nearWinCloser)  { windowTarget[nw] = (windowTarget[nw] > 0.5f) ? 0f : 1f; sfx.reload(); }
            else if (nd >= 0)   { doorTarget[nd]   = (doorTarget[nd] > 0.5f)   ? 0f : 1f; sfx.reload(); }
        }
        for (int i = 0; i < doorData.length; i++) {
            doorOpen[i] += (doorTarget[i] - doorOpen[i]) * Math.min(1f, dt * 6f);
            if (doorOpen[i] < 0.001f) doorOpen[i] = 0f;
            else if (doorOpen[i] > 0.999f) doorOpen[i] = 1f;
        }
        for (int i = 0; i < windowData.length; i++) {
            windowOpen[i] += (windowTarget[i] - windowOpen[i]) * Math.min(1f, dt * 6f);
            if (windowOpen[i] < 0.001f) windowOpen[i] = 0f;
            else if (windowOpen[i] > 0.999f) windowOpen[i] = 1f;
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
            float dyaw = dd.length > 13 ? dd[13] : 0f;       // house yaw (0 for the procedural town)
            Matrix.setIdentityM(doorBase, 0);               // hinge: origin ends at the leaf centre
            Matrix.translateM(doorBase, 0, dd[0], dd[1], dd[2]);   // leaf centre (already spun into world)
            if (dyaw != 0f) Matrix.rotateM(doorBase, 0, dyaw, 0f, 1f, 0f);   // into the house's local frame
            if (axis == 0) {                                // wall spans X, hinge on the +X edge
                Matrix.translateM(doorBase, 0, tH, 0f, 0f);
                Matrix.rotateM(doorBase, 0, ang, 0f, 1f, 0f);
                Matrix.translateM(doorBase, 0, -tH, 0f, 0f);
            } else {                                        // wall spans Z, hinge on the +Z edge
                Matrix.translateM(doorBase, 0, 0f, 0f, tH);
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

    // --- openable window sashes (reachable ground-floor windows) — same hinge maths as the doors ---

    /** Build the interactive sash records from the (base town) house list. A reachable ground-floor opening
     *  (from the shared windowSlots) becomes a casement leaf hinged on one jamb, tinted with its house glass. */
    private void buildOpenableWindows() {
        java.util.List<float[]> wins = new java.util.ArrayList<float[]>();
        for (float[] hh : houseRects) {
            float cx = hh[0], cz = hh[1], w = hh[2], dd = hh[3], h = hh[4];
            int ds = (int) hh[5], dens = (int) hh[11], storeys = Math.round(hh[23]);
            float wsc = hh[12], foundH = hh[16], yaw = hh.length > 25 ? hh[25] : 0f;
            float gr = hh[13], gg = hh[14], gb = hh[15];
            if (dens == 0 || !houseCuts(hh)) continue;
            long seed = houseSeedOf(cx, cz);
            sashSide(wins, hh, cx, cz + dd * 0.5f, w, h, 0, 0, ds == 0, dens, wsc, storeys, foundH, seed, gr, gg, gb, yaw);  // +z
            sashSide(wins, hh, cx, cz - dd * 0.5f, w, h, 0, 1, ds == 1, dens, wsc, storeys, foundH, seed, gr, gg, gb, yaw);  // -z
            sashSide(wins, hh, cx + w * 0.5f, cz, dd, h, 1, 2, ds == 2, dens, wsc, storeys, foundH, seed, gr, gg, gb, yaw);  // +x
            sashSide(wins, hh, cx - w * 0.5f, cz, dd, h, 1, 3, ds == 3, dens, wsc, storeys, foundH, seed, gr, gg, gb, yaw);  // -x
        }
        windowData = wins.toArray(new float[0][]);
        windowOpen = new float[windowData.length];
        windowTarget = new float[windowData.length];
        nearWindow = -1;
    }

    private void sashSide(java.util.List<float[]> out, float[] hh, float wcx, float wcz, float span, float h,
                          int axis, int side, boolean doorWall, int dens, float wsc, int storeys, float foundH,
                          long seed, float gr, float gg, float gb, float yaw) {
        java.util.ArrayList<float[]> slots = new java.util.ArrayList<float[]>();
        windowSlots(span, h, dens, wsc, storeys, foundH, doorWall, seed, side, slots);
        float swingSign = (side == 1 || side == 2) ? -1f : 1f;   // open OUTWARD, same convention as the door leaf
        for (float[] s : slots) {
            if (s[5] == 0f) continue;                            // reachable ground-floor sashes only
            float t = s[0], wy = s[1], winW = s[2], winH = s[3];
            float lx = axis == 0 ? wcx + t : wcx;
            float lz = axis == 0 ? wcz     : wcz + t;
            addWindowSash(out, lx, wy, lz, winW * 0.5f, winH * 0.5f, axis, swingSign, hh[0], hh[1], yaw, gr, gg, gb);
        }
    }

    /** Add one sash record, rotating its centre about the house centre by yaw (matches rotateDoorAbout). */
    private void addWindowSash(java.util.List<float[]> out, float lx, float ly, float lz, float tHalf, float vHalf,
                               int axis, float swingSign, float cxH, float czH, float yaw, float gr, float gg, float gb) {
        float wx = lx, wz = lz;
        if (yaw != 0f) {
            double a = Math.toRadians(yaw);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float rx = lx - cxH, rz = lz - czH;
            wx = cxH + rx * ca + rz * sa;
            wz = czH - rx * sa + rz * ca;
        }
        out.add(new float[]{wx, ly, wz, tHalf, vHalf, 0.04f, axis, 1f, gr, gg, gb, 0f, swingSign, yaw});
    }

    /** Draw the openable sashes: opaque frames + muntins, then reflective glass in a see-through pass so the
     *  real interior shows through a closed pane. Distance-culled; hinge transform mirrors drawDoors. */
    private void drawWindows() {
        if (windowData.length == 0) return;
        float cull = 44f * 44f;   // keep glass on every in-town window (the cut hole is permanent); fog hides beyond
        GLES20.glUseProgram(prog3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, metalTex);
        for (int i = 0; i < windowData.length; i++) {                 // opaque: frame ring + cross muntins
            float[] wd = windowData[i];
            float dx = wd[0] - px, dz = wd[2] - pz;
            if (dx * dx + dz * dz > cull) continue;
            winHinge(wd, windowOpen[i]);
            int axis = (int) wd[6]; float tH = wd[3], vH = wd[4];
            float fr = 0.90f, fg = 0.89f, fb = 0.84f;                 // painted white frame
            winBit(axis, 0f, vH, 0f, tH * 2f + 0.06f, 0.06f, 0.09f, 0f, fr, fg, fb);   // head
            winBit(axis, 0f, -vH, 0f, tH * 2f + 0.06f, 0.06f, 0.09f, 0f, fr, fg, fb);  // sill rail
            winBit(axis, -tH, 0f, 0f, 0.06f, vH * 2f, 0.09f, 0f, fr, fg, fb);          // jambs
            winBit(axis,  tH, 0f, 0f, 0.06f, vH * 2f, 0.09f, 0f, fr, fg, fb);
            winBit(axis, 0f, 0f, 0f, 0.045f, vH * 2f, 0.07f, 0f, fr, fg, fb);          // muntin cross
            winBit(axis, 0f, 0f, 0f, tH * 2f, 0.045f, 0.07f, 0f, fr, fg, fb);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, winTex);           // transparent: reflective glass
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        for (int i = 0; i < windowData.length; i++) {
            float[] wd = windowData[i];
            float dx = wd[0] - px, dz = wd[2] - pz;
            if (dx * dx + dz * dz > cull) continue;
            winHinge(wd, windowOpen[i]);
            int axis = (int) wd[6];
            winBit(axis, 0f, 0f, 0f, wd[3] * 2f - 0.02f, wd[4] * 2f - 0.02f, 0.03f, 5f, wd[8], wd[9], wd[10]);
        }
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /** Build the sash hinge transform into winBase: leaf centre -> house yaw -> swing about the jamb edge. */
    private void winHinge(float[] wd, float open) {
        Matrix.setIdentityM(winBase, 0);
        Matrix.translateM(winBase, 0, wd[0], wd[1], wd[2]);
        float dyaw = wd[13];
        if (dyaw != 0f) Matrix.rotateM(winBase, 0, dyaw, 0f, 1f, 0f);
        int axis = (int) wd[6];
        float ang = open * 78f * wd[12], tH = wd[3];
        if (axis == 0) { Matrix.translateM(winBase, 0, tH, 0f, 0f); Matrix.rotateM(winBase, 0, ang, 0f, 1f, 0f); Matrix.translateM(winBase, 0, -tH, 0f, 0f); }
        else           { Matrix.translateM(winBase, 0, 0f, 0f, tH); Matrix.rotateM(winBase, 0, ang, 0f, 1f, 0f); Matrix.translateM(winBase, 0, 0f, 0f, -tH); }
    }

    private void winBit(int axis, float lt, float lv, float ld, float st, float sv, float sd, float mode, float r, float g, float b) {
        System.arraycopy(winBase, 0, model, 0, 16);
        if (axis == 0) { Matrix.translateM(model, 0, lt, lv, ld); Matrix.scaleM(model, 0, st, sv, sd); }
        else           { Matrix.translateM(model, 0, ld, lv, lt); Matrix.scaleM(model, 0, sd, sv, st); }
        drawWorld(cube, 36, mode, r, g, b);
    }

    // --- fountain water ---

    /** Bake every registered pool/stream into one mesh for the animated-water shader branch.
     *  Vertex encoding: UV.y picks the surface kind (0 pool, 1 falling stream, 2 basin floor);
     *  UV.x is the radial coordinate (pools/floors, 0 centre -> 1 rim) or the fall parameter
     *  (streams, 0 top -> 1 splash); a pool vertex's normal.xz carries the radial DIRECTION so
     *  the fragment shader can slope ripple rings without knowing the disc centre. */
    private float[] makeWaterMeshData() {
        int discs = waterDiscs == null ? 0 : waterDiscs.size();
        int strms = waterStreams == null ? 0 : waterStreams.size();
        if (discs + strms == 0) { waterVerts = 0; return new float[0]; }
        int SEG = 40;
        float[] d = new float[(discs * 2 * SEG * 3 + strms * 12) * 8];
        int o = 0;
        for (int di = 0; di < discs; di++) {                 // opaque-ish floors FIRST (painter's order)
            float[] w = waterDiscs.get(di);
            if (w[3] > 0.6f) o = waterFan(d, o, w[0], 0.10f, w[2], w[3] + 0.06f, SEG, 2f);
        }
        for (int di = 0; di < discs; di++) {
            float[] w = waterDiscs.get(di);
            o = waterFan(d, o, w[0], w[1], w[2], w[3], SEG, 0f);
        }
        if (waterStreams != null) for (float[] s : waterStreams) {
            for (int q = 0; q < 2; q++) {                    // two crossed vertical quads
                float ux = q == 0 ? 1f : 0f, uz = q == 0 ? 0f : 1f;
                float ax = s[0] - ux * s[4], az = s[2] - uz * s[4];
                float bx = s[0] + ux * s[4], bz = s[2] + uz * s[4];
                float nx = uz, nz = -ux;                     // any horizontal normal; the branch only shades by UV
                o = put(d, o, ax, s[1], az, nx, 0f, nz, 0f, 1f);
                o = put(d, o, bx, s[1], bz, nx, 0f, nz, 0f, 1f);
                o = put(d, o, bx, s[3], bz, nx, 0f, nz, 1f, 1f);
                o = put(d, o, ax, s[1], az, nx, 0f, nz, 0f, 1f);
                o = put(d, o, bx, s[3], bz, nx, 0f, nz, 1f, 1f);
                o = put(d, o, ax, s[3], az, nx, 0f, nz, 1f, 1f);
            }
        }
        waterVerts = o / 8;
        return o == d.length ? d : java.util.Arrays.copyOf(d, o);
    }

    /** One horizontal disc as a triangle fan (emitted as independent triangles). */
    private static int waterFan(float[] d, int o, float cx, float y, float cz, float r, int seg, float kind) {
        for (int k = 0; k < seg; k++) {
            double a0 = k * Math.PI * 2.0 / seg, a1 = (k + 1) * Math.PI * 2.0 / seg;
            float s0 = (float) Math.sin(a0), c0 = (float) Math.cos(a0);
            float s1 = (float) Math.sin(a1), c1 = (float) Math.cos(a1);
            o = put(d, o, cx, y, cz, 0f, 1f, 0f, 0f, kind);
            o = put(d, o, cx + s0 * r, y, cz + c0 * r, s0, 1f, c0, 1f, kind);
            o = put(d, o, cx + s1 * r, y, cz + c1 * r, s1, 1f, c1, 1f, kind);
        }
        return o;
    }

    private void drawWater() {
        if (waterMesh == null || waterVerts <= 0) return;
        GLES20.glUseProgram(prog3);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        Matrix.setIdentityM(model, 0);
        drawWorld(waterMesh, waterVerts, 6f, 0.030f, 0.105f, 0.125f);   // deep pool tint (linear, pre-ACES); shader adds sky + glints
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
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

        // While the muzzle flash burns, the whole viewmodel catches a warm light kiss that decays with it
        // (clamped: the shotgun starts its timer at 1.6x MUZZLE_TIME).
        gunFlashAdd = muzzleTimer > 0f ? 0.28f * Math.min(1f, muzzleTimer / MUZZLE_TIME) : 0f;

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
        for (int s = 0; s < 3; s++)                                               // rear slide serrations
            gunPart(0f, 0.032f, 0.085f + s * 0.022f, 0.064f, 0.052f, 0.008f, dk * 0.55f, dg * 0.55f, dd * 0.55f);
        gunPart(0.0305f, 0.045f, 0.035f, 0.004f, 0.030f, 0.085f, 0.05f, 0.05f, 0.06f);   // ejection port (right)
        gunPart(0f, -0.049f, -0.135f, 0.046f, 0.012f, 0.100f, 0.07f, 0.07f, 0.09f);      // accessory rail
        gunPartR(0f, -0.100f, 0.052f, 0.040f, 0.050f, 0.020f, 13f, fr * 0.7f, fg * 0.7f, fb * 0.7f);  // mag release
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
        gunPart(0f, -0.02f, -0.145f, 0.058f, 0.066f, 0.014f, mk * 0.8f, mg * 0.8f, mb * 0.8f);   // shroud ring
        gunPart(0f, -0.02f, -0.265f, 0.058f, 0.066f, 0.014f, mk * 0.8f, mg * 0.8f, mb * 0.8f);   // shroud ring
        gunPart(0f, 0.062f, 0.145f, 0.030f, 0.030f, 0.030f, mk * 0.6f, mg * 0.6f, mb * 0.6f);    // rear sight drum
        gunPart(0f, -0.038f, 0.30f, 0.036f, 0.020f, 0.14f, bk * 0.85f, bg * 0.85f, bb * 0.85f);  // lower stock strut
        gunPart(0.045f, -0.055f, 0.10f, 0.012f, 0.030f, 0.024f, mk * 0.7f, mg * 0.7f, mb * 0.7f);// mag release lever
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
        gunPart(-0.033f, -0.016f, -0.05f, 0.008f, 0.012f, 0.22f, mk * 0.75f, mg * 0.75f, mb * 0.75f);  // action bars
        gunPart(0.033f, -0.016f, -0.05f, 0.008f, 0.012f, 0.22f, mk * 0.75f, mg * 0.75f, mb * 0.75f);
        gunPart(0f, 0.008f, -0.545f, 0.034f, 0.048f, 0.020f, mk * 0.8f, mg * 0.8f, mb * 0.8f);   // barrel/mag clamp
        gunPart(-0.038f, 0.012f, 0.10f, 0.008f, 0.052f, 0.10f, 0.10f, 0.10f, 0.11f);             // side-saddle plate
        for (int s = 0; s < 3; s++) {                                                            // spare shells (red + brass)
            gunPart(-0.045f, 0.020f, 0.065f + s * 0.032f, 0.014f, 0.036f, 0.015f, 0.68f, 0.12f, 0.08f);
            gunPart(-0.045f, -0.004f, 0.065f + s * 0.032f, 0.015f, 0.014f, 0.016f, 0.72f, 0.58f, 0.22f);
        }
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
        gunPart(0f, sc + 0.032f, -0.01f, 0.022f, 0.026f, 0.030f, 0.07f, 0.07f, 0.08f);   // elevation turret
        gunPart(0.034f, sc, -0.01f, 0.026f, 0.022f, 0.030f, 0.07f, 0.07f, 0.08f);        // windage turret
        gunPart(0f, -0.045f, 0.16f, 0.048f, 0.026f, 0.10f, bk + 0.04f, bg + 0.04f, bb + 0.04f);  // cheek riser
        gunPartR(-0.022f, -0.055f, -0.62f, 0.014f, 0.15f, 0.016f, 14f, mk * 0.75f, mg * 0.75f, mb * 0.75f);  // folded bipod legs
        gunPartR(0.022f, -0.055f, -0.62f, 0.014f, 0.15f, 0.016f, 14f, mk * 0.75f, mg * 0.75f, mb * 0.75f);
        gunPart(0.085f, 0.030f, 0.14f, 0.022f, 0.022f, 0.022f, mk + 0.08f, mg + 0.08f, mb + 0.08f);  // bolt knob ball
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
        submit(cube, 36, mvp, tmpA, 4f, 3.4f * k, 3.0f * k, 2.0f * k);   // white-hot core (mode 4 clamps safely)
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        GLES20.glDepthMask(false);
        Matrix.setIdentityM(partM, 0);
        Matrix.translateM(partM, 0, 0f, 0.02f, mz);
        float gs = (0.16f + 0.14f * k) * scaleMul;
        Matrix.scaleM(partM, 0, gs, gs, gs);
        Matrix.multiplyMM(tmpA, 0, gunBase, 0, partM, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmpA, 0);
        submit(sphere, sphereVerts, mvp, tmpA, 4f, 1.15f * k, 0.72f * k, 0.30f * k);
        // rotating 4-point star inside the additive block: the classic flickering muzzle-gas flash
        for (int fl = 0; fl < 2; fl++) {
            Matrix.setIdentityM(partM, 0);
            Matrix.translateM(partM, 0, 0f, 0.02f, mz);
            Matrix.rotateM(partM, 0, fl * 90f + 45f + (timeAcc * 700f) % 90f, 0f, 0f, 1f);
            Matrix.scaleM(partM, 0, (0.30f + 0.18f * k) * scaleMul, 0.016f * scaleMul, 0.016f * scaleMul);
            Matrix.multiplyMM(tmpA, 0, gunBase, 0, partM, 0);
            Matrix.multiplyMM(mvp, 0, proj, 0, tmpA, 0);
            submit(cube, 36, mvp, tmpA, 4f, 1.9f * k, 1.4f * k, 0.55f * k);
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
        submit(cube, 36, mvp, tmpA, 3f, r + gunFlashAdd, g + gunFlashAdd * 0.70f, b + gunFlashAdd * 0.35f);
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
        submit(cube, 36, mvp, tmpA, 3f, r + gunFlashAdd, g + gunFlashAdd * 0.70f, b + gunFlashAdd * 0.35f);
    }

    private void updateCamera(float dt) {
        if (state == ST_EDIT) { updateEditor(dt); return; }
        if (state != ST_PLAYING && state != ST_SANDBOX) {
            // Frozen (hub / summary): hold the camera and just show the arena from the current pose.
            float cp = (float) Math.cos(pitch);
            float fdx = cp * (float) Math.sin(yaw), fdy = (float) Math.sin(pitch), fdz = -cp * (float) Math.cos(yaw);
            Matrix.setLookAtM(view, 0, px, py, pz, px + fdx, py + fdy, pz + fdz, 0f, 1f, 0f);
            setCamBasis(fdx, fdy, fdz);            // keep the sky's view basis in step with the frozen camera
            return;
        }
        if (input.consumeHubMenu()) { if (state == ST_SANDBOX) sandboxExit(); else abortToHub(); return; }   // MENU/EXIT: bail to the hub (a run does NOT count)
        if (state == ST_PLAYING) {
            if (input.consumeAimToggle()) aimOn = !aimOn;
            if (input.consumeSwitch()) cycleWeapon();
        }

        float sens = LOOK_SENS * (1f - 0.45f * aim);     // steadier aim down sights
        input.consumeLook(lookTmp);
        yaw += lookTmp[0] * sens;
        pitch -= lookTmp[1] * sens;
        float limit = 1.48f;
        pitch = clamp(pitch, -limit, limit);

        if (state == ST_PLAYING) {
            boolean fired = false;
            if (input.consumeFire()) { fire(); fired = true; }
            if (!fired && W_AUTO[curWeapon] && input.isFireHeld()) fire();
        }

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
            // Walking off a ledge consumes the ground jump: otherwise Double-Jump owners got TWO
            // mid-air jumps after stepping off (vs one after a normal jump) — free extra height.
            if (wasGrounded && jumpsUsed == 0) jumpsUsed = 1;
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
        setCamBasis(ldx, ldy, ldz);
    }

    /** Cache the camera basis (forward/right/up) so the sky shader can rebuild per-pixel view rays.
     *  Called from EVERY camera path (gameplay, frozen hub/summary, editor) — a stale or zero basis
     *  renders the sky with the wrong orientation (or NaN via normalize(0) on a fresh launch). */
    private void setCamBasis(float ldx, float ldy, float ldz) {
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
            float dxw = px - b[0], dzw = pz - b[2];
            float yawB = b.length > 9 ? b[9] : 0f;
            if (yawB != 0f) {                            // rotated box: measure the gap in its own frame
                double a = Math.toRadians(yawB);
                float caB = (float) Math.cos(a), saB = (float) Math.sin(a);
                float tx = dxw * caB - dzw * saB, tz = dxw * saB + dzw * caB;
                dxw = tx; dzw = tz;
            }
            float ddx = Math.max(Math.abs(dxw) - b[3] * 0.5f, 0f);
            float ddz = Math.max(Math.abs(dzw) - b[5] * 0.5f, 0f);
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
            float yawB = b.length > 9 ? b[9] : 0f;
            boolean over = yawB != 0f
                    ? insideYawXZ(px, pz, b[0], b[2], hx, hz, yawB)     // rotated box: its REAL footprint
                    : (px > b[0] - hx && px < b[0] + hx && pz > b[2] - hz && pz < b[2] + hz);
            if (over && boxTop > support) support = boxTop;
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
            float yawB = b.length > 9 ? b[9] : 0f;
            if (yawB != 0f) {                                    // rotated house part -> oriented-box resolve (AABB path when yaw=0)
                obb2[0] = x; obb2[1] = z;
                obbPush(obb2, b[0], b[2], b[3] * 0.5f + r, b[5] * 0.5f + r, yawB);
                x = obb2[0]; z = obb2[1];
                continue;
            }
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
            float yawD = dd.length > 13 ? dd[13] : 0f;
            if (yawD != 0f) {                                    // door of a rotated house -> oriented resolve
                obb2[0] = x; obb2[1] = z;
                obbPush(obb2, dd[0], dd[2], hx + r, hz + r, yawD);
                x = obb2[0]; z = obb2[1];
                continue;
            }
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

    /** True if world point (wx,wz) lies inside the XZ footprint of a box centred at (bx,bz) with half-extents
     *  (hx,hz) along its own axes, rotated yawDeg about Y — the containment twin of obbPush, used by every
     *  footprint test that must respect rotated houses/fences (support, mantle, spawn, steering). */
    private static boolean insideYawXZ(float wx, float wz, float bx, float bz, float hx, float hz, float yawDeg) {
        double a = Math.toRadians(yawDeg);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float dx = wx - bx, dz = wz - bz;
        float lx = dx * ca - dz * sa, lz = dx * sa + dz * ca;    // world -> box-local (Ry(-yaw))
        return lx > -hx && lx < hx && lz > -hz && lz < hz;
    }

    /** Resolve a point (io2[0]=x, io2[1]=z) out of an oriented box centred at (bx,bz), half-extents (hx,hz)
     *  along the box's own axes, rotated yawDeg about Y. yawDeg=0 is identical to the plain AABB push. */
    private static void obbPush(float[] io2, float bx, float bz, float hx, float hz, float yawDeg) {
        double a = Math.toRadians(yawDeg);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float dx = io2[0] - bx, dz = io2[1] - bz;
        float lx = dx * ca - dz * sa, lz = dx * sa + dz * ca;    // world -> box-local (Ry(-yaw))
        if (lx > -hx && lx < hx && lz > -hz && lz < hz) {
            float pL = lx + hx, pR = hx - lx, pD = lz + hz, pU = hz - lz;
            float m = Math.min(Math.min(pL, pR), Math.min(pD, pU));
            if (m == pL) lx = -hx; else if (m == pR) lx = hx; else if (m == pD) lz = -hz; else lz = hz;
            io2[0] = bx + lx * ca + lz * sa;                     // box-local -> world (Ry(yaw))
            io2[1] = bz - lx * sa + lz * ca;
        }
    }

    // ===================== in-game editor: touch picking math (pure, JVM-testable) =====================
    // NB: android.opengl.Matrix.multiplyMM/multiplyMV are *native* (no JVM impl), so the picking path uses
    // these pure-Java column-major helpers instead — cheap (called on tap/drag) and unit-testable off-device.

    /** out(4x4) = a x b, column-major (same convention as Matrix.multiplyMM). out must not alias a or b. */
    static void mul4(float[] out, float[] a, float[] b) {
        for (int c = 0; c < 4; c++)
            for (int r = 0; r < 4; r++) {
                float s = 0f;
                for (int k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
                out[c * 4 + r] = s;
            }
    }

    /** out(4) = m(4x4) x v(4), column-major. */
    static void mulMatVec4(float[] out, float[] m, float[] v) {
        for (int r = 0; r < 4; r++)
            out[r] = m[0 * 4 + r] * v[0] + m[1 * 4 + r] * v[1] + m[2 * 4 + r] * v[2] + m[3 * 4 + r] * v[3];
    }

    /** Pure-Java 4x4 inverse (Mesa gluInvertMatrix), column-major. Returns false if singular.
     *  Used instead of Matrix.invertM because android.jar's Matrix is a compile-only stub off-device. */
    static boolean invert4(float[] out, float[] m) {
        float[] inv = new float[16];
        inv[0]  =  m[5]*m[10]*m[15] - m[5]*m[11]*m[14] - m[9]*m[6]*m[15] + m[9]*m[7]*m[14] + m[13]*m[6]*m[11] - m[13]*m[7]*m[10];
        inv[4]  = -m[4]*m[10]*m[15] + m[4]*m[11]*m[14] + m[8]*m[6]*m[15] - m[8]*m[7]*m[14] - m[12]*m[6]*m[11] + m[12]*m[7]*m[10];
        inv[8]  =  m[4]*m[9]*m[15]  - m[4]*m[11]*m[13] - m[8]*m[5]*m[15] + m[8]*m[7]*m[13] + m[12]*m[5]*m[11] - m[12]*m[7]*m[9];
        inv[12] = -m[4]*m[9]*m[14]  + m[4]*m[10]*m[13] + m[8]*m[5]*m[14] - m[8]*m[6]*m[13] - m[12]*m[5]*m[10] + m[12]*m[6]*m[9];
        inv[1]  = -m[1]*m[10]*m[15] + m[1]*m[11]*m[14] + m[9]*m[2]*m[15] - m[9]*m[3]*m[14] - m[13]*m[2]*m[11] + m[13]*m[3]*m[10];
        inv[5]  =  m[0]*m[10]*m[15] - m[0]*m[11]*m[14] - m[8]*m[2]*m[15] + m[8]*m[3]*m[14] + m[12]*m[2]*m[11] - m[12]*m[3]*m[10];
        inv[9]  = -m[0]*m[9]*m[15]  + m[0]*m[11]*m[13] + m[8]*m[1]*m[15] - m[8]*m[3]*m[13] - m[12]*m[1]*m[11] + m[12]*m[3]*m[9];
        inv[13] =  m[0]*m[9]*m[14]  - m[0]*m[10]*m[13] - m[8]*m[1]*m[14] + m[8]*m[2]*m[13] + m[12]*m[1]*m[10] - m[12]*m[2]*m[9];
        inv[2]  =  m[1]*m[6]*m[15]  - m[1]*m[7]*m[14]  - m[5]*m[2]*m[15] + m[5]*m[3]*m[14] + m[13]*m[2]*m[7]  - m[13]*m[3]*m[6];
        inv[6]  = -m[0]*m[6]*m[15]  + m[0]*m[7]*m[14]  + m[4]*m[2]*m[15] - m[4]*m[3]*m[14] - m[12]*m[2]*m[7]  + m[12]*m[3]*m[6];
        inv[10] =  m[0]*m[5]*m[15]  - m[0]*m[7]*m[13]  - m[4]*m[1]*m[15] + m[4]*m[3]*m[13] + m[12]*m[1]*m[7]  - m[12]*m[3]*m[5];
        inv[14] = -m[0]*m[5]*m[14]  + m[0]*m[6]*m[13]  + m[4]*m[1]*m[14] - m[4]*m[2]*m[13] - m[12]*m[1]*m[6]  + m[12]*m[2]*m[5];
        inv[3]  = -m[1]*m[6]*m[11]  + m[1]*m[7]*m[10]  + m[5]*m[2]*m[11] - m[5]*m[3]*m[10] - m[9]*m[2]*m[7]   + m[9]*m[3]*m[6];
        inv[7]  =  m[0]*m[6]*m[11]  - m[0]*m[7]*m[10]  - m[4]*m[2]*m[11] + m[4]*m[3]*m[10] + m[8]*m[2]*m[7]   - m[8]*m[3]*m[6];
        inv[11] = -m[0]*m[5]*m[11]  + m[0]*m[7]*m[9]   + m[4]*m[1]*m[11] - m[4]*m[3]*m[9]  - m[8]*m[1]*m[7]   + m[8]*m[3]*m[5];
        inv[15] =  m[0]*m[5]*m[10]  - m[0]*m[6]*m[9]   - m[4]*m[1]*m[10] + m[4]*m[2]*m[9]  + m[8]*m[1]*m[6]   - m[8]*m[2]*m[5];
        float det = m[0]*inv[0] + m[1]*inv[4] + m[2]*inv[8] + m[3]*inv[12];
        if (det == 0f) return false;
        float idet = 1f / det;
        for (int i = 0; i < 16; i++) out[i] = inv[i] * idet;
        return true;
    }

    /** Two-point unprojection: world-space ray for a touch at (px,py) in a vw x vh viewport, given vp = proj*view.
     *  Writes ray origin -> O[0..2] and a normalised direction -> D[0..2]. Returns false if vp isn't invertible. */
    static boolean screenRay(float px, float py, int vw, int vh, float[] vp, float[] O, float[] D) {
        float[] inv = new float[16];
        if (!invert4(inv, vp)) return false;
        float ndcX = 2f * px / vw - 1f;
        float ndcY = 1f - 2f * py / vh;                          // Android touch is top-left, GL NDC is bottom-left
        float[] near = new float[4], far = new float[4];
        mulMatVec4(near, inv, new float[]{ndcX, ndcY, -1f, 1f});
        mulMatVec4(far,  inv, new float[]{ndcX, ndcY,  1f, 1f});
        if (Math.abs(near[3]) < 1e-9f || Math.abs(far[3]) < 1e-9f) return false;
        float nx = near[0] / near[3], ny = near[1] / near[3], nz = near[2] / near[3];   // perspective divide (both points)
        float fx = far[0]  / far[3],  fy = far[1]  / far[3],  fz = far[2]  / far[3];
        float dx = fx - nx, dy = fy - ny, dz = fz - nz, len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9f) return false;
        O[0] = nx; O[1] = ny; O[2] = nz;
        D[0] = dx / len; D[1] = dy / len; D[2] = dz / len;
        return true;
    }

    /** Intersect ray (O,D) with the horizontal plane y = planeY. Writes the hit -> hit[0..2]; false if parallel/behind. */
    static boolean rayPlaneY(float[] O, float[] D, float planeY, float[] hit) {
        float denom = D[1];
        if (Math.abs(denom) < 1e-6f) return false;               // parallel to the ground / looking at the horizon
        float t = (planeY - O[1]) / denom;
        if (t < 0f) return false;                                // plane is behind the camera
        hit[0] = O[0] + t * D[0]; hit[1] = planeY; hit[2] = O[2] + t * D[2];
        return true;
    }

    /** Ray vs a box centred at (cx,cy,cz) with half-extents (hx,hy,hz) and yawDeg about Y.
     *  Returns the entry distance (>=0) or -1 for a miss. Slab test in the box's local frame. */
    static float rayBox(float[] O, float[] D, float cx, float cy, float cz, float hx, float hy, float hz, float yawDeg) {
        double a = Math.toRadians(yawDeg);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float ox = O[0] - cx, oz = O[2] - cz;
        float lox = ox * ca - oz * sa, loz = ox * sa + oz * ca;  // world -> box-local (Ry(-yaw)), same convention as obbPush
        float ldx = D[0] * ca - D[2] * sa, ldz = D[0] * sa + D[2] * ca;
        float[] lo = {lox, O[1] - cy, loz}, ld = {ldx, D[1], ldz}, h = {hx, hy, hz};
        float tmin = -1e30f, tmax = 1e30f;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(ld[i]) < 1e-8f) {
                if (lo[i] < -h[i] || lo[i] > h[i]) return -1f;   // parallel to this slab and outside it
            } else {
                float invd = 1f / ld[i];
                float t1 = (-h[i] - lo[i]) * invd, t2 = (h[i] - lo[i]) * invd;
                if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
                if (t1 > tmin) tmin = t1;
                if (t2 < tmax) tmax = t2;
            }
        }
        if (tmax >= Math.max(tmin, 0f)) return Math.max(tmin, 0f);
        return -1f;
    }

    // ===================== in-game editor: logic =====================
    static float cosD(float deg) { return (float) Math.cos(Math.toRadians(deg)); }
    static float sinD(float deg) { return (float) Math.sin(Math.toRadians(deg)); }

    private void edEnter() {
        state = ST_EDIT;
        selObj = -1; armedCat = -1; armedKind = -1; edGrabbed = false; edGrabMoved = false; edPaletteOpen = false;
        edPrevPanActive = false; edPrevCamCount = 0;
        edCamPivotX = px; edCamPivotZ = pz; edCamYaw = yaw; edCamPitch = 0.85f; edCamDist = 26f;
        editDirty = true; edDraggingHouse = false;
        edSetToast("OBJEKTE oeffnet die Palette. Objekt antippen zum Bearbeiten.");
    }
    private void edExit() {
        edInsideHouse = -1;
        saveLevel(true);
        // Fog was snapped far out for the editor's clear overhead view; reset it to the level base so the
        // hub doesn't render fog-free for several seconds while the weather crossfade drags it back.
        effFogStart = fogStart; effFogEnd = fogEnd;
        state = ST_HUB; hubWasShown = false;
    }
    private void edSetToast(String m) { edToast = m; edToastT = 1.8f; }

    /** Double-tapping a placed house drops into a top-down "look inside" view of it: its roof is hidden and
     *  its baked interior is unpacked into editable furniture pieces you can move/rotate/delete/add. */
    private void edEnterHouse(int idx) {
        if (idx < 0 || idx >= editObjs.size() || (int) editObjs.get(idx)[0] != 3) return;
        edUnpackHouse(idx);
        float[] o = editObjs.get(idx);
        edSavePivotX = edCamPivotX; edSavePivotZ = edCamPivotZ; edSaveYaw = edCamYaw; edSavePitch = edCamPitch; edSaveDist = edCamDist;
        edInsideHouse = idx;
        float[] hp = HOUSE_PRESETS[Math.max(0, Math.min((int) o[1], HOUSE_PRESETS.length - 1))];
        float s = o[5] <= 0f ? 1f : o[5];
        edCamPivotX = o[2]; edCamPivotZ = o[3];
        edCamYaw = o[4] * 0.017453292f;                    // face the house's own orientation
        edCamPitch = 1.28f;                                 // steep bird's-eye (not dead-straight, so furniture reads in 3D)
        edCamDist = Math.max(hp[0], hp[1]) * s * 0.85f + 2.5f;   // close enough that the furniture is clearly visible
        edSetFloor(0);                                      // always start on the lowest storey
        selObj = -1; armedCat = -1; armedKind = -1; edPaletteOpen = false; edCatTab = 0;
        editDirty = true;                                   // re-bake: hides this house's flat roof slab
        edSetToast("Innenraum - Etage " + (edFloor + 1) + ": Moebel antippen und bearbeiten");
    }
    /** Storeys of an editor house (from its preset height). */
    private int edHouseFloors(float[] o) {
        float[] hp = HOUSE_PRESETS[Math.max(0, Math.min((int) o[1], HOUSE_PRESETS.length - 1))];
        float h = hp[2] * (o[5] <= 0f ? 1f : o[5]);
        return h >= 5.5f ? 2 : 1;
    }
    private float edFloorH(float[] o) {
        float[] hp = HOUSE_PRESETS[Math.max(0, Math.min((int) o[1], HOUSE_PRESETS.length - 1))];
        float h = hp[2] * (o[5] <= 0f ? 1f : o[5]);
        return h / edHouseFloors(o);
    }
    /** World Y of the current interior floor (for placing furniture at storey height). */
    private float edCurrentFloorY() {
        if (edInsideHouse < 0 || edInsideHouse >= editObjs.size()) return 0f;
        return edFloor * edFloorH(editObjs.get(edInsideHouse));
    }
    /** Switch which storey the interior view looks at / places on; raises the camera to that floor. */
    private void edSetFloor(int f) {
        if (edInsideHouse < 0 || edInsideHouse >= editObjs.size()) return;
        float[] o = editObjs.get(edInsideHouse);
        int floors = edHouseFloors(o); float floorH = edFloorH(o);
        edFloor = f < 0 ? 0 : (f >= floors ? floors - 1 : f);
        edCamPivotY = edFloor * floorH + floorH * 0.5f;    // look at the middle of the chosen storey
    }
    private void edLeaveHouse() {
        edInsideHouse = -1; edFloor = 0; edCamPivotY = 0f;
        edCamPivotX = edSavePivotX; edCamPivotZ = edSavePivotZ; edCamYaw = edSaveYaw; edCamPitch = edSavePitch; edCamDist = edSaveDist;
        selObj = -1; armedCat = -1; armedKind = -1;
        editDirty = true;                                   // re-bake: the house's roof slab is drawn again
    }
    /** Turn a house's auto-furnished (baked) interior into editable FU furniture pieces, once. Marks the house
     *  custom-furnished (field 6) so its baked furniture + ceiling stay off and a plain floor is drawn instead. */
    private void edUnpackHouse(int idx) {
        float[] o = editObjs.get(idx);
        if (o.length > 6 && o[6] != 0f) return;             // already unpacked
        if (o.length <= 6) { float[] n = new float[7]; System.arraycopy(o, 0, n, 0, o.length); editObjs.set(idx, n); o = n; }
        o[6] = 1f;
        int kind = (int) o[1]; float s = o[5] <= 0f ? 1f : o[5];
        float[] hp = HOUSE_PRESETS[Math.max(0, Math.min(kind, HOUSE_PRESETS.length - 1))];
        float w = hp[0] * s, d = hp[1] * s, cx = o[2], cz = o[3], yaw = o[4];
        float ca = cosD(yaw), sa = sinD(yaw);
        float[][] lay = {                                   // {furnKind, localX, localZ}
            {7, 0f, 0f},                                    // Teppich (rug), centre
            {0, -w * 0.24f, -d * 0.24f},                    // Bett
            {1,  w * 0.30f, -d * 0.30f},                    // Schrank
            {2,  w * 0.18f,  d * 0.20f},                    // Tisch
            {3,  w * 0.18f - 0.7f, d * 0.20f},              // Stuhl
            {3,  w * 0.18f + 0.7f, d * 0.20f},              // Stuhl
        };
        for (float[] p : lay) {
            float wx = cx + p[1] * ca + p[2] * sa, wz = cz - p[1] * sa + p[2] * ca;
            editObjs.add(new float[]{0f, p[0], wx, wz, yaw, 1f});
        }
        edHouseMeshDirty = true; editDirty = true;
    }

    /** Move the orbit camera's ground pivot by a stick deflection, relative to the current view yaw:
     *  my (screen-up) drives forward/back along the view direction, mx (screen-right) strafes. Pure +
     *  unit-testable. pivotXZ = {x,z}, updated in place and clamped to the town bounds. */
    static void edMovePivot(float[] pivotXZ, float yaw, float mx, float my, float speed, float dt) {
        float s = (float) Math.sin(yaw), c = (float) Math.cos(yaw);
        // ground forward (toward where the camera looks) = (-sin,-cos); screen-right = (cos,-sin)
        float dX = (c * mx - s * my) * speed * dt;
        float dZ = (-s * mx - c * my) * speed * dt;
        pivotXZ[0] = clamp(pivotXZ[0] + dX, -60f, 60f);
        pivotXZ[1] = clamp(pivotXZ[1] + dZ, -60f, 60f);
    }

    private final float[] edPivotXZ = new float[2];

    /** Per-frame editor logic: rebuilds the orbit camera matrix, then hands off to the pure gesture logic. */
    private void updateEditor(float dt) {
        if (edToastT > 0f) edToastT -= dt;

        // left move stick: translate the camera pivot over the ground, yaw-relative, speed scaled by zoom
        input.getEditStick(edStickArr);
        if (edStickArr[0] != 0f && (edStickArr[1] != 0f || edStickArr[2] != 0f)) {
            edPivotXZ[0] = edCamPivotX; edPivotXZ[1] = edCamPivotZ;
            float speed = clamp(edCamDist * 0.85f, 9f, 60f);
            edMovePivot(edPivotXZ, edCamYaw, edStickArr[1], edStickArr[2], speed, dt);
            edCamPivotX = edPivotXZ[0]; edCamPivotZ = edPivotXZ[1];
        }

        float cp = (float) Math.cos(edCamPitch), sp = (float) Math.sin(edCamPitch);
        float ex = edCamPivotX + edCamDist * cp * (float) Math.sin(edCamYaw);
        float ey = Math.max(1.5f, edCamPivotY + edCamDist * sp);
        float ez = edCamPivotZ + edCamDist * cp * (float) Math.cos(edCamYaw);
        Matrix.setLookAtM(view, 0, ex, ey, ez, edCamPivotX, edCamPivotY, edCamPivotZ, 0f, 1f, 0f);
        float fdx = edCamPivotX - ex, fdy = edCamPivotY - ey, fdz = edCamPivotZ - ez;
        float fl = (float) Math.sqrt(fdx * fdx + fdy * fdy + fdz * fdz); if (fl < 1e-4f) fl = 1f;
        setCamBasis(fdx / fl, fdy / fl, fdz / fl);   // sky follows the orbit camera (was stale gameplay basis)
        mul4(edVP, proj, view);

        input.getEditPan(edPanArr);
        input.getEditCam(edCamArr);
        edProcessGesture(edPanArr[0] != 0f, edPanArr[1], edPanArr[2],
                         (int) edCamArr[0], edCamArr[1], edCamArr[2], edCamArr[3], edCamArr[4]);
    }

    /** Real-mobile-FPS controls: the LEFT move stick translates the camera (handled in updateEditor); the
     *  RIGHT side is what this routes. A single right finger drags the selected object (ground point tracks
     *  the finger 1:1); with nothing selected it does nothing. Two right fingers: pinch changes distance
     *  (zoom), twisting the pair rotates yaw, vertical drift tilts pitch -- all from the same gesture, like
     *  a map app. Pure math against the already-built edVP -- no Matrix.* device-only calls, so this is
     *  independently unit-testable off-device (unlike updateEditor(), which needs Matrix.setLookAtM). */
    private void edProcessGesture(boolean panActive, float panX, float panY,
                                   int camCount, float camAx, float camAy, float camBx, float camBy) {
        // A right finger drags the selected object ONLY if it pressed on it (or is already dragging it);
        // otherwise that single finger orbits the view (yaw + pitch), like the gameplay look control.
        boolean grabbing = selObj >= 0 && armedCat < 0 && panActive && (edGrabbed || edRayHitsObj(selObj, panX, panY));
        if (grabbing) {
            float[] o = editObjs.get(selObj);
            // Drag along the object's own floor plane: upper-storey furniture used to be dragged against the
            // GROUND plane, which put the touch point far off the piece under the steep interior camera.
            float dragY = ((int) o[0] == 0 && o.length > 6) ? o[6] : 0f;
            if (!edGrabbed) {
                if (screenRay(panX, panY, width, height, edVP, edRO, edRD) && rayPlaneY(edRO, edRD, dragY, edHit)) {
                    edGrabOffX = o[2] - edHit[0]; edGrabOffZ = o[3] - edHit[2];
                    edGrabbed = true; edGrabMoved = false;
                }
            } else if (screenRay(panX, panY, width, height, edVP, edRO, edRD) && rayPlaneY(edRO, edRD, dragY, edHit)) {
                float nx = edHit[0] + edGrabOffX, nz = edHit[2] + edGrabOffZ;
                if (!edGrabMoved && (Math.abs(nx - o[2]) > 0.03f || Math.abs(nz - o[3]) > 0.03f)) {
                    edPushUndo("MOD", selObj, o); edGrabMoved = true;   // one snapshot, only once it truly moves
                    if ((int) o[0] == 3) edDraggingHouse = true;        // hide the heavy house mesh while it slides (box-shell follows)
                }
                if (edGrabMoved) { o[2] = nx; o[3] = nz; editDirty = true; }
            }
        } else {
            if (edGrabbed && edGrabMoved && selObj >= 0) { edSnapObj(selObj); editDirty = true; }
            if (edDraggingHouse) { edDraggingHouse = false; edHouseMeshDirty = true; editDirty = true; }   // re-bake at the drop spot
            edGrabbed = false; edGrabMoved = false;
            if (panActive && edPrevPanActive) {                        // 1-finger look: turn (yaw) + tilt (pitch)
                edCamYaw += (panX - edPrevPanX) * ED_LOOK_SENS;
                edCamPitch = clamp(edCamPitch + (panY - edPrevPanY) * ED_LOOK_SENS, 0.12f, 1.50f);
            }
        }
        edPrevPanActive = panActive; edPrevPanX = panX; edPrevPanY = panY;

        if (camCount == 2) {          // right-half two fingers: pinch=zoom, twist=yaw, vertical drift=tilt (pitch)
            float gap = (float) Math.hypot(camBx - camAx, camBy - camAy);
            float ang = (float) Math.atan2(camBy - camAy, camBx - camAx);
            float midY = (camAy + camBy) * 0.5f;
            if (edPrevCamCount == 2) {
                if (edPrevCamGap > 2f && gap > 2f) edCamDist = clamp(edCamDist * edPrevCamGap / gap, 4f, 90f);
                float dAng = ang - edPrevCamAng;
                while (dAng > Math.PI) dAng -= 2f * (float) Math.PI;
                while (dAng < -Math.PI) dAng += 2f * (float) Math.PI;
                edCamYaw += dAng;
                // Dragging the two-finger pair DOWN the screen flattens toward a top-down view (pitch up
                // toward 90 deg); dragging UP tilts more level with the horizon (pitch down) -- same
                // convention as Google Maps' two-finger tilt. All three axes read off the same 2 fingers,
                // like Maps' combined pan/zoom/rotate/tilt gesture -- no separate buttons needed.
                edCamPitch = clamp(edCamPitch + (midY - edPrevCamMidY) * ED_TILT_SENS, 0.12f, 1.50f);
            }
            edPrevCamGap = gap; edPrevCamAng = ang; edPrevCamMidY = midY;
        }
        edPrevCamCount = camCount;
    }

    private void edSnapObj(int i) {
        if (!edSnap || i < 0 || i >= editObjs.size()) return;
        float[] o = editObjs.get(i);
        o[2] = Math.round(o[2] / ED_SNAP_POS) * ED_SNAP_POS;
        o[3] = Math.round(o[3] / ED_SNAP_POS) * ED_SNAP_POS;
        o[4] = Math.round(o[4] / ED_SNAP_YAW) * ED_SNAP_YAW;
    }

    /** World-space AABB of an editObj -> out={cx,cy,cz,hx,hy,hz} (yaw applied separately in picking). */
    private void edObjBounds(float[] o, float[] out) {
        int cat = (int) o[0], kind = (int) o[1]; float s = o[5] <= 0f ? 1f : o[5];
        float hx, hy, hz, cy;
        if (cat == 0) {
            float fx = 0.12f, fz = 0.12f, top = 0.2f;
            float[][] rec = (kind >= 0 && kind < FURN.length) ? FURN[kind] : FURN[0];
            for (float[] p : rec) { fx = Math.max(fx, Math.abs(p[0]) + p[3] * 0.5f); fz = Math.max(fz, Math.abs(p[2]) + p[5] * 0.5f); top = Math.max(top, p[1] + p[4] * 0.5f); }
            float fy = o.length > 6 ? o[6] : 0f;          // upper-floor furniture sits (and is picked) at its storey
            hx = fx * s; hz = fz * s; hy = top * s * 0.5f; cy = top * s * 0.5f + fy;
        } else if (cat == 2) {
            float[] pr = PROP_PRESETS[Math.max(0, Math.min(kind, PROP_PRESETS.length - 1))];
            hx = pr[0] * 0.5f * s; hz = pr[2] * 0.5f * s; hy = pr[1] * 0.5f * s; cy = pr[1] * 0.5f * s;
        } else if (cat == 3) {
            float[] hp = HOUSE_PRESETS[Math.max(0, Math.min(kind, HOUSE_PRESETS.length - 1))];
            float top = hp[2] * s + 0.3f;                 // wall height + roof slab
            hx = hp[0] * 0.5f * s; hz = hp[1] * 0.5f * s; hy = top * 0.5f; cy = top * 0.5f;
        } else {
            float h = kind == 0 ? 2.4f : (kind == 1 ? 0.9f : 0.5f), rad = kind == 0 ? 0.9f : (kind == 1 ? 0.7f : 0.4f);
            hx = rad * s; hz = rad * s; hy = h * s * 0.5f; cy = h * s * 0.5f;
        }
        out[0] = o[2]; out[1] = cy; out[2] = o[3]; out[3] = hx; out[4] = hy; out[5] = hz;
    }

    /** True if the ray through screen (sx,sy) hits object i's (yaw-aware) bounds -- used to gate a drag so
     *  only a press ON the selected object starts moving it (a tap elsewhere just reselects/deselects). */
    private boolean edRayHitsObj(int i, float sx, float sy) {
        if (i < 0 || i >= editObjs.size()) return false;
        if (!screenRay(sx, sy, width, height, edVP, edRO, edRD)) return false;
        float[] o = editObjs.get(i); edObjBounds(o, edBnd);
        return rayBox(edRO, edRD, edBnd[0], edBnd[1], edBnd[2], edBnd[3] + 0.25f, edBnd[4] + 0.25f, edBnd[5] + 0.25f, o[4]) >= 0f;
    }

    /** Ray-pick the nearest editObj at screen (px,py); returns its index or -1. */
    private int edPickAt(float px2, float py2) {
        if (!screenRay(px2, py2, width, height, edVP, edRO, edRD)) return -1;
        int best = -1; float bestT = 1e30f; float[] b = new float[6];
        for (int i = 0; i < editObjs.size(); i++) {
            float[] o = editObjs.get(i);
            // Inside a house, house shells are transparent to picking: the shell's bounds enclose all its
            // furniture, so the top-down ray always hit the shell first and furniture was unselectable.
            if (edInsideHouse >= 0 && (int) o[0] == 3) continue;
            edObjBounds(o, b);
            float t = rayBox(edRO, edRD, b[0], b[1], b[2], b[3] + 0.2f, b[4] + 0.2f, b[5] + 0.2f, o[4]);
            if (t >= 0f && t < bestT) { bestT = t; best = i; }
        }
        return best;
    }

    /** The 27-field house record (matching the mesh builders' houseRects format) for a placed house preset.
     *  {@code furnished}=false -> auto-furnish the interior (baked); true -> custom-furnished (editable FU
     *  pieces), so the baked furniture + ceiling are suppressed. */
    private float[] ovHouseRecord(int kind, float x, float z, float yaw, float s, boolean furnished) {
        float[] hp = HOUSE_PRESETS[Math.max(0, Math.min(kind, HOUSE_PRESETS.length - 1))];
        float w = hp[0] * s, d = hp[1] * s, h = hp[2] * s;
        float storeys = h >= 5.5f ? 2f : 1f;
        return new float[]{
            x, z, w, d, h, hp[3],                          // cx cz w d h doorSide
            0.69f, 0.31f, 0.16f,                           // roof colour (terracotta)
            -1f, -1f,                                      // pitch, overhang (auto)
            2f, 1f,                                        // window density, size
            GLASS_DEF[0], GLASS_DEF[1], GLASS_DEF[2],      // glass tint
            0.30f, 0.42f, 0.40f, 0.40f,                    // foundation height + colour
            -1f, -1f, -1f,                                 // trim band (none)
            storeys, hp[9] * s, yaw, furnished ? 0f : 1f   // storeys, doorW, yaw, autoFurnish
        };
    }

    /** Bake the pitched roofs / windows / bands / interiors of ALL placed houses into a separate small mesh set,
     *  reusing the town's builders by briefly pointing houseRects/interiorPieces at the overlay list and then
     *  restoring the base town's meshes untouched. Runs only on edHouseMeshDirty, so the window builder's large
     *  scratch is never allocated per frame. */
    private void bakeOverlayHouses() {
        java.util.List<float[]> ovH = new java.util.ArrayList<float[]>();
        for (float[] o : editObjs)
            if ((int) o[0] == 3) ovH.add(ovHouseRecord((int) o[1], o[2], o[3], o[4], o[5] <= 0f ? 1f : o[5], o.length > 6 && o[6] != 0f));
        if (ovH.isEmpty()) {
            ovRoofMesh = ovWinMesh = ovTrimMesh = ovRevealMesh = ovBandMesh = ovIntMesh = null;
            ovRoofVerts = ovWinVerts = ovTrimVerts = ovRevealVerts = ovBandVerts = ovIntVerts = 0;
            ovRoofGroups = ovWinGroups = ovBandGroups = ovIntGroups = null;
            ovHouseColliders = null;
            return;
        }
        // snapshot every base field the builders overwrite, so the town's meshes are untouched afterwards
        java.util.List<float[]> sRects = houseRects, sPieces = interiorPieces;
        float[][] sRoofG = roofGroups, sWinG = winGroups, sBandG = bandGroups, sIntG = interiorGroups;
        int sRoofV = roofVerts, sWinV = windowVerts, sTrimV = trimVerts, sRevealV = revealVerts, sBandV = bandVerts, sIntV = interiorVerts;
        float[] sTrim = trimData, sReveal = revealData;
        java.util.List<float[]> colliders = new java.util.ArrayList<float[]>();
        bakingOverlay = true;                          // editor houses keep every pane baked (no interactive sashes)
        try {
            houseRects = ovH;
            ovRoofMesh = makeBuffer(makeRoofMesh());   ovRoofGroups = roofGroups; ovRoofVerts = roofVerts;
            ovWinMesh  = makeBuffer(makeWindowMesh());  ovWinGroups = winGroups;  ovWinVerts = windowVerts;
            ovTrimMesh = makeBuffer(trimData);          ovTrimVerts = trimVerts;
            ovRevealMesh = makeBuffer(revealData);      ovRevealVerts = revealVerts;
            ovBandMesh = makeBuffer(makeBandMesh());    ovBandGroups = bandGroups; ovBandVerts = bandVerts;
            interiorPieces = new java.util.ArrayList<float[]>();
            for (float[] hh : ovH) if (hh[26] != 0f) furnishHouse(colliders, hh[0], hh[1], hh[2], hh[3], hh[4], (int) hh[5], hh[25]);   // skip unpacked houses (their furniture is editable FU)
            ovIntMesh = makeBuffer(makeInteriorMesh()); ovIntGroups = interiorGroups; ovIntVerts = interiorVerts;
        } finally {
            bakingOverlay = false;
            houseRects = sRects; interiorPieces = sPieces;
            roofGroups = sRoofG; winGroups = sWinG; bandGroups = sBandG; interiorGroups = sIntG;
            roofVerts = sRoofV; windowVerts = sWinV; trimVerts = sTrimV; revealVerts = sRevealV; bandVerts = sBandV; interiorVerts = sIntV;
            trimData = sTrim; revealData = sReveal;
        }
        ovHouseColliders = colliders.isEmpty() ? null : colliders;
    }

    private void rebuildOverlay() {
        if (baseBoxes == null) baseBoxes = boxes;                 // first call: snapshot the base
        if (edHouseMeshDirty) { bakeOverlayHouses(); edHouseMeshDirty = false; }
        java.util.List<float[]> ov = new java.util.ArrayList<float[]>();
        java.util.List<float[]> doorSink = new java.util.ArrayList<float[]>();   // placed-house door leaves (discarded -> open doorway)
        for (int ei = 0; ei < editObjs.size(); ei++) {
            float[] o = editObjs.get(ei);
            int cat = (int) o[0], kind = (int) o[1]; float x = o[2], z = o[3], yaw = o[4], s = o[5] <= 0f ? 1f : o[5];
            float ca = cosD(yaw), sa = sinD(yaw);
            if (cat == 0) {
                float fy = o.length > 6 ? o[6] : 0f;              // storey height for furniture placed on an upper floor
                float[][] rec = (kind >= 0 && kind < FURN.length) ? FURN[kind] : FURN[0];
                for (float[] p : rec) {
                    if (p[7] == 0f) continue;                     // only solid pieces collide
                    float dx = p[0] * s, dz = p[2] * s;
                    float wx = x + dx * ca + dz * sa, wz = z - dx * sa + dz * ca;
                    ov.add(new float[]{wx, p[1] * s + fy, wz, p[3] * s, p[4] * s, p[5] * s, 0.5f, 0.5f, 0.5f, yaw, 1f});
                }
            } else if (cat == 2) {
                float[] pr = PROP_PRESETS[Math.max(0, Math.min(kind, PROP_PRESETS.length - 1))];
                ov.add(new float[]{x, pr[1] * 0.5f * s, z, pr[0] * s, pr[1] * s, pr[2] * s, pr[3], pr[4], pr[5], yaw});
            } else if (cat == 3) {
                // Bake a placed house as a box-shell (walls with a door gap + roof slab + doorstep +
                // chimney/awning) straight into the overlay; the discarded door leaf leaves a walk-in doorway.
                float[] hp = HOUSE_PRESETS[Math.max(0, Math.min(kind, HOUSE_PRESETS.length - 1))];
                int shellStart = ov.size();
                addBuilding(ov, doorSink, x, z, hp[0] * s, hp[1] * s, hp[2] * s,
                            (int) hp[3], (int) hp[4], hp[5], hp[6], hp[7], (int) hp[8],
                            hp[9] * s, hp[10] * s, hp[11], hp[12], hp[13], (int) hp[14], (int) hp[15], yaw);
                if (ei == edInsideHouse) {
                    // Looking inside THIS house from above: its flat roof-slab box used to stay visible and
                    // completely hid the interior (the "empty storey" the player actually saw WAS the slab).
                    // Mark the slab render-skip: still collides as the ceiling, no longer drawn. The pitched
                    // ovRoof mesh is already hidden by the draw loop while edInsideHouse >= 0.
                    float slabY = hp[2] * s + 0.15f;
                    for (int bi = shellStart; bi < ov.size(); bi++) {
                        float[] bb = ov.get(bi);
                        if (Math.abs(bb[1] - slabY) < 0.02f && Math.abs(bb[4] - 0.3f) < 0.02f) {
                            if (bb.length < 12) { float[] nb = new float[12]; System.arraycopy(bb, 0, nb, 0, bb.length); ov.set(bi, nb); bb = nb; }
                            bb[10] = 1f;
                            break;
                        }
                    }
                }
                if (o.length > 6 && o[6] != 0f) {   // unpacked house: baked interior/ceiling off -> a plain thin (walkable) floor
                    float fw = Math.max(0.6f, hp[0] * s - 0.3f), fd = Math.max(0.6f, hp[1] * s - 0.3f);
                    ov.add(new float[]{x, 0.02f, z, fw, 0.04f, fd, 0.55f, 0.45f, 0.34f, yaw});
                }
            }
        }
        int extra = ovHouseColliders == null ? 0 : ovHouseColliders.size();
        float[][] merged = new float[baseBoxes.length + ov.size() + extra][];
        System.arraycopy(baseBoxes, 0, merged, 0, baseBoxes.length);
        int mi = baseBoxes.length;
        for (int i = 0; i < ov.size(); i++) merged[mi++] = ov.get(i);
        if (ovHouseColliders != null) for (float[] c : ovHouseColliders) merged[mi++] = c;   // placed-house furniture collision
        boxes = merged;
        makeOverlayTrees();
        shadowMesh = makeBuffer(makeShadowMeshData());   // editor/sandbox objects cast baked shadows too
        navBlocked = null;                               // colliders changed: rebake the walkability grid lazily
        editDirty = false;
    }

    private void makeOverlayTrees() {
        int cnt = 0; for (float[] o : editObjs) if ((int) o[0] == 1) cnt++;
        if (cnt == 0) { overlayTrees = null; overlayTreeVerts = 0; return; }
        // Budget = the true worst-case entry: a flowering bush is up to 25 vBoxes = 7200 floats (the old
        // 6000/6100 budget was smaller -> a single such bush could overflow the array = GL-thread crash).
        float[] d = new float[cnt * 7400 + 512]; int oo = 0; Random r = new Random(707);
        int[] fc = {4, 5, 6, 7, 8, 9, 10}; float uStem = cell(3), uCtr = cell(11);
        for (float[] o : editObjs) {
            if ((int) o[0] != 1) continue;
            int kind = (int) o[1]; float x = o[2], z = o[3], s = o[5] <= 0f ? 1f : o[5]; float by = terrainH(x, z);
            if (oo > d.length - 7300) break;
            if (kind == 1) oo = vBush(d, oo, x, by, z, r.nextInt(6), r, fc);
            else if (kind == 2) oo = vFlower(d, oo, x, by, z, 1 + r.nextInt(4), r, uStem, uCtr, fc);
            else oo = vTree(d, oo, x, by, z, Math.max(0.4f, s), true, r);
        }
        overlayTreeVerts = oo / 8;
        overlayTrees = makeBuffer(java.util.Arrays.copyOf(d, oo));
    }

    // --- editor actions ---
    /** Tapping the already-armed palette item again un-arms it (second way out of placement mode).
     *  Arming also collapses the palette -- you've made your choice, the world view is what matters now. */
    private void edArm(int cat, int kind) {
        if (armedCat == cat && armedKind == kind) { armedCat = -1; armedKind = -1; return; }
        armedCat = cat; armedKind = kind; selObj = -1; edGrabbed = false; edPaletteOpen = false;
    }

    /** Place the armed item directly at the tapped screen point's ground hit — one tap, no separate confirm.
     *  Stays armed afterward so repeated taps stamp down more of the same kind (e.g. a row of trees). */
    private void edPlaceAt(float sx, float sy) {
        if (armedCat < 0) return;
        float floorY = edCurrentFloorY();                 // inside a house on an upper storey: place at that height
        if (!screenRay(sx, sy, width, height, edVP, edRO, edRD) || !rayPlaneY(edRO, edRD, floorY, edHit)) {
            edSetToast("Kein Bodenpunkt getroffen — Kamera kippen"); return;
        }
        float sc = armedCat == 1 ? (armedKind == 0 ? 1.1f : (armedKind == 1 ? 0.7f : 0.6f)) : 1f;
        float[] o = (armedCat == 0 && floorY > 0.01f)
                  ? new float[]{armedCat, armedKind, edHit[0], edHit[2], 0f, sc, floorY}   // furniture carries its storey height
                  : new float[]{armedCat, armedKind, edHit[0], edHit[2], 0f, sc};
        editObjs.add(o);
        edSnapObj(editObjs.size() - 1);
        edPushUndo("ADD", editObjs.size() - 1, null);
        editDirty = true; edHouseMeshDirty = true; if (sfx != null) sfx.swap();
        edSetToast(edLabel(armedCat, armedKind) + " platziert");
    }
    private void edRotate(float deg) { if (selObj < 0) return; float[] o = editObjs.get(selObj); edPushUndo("MOD", selObj, o); o[4] = ((o[4] + deg) % 360f + 360f) % 360f; editDirty = true; edHouseMeshDirty = true; }
    private void edScale(float f) { if (selObj < 0) return; float[] o = editObjs.get(selObj); edPushUndo("MOD", selObj, o); o[5] = clamp((o[5] <= 0f ? 1f : o[5]) * f, 0.3f, 4f); editDirty = true; edHouseMeshDirty = true; }
    private void edDelete() {
        if (selObj < 0) return;
        int del = selObj;
        edPushUndo("DEL", del, editObjs.get(del));
        // edInsideHouse is a raw index into editObjs: keep it valid across the removal (leave the interior
        // view if its own house is deleted; shift it down if an earlier object is removed).
        if (del == edInsideHouse) edLeaveHouse();
        else if (del < edInsideHouse) edInsideHouse--;
        editObjs.remove(del);
        selObj = -1; editDirty = true; edHouseMeshDirty = true; if (sfx != null) sfx.swap();
    }
    private void edDuplicate() {
        if (selObj < 0) return; float[] o = editObjs.get(selObj);
        float[] c = o.clone(); c[2] += 1.5f; c[3] += 1.5f;
        editObjs.add(c); selObj = editObjs.size() - 1; edSnapObj(selObj);
        edPushUndo("ADD", selObj, null); editDirty = true; edHouseMeshDirty = true; if (sfx != null) sfx.swap();
    }
    private void edPushUndo(String op, int idx, float[] before) {
        edUndo.add(new Object[]{op, Integer.valueOf(idx), before == null ? null : before.clone()});
        while (edUndo.size() > 48) edUndo.remove(0);
    }
    private void edUndoPop() {
        if (edUndo.isEmpty()) { edSetToast("Nichts rueckgaengig"); return; }
        Object[] e = edUndo.remove(edUndo.size() - 1);
        String op = (String) e[0]; int idx = ((Integer) e[1]).intValue(); float[] before = (float[]) e[2];
        if (op.equals("ADD")) {
            if (idx >= 0 && idx < editObjs.size()) {
                if (idx == edInsideHouse) edLeaveHouse();            // keep edInsideHouse valid across the removal
                else if (idx < edInsideHouse) edInsideHouse--;
                editObjs.remove(idx);
            }
            selObj = -1;
        }
        else if (op.equals("DEL")) { if (before != null) { idx = Math.min(idx, editObjs.size()); editObjs.add(idx, before); if (idx <= edInsideHouse) edInsideHouse++; selObj = idx; } }
        else if (op.equals("MOD")) { if (before != null && idx >= 0 && idx < editObjs.size()) editObjs.set(idx, before); selObj = idx; }
        editDirty = true; edHouseMeshDirty = true; edSetToast("Rueckgaengig");
    }

    private static String edLabel(int cat, int kind) {
        if (cat == 0) return kind >= 0 && kind < ED_FURN_NAMES.length ? ED_FURN_NAMES[kind] : "Moebel";
        if (cat == 1) return kind == 1 ? "Busch" : (kind == 2 ? "Blume" : "Baum");
        if (cat == 3) return kind >= 0 && kind < HOUSE_NAMES.length ? HOUSE_NAMES[kind] : "Haus";
        return kind >= 0 && kind < PROP_NAMES.length ? PROP_NAMES[kind] : "Prop";
    }
    private static final String[] ED_FURN_NAMES = {"Bett", "Schrank", "Tisch", "Stuhl", "Sofa", "Regal", "Pflanze", "Teppich", "Lampe",
                                                   "Kommode", "Truhe", "Schreibtisch", "Ofen", "Kamin", "Bank", "Nachttisch", "Spiegel"};

    /** Serialize the editable overlay as FU/T/B lines, appended to the kept scenery text; write level.lvl. */
    private String edSerialize() {
        StringBuilder sb = new StringBuilder();
        if (baseLevelText != null && baseLevelText.length() > 0) sb.append(baseLevelText).append("\n");
        for (float[] o : editObjs) {
            int cat = (int) o[0], kind = (int) o[1];
            String x = fr(o[2]), z = fr(o[3]), yaw = fr(o[4]); float sc = o[5] <= 0f ? 1f : o[5]; String s = fr(sc);
            if (cat == 0) sb.append("FU ").append(kind).append(' ').append(x).append(' ').append(z).append(' ').append(yaw).append(' ').append(s).append(' ').append(fr(o.length > 6 ? o[6] : 0f)).append('\n');
            else if (cat == 1) sb.append("T ").append(x).append(' ').append(z).append(' ').append(s).append(' ').append(kind).append(' ').append(yaw).append('\n');
            else if (cat == 3) sb.append("EH ").append(kind).append(' ').append(x).append(' ').append(z).append(' ').append(yaw).append(' ').append(s).append(' ').append(o.length > 6 ? (int) o[6] : 0).append('\n');
            else {
                float[] pr = PROP_PRESETS[Math.max(0, Math.min(kind, PROP_PRESETS.length - 1))];
                sb.append("B ").append(x).append(' ').append(z).append(' ').append(fr(pr[0] * sc)).append(' ').append(fr(pr[1] * sc)).append(' ').append(fr(pr[2] * sc))
                  .append(' ').append(fr(pr[3])).append(' ').append(fr(pr[4])).append(' ').append(fr(pr[5])).append(' ').append(yaw).append('\n');
            }
        }
        return sb.toString();
    }
    private static String fr(float v) { return Float.toString(Math.round(v * 1000f) / 1000f); }

    private void saveLevel(boolean toast) {
        java.io.FileWriter w = null;
        try {
            java.io.File dir = appCtx.getExternalFilesDir(null);
            if (dir == null) { if (toast) edSetToast("Speichern fehlgeschlagen (kein Speicher)"); return; }
            w = new java.io.FileWriter(new java.io.File(dir, "level.lvl"));
            w.write("# AIGames in-game editor save\n");
            w.write(edSerialize());
            if (toast) edSetToast("Gespeichert (" + editObjs.size() + " Objekte)");
        } catch (Exception e) { if (toast) edSetToast("Speichern fehlgeschlagen"); }
        finally { if (w != null) try { w.close(); } catch (Exception e2) { /* fd released regardless */ } }
    }

    // ===================== in-game editor: rendering =====================
    /** Draw every editor-placed furniture piece as tinted cubes (matches the baked interior look). */
    private void drawOverlayFurniture() {
        boolean any = false; for (float[] o : editObjs) if ((int) o[0] == 0) { any = true; break; }
        if (!any) return;
        GLES20.glUseProgram(prog3);
        GLES20.glUniform1f(uWorldUV, 0f);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, woodTex);
        for (float[] o : editObjs) {
            if ((int) o[0] != 0) continue;
            int kind = (int) o[1]; float x = o[2], z = o[3], yaw = o[4], s = o[5] <= 0f ? 1f : o[5];
            float fy = o.length > 6 ? o[6] : 0f;   // storey height for upper-floor furniture
            float ca = cosD(yaw), sa = sinD(yaw);
            float[][] rec = (kind >= 0 && kind < FURN.length) ? FURN[kind] : FURN[0];
            for (float[] p : rec) {
                float dx = p[0] * s, dz = p[2] * s;
                float wx = x + dx * ca + dz * sa, wz = z - dx * sa + dz * ca;
                Matrix.setIdentityM(model, 0);
                Matrix.translateM(model, 0, wx, p[1] * s + fy, wz);
                if (yaw != 0f) Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f);
                Matrix.scaleM(model, 0, p[3] * s, p[4] * s, p[5] * s);
                float[] col = INT_COLS[(int) p[6]];
                drawWorld(cube, 36, 0f, col[0], col[1], col[2]);
            }
        }
    }

    private void edBoxLocal(float lx, float lz, float ox, float oz, float yaw, float y, float sx, float sy, float sz, float r, float g, float b) {
        float ca = cosD(yaw), sa = sinD(yaw);
        float wx = ox + lx * ca + lz * sa, wz = oz - lx * sa + lz * ca;
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, wx, y, wz);
        if (yaw != 0f) Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f);
        Matrix.scaleM(model, 0, sx, sy, sz);
        drawWorld(cube, 36, 0f, r, g, b);
    }
    private void edGroundRect(float cx, float cz, float hx, float hz, float yaw, float y, float t, float r, float g, float b) {
        edBoxLocal(0, hz, cx, cz, yaw, y, hx * 2f + t, t, t, r, g, b);
        edBoxLocal(0, -hz, cx, cz, yaw, y, hx * 2f + t, t, t, r, g, b);
        edBoxLocal(hx, 0, cx, cz, yaw, y, t, t, hz * 2f + t, r, g, b);
        edBoxLocal(-hx, 0, cx, cz, yaw, y, t, t, hz * 2f + t, r, g, b);
    }
    /** Selection footprint, pulsing cyan outline top + bottom, drawn in the 3D world. */
    private void drawEditGizmos() {
        if (selObj < 0 || selObj >= editObjs.size()) return;
        GLES20.glUseProgram(prog3);
        GLES20.glUniform1f(uWorldUV, 0f);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, metalTex);
        float[] b = new float[6];
        float pulse = 0.7f + 0.3f * pulse01(3.2f, 0f);
        float[] o = editObjs.get(selObj); edObjBounds(o, b);
        edGroundRect(o[2], o[3], b[3], b[5], o[4], 0.08f, 0.06f, AC_TOXIC[0] * pulse, AC_TOXIC[1] * pulse, AC_TOXIC[2] * pulse);
        edGroundRect(o[2], o[3], b[3], b[5], o[4], b[1] * 2f + 0.05f, 0.05f, AC_TOXIC[0] * pulse, AC_TOXIC[1] * pulse, AC_TOXIC[2] * pulse);
    }

    /** Palette tabs: 0 Moebel (seating/storage) · 1 Deko (accents) · 2 Pflanzen · 3 Props · 4 Haeuser.
     *  17 furniture kinds no longer fit one column, so furniture is split over two tabs. */
    private int[][] edCatKinds(int t) {
        if (t == 0) return new int[][]{{0,0},{0,1},{0,2},{0,3},{0,4},{0,14},{0,11},{0,15},{0,9}};
        if (t == 1) return new int[][]{{0,5},{0,6},{0,7},{0,8},{0,10},{0,12},{0,13},{0,16}};
        if (t == 2) return new int[][]{{1,0},{1,1},{1,2}};
        if (t == 4) return new int[][]{{3,0},{3,1},{3,2},{3,3}};
        return new int[][]{{2,0},{2,1},{2,2},{2,3},{2,4}};
    }
    /** A small filled swatch (no font glyph needed) so a category/kind reads at a glance. */
    private void edSwatch(float cx, float cy, float r, int cat) {
        float[] c = cat == 0 ? new float[]{0.60f, 0.42f, 0.26f} : cat == 1 ? new float[]{0.82f, 0.66f, 0.38f}
                  : cat == 2 ? new float[]{0.36f, 0.68f, 0.34f}
                  : cat == 4 ? new float[]{0.80f, 0.55f, 0.34f} : new float[]{0.62f, 0.66f, 0.72f};
        drawCircle(cx, cy, r, 0f, 0f, 0f, 0.35f);
        drawCircle(cx, cy - r * 0.12f, r * 0.86f, c[0], c[1], c[2], 1f);
    }
    /** Glossy pill button (drawButton) when active/armed, a flatter bevelled card otherwise — matches the hub's look. */
    private boolean edBtn(float cx, float cy, float w, float h, String label, float tsize, boolean active, boolean tapped, float px, float py) {
        boolean hit = tapped && hitRect(cx, cy, w, h, px, py);
        float rad = Math.min(16f * us, h * 0.32f);
        if (active) {
            drawGlow(cx, cy, w, h, rad, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 0.55f);
            drawButton(cx, cy, w, h, rad, 0.20f, 0.42f, 0.12f, 0.97f, false);
        } else {
            drawCard(cx, cy, w, h, rad, 0.082f, 0.092f, 0.064f, 0.94f);
        }
        drawTextCenteredShadow(label, cx, cy, tsize, active ? 1f : 0.92f, active ? 1f : 0.90f, active ? 0.92f : 0.80f, 1f);
        return hit;
    }
    /** SANDBOX 2D overlay: a top toolbar (pick a kind + place/move/rotate/delete), a top-right EXIT, a centre
     *  reticle (where "place in front" lands) and the move-stick base. Look/move stay the gameplay FPS controls;
     *  the touch view routes only this top strip to menu-taps (see FpsGLSurfaceView / InputState.sandboxMode). */
    private void drawSandboxHud() {
        menuPreamble();
        if (edToastT > 0f) edToastT -= 0.02f;
        boolean tapped = input.consumeMenuTap(tap);
        float tx = tap[0], ty = tap[1];

        drawRectPx(width * 0.5f, 78f * us, width, 156f * us, 0.05f, 0.055f, 0.04f, 0.42f);   // strip backdrop

        // tool row — which kind PLATZIEREN drops
        float bw = 150f * us, bh = 56f * us, gap = 6f * us, bx = 16f * us + bw * 0.5f, y1 = 46f * us;
        for (int i = 0; i < SB_TOOLS.length; i++) {
            if (edBtn(bx, y1, bw, bh, SB_TOOL_NAMES[i], 20f, sbTool == i, tapped, tx, ty)) { sbTool = i; if (sfx != null) sfx.swap(); tapped = false; }
            bx += bw + gap;
        }
        // EXIT (top-right)
        float exW = 150f * us, exH = 60f * us, exX = width - 20f * us - exW * 0.5f, exY = 48f * us;
        boolean exHit = tapped && hitRect(exX, exY, exW, exH, tx, ty);
        drawRoundRect(exX, exY, exW, exH, exH * 0.5f, 0.62f, 0.20f, 0.22f, 0.96f);
        drawTextCentered("EXIT", exX, exY, 26f, 1f, 0.94f, 0.92f, 1f);
        if (exHit) { sandboxExit(); return; }

        // action row — PLATZIEREN & WAEHLEN are always live; BEWEGEN/DREHEN/LOESCHEN need a selection.
        // WAEHLEN re-selects whatever object the centre reticle is pointing at (so you can edit anything,
        // not just the just-placed one — matters especially after a reload, when nothing is selected).
        boolean has = selObj >= 0 && selObj < editObjs.size();
        float aw = 190f * us, ah = 56f * us, agap = 8f * us, ax = 16f * us + aw * 0.5f, y2 = 112f * us;
        String[] acts = {"PLATZIEREN", "WAEHLEN", "BEWEGEN", "DREHEN", "LOESCHEN"};
        for (int i = 0; i < acts.length; i++) {
            boolean enabled = (i <= 1) || has;
            boolean hit = edBtn(ax, y2, aw, ah, acts[i], 19f, false, tapped && enabled, tx, ty);
            if (!enabled) drawRoundRect(ax, y2, aw, ah, Math.min(16f * us, ah * 0.32f), 0.04f, 0.05f, 0.07f, 0.55f);
            if (hit) {
                if (i == 0) edPlaceInFront(SB_TOOLS[sbTool][0], SB_TOOLS[sbTool][1]);
                else if (i == 1) {
                    int p = sbPickReticle();
                    if (p >= 0) { selObj = p; if (sfx != null) sfx.swap(); edSetToast(edLabel((int) editObjs.get(p)[0], (int) editObjs.get(p)[1]) + " gewaehlt"); }
                    else edSetToast("Nichts anvisiert — Objekt anschauen");
                } else if (i == 2) edMoveInFront();
                else if (i == 3) { edRotate(15f); saveSandboxObjs(); }
                else { edDelete(); saveSandboxObjs(); }
                tapped = false;
            }
            ax += aw + agap;
        }

        // centre reticle (the aim point for "place in front")
        drawCircle(width * 0.5f, height * 0.5f, 7f * us, 0f, 0f, 0f, 0.5f);
        drawCircle(width * 0.5f, height * 0.5f, 4.5f * us, 0.9f, 0.97f, 1f, 0.95f);

        // move-stick base (fixed bottom-left, matches the gameplay stick geometry)
        float scx = Hud.moveCx(), scy = Hud.moveCy(height);
        drawCircle(scx, scy, Hud.MOVE_RADIUS, 0.10f, 0.12f, 0.16f, 0.34f);
        drawCircle(scx, scy, Hud.MOVE_RADIUS * 0.42f, 0.70f, 0.85f, 1f, 0.30f);

        // hint / selection readout + a corner label. Recompute selection validity here: an action above
        // (e.g. LOESCHEN) may have changed selObj/editObjs this same frame, so `has` is stale by now.
        boolean selNow = selObj >= 0 && selObj < editObjs.size();
        String hint = selNow ? ("Ausgewaehlt: " + edLabel((int) editObjs.get(selObj)[0], (int) editObjs.get(selObj)[1]))
                             : "PLATZIEREN: Objekt vor dir  ·  WAEHLEN: anvisiertes Objekt bearbeiten";
        drawTextCentered(hint, width * 0.5f, height - 34f * us, 18f, 0.85f, 0.90f, 0.98f, 0.9f);
        drawText("SANDBOX", 18f * us, height - 16f * us, 14f, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 0.8f);
        if (edToast != null && edToastT > 0f) drawTextCentered(edToast, width * 0.5f, 172f * us, 20f, 1f, 1f, 0.85f, Math.min(1f, edToastT));
    }

    /** 2D editor UI + all tap dispatch (UI buttons first, then world select/place). */
    private void drawEditorHud() {
        menuPreamble();
        boolean tapped = input.consumeMenuTap(edTap);
        float px = edTap[0], py = edTap[1];

        // --- top bar: dark band + soft glow line + accent, like the hub header ---
        float topY = 42f * us, bh = 60f * us, hbH = 88f * us;
        drawRectPx(width * 0.5f, topY, width, hbH, 0.055f, 0.058f, 0.042f, 0.96f);
        drawBloodEdge(hbH, width);
        float fertigW = Math.min(210f * us, width * 0.22f);
        if (edInsideHouse >= 0) {
            if (edBtn(20f * us + fertigW * 0.5f, topY, fertigW, bh, "ZURUECK", 25f, false, tapped, px, py)) { edLeaveHouse(); return; }
        } else if (edBtn(20f * us + fertigW * 0.5f, topY, fertigW, bh, "MENUE", 26f, false, tapped, px, py)) { edExit(); return; }
        drawTextCenteredShadow(edInsideHouse >= 0 ? "INNENRAUM" : "EDITOR", width * 0.5f, topY, 25f, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 1f);
        // Right-side group sized as a fixed FRACTION of width (never fixed px) so it can never collide with
        // FERTIG on a narrow/low-res screen — three equal buttons filling a 58%-of-width budget.
        float rGap = 10f * us, rBudget = width * 0.58f, wBtn = (rBudget - 2f * rGap) / 3f;
        float rCx = width - 20f * us - wBtn * 0.5f;
        if (edBtn(rCx, topY, wBtn, bh, "SPEICHERN", 23f, false, tapped, px, py)) { saveLevel(true); tapped = false; }
        rCx -= wBtn + rGap;
        if (edBtn(rCx, topY, wBtn, bh, edSnap ? "RASTER AN" : "RASTER AUS", 20f, edSnap, tapped, px, py)) { edSnap = !edSnap; tapped = false; }
        rCx -= wBtn + rGap;
        if (edBtn(rCx, topY, wBtn, bh, "UNDO", 23f, false, tapped, px, py)) { edUndoPop(); tapped = false; }

        // --- palette toggle: a small always-visible tab; the category+kind list only exists while open,
        // so a placed/browsing session mostly shows the world, not a wall of buttons. ---
        float toggleW = Math.min(190f * us, width * 0.24f), toggleH = 58f * us;
        float toggleCx = 20f * us + toggleW * 0.5f, toggleCy = topY + hbH + 24f * us + toggleH * 0.5f;
        if (edBtn(toggleCx, toggleCy, toggleW, toggleH, edPaletteOpen ? "OBJEKTE ZU" : "OBJEKTE", 23f, edPaletteOpen, tapped, px, py)) { edPaletteOpen = !edPaletteOpen; tapped = false; }
        edSwatch(toggleCx - toggleW * 0.5f + 18f * us, toggleCy, 8f * us, edCatTab);

        float stickTopY = toggleCy + toggleH * 0.5f + 12f * us;   // move-stick may start below the OBJEKTE toggle...

        // --- interior view: floor stepper (always start on the lowest storey; step up/down yourself) ---
        if (edInsideHouse >= 0 && edInsideHouse < editObjs.size()) {
            int floors = edHouseFloors(editObjs.get(edInsideHouse));
            float fbY = toggleCy + toggleH * 0.5f + 40f * us, fbH = 56f * us, fbW = 74f * us;
            float fcx = toggleCx - toggleW * 0.5f + fbW * 0.5f;
            if (edBtn(fcx, fbY, fbW, fbH, "-", 30f, false, tapped, px, py)) { edSetFloor(edFloor - 1); tapped = false; }
            drawTextCenteredShadow("ETAGE " + (edFloor + 1) + "/" + floors, fcx + fbW * 0.5f + 74f * us, fbY, 21f, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 1f);
            if (edBtn(fcx + fbW + 148f * us, fbY, fbW, fbH, "+", 30f, false, tapped, px, py)) { edSetFloor(edFloor + 1); tapped = false; }
            stickTopY = Math.max(stickTopY, fbY + fbH * 0.5f + 12f * us);
        }

        if (edPaletteOpen) {
            String[] cats = {"Moebel", "Deko", "Pflanzen", "Props", "Haeuser"};
            float catW = 150f * us, catH = 54f * us, catX = 96f * us + catW * 0.5f, catGap = 8f * us;
            int[][] kinds = edCatKinds(edCatTab);
            float kx = catX + catW * 0.5f + 14f * us + 210f * us * 0.5f, kw = 210f * us, kh = 50f * us, kGap = 6f * us;
            float palTop = toggleCy + toggleH * 0.5f + 14f * us;
            float palBot = palTop + Math.max(cats.length * (catH + catGap), kinds.length * (kh + kGap)) - Math.max(catGap, kGap);
            stickTopY = palBot + 16f * us;                         // ...or below the whole open palette
            float palCx = (catX - catW * 0.5f + kx + kw * 0.5f) * 0.5f, palCy = (palTop + palBot) * 0.5f;
            drawCard(palCx, palCy, (kx + kw * 0.5f) - (catX - catW * 0.5f) + 20f * us, palBot - palTop + 24f * us, 18f * us, 0.075f, 0.09f, 0.145f, 0.90f);
            for (int i = 0; i < cats.length; i++) {
                float cy = palTop + i * (catH + catGap) + catH * 0.5f;
                if (edBtn(catX, cy, catW, catH, cats[i], 22f, edCatTab == i, tapped, px, py)) { edCatTab = i; tapped = false; }
                edSwatch(catX - catW * 0.5f + 20f * us, cy, 9f * us, i);
            }
            for (int i = 0; i < kinds.length; i++) {
                float cy = palTop + i * (kh + kGap) + kh * 0.5f;
                boolean armedThis = armedCat == kinds[i][0] && armedKind == kinds[i][1];
                if (edBtn(kx, cy, kw, kh, edLabel(kinds[i][0], kinds[i][1]), 22f, armedThis, tapped, px, py)) { edArm(kinds[i][0], kinds[i][1]); tapped = false; }
            }
        }

        // --- placement mode: tap anywhere in the world to drop it; one "Fertig" button to stop ---
        if (armedCat >= 0) {
            drawChip(width * 0.5f, height - 46f * us, measureText("Antippen zum Platzieren: " + edLabel(armedCat, armedKind), 24f) + 46f * us, 54f * us, 0.07f, 0.10f, 0.16f, 0.92f);
            drawTextCentered("Antippen zum Platzieren: " + edLabel(armedCat, armedKind), width * 0.5f, height - 46f * us, 24f, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 1f);
            float doneW = Math.min(230f * us, width * 0.24f);
            if (edBtn(width - 20f * us - doneW * 0.5f, height - 46f * us, doneW, 74f * us, "OK", 27f, true, tapped, px, py)) { armedCat = -1; armedKind = -1; tapped = false; }
        } else if (selObj >= 0 && selObj < editObjs.size()) {
            float[] o = editObjs.get(selObj);
            String info = edLabel((int) o[0], (int) o[1]) + "  x" + (Math.round((o[5] <= 0f ? 1f : o[5]) * 10f) / 10f);
            drawChip(width * 0.5f, height - 150f * us, measureText(info, 24f) + 46f * us, 46f * us, 0.10f, 0.09f, 0.05f, 0.92f);
            drawTextCentered(info, width * 0.5f, height - 150f * us, 24f, AC_GOLD[0], AC_GOLD[1], AC_GOLD[2], 1f);
            // 7 buttons in a row, budgeted as a FRACTION of width so they can never overflow the screen.
            float ty = height - 58f * us, bhh = 76f * us, gap = 8f * us;
            float budget = Math.min(120f * us * 7f + gap * 6f, width * 0.94f);
            float bw = (budget - gap * 6f) / 7f;
            float x = width * 0.5f - budget * 0.5f + bw * 0.5f;
            drawCard(width * 0.5f, ty, budget + 16f * us, bhh + 16f * us, 20f * us, 0.075f, 0.09f, 0.145f, 0.88f);
            if (edBtn(x, ty, bw, bhh, "-15", 24f, false, tapped, px, py)) { edRotate(-15f); tapped = false; } x += bw + gap;
            if (edBtn(x, ty, bw, bhh, "+15", 24f, false, tapped, px, py)) { edRotate(15f); tapped = false; } x += bw + gap;
            if (edBtn(x, ty, bw, bhh, "90", 24f, false, tapped, px, py)) { edRotate(90f); tapped = false; } x += bw + gap;
            if (edBtn(x, ty, bw, bhh, "kleiner", 21f, false, tapped, px, py)) { edScale(0.9f); tapped = false; } x += bw + gap;
            if (edBtn(x, ty, bw, bhh, "groesser", 20f, false, tapped, px, py)) { edScale(1.1f); tapped = false; } x += bw + gap;
            if (edBtn(x, ty, bw, bhh, "Kopie", 22f, false, tapped, px, py)) { edDuplicate(); tapped = false; } x += bw + gap;
            if (edBtn(x, ty, bw, bhh, "Loeschen", 20f, false, tapped, px, py)) { edDelete(); tapped = false; } x += bw + gap;
        } else {
            drawTextCenteredShadow("Stick links bewegt  -  1 Finger rechts dreht die Sicht  -  2 Finger zoom  -  Objekt antippen", width * 0.5f, height - 30f * us, 19f, 0.75f, 0.8f, 0.9f, 0.85f);
        }

        if (tapped) {
            if (armedCat >= 0) edPlaceAt(px, py);          // world tap while armed: place directly where tapped
            else selObj = edPickAt(px, py);                // otherwise: select / deselect
        }
        if (input.consumeEditDoubleTap(edTap2)) {          // double-tap: deep-edit the object under the finger
            int hit = edPickAt(edTap2[0], edTap2[1]);
            if (hit >= 0 && (int) editObjs.get(hit)[0] == 3 && edInsideHouse < 0) edEnterHouse(hit);   // a house -> its interior
            else if (hit >= 0) { selObj = hit; edSetToast("Bearbeiten: ziehen zum Verschieben"); }     // other object -> select + move
        }

        // --- left move stick: publish the clear vertical band, draw a faint corner hint when navigating,
        //     and the live floating stick while held. Band excludes the top bar/palette and bottom bar so a
        //     press on a button is never swallowed by the stick. ---
        float stickBotY = height - 60f * us;                       // above the bottom hint line...
        if (armedCat >= 0) stickBotY = height - 96f * us;          // ...or the place chip + FERTIG...
        else if (selObj >= 0 && selObj < editObjs.size()) stickBotY = height - 118f * us;   // ...or the action bar + info chip
        input.setEditStickBounds(stickTopY, stickBotY);

        input.getEditStick(edStickArr);
        float rStick = Math.max(80f, height * 0.16f);
        boolean stickOn = edStickArr[0] != 0f;
        boolean navFree = armedCat < 0 && selObj < 0;              // only show the idle hint during free navigation
        if (stickOn || navFree) {
            float baseCx = stickOn ? edStickArr[3] : width * 0.13f;
            float baseCy = stickOn ? edStickArr[4] : height * 0.72f;
            float knobX = stickOn ? baseCx + edStickArr[1] * rStick : baseCx;
            float knobY = stickOn ? baseCy - edStickArr[2] * rStick : baseCy;
            float ringA = stickOn ? 0.30f : 0.16f, wellA = stickOn ? 0.42f : 0.22f, knobA = stickOn ? 0.85f : 0.5f;
            drawCircle(baseCx, baseCy, rStick + 2.5f * us, 1f, 1f, 1f, ringA);        // ring
            drawCircle(baseCx, baseCy, rStick, 0.10f, 0.12f, 0.16f, wellA);          // dark well
            drawPad(knobX, knobY, 62f, 0.85f, 0.88f, 0.95f, knobA);                  // glossy knob
            if (!stickOn) drawTextCentered("BEWEGEN", baseCx, baseCy + rStick + 22f * us, 17f, 0.8f, 0.85f, 0.95f, 0.7f);
        }

        if (edToastT > 0f && edToast != null) {
            float a = Math.min(1f, edToastT);
            drawChip(width * 0.5f, height * 0.30f, measureText(edToast, 26f) + 46f * us, 54f * us, 0.06f, 0.16f, 0.10f, 0.90f * a);
            drawTextCentered(edToast, width * 0.5f, height * 0.30f, 26f, 1f, 1f, 1f, a);
        }
    }

    private void tickTimers(float dt) {
        if (storyTimer > 0f) storyTimer -= dt;
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
        input.setMenuMode(state == ST_HUB || state == ST_SUMMARY);
        input.setEditMode(state == ST_EDIT);
        input.setSandboxMode(state == ST_SANDBOX);
    }

    private void restart() {
        // Drain gameplay input queued while the camera was frozen (hub/summary/death): the frozen branch
        // consumes nothing, so look deltas + jump/fire flags accumulated and fired all at once on the first
        // frame of the next run (a violent camera snap). Menu-tap channels stay untouched.
        input.consumeLook(lookTmp);
        input.consumeJump(); input.consumeFire(); input.consumeAimToggle(); input.consumeSwitch();
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
        for (int i = 0; i < windowOpen.length; i++) { windowOpen[i] = 0f; windowTarget[i] = 0f; }
        nearWindow = -1;
        for (int i = 0; i < MAX_ENEMIES; i++) { enAlive[i] = false; enScale[i] = 1f; enBoss[i] = 0; enKx[i] = 0f; enKz[i] = 0f; enWind[i] = 0f; blipAge[i] = 999f; blipRel[i] = -1f; enStuck[i] = 0f; enProgT[i] = 0f; }
        navTimer = 0f; navPx = 1e9f; navFieldValid = false;
        radarPrevT = -1f;
        for (int i = 0; i < MAX_PICKUPS; i++) pkLife[i] = 0f;
        hitStop = 0f;
        for (int i = 0; i < MAX_PARTICLES; i++) pLife[i] = 0f;
        waveBreak = 0f; waveBanner = 0f; bossPending = 0;
        beginWave(1);
    }

    /** Begin a fresh run from the hub. */
    private void startRun() {
        preRunCash = cash; preRunXp = xp; preRunLevel = playerLevel;   // snapshot the bank so an ABORTED run can be un-counted
        restart();
        storyTimer = 7f;                 // chapter briefing card over the first seconds
        state = ST_PLAYING;
        hubWasShown = false; hubTabShown = -1;   // replay hub entrance + content stagger next visit
    }

    /** Leave a run early via the in-game MENU button: this is an ABORT, so the run's earnings do NOT count --
     *  cash/xp are rolled back to the pre-run snapshot and the high score is left untouched (unlike dying,
     *  which banks the run through the summary). */
    private void abortToHub() {
        cash = preRunCash; xp = preRunXp; playerLevel = preRunLevel;   // roll back everything earned this run
        saveMeta();                               // persist the rollback so nothing leaks if the app closes
        deathAnim = 0f; aimOn = false; aim = 0f; sprinting = false;
        state = ST_HUB; hubWasShown = false; hubTabShown = -1;
    }

    /** Enter the peaceful first-person SANDBOX from the hub: reset the pose, kill any wave scheduling, then
     *  swap the editObjs overlay to the sandbox's own (backing up the authored/combat overlay) so nothing a
     *  run relies on is touched and nothing placed here can leak into combat. */
    private void sandboxEnter() {
        restart();                                                     // pose px=0,pz=9,yaw=0, HP full, enAlive[] cleared
        waveToSpawn = 0; waveRemaining = 0; waveBreak = 0f; waveBanner = 0f; bossPending = 0;   // undo restart()'s beginWave(1)
        for (int i = 0; i < MAX_ENEMIES; i++) enAlive[i] = false;
        playerHP = effMaxHp(); hurtFlash = 0f;
        levelObjsBackup.clear();                                       // snapshot the authored/combat overlay …
        for (float[] o : editObjs) levelObjsBackup.add(o.clone());
        editObjs.clear(); edUndo.clear();                             // … then load the sandbox's own overlay
        loadSandboxObjs();
        selObj = -1; armedCat = -1; armedKind = -1; sbTool = 0;
        aimOn = false; aim = 0f;
        editDirty = true; edHouseMeshDirty = true;                    // rebake boxes = baseBoxes + sandbox overlay
        state = ST_SANDBOX; hubWasShown = false; hubTabShown = -1;
    }

    /** Leave the sandbox back to the hub: persist the sandbox overlay to sandbox.lvl, then restore the
     *  authored/combat overlay so a subsequent PLAY sees exactly the base + level.lvl world. */
    private void sandboxExit() {
        saveSandboxObjs();
        editObjs.clear();
        for (float[] o : levelObjsBackup) editObjs.add(o);
        levelObjsBackup.clear(); edUndo.clear();
        selObj = -1; armedCat = -1; armedKind = -1;
        aimOn = false; aim = 0f;
        editDirty = true; edHouseMeshDirty = true;                    // rebake boxes back to base + authored overlay
        state = ST_HUB; hubWasShown = false; hubTabShown = -1;
    }

    // Placement distance in front of the player, by category (houses need more room than props/plants).
    private float sbFrontDist(int cat) { return cat == 3 ? 6.0f : (cat == 1 ? 3.5f : 3.2f); }

    /** SANDBOX "place in front": drop the given kind ~a few metres ahead of the player, facing them, and
     *  auto-select it. Reuses the editor's add+snap+undo+dirty tail — only the ground ray is replaced by the
     *  reticle-forward vector (player yaw is RADIANS; editObjs[4] is DEGREES, hence the conversion). */
    private void edPlaceInFront(int cat, int kind) {
        float d = sbFrontDist(cat);
        float wx = px + (float) Math.sin(yaw) * d;
        float wz = pz - (float) Math.cos(yaw) * d;
        float yawDeg = (float) Math.toDegrees(yaw) + (cat == 3 ? 180f : 0f);   // houses: door faces the player
        yawDeg = ((yawDeg % 360f) + 360f) % 360f;
        float sc = cat == 1 ? (kind == 0 ? 1.1f : (kind == 1 ? 0.7f : 0.6f)) : 1f;   // matches edPlaceAt's per-kind default
        editObjs.add(new float[]{cat, kind, wx, wz, yawDeg, sc});
        edSnapObj(editObjs.size() - 1);
        edPushUndo("ADD", editObjs.size() - 1, null);
        selObj = editObjs.size() - 1;
        editDirty = true; edHouseMeshDirty = true; if (sfx != null) sfx.swap();
        saveSandboxObjs();
        edSetToast(edLabel(cat, kind) + " platziert");
    }

    /** SANDBOX "move": re-drop the current selection in front of the player. */
    private void edMoveInFront() {
        if (selObj < 0 || selObj >= editObjs.size()) return;
        float[] o = editObjs.get(selObj);
        float d = sbFrontDist((int) o[0]);
        edPushUndo("MOD", selObj, o);
        o[2] = px + (float) Math.sin(yaw) * d;
        o[3] = pz - (float) Math.cos(yaw) * d;
        editDirty = true; edHouseMeshDirty = true;
        edSnapObj(selObj);
        if (sfx != null) sfx.swap();
        saveSandboxObjs();
    }

    /** SANDBOX "select what you're looking at": cast a ray from the screen-centre reticle (using the live
     *  first-person view-projection) and select the nearest editObjs object it hits. -1 = nothing hit. Mirrors
     *  edPickAt but feeds the FP camera's proj*view instead of the editor's orbit matrix. */
    private int sbPickReticle() {
        Matrix.multiplyMM(sbVP, 0, proj, 0, view, 0);
        if (!screenRay(width * 0.5f, height * 0.5f, width, height, sbVP, edRO, edRD)) return -1;
        int best = -1; float bestT = 1e30f; float[] b = new float[6];
        for (int i = 0; i < editObjs.size(); i++) {
            float[] o = editObjs.get(i);
            edObjBounds(o, b);
            float t = rayBox(edRO, edRD, b[0], b[1], b[2], b[3] + 0.3f, b[4] + 0.3f, b[5] + 0.3f, o[4]);
            if (t >= 0f && t < bestT) { bestT = t; best = i; }
        }
        return best;
    }

    /** Persist the sandbox overlay to a SEPARATE <externalFiles>/sandbox.lvl (never level.lvl). Generic
     *  "O cat kind x z yaw scale f6" grammar so every field round-trips exactly. Best-effort (silent). */
    private void saveSandboxObjs() {
        java.io.FileWriter w = null;
        try {
            if (appCtx == null) return;
            java.io.File dir = appCtx.getExternalFilesDir(null);
            if (dir == null) return;
            w = new java.io.FileWriter(new java.io.File(dir, "sandbox.lvl"));
            w.write("# AIGames sandbox overlay: O cat kind x z yaw scale f6\n");
            for (float[] o : editObjs) {
                float f6 = o.length > 6 ? o[6] : 0f, s = o[5] <= 0f ? 1f : o[5];
                w.write("O " + (int) o[0] + ' ' + (int) o[1] + ' ' + fr(o[2]) + ' ' + fr(o[3]) + ' ' + fr(o[4]) + ' ' + fr(s) + ' ' + fr(f6) + '\n');
            }
        } catch (Exception e) { /* best-effort persistence */ }
        finally { if (w != null) try { w.close(); } catch (Exception e2) { /* fd released regardless */ } }
    }

    /** Reload the sandbox overlay from sandbox.lvl into editObjs (caller has already cleared it). Silent. */
    private void loadSandboxObjs() {
        java.io.BufferedReader br = null;
        try {
            if (appCtx == null) return;
            java.io.File dir = appCtx.getExternalFilesDir(null);
            if (dir == null) return;
            java.io.File f = new java.io.File(dir, "sandbox.lvl");
            if (!f.exists()) return;
            br = new java.io.BufferedReader(new java.io.FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                String[] t = line.split("\\s+");
                if (!t[0].equals("O") || t.length < 7) continue;
                editObjs.add(new float[]{pf(t[1]), pf(t[2]), pf(t[3]), pf(t[4]), pf(t[5]), pf(t[6]), t.length > 7 ? pf(t[7]) : 0f});
            }
        } catch (Exception e) { /* best-effort */ }
        finally { if (br != null) try { br.close(); } catch (Exception e) {} }
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
                float base = terrainH(enX[i], enZ[i]);    // hit boxes stand where the enemy is DRAWN (not y=0)
                if (enType[i] == 1 && enBoss[i] == 0) {   // crawler: long low body, head out in front — aim LOW
                    float bt = rayBox(px, py, pz, dx, dy, dz,
                            enX[i] - 0.55f * sc, base, enZ[i] - 0.55f * sc, enX[i] + 0.55f * sc, base + 0.50f * sc, enZ[i] + 0.55f * sc);
                    if (bt >= 0f && bt < best) { best = bt; type = 1; idx = i; }
                    float hx = enX[i] + (float) Math.sin(enFace[i]) * 0.38f * sc;   // head crawls ahead of the torso
                    float hz = enZ[i] + (float) Math.cos(enFace[i]) * 0.38f * sc;
                    float ht = rayBox(px, py, pz, dx, dy, dz,
                            hx - 0.20f * sc, base + 0.20f * sc, hz - 0.20f * sc, hx + 0.20f * sc, base + 0.62f * sc, hz + 0.20f * sc);
                    if (ht >= 0f && ht < best) { best = ht; type = 2; idx = i; }
                    continue;
                }
                float bt = rayBox(px, py, pz, dx, dy, dz,
                        enX[i] - 0.45f * sc, base, enZ[i] - 0.45f * sc, enX[i] + 0.45f * sc, base + 1.24f * sc, enZ[i] + 0.45f * sc);
                if (bt >= 0f && bt < best) { best = bt; type = 1; idx = i; }
                float ht = rayBox(px, py, pz, dx, dy, dz,
                        enX[i] - 0.24f * sc, base + 1.26f * sc, enZ[i] - 0.24f * sc, enX[i] + 0.24f * sc, base + 1.78f * sc, enZ[i] + 0.24f * sc);
                if (ht >= 0f && ht < best) { best = ht; type = 2; idx = i; }
            }
            for (int i = 0; i < boxes.length; i++) {
                float[] b = boxes[i];
                float t;
                if (b.length > 9 && b[9] != 0f) {         // rotated wall/fence: yaw-aware ray test (bullets used
                    shotO[0] = px; shotO[1] = py; shotO[2] = pz;   //  to hit the invisible UNROTATED footprint)
                    shotD[0] = dx; shotD[1] = dy; shotD[2] = dz;
                    t = rayBox(shotO, shotD, b[0], b[1], b[2], b[3] * 0.5f, b[4] * 0.5f, b[5] * 0.5f, b[9]);
                } else {
                    t = rayBox(px, py, pz, dx, dy, dz,
                            b[0] - b[3] * 0.5f, b[1] - b[4] * 0.5f, b[2] - b[5] * 0.5f,
                            b[0] + b[3] * 0.5f, b[1] + b[4] * 0.5f, b[2] + b[5] * 0.5f);
                }
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
                boolean lowHit = enType[idx] == 1 && enBoss[idx] == 0;              // crawler: feedback near the ground
                spawnHitSparks(enX[idx], terrainH(enX[idx], enZ[idx]) + (lowHit ? (head ? 0.45f : 0.32f) : (head ? 1.6f : 0.95f)) * enScale[idx], enZ[idx], head, dx, dy, dz);
                // floating damage number at the enemy (projected to screen)
                if (optDmgNum && projectToScreen(enX[idx], (lowHit ? 0.75f : 1.45f) * enScale[idx], enZ[idx], projScreen)) {
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
            drawRoundRect(x - tw * 0.5f, cy, tw, 28f * us, 14f * us, 0.05f, 0.045f, 0.035f, 0.55f * a);
            float dr = feedKind[i] == 1 ? 1f : (feedKind[i] == 2 ? 1f : 0.95f);
            float dg = feedKind[i] == 1 ? 0.82f : (feedKind[i] == 2 ? 0.3f : 0.35f);
            float db = feedKind[i] == 1 ? 0.25f : (feedKind[i] == 2 ? 0.25f : 0.3f);
            drawCircle(x - tw + 16f * us, cy, 5f * us, dr, dg, db, 0.95f * a);
            drawText(feedText[i], x - tw + 28f * us, cy, 15f, 0.88f, 0.85f, 0.76f, a);
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
        spawnDeathBurst(enX[idx], enZ[idx],
                enScale[idx] * (enType[idx] == 1 && enBoss[idx] == 0 ? 0.6f : 1f),   // crawlers burst low + small
                enOutfit[idx]);
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
        baseGain = Math.round(baseGain * (1f + 0.30f * levelId));   // later chapters pay for their danger
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
            spawnPopup("LEVEL UP!", width * 0.5f, height * 0.33f, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 32f);
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

    // ---- baked directional shadow mesh -----------------------------------------------------------
    // Every static object casts a real projected shadow: its base corners plus its top corners pushed
    // along the sun ray onto the ground plane, hulled and fan-triangulated. Houses cast one clean hull
    // each; every outdoor prop box (fences, stalls, lamps, crates...) casts its own; interior furniture
    // casts onto its room floor; trees cast a sun-aligned ellipse. Drawn with multiplicative blending.

    /** 2D convex hull (Andrew), pts = {x,z}; result count returned, hull written back into pts order. */
    private static int hull2d(float[][] pts, int n, float[][] out) {
        java.util.Arrays.sort(pts, 0, n, new java.util.Comparator<float[]>() {
            public int compare(float[] a, float[] b) { return a[0] != b[0] ? Float.compare(a[0], b[0]) : Float.compare(a[1], b[1]); }
        });
        int k = 0;
        for (int i = 0; i < n; i++) {                            // lower
            while (k >= 2 && (out[k-1][0]-out[k-2][0])*(pts[i][1]-out[k-2][1]) - (out[k-1][1]-out[k-2][1])*(pts[i][0]-out[k-2][0]) <= 0f) k--;
            out[k++] = pts[i];
        }
        int lower = k + 1;
        for (int i = n - 2; i >= 0; i--) {                       // upper
            while (k >= lower && (out[k-1][0]-out[k-2][0])*(pts[i][1]-out[k-2][1]) - (out[k-1][1]-out[k-2][1])*(pts[i][0]-out[k-2][0]) <= 0f) k--;
            out[k++] = pts[i];
        }
        return k - 1;
    }

    /** Project one yawed box (centre cx/cz, half sizes, yaw) with top height topH onto plane y: hull fan. */
    private static int shadowHull(float[] d, int o, float cx, float cz, float hx, float hz, float yawDeg,
                                  float topH, float planeY) {
        double a = Math.toRadians(yawDeg);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float off = Math.max(0f, topH - planeY);
        float[][] pts = new float[8][];
        int n = 0;
        for (int i = 0; i < 4; i++) {
            float lx = (i % 2 == 0 ? -1 : 1) * hx, lz = (i < 2 ? -1 : 1) * hz;
            float wx = cx + lx * ca + lz * sa, wz = cz - lx * sa + lz * ca;
            pts[n++] = new float[]{wx, wz};                                    // base corner
            pts[n++] = new float[]{wx + SHX * off, wz + SHZ * off};            // top corner cast onto the plane
        }
        float[][] hu = new float[9][];
        int hn = hull2d(pts, n, hu);
        for (int i = 1; i + 1 < hn; i++) {                                     // fan triangulate
            if (o > d.length - 3 * 8) return o;
            o = put(d, o, hu[0][0], planeY, hu[0][1], 0f, 1f, 0f, 0.5f, 0.5f);
            o = put(d, o, hu[i][0], planeY, hu[i][1], 0f, 1f, 0f, 0.5f, 0.5f);
            o = put(d, o, hu[i + 1][0], planeY, hu[i + 1][1], 0f, 1f, 0f, 0.5f, 0.5f);
        }
        return o;
    }

    private float[] makeShadowMeshData() {
        java.util.List<float[]> hRects = houseRects != null ? houseRects : java.util.Collections.<float[]>emptyList();
        float[][] bxs = boxes != null ? boxes : new float[0][];
        int est = (hRects.size() + bxs.length + (interiorPieces == null ? 0 : interiorPieces.size())
                + (treeList == null ? 0 : treeList.length)) * 21 * 8 + 512;
        float[] d = new float[est];
        int o = 0;
        // one clean hull per house (walls/roof/chimney skipped below so corners don't double-darken)
        for (float[] hh : hRects)
            o = shadowHull(d, o, hh[0], hh[1], hh[2] * 0.5f + 0.15f, hh[3] * 0.5f + 0.15f,
                    hh.length > 25 ? hh[25] : 0f, Math.min(hh[4], 5f), 0.055f);
        // every free-standing prop box (fence rails/posts, stalls, lamps, benches, crates, planters...)
        for (float[] b : bxs) {
            if (b.length > 10 && b[10] != 0f) continue;            // invisible collider: visual casts elsewhere
            float top = b[1] + b[4] * 0.5f, bottom = b[1] - b[4] * 0.5f;
            if (top < 0.12f || bottom > 2.2f) continue;            // paving casts nothing; high slabs belong to houses
            boolean inHouse = false;
            for (float[] hh : hRects) {
                houseLocal(hh, b[0], b[2], hlTmp);
                if (Math.abs(hlTmp[0]) < hh[2] * 0.5f + 0.4f && Math.abs(hlTmp[1]) < hh[3] * 0.5f + 0.4f) { inHouse = true; break; }
            }
            if (inHouse) continue;                                 // walls/doorsteps/canopies: the house hull covers it
            if (o > d.length - 24 * 8) break;
            o = shadowHull(d, o, b[0], b[2], b[3] * 0.5f, b[5] * 0.5f, b.length > 9 ? b[9] : 0f,
                    Math.min(top, 2.5f), 0.055f);
        }
        // interior furniture: a soft hull cast onto the room floor right under each piece
        if (interiorPieces != null) for (float[] p : interiorPieces) {
            float top = p[1] + p[4] * 0.5f, bottom = p[1] - p[4] * 0.5f;
            if (p[4] < 0.12f || bottom > 1.6f) continue;           // rugs/ceiling bulbs cast nothing useful
            if (o > d.length - 24 * 8) break;
            o = shadowHull(d, o, p[0], p[2], p[3] * 0.5f, p[5] * 0.5f, p.length > 7 ? p[7] : 0f,
                    Math.min(top, bottom + 0.9f), Math.max(0.055f, bottom + 0.015f));
        }
        // trees: a sun-aligned soft ellipse (as a rotated quad) offset the way the crown would fall
        if (treeList != null) for (float[] t : treeList) {
            if (o > d.length - 6 * 8) break;
            float ts = t[2];
            float cxs = t[0] + SHX * 1.6f * ts, czs = t[1] + SHZ * 1.6f * ts;
            double az = Math.toRadians(SHADOW_AZI);
            float caz = (float) Math.cos(az), saz = (float) Math.sin(az);
            float hl = 1.55f * ts, hw = 1.00f * ts;                // long axis along the sun azimuth
            float y = terrainH(t[0], t[1]) + 0.055f;
            float[][] q = {{-hw, -hl}, {hw, -hl}, {hw, hl}, {-hw, hl}};
            float[][] w = new float[4][2];
            for (int i = 0; i < 4; i++) {
                w[i][0] = cxs + q[i][0] * caz + q[i][1] * saz;
                w[i][1] = czs - q[i][0] * saz + q[i][1] * caz;
            }
            o = put(d, o, w[0][0], y, w[0][1], 0f, 1f, 0f, 0.5f, 0.5f);
            o = put(d, o, w[1][0], y, w[1][1], 0f, 1f, 0f, 0.5f, 0.5f);
            o = put(d, o, w[2][0], y, w[2][1], 0f, 1f, 0f, 0.5f, 0.5f);
            o = put(d, o, w[0][0], y, w[0][1], 0f, 1f, 0f, 0.5f, 0.5f);
            o = put(d, o, w[2][0], y, w[2][1], 0f, 1f, 0f, 0.5f, 0.5f);
            o = put(d, o, w[3][0], y, w[3][1], 0f, 1f, 0f, 0.5f, 0.5f);
        }
        shadowVerts = o / 8;
        return java.util.Arrays.copyOf(d, o);
    }

    /** The baked shadow mesh: multiplicative blend darkens whatever is underneath (ground, floors,
     *  paving). Drawn AFTER the whole static world so interior floors don't repaint the furniture
     *  shadows; depth-tested (no write) so walls/boxes still occlude shadows behind them. Overcast
     *  melts the sun shadows away; mode 4 = flat unlit colour (the multiply factor). */
    private void drawShadowMesh() {
        if (shadowMesh == null || shadowVerts <= 0) return;
        GLES20.glUseProgram(prog3);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ZERO, GLES20.GL_SRC_COLOR);
        GLES20.glDepthMask(false);
        Matrix.setIdentityM(model, 0);
        float lift = 0.78f * wOvercast;                            // -> factor ~1 when fully overcast
        drawWorld(shadowMesh, shadowVerts, 4f,
                0.40f + (1f - 0.40f) * lift, 0.43f + (1f - 0.43f) * lift, 0.55f + (1f - 0.55f) * lift);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
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
        // Static geometry (houses, props, fences, trees, furniture) now casts baked projected shadows
        // above; only things the bake can't know keep a soft blob: editor-placed PLANTS (their visual
        // lives in the overlay vegetation mesh) and the moving enemies below.
        float cullSq = 28f * 28f;
        for (float[] o : editObjs) {
            if ((int) o[0] != 1) continue;                          // props/houses are boxes -> baked on rebuild
            int pk = (int) o[1];
            if (pk == 2) continue;                                  // flowers are too small to cast a blob
            float ox = o[2], oz = o[3], s = o[5] <= 0f ? 1f : o[5];
            float dx = ox - px, dz = oz - pz;
            float sh = pk == 1 ? 0.5f * s : 1.3f * s, sr = pk == 1 ? 0.6f * s : 1.05f * s;
            if (dx * dx + dz * dz < cullSq) blob(ox, terrainH(ox, oz), sh, oz, sr);
        }
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (enAlive[i]) blob(enX[i], terrainH(enX[i], enZ[i]), 1.0f, enZ[i], 0.55f);
        }
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void blob(float ox, float groundY, float oy, float oz, float baseR) {
        float s = oy / (-LDY);                                  // projection distance from base to shadow tip
        float cx = ox + LDX * s * 0.5f, cz = oz + LDZ * s * 0.5f;   // centre the oval midway between base and tip
        float halfLen = baseR + 0.28f * oy;                     // stretched along the sun azimuth (longer for taller objects)
        float halfWid = baseR * 0.9f;                           // a touch narrower across -> reads as a cast shadow, not a pool
        // 0.45 base: the smoothstep penumbra shader has a denser core than the old linear tent. Overcast melts
        // the directional shadow toward faint contact-AO (no sun = no hard shadow), and fog fades shadows with
        // the same curve their casters fade, so they stop popping through the haze.
        float alpha = 0.45f / (1f + s * 0.18f);
        alpha *= 1f - 0.55f * wOvercast;
        float dCam = (float) Math.sqrt((cx - px) * (cx - px) + (cz - pz) * (cz - pz));
        alpha *= 1f - Math.max(0f, Math.min(0.82f, (dCam - effFogStart) / Math.max(effFogEnd - effFogStart, 0.001f)));
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, cx, groundY + 0.02f, cz);
        Matrix.rotateM(model, 0, SHADOW_AZI, 0f, 1f, 0f);       // local +Z runs along the sun azimuth
        Matrix.scaleM(model, 0, halfWid, 1f, halfLen);
        Matrix.multiplyMM(tmpA, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmpA, 0);
        GLES20.glUniformMatrix4fv(uBlobMVP, 1, false, mvp, 0);
        GLES20.glUniform1f(uBlobA, alpha);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, circleVerts);
    }

    // --- HUD: radar, enemy health bars, boss bar ---

    /** A corner radar that behaves like the real thing: the rotating sweep line paints a red echo when
     *  it passes an enemy's bearing; the echo then afterglows and fades where it was painted. */
    private void drawRadar() {
        float rad = 64f * us;
        float rcx = 24f * us + rad, rcy = 178f * us + rad;   // top-left, below the cash/level stack
        float fX = camFwd[0], fZ = camFwd[2];
        float fl = (float) Math.sqrt(fX * fX + fZ * fZ); if (fl < 1e-4f) fl = 1f; fX /= fl; fZ /= fl;
        float rX = -fZ, rZ = fX;                                 // right = forward rotated -90°

        // sweep bookkeeping: track each enemy's bearing RELATIVE to the advancing sweep. The gap
        // shrinks steadily and wraps 0→2π exactly when the line passes over the enemy — detecting
        // that wrap catches every crossing no matter how fast the player (or the enemy) is turning.
        float dtR = radarPrevT < 0f ? 0f : Math.min(0.25f, timeAcc - radarPrevT);
        radarPrevT = timeAcc;
        float s1 = timeAcc * RADAR_RATE;
        float range = 40f;
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (blipAge[i] < 900f) blipAge[i] += dtR;            // echoes keep decaying even after a kill
            if (!enAlive[i]) { blipRel[i] = -1f; continue; }
            float dx = enX[i] - px, dz = enZ[i] - pz;
            float b = (float) Math.atan2(dx * rX + dz * rZ, dx * fX + dz * fZ);   // radar bearing, 0 = up
            float rel = (b - s1) % 6.2831853f; if (rel < 0f) rel += 6.2831853f;
            if (blipRel[i] >= 0f && Math.abs(rel - blipRel[i]) > 3.1415926f) {    // wrapped → sweep passed over
                blipX[i] = enX[i]; blipZ[i] = enZ[i]; blipAge[i] = 0f; blipBoss[i] = enBoss[i] > 0;
            }
            blipRel[i] = rel;
        }

        // military phosphor dish
        drawCircle(rcx, rcy + 3f * us, rad + 5f * us, 0f, 0f, 0f, 0.35f);          // shadow
        drawCircle(rcx, rcy, rad + 3f * us, 0.10f, 0.13f, 0.07f, 0.92f);           // gunmetal housing
        drawCircle(rcx, rcy, rad + 1f * us, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 0.28f); // powered rim
        drawCircle(rcx, rcy, rad, 0.015f, 0.045f, 0.020f, 0.82f);                  // dark phosphor glass
        for (int k = 0; k < 16; k++) {                                             // dotted range rings
            float a2 = k * 0.3927f;
            drawCircle(rcx + (float) Math.sin(a2) * rad * 0.62f, rcy - (float) Math.cos(a2) * rad * 0.62f,
                    1.3f * us, 0.35f, 0.75f, 0.35f, 0.30f);
            if ((k & 1) == 0)
                drawCircle(rcx + (float) Math.sin(a2) * rad * 0.31f, rcy - (float) Math.cos(a2) * rad * 0.31f,
                        1.2f * us, 0.35f, 0.75f, 0.35f, 0.26f);
        }
        drawRectPx(rcx, rcy, rad * 2f, 1.2f * us, 0.30f, 0.65f, 0.30f, 0.14f);     // cross hairs
        drawRectPx(rcx, rcy, 1.2f * us, rad * 2f, 0.30f, 0.65f, 0.30f, 0.14f);

        // the sweep: a bright leading line with a fading phosphor wedge trailing behind it
        float sw = s1 % 6.2831853f;
        for (int t = 6; t >= 0; t--) {
            float a2 = sw - t * 0.075f;
            float la = t == 0 ? 0.9f : 0.30f * (1f - t / 7.5f);
            float ss = (float) Math.sin(a2), sc = (float) Math.cos(a2);
            int nd = t == 0 ? 8 : 5;
            for (int k = 1; k <= nd; k++) {
                float rr = rad * k / nd;
                drawCircle(rcx + ss * rr, rcy - sc * rr, (t == 0 ? 2.4f : 2.6f) * us,
                        0.55f, 1f, 0.45f, la * (0.35f + 0.65f * k / nd));
            }
        }
        drawRoundRect(rcx, rcy - rad + 6f * us, 4f * us, 10f * us, 2f * us, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 0.85f); // forward notch

        // painted echoes: red, brightest right after the sweep hits, then glow-fading in place
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (blipAge[i] >= RADAR_GLOW) continue;
            float k = 1f - blipAge[i] / RADAR_GLOW;                 // 1 fresh → 0 gone
            float dx = blipX[i] - px, dz = blipZ[i] - pz;
            float nx = (dx * rX + dz * rZ) / range, ny = (dx * fX + dz * fZ) / range;
            float bl = (float) Math.sqrt(nx * nx + ny * ny);
            boolean edge = bl > 1f; if (edge) { nx /= bl; ny /= bl; }
            float bx = rcx + nx * rad * 0.9f, by = rcy - ny * rad * 0.9f;
            float fade = k * k * (edge ? 0.55f : 1f);               // quadratic ≈ phosphor decay
            boolean boss = blipBoss[i];
            float bs = (boss ? 6.5f : 4.2f) * us * (0.82f + 0.30f * k);
            drawCircle(bx, by, bs + 2f * us, 0.45f, 0.03f, 0.02f, 0.55f * fade);    // dark blood halo
            drawCircle(bx, by, bs, 1f, boss ? 0.40f : 0.16f, 0.10f, 0.95f * fade);  // red echo
            if (blipAge[i] < 0.30f) {                               // fresh paint: ping pulse + hot core
                float pk = 1f - blipAge[i] / 0.30f;
                drawCircle(bx, by, bs + (1f - pk) * 13f * us, 1f, 0.30f, 0.20f, pk * 0.30f);
                drawCircle(bx, by, bs * 0.65f, 1f, 0.92f, 0.80f, pk * 0.9f);
            }
        }
        drawCircle(rcx, rcy, 4.2f * us, 0.80f, 1f, 0.72f, 0.97f);                   // player
    }

    /** Small floating health bars over wounded enemies (and always over bosses). */
    private void drawEnemyHealthBars() {
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (!enAlive[i] || enMaxHP[i] <= 0f) continue;
            float frac = enHP[i] / enMaxHP[i];
            if (frac >= 0.999f && enBoss[i] == 0) continue;
            float topY = terrainH(enX[i], enZ[i])
                    + (enType[i] == 1 && enBoss[i] == 0 ? 0.90f : 1.95f) * enScale[i];   // crawlers hug the ground
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

        if (storyTimer > 0f) {                                  // chapter briefing card, fading out
            float sa = Math.min(1f, storyTimer / 1.1f) * Math.min(1f, (7f - storyTimer) * 2.5f);
            float pw = Math.min(760f * us, width * 0.86f), ph2 = 168f * us, py2 = height * 0.30f;
            drawRoundRect(width * 0.5f, py2, pw, ph2, 18f * us, 0.030f, 0.038f, 0.026f, 0.82f * sa);
            drawRoundRect(width * 0.5f, py2 - ph2 * 0.5f + 3f * us, pw - 36f * us, 3f * us, 1.5f * us, AC_BLOOD[0], AC_BLOOD[1], AC_BLOOD[2], 0.85f * sa);
            drawTextCentered(LV_TAG[levelId], width * 0.5f, py2 - ph2 * 0.5f + 30f * us, 19f, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], sa);
            for (int li = 0; li < LV_BRIEF[levelId].length; li++)
                drawTextCentered(LV_BRIEF[levelId][li], width * 0.5f, py2 - ph2 * 0.5f + (64f + li * 32f) * us, 14.5f,
                        AC_BONE[0], AC_BONE[1], AC_BONE[2], 0.92f * sa);
        }

        GLES20.glUseProgram(progVig);
        quad.position(0);
        GLES20.glEnableVertexAttribArray(aPVig);
        GLES20.glVertexAttribPointer(aPVig, 2, GLES20.GL_FLOAT, false, 8, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);

        if (weatherLabelT > 0f) {                          // brief name announced when the weather changes
            float a = Math.max(0f, Math.min(1f, Math.min(weatherLabelT, 4.5f - weatherLabelT) * 2f));
            int wc = WX_ORDER[wxIdx];
            float lr = wc == WX_SUN ? 1.0f : wc == WX_RAIN ? 0.62f : wc == WX_SNOW ? 0.95f : wc == WX_DUSK ? 1.0f : 0.82f;
            float lg = wc == WX_SUN ? 0.85f : wc == WX_RAIN ? 0.76f : wc == WX_SNOW ? 0.97f : wc == WX_DUSK ? 0.62f : 0.85f;
            float lb = wc == WX_SUN ? 0.45f : wc == WX_RAIN ? 0.98f : wc == WX_SNOW ? 1.0f : wc == WX_DUSK ? 0.34f : 0.88f;
            drawTextCentered(WX_NAME[wc], width * 0.5f, height * 0.155f, 30f, lr, lg, lb, a * 0.92f);
        }

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
            drawCircle(mcx, mcy, Hud.MOVE_RADIUS + 2.5f * us, 0.9f, 0.92f, 0.8f, 0.20f);     // ring
            drawCircle(mcx, mcy, Hud.MOVE_RADIUS, 0.09f, 0.10f, 0.08f, 0.34f);              // dark well
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
            drawPad(jpx, jpy, Hud.JUMP_RADIUS, 0.34f, 0.42f, 0.20f, ja);
            drawRectPx(jpx, jpy - 11f * us, 11f * us, 6f * us, 1f, 1f, 1f, 0.9f);
            drawRectPx(jpx, jpy - 2f * us, 21f * us, 6f * us, 1f, 1f, 1f, 0.9f);
            drawRectPx(jpx, jpy + 7f * us, 31f * us, 6f * us, 1f, 1f, 1f, 0.9f);

            // menu button (top-right) — one tap returns to the hub (aborts the run, earnings do not count)
            float mnx = Hud.menuCx(width), mny = Hud.menuCy(height);
            drawPad(mnx, mny, Hud.MENU_RADIUS, 0.24f, 0.24f, 0.20f, 0.5f);
            drawTextCentered("MENU", mnx, mny, 18f, AC_BONE[0], AC_BONE[1], AC_BONE[2], 0.95f);

            // interact prompt — appears in range of a door OR a window; tap it to open/close the nearest one
            if (nearDoor >= 0 || nearWindow >= 0) {
                float ix = Hud.interactCx(width), iy = Hud.interactCy(height);
                boolean win = nearWinCloser;
                boolean open = win ? windowOpen[nearWindow] > 0.5f : doorOpen[nearDoor] > 0.5f;
                drawGlow(ix, iy, Hud.INTERACT_RADIUS * 2f, Hud.INTERACT_RADIUS * 2f, Hud.INTERACT_RADIUS,
                        open ? 0.9f : 0.3f, open ? 0.55f : 0.85f, open ? 0.3f : 0.45f, 0.8f);
                drawPad(ix, iy, Hud.INTERACT_RADIUS, open ? 0.85f : 0.28f, open ? 0.5f : 0.82f, open ? 0.28f : 0.45f, 0.55f);
                if (win) {                                                                       // window icon: framed glass + muntin cross
                    drawRoundRect(ix, iy, 46f * us, 46f * us, 5f * us, 0.93f, 0.93f, 0.80f, 0.96f);
                    drawRoundRect(ix, iy, 36f * us, 36f * us, 3f * us, 0.55f, 0.68f, 0.80f, 0.95f);
                    drawRectPx(ix, iy, 36f * us, 4f * us, 0.93f, 0.93f, 0.80f, 0.96f);
                    drawRectPx(ix, iy, 4f * us, 36f * us, 0.93f, 0.93f, 0.80f, 0.96f);
                } else {
                    drawRoundRect(ix, iy, 40f * us, 60f * us, 6f * us, 0.93f, 0.93f, 0.80f, 0.96f);   // door frame
                    drawRoundRect(ix, iy, 28f * us, 48f * us, 4f * us, 0.46f, 0.30f, 0.17f, 0.97f);   // door leaf
                    drawCircle(ix + 8f * us, iy, 4f * us, 0.96f, 0.9f, 0.45f, 1f);                    // knob
                }
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

            drawTextCenteredShadow("" + score, width * 0.5f, 32f * us, 34f, AC_BONE[0], AC_BONE[1], AC_BONE[2], 0.97f);

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
            drawRoundRect(bx0 + bw * 0.5f, by, bw, bh, brad, 0.13f, 0.09f, 0.08f, 0.78f);                     // dried track
            if (hp > 0.001f) {
                // blood fill: always red, draining darker as it empties
                drawRoundRect(bx0 + bw * hp * 0.5f, by, bw * hp, bh, brad, 0.45f + 0.40f * hp, 0.07f + 0.08f * hp, 0.06f, 0.95f);
                drawRoundRect(bx0 + bw * hp * 0.5f, by - bh * 0.22f, bw * hp - 6f * us, bh * 0.34f, brad * 0.6f,
                              0.95f, 0.30f + 0.25f * hp, 0.25f, 0.30f);                                       // wet sheen
                // blood runners dripping off the bar (one live at the fill edge, one dried mid-bar)
                float dpx = bx0 + bw * hp - 6f * us;
                float dl = (7f + 5f * pulse01(0.8f, 1.7f)) * us;
                drawRoundRect(dpx, by + bh * 0.5f + dl * 0.5f, 4.5f * us, dl, 2.2f * us, 0.55f, 0.07f, 0.06f, 0.85f);
                drawCircle(dpx, by + bh * 0.5f + dl + 1.5f * us, 2.8f * us, 0.62f, 0.09f, 0.07f, 0.9f);
                if (hp > 0.35f)
                    drawRoundRect(bx0 + bw * hp * 0.45f, by + bh * 0.5f + 5f * us, 4f * us, 10f * us, 2f * us, 0.45f, 0.06f, 0.05f, 0.6f);
            }

            // cash + level + xp progress (top-left, under the health bar)
            drawTextShadow("$" + cash, 40f, 106f * us, 26f, AC_GOLD[0], AC_GOLD[1], AC_GOLD[2], 1f);
            drawTextShadow("LV " + playerLevel, 40f, 150f * us, 20f, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 1f);
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
                drawRoundRect(width * 0.5f, cy2, cw, ch, ch * 0.5f, 0.055f, 0.045f, 0.038f, 0.78f);
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
            drawRoundRect(width * 0.5f, 134f * us, wpw, 38f * us, 19f * us, 0.055f, 0.045f, 0.038f, 0.72f);
            drawTextCentered(waveStr, width * 0.5f, 134f * us, 20f, AC_BONE[0], AC_BONE[1], AC_BONE[2], 0.95f);
            // wave progress bar (enemies cleared) + remaining count
            float cleared = waveSize - waveRemaining; if (cleared < 0f) cleared = 0f;
            float wf = waveSize > 0 ? cleared / (float) waveSize : 0f;
            float pbw = Math.max(wpw, 190f * us), pby = 162f * us;
            drawRoundRect(width * 0.5f, pby, pbw, 7f * us, 3.5f * us, 0.11f, 0.11f, 0.09f, 0.75f);
            if (wf > 0.001f) drawRoundRect(width * 0.5f - pbw * 0.5f + pbw * wf * 0.5f, pby, pbw * wf, 7f * us, 3.5f * us, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 0.93f);
            drawTextCentered(Math.max(0, waveRemaining) + " LEFT", width * 0.5f, pby + 18f * us, 13f, 0.80f, 0.77f, 0.66f, 0.82f);
            if (waveBanner > 0f) {
                float ba = Math.min(1f, waveBanner);                       // fade out at the end
                float elapsed = waveBannerMax - waveBanner;
                float pin = clamp(elapsed / 0.30f, 0f, 1f);                // 0→1 entrance
                float ease = 1f - (1f - pin) * (1f - pin);
                float scale = 0.72f + 0.28f * ease;
                boolean boss = bossPending > 0 || waveBannerText.indexOf("BOSS") >= 0;
                float gr = boss ? 1f : AC_TOXIC[0], gg = boss ? 0.45f : AC_TOXIC[1], gb = boss ? 0.32f : AC_TOXIC[2];
                float bnY = height * 0.27f + (1f - ease) * -26f * us;      // slides down into place
                float sz = 40f * scale, tw = measureText(waveBannerText, sz) + 80f * us, bh2 = 64f * us * scale;
                drawGlow(width * 0.5f, bnY, tw, bh2, bh2 * 0.5f, gr, gg, gb, ba * 0.9f);
                drawRoundRect(width * 0.5f, bnY, tw + 5f * us, bh2 + 5f * us, bh2 * 0.5f + 2.5f * us, gr, gg, gb, ba * 0.5f); // rim
                drawRoundRect(width * 0.5f, bnY, tw, bh2, bh2 * 0.5f, 0.055f, 0.045f, 0.038f, ba * 0.9f);
                drawRoundRect(width * 0.5f, bnY - bh2 * 0.5f + 16f * us * scale, tw * 0.96f, 3f * us, 1.5f * us, gr, gg, gb, ba * 0.7f);
                drawTextCenteredShadow(waveBannerText, width * 0.5f, bnY, sz, 1f, boss ? 0.45f : 0.95f, boss ? 0.32f : 0.6f, ba);
            }
            // between-wave countdown to the next wave
            if (waveBreak > 0f) {
                int cd = Math.max(1, (int) Math.ceil(waveBreak));
                drawTextCenteredShadow("NEXT WAVE IN " + cd, width * 0.5f, height * 0.40f, 22f, AC_BONE[0], AC_BONE[1], AC_BONE[2], 0.92f);
            }

            drawPopups();   // floating "+$" / HEADSHOT / combo reward texts
        }

        // build number: a small dim chip in the top-right corner (was a big gold readout)
        String bstr = "B" + buildNumber;
        float bcw = measureText(bstr, 15f) + 26f * us, bchx = width - 18f * us - bcw * 0.5f;
        drawRoundRect(bchx, 32f * us, bcw, 32f * us, 16f * us, 0.05f, 0.055f, 0.04f, 0.45f);
        drawTextCentered(bstr, bchx, 32f * us, 15f, 0.70f, 0.72f, 0.60f, 0.8f);

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
        drawTextCentered("RUN OVER", cx + 3f * us, height * 0.14f + 3f * us, 56f, 0.35f, 0.04f, 0.03f, 0.9f);
        drawTextCenteredShadow("RUN OVER", cx, height * 0.14f, 56f, 0.95f, 0.22f, 0.16f, 1f);
        drawTextCenteredShadow("SCORE " + score, cx, height * 0.14f + 86f * us, 30f, AC_BONE[0], AC_BONE[1], AC_BONE[2], 0.95f);
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
        drawCard(cx, y + 58f * us, Math.min(640f * us, width * 0.9f), 210f * us, 24f * us, 0.078f, 0.088f, 0.060f, 0.95f);
        drawTextCenteredShadow("EARNED $" + runCash, cx, y, 34f, AC_GOLD[0], AC_GOLD[1], AC_GOLD[2], 1f);
        drawTextCenteredShadow("+" + runXp + " XP", cx, y + 52f * us, 24f, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 0.95f);
        drawTextCenteredShadow(runKills + " KILLS   WAVE " + wave, cx, y + 96f * us, 24f, 0.85f, 0.85f, 0.92f, 0.95f);
        if (levelsGainedThisRun > 0)
            drawTextCenteredShadow("LEVEL UP - NOW LV " + playerLevel, cx, y + 144f * us, 26f, AC_GREEN[0], AC_GREEN[1], AC_GREEN[2], 1f);

        float cpb = pulse01(2.8f, 0f);
        drawRoundRect(cx, btnY, btnW + (18f + 14f * cpb) * us, btnH + (18f + 14f * cpb) * us, btnH * 0.5f + (9f + 7f * cpb) * us, 0.90f, 0.16f, 0.10f, 0.07f + 0.08f * cpb);
        drawButton(cx, btnY, btnW, btnH, btnH * 0.5f, 0.70f, 0.13f, 0.09f, 1f, true);
        drawSheen(cx, btnY, btnW, btnH, btnH * 0.5f, 3.0f, 0f, 0.20f);
        drawTextCenteredShadow("CONTINUE", cx, btnY, 34f, AC_BONE[0], AC_BONE[1], AC_BONE[2], 1f);
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
        drawQuadNDC(0f, 0f, 1f, 1f, 0.02f, 0.03f, 0.02f, 0.72f);
        float cx = width * 0.5f, cw = Math.min(580f * us, width * 0.72f), ch = 460f * us, cy = height * 0.46f;
        drawCard(cx, cy, cw, ch, 26f * us, 0.078f, 0.088f, 0.060f, 0.99f);
        drawTextCenteredShadow("SETTINGS", cx, cy - ch * 0.5f + 46f * us, 30f, AC_BONE[0], AC_BONE[1], AC_BONE[2], 1f);
        drawRoundRect(cx, cy - ch * 0.5f + 74f * us, 120f * us, 4f * us, 2f * us, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 0.85f);
        String[] labels = {"SCREEN SHAKE", "DAMAGE NUMBERS", "RADAR", "KILLFEED"};
        boolean[] vals = {optShake, optDmgNum, optRadar, optKillfeed};
        float rowY0 = cy - ch * 0.5f + 132f * us, rh = 66f * us;
        for (int i = 0; i < 4; i++) {
            float ry = rowY0 + i * rh;
            drawTextShadow(labels[i], cx - cw * 0.5f + 42f * us, ry, 20f, 0.86f, 0.9f, 0.96f, 1f);
            float tw = 104f * us, th = 44f * us, tx = cx + cw * 0.5f - 42f * us - tw * 0.5f;
            boolean on = vals[i];
            if (tapped && hitRect(tx, ry, tw + 24f * us, th + 16f * us, tap[0], tap[1])) { toggleOpt(i); on = !on; }
            drawRoundRect(tx, ry, tw, th, th * 0.5f, on ? 0.20f : 0.14f, on ? 0.48f : 0.15f, on ? 0.10f : 0.12f, 0.96f);
            drawCircle(on ? tx + tw * 0.5f - th * 0.5f : tx - tw * 0.5f + th * 0.5f, ry, th * 0.4f, 1f, 1f, 1f, 0.96f);
            drawTextCentered(on ? "ON" : "OFF", on ? tx - tw * 0.22f : tx + tw * 0.2f, ry, 13f, 1f, 1f, 1f, 0.85f);
        }
        float by = cy + ch * 0.5f - 50f * us, bw = 220f * us, bh = 58f * us;
        if (tapped && hitRect(cx, by, bw, bh, tap[0], tap[1])) { showSettings = false; saveMeta(); sfx.swap(); }
        drawButton(cx, by, bw, bh, bh * 0.5f, 0.58f, 0.13f, 0.09f, 1f, false);
        drawTextCenteredShadow("CLOSE", cx, by, 22f, AC_BONE[0], AC_BONE[1], AC_BONE[2], 1f);
    }

    /** The hub: balance, three tabs, the PLAY button, and the active tab's content. */
    private void drawHub() {
        menuPreamble();
        fillVGradient(0.050f, 0.066f, 0.042f, 0.010f, 0.014f, 0.009f);        // sickly green-black
        drawMenuMotes();                                                       // drifting spores + embers
        drawVignette();                                                        // edge depth (under the UI)
        if (!hubWasShown) { hubWasShown = true; hubEnterAnim = 0f; }           // entrance fade on (re)enter
        hubEnterAnim = Math.min(1f, hubEnterAnim + 0.05f);
        float cx = width * 0.5f;
        float hbH = 120f * us;                                                // header band height
        // charred header band with a faint top sheen + the blood accent edge dripping into the page
        drawRectPx(cx, hbH * 0.5f, width, hbH, 0.055f, 0.058f, 0.042f, 0.98f);
        drawRectPx(cx, 2f * us, width, 4f * us, 0.17f, 0.18f, 0.12f, 0.5f);              // top sheen
        drawBloodEdge(hbH, width);
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

        // title (centred): a blood-smeared under-print gives the lettering a butcher-stencil feel
        drawTextCentered("AIGAMES FPS", cx + 2.5f * us, 52.5f * us, 27f, 0.38f, 0.05f, 0.04f, 0.85f);
        drawTextCenteredShadow("AIGAMES FPS", cx, 50f * us, 27f, AC_BONE[0], AC_BONE[1], AC_BONE[2], 0.97f);
        drawSheen(cx, 50f * us, measureText("AIGAMES FPS", 27f) + 24f * us, 40f * us, 14f * us, 5.0f, 0f, 0.16f);
        drawRoundRect(cx, 84f * us, 120f * us, 4f * us, 2f * us, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 0.85f);

        // level chip (infection green) + xp bar right
        String lvStr = "LV " + playerLevel;
        float lvTxtW = measureText(lvStr, 26f), lvChipW = lvTxtW + 40f * us;
        float lvChipX = width - 24f * us - lvChipW * 0.5f;
        drawChip(lvChipX, 50f * us, lvChipW, 50f * us, 0.055f, 0.095f, 0.045f, 0.92f);
        drawTextCentered(lvStr, lvChipX, 50f * us, 26f, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 1f);
        float lp = levelProgress(), xbw = Math.min(300f * us, width * 0.36f), xbx = width - 24f * us - xbw;
        if (lpShown < 0f) lpShown = lp;
        lpShown += (lp - lpShown) * 0.1f;                                  // smooth fill
        if (Math.abs(lp - lpShown) < 0.002f) lpShown = lp;
        drawRoundRect(xbx + xbw * 0.5f, 92f * us, xbw, 12f * us, 6f * us, 0.11f, 0.12f, 0.09f, 0.9f);
        if (lpShown > 0.001f) {
            drawRoundRect(xbx + xbw * lpShown * 0.5f, 92f * us, xbw * lpShown, 12f * us, 6f * us, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 0.97f);
            drawRoundRect(xbx + xbw * lpShown * 0.5f, 89f * us, xbw * lpShown - 4f * us, 4f * us, 2f * us, 0.82f, 1f, 0.45f, 0.6f);
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
            drawRoundRect(txp, tabY, tabW, tabH, trad, 0.080f, 0.090f, 0.062f, 0.92f);
            drawRoundRect(txp, tabY - tabH * 0.5f + 2.4f * us, tabW - 2f * trad, 2.4f * us, 1.2f * us, 0.19f, 0.21f, 0.13f, 0.5f);
        }
        // sliding selection highlight: lerp toward the active tab's centre
        float tabTargetX = x0 + hubTab * (tabW + gap);
        if (tabIndX < 0f) tabIndX = tabTargetX;
        tabIndX += (tabTargetX - tabIndX) * 0.28f;
        float tb = pulse01(2.4f, 0f), tix = tabIndX;
        drawRoundRect(tix, tabY, tabW + (16f + 8f * tb) * us, tabH + (16f + 8f * tb) * us, trad + (8f + 4f * tb) * us, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 0.07f + 0.06f * tb);
        drawRoundRect(tix, tabY, tabW + 8f * us, tabH + 8f * us, trad + 4f * us, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 0.18f + 0.10f * tb);
        drawRoundRect(tix, tabY, tabW, tabH, trad, 0.145f, 0.30f, 0.085f, 0.99f);
        drawRectPx(tix, tabY - tabH * 0.24f, tabW - 2f * trad, tabH * 0.40f, 0.185f, 0.38f, 0.11f, 0.75f);
        drawRoundRect(tix, tabY - tabH * 0.5f + 3f * us, tabW - 2f * trad, 3f * us, 1.5f * us, 0.78f, 1f, 0.42f, 0.55f);
        drawRoundRect(tix, tabY + tabH * 0.5f - 8f * us, tabW * 0.40f, 5f * us, 2.5f * us, 0.80f, 1f, 0.45f, 0.97f);
        // labels on top, brightening as the highlight passes under them
        for (int i = 0; i < 3; i++) {
            float txp = x0 + i * (tabW + gap);
            float prox = clamp(1f - Math.abs(txp - tabIndX) / (tabW + gap), 0f, 1f);
            drawTextCenteredShadow(tabs[i], txp, tabY, 21f, 1f, 1f, 1f, 0.5f + 0.5f * prox);
        }

        // PLAY button: a big blood-red slab with a breathing glow — the "go get bitten" button
        float playH = 122f * us, playW = Math.min(520f * us, width * 0.88f), playRad = playH * 0.5f;
        float playY = height - 26f * us - playH * 0.5f;
        if (tapped && hitRect(cx, playY, playW, playH, tap[0], tap[1])) { startRun(); return; }

        // --- story chapter selector: three chips riding on the PLAY slab, locked ones show their level gate ---
        float chH = 56f * us, chGap = 10f * us;
        float chW = (playW - 2f * chGap) / 3f;
        float chY = playY - playH * 0.5f - 10f * us - chH * 0.5f;
        for (int i = 0; i < 3; i++) {
            float chX = cx - playW * 0.5f + chW * 0.5f + i * (chW + chGap);
            boolean unlocked = playerLevel >= LV_UNLOCK[i];
            boolean sel = levelId == i;
            if (tapped && hitRect(chX, chY, chW, chH, tap[0], tap[1])) {
                if (unlocked && levelId != i) {
                    levelId = i; prefs.edit().putInt("levelSel", i).apply();
                    worldDirty = true; sfx.swap();
                }
                tapped = false;
            }
            if (sel) drawRoundRect(chX, chY, chW + 8f * us, chH + 8f * us, chH * 0.5f + 4f * us, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 0.22f);
            drawRoundRect(chX, chY, chW, chH, chH * 0.5f, sel ? 0.145f : 0.078f, sel ? 0.285f : 0.088f, sel ? 0.085f : 0.062f, 0.95f);
            if (unlocked) {
                drawTextCentered(LV_NAME[i], chX, chY, 15.5f, sel ? 0.92f : 0.64f, sel ? 1f : 0.68f, sel ? 0.62f : 0.52f, 1f);
            } else {
                drawTextCentered(LV_NAME[i], chX, chY - 10f * us, 13f, 0.40f, 0.41f, 0.36f, 0.9f);
                drawTextCentered("AB LV " + LV_UNLOCK[i], chX, chY + 13f * us, 12f, 0.72f, 0.30f, 0.16f, 0.95f);
            }
        }
        drawTextCentered(LV_TAG[levelId], cx, chY - chH * 0.5f - 16f * us, 13f, 0.66f, 0.60f, 0.44f, 0.9f);
        float pb = pulse01(2.8f, 0f);
        drawRoundRect(cx, playY, playW + (20f + 16f * pb) * us, playH + (20f + 16f * pb) * us, playRad + (10f + 8f * pb) * us, 0.90f, 0.16f, 0.10f, 0.07f + 0.09f * pb);
        drawButton(cx, playY, playW, playH, playRad, 0.70f, 0.13f, 0.09f, 1f, true);
        drawSheen(cx, playY, playW, playH, playRad, 3.0f, 0f, 0.22f);
        drawTextCenteredShadow("PLAY", cx, playY, 48f, AC_BONE[0], AC_BONE[1], AC_BONE[2], 1f);
        // BAUEN / EDITOR entry (bottom-left, aligned with PLAY) — military olive drab
        float ebW = 236f * us, ebH = 76f * us, ebX = 22f * us + ebW * 0.5f, ebY = playY;
        if (tapped && hitRect(ebX, ebY, ebW, ebH, tap[0], tap[1])) {
            if (levelId != 0) { levelId = 0; prefs.edit().putInt("levelSel", 0).apply(); rebuildWorldForLevel(); }
            edEnter(); return;   // the editor always edits chapter 1's village
        }
        drawRoundRect(ebX, ebY, ebW, ebH, ebH * 0.5f, 0.24f, 0.29f, 0.13f, 0.96f);
        drawRoundRect(ebX, ebY - ebH * 0.5f + 2.6f * us, ebW - 2f * ebH * 0.5f, 2.6f * us, 1.3f * us, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 0.55f);
        drawTextCentered("BAUEN", ebX, ebY, 30f, 0.93f, 0.95f, 0.85f, 1f);
        // SANDBOX entry (bottom-right, mirrors BAUEN): plague violet, peaceful build + roam.
        float sbW = 236f * us, sbH = 76f * us, sbX = width - 22f * us - sbW * 0.5f, sbY = playY;
        if (tapped && hitRect(sbX, sbY, sbW, sbH, tap[0], tap[1])) {
            if (levelId != 0) { levelId = 0; prefs.edit().putInt("levelSel", 0).apply(); rebuildWorldForLevel(); }
            sandboxEnter(); return;   // sandbox builds on chapter 1's village too
        }
        drawRoundRect(sbX, sbY, sbW, sbH, sbH * 0.5f, 0.28f, 0.15f, 0.32f, 0.96f);
        drawRoundRect(sbX, sbY - sbH * 0.5f + 2.6f * us, sbW - sbH, 2.6f * us, 1.3f * us, AC_TOXIC[0], AC_TOXIC[1], AC_TOXIC[2], 0.55f);
        drawTextCentered("SANDBOX", sbX, sbY, 30f, 0.94f, 0.90f, 0.96f, 1f);
        drawText("BUILD " + buildNumber, 18f * us, height - 16f * us, 13f, 0.52f, 0.54f, 0.44f, 0.65f);
        if (highScore > 0) drawTextRight("BEST SCORE  " + highScore, width - 18f * us, height - 16f * us, 14f, AC_GOLD[0], AC_GOLD[1], AC_GOLD[2], 0.7f);

        // tab-switch reveal: reset on change, then ease 0→1 — rows stagger in (see rowReveal())
        if (hubTab != hubTabShown) { hubTabShown = hubTab; hubTabAnim = 0f; }
        hubTabAnim = Math.min(1f, hubTabAnim + 0.06f);

        float top = tabY + tabH * 0.5f + 18f * us, bottom = chY - chH * 0.5f - 34f * us;   // stop above the chapter chips
        if (hubTab == 0) hubWeapons(top, bottom, tapped);
        else if (hubTab == 1) hubUpgrades(top, bottom, tapped);
        else hubAbilities(top, bottom, tapped);
        drawPopups();   // purchase "-$" feedback
        if (hubEnterAnim < 1f) {                                              // whole-hub entrance fade-in
            float e = 1f - (1f - hubEnterAnim) * (1f - hubEnterAnim);
            drawRectPx(cx, height * 0.5f, width, height, 0.010f, 0.014f, 0.009f, (1f - e) * 0.97f);
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
        drawCard(cx, y, rowW, rowH, 18f * us, 0.082f, 0.092f, 0.064f, 0.96f);
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
            drawRoundRect(bcx, y, btnW, btnH, brad, 0.11f, 0.18f, 0.08f, 0.92f);
            drawTextCenteredShadow(doneText, bcx, y, 22f, AC_GREEN[0], AC_GREEN[1], AC_GREEN[2], 1f);
        } else if (locked) {
            drawRoundRect(bcx, y, btnW, btnH, brad, 0.24f, 0.09f, 0.08f, 0.92f);
            drawTextCenteredShadow(lockMsg, bcx, y, 17f, AC_RED[0], AC_RED[1], AC_RED[2], 1f);
        } else {
            if (afford) {
                drawButton(bcx, y, btnW, btnH, brad, 0.30f, 0.60f, 0.14f, 1f, false);
                drawSheen(bcx, y, btnW, btnH, brad, 3.6f, y * 0.012f, 0.16f);   // staggered shine
            } else {
                drawRoundRect(bcx, y, btnW, btnH, brad, 0.14f, 0.15f, 0.12f, 0.92f);
            }
            drawTextCenteredShadow("$" + cost, bcx, y, 23f, 1f, 1f, 1f, afford ? 1f : 0.45f);
            if (tapped && afford && reveal > 0.92f && hitRect(bcx, y, btnW, btnH, tap[0], tap[1])) buy = true;
        }
        // per-row fade-in from the dark backdrop (staggered by reveal)
        if (reveal < 1f)
            drawRoundRect(cx, y, rowW + 10f * us, rowH + 10f * us, 22f * us, 0.014f, 0.020f, 0.012f, (1f - reveal) * 0.96f);
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
            drawRoundRect(sx, syc, selW, selH, selH * 0.5f, sel ? 0.17f : 0.085f, sel ? 0.32f : 0.095f, sel ? 0.10f : 0.066f, owned ? 0.95f : 0.4f);
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
    // Zombie-apocalypse palette: infection green carries status/interactive accents, blood red is
    // reserved for danger + the signature header drips, gold stays legible for cash.
    private static final float[] AC_GOLD  = {0.95f, 0.76f, 0.28f};   // scavenged brass
    private static final float[] AC_TOXIC  = {0.62f, 0.94f, 0.26f};   // infection green (primary accent)
    private static final float[] AC_GREEN = {0.55f, 0.90f, 0.30f};   // toxic ok/success
    private static final float[] AC_RED   = {0.95f, 0.30f, 0.22f};   // fresh blood / locked
    private static final float[] AC_BLOOD = {0.66f, 0.09f, 0.06f};   // dried blood (drips, trims)
    private static final float[] AC_BONE  = {0.93f, 0.90f, 0.80f};   // bone/parchment text
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
            else drawRoundRect(px2, y, ps, ps, ps * 0.32f, 0.24f, 0.26f, 0.20f, 0.85f);
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

    /** The zombie-UI signature: a blood accent line with a soft glow above and drying runners
     *  dripping below it. Drip layout is deterministic (hash of the column index); one runner per
     *  header slowly stretches so the whole thing feels alive without being busy. */
    private void drawBloodEdge(float y, float w) {
        for (int i = 0; i < 3; i++)
            drawRectPx(w * 0.5f, y + (4f + i * 6f) * us, w, 6f * us, AC_BLOOD[0], AC_BLOOD[1], AC_BLOOD[2], 0.07f - i * 0.018f);
        drawRectPx(w * 0.5f, y, w, 3f * us, 0.74f, 0.10f, 0.07f, 0.92f);
        int n = Math.max(4, (int) (w / (170f * us)));
        for (int d = 0; d <= n; d++) {
            float fx = (d + 0.5f) / (n + 1f) * w + (((d * 37) % 23) - 11) * us;   // jittered spacing
            float len = (9f + ((d * 53) % 17) * 1.7f) * us;                        // 9..36 px runners
            if (d == 2) len *= 1f + 0.22f * pulse01(0.35f, 0f);                    // one slow live drip
            float ww = (4.2f + ((d * 29) % 3) * 1.3f) * us;
            drawRoundRect(fx, y + 1.5f * us + len * 0.5f, ww, len, ww * 0.5f, AC_BLOOD[0], AC_BLOOD[1], AC_BLOOD[2], 0.82f);
            drawCircle(fx, y + 2.5f * us + len, ww * 0.62f, 0.74f, 0.11f, 0.08f, 0.88f);
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

    /** Slow drifting spores (sickly green) with the odd ember for the menu backdrop — deterministic
     *  positions, looping upward like ash rising off a fire. */
    private void drawMenuMotes() {
        for (int i = 0; i < 30; i++) {
            float fx = ((i * 79 + 17) % 100) / 100f * width;
            float spd = 10f + (i % 6) * 5f;
            float fy = height - (((timeAcc * spd) + i * 151f) % (height + 100f));
            float sz = (1.4f + (i % 3) * 0.9f) * us;
            float aa = (0.04f + 0.05f * pulse01(1.3f, i * 0.7f)) * (0.5f + 0.5f * (i % 2));
            boolean ember = i % 7 == 0;
            drawCircle(fx, fy, sz, ember ? 1f : 0.55f, ember ? 0.55f : 0.85f, ember ? 0.25f : 0.35f, aa * (ember ? 1.5f : 1f));
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
        drawRectPx(cx, cy - h * 0.26f, w - 2f * rad, h * 0.46f, r + 0.032f, g + 0.032f, b + 0.022f, 0.6f * a);
        // hairline highlight tucked just inside the top edge
        drawRoundRect(cx, cy - h * 0.5f + 2.4f * us, w - 2f * rad - 4f * us, 2.6f * us, 1.2f * us,
                      Math.min(1f, r + 0.22f), Math.min(1f, g + 0.22f), Math.min(1f, b + 0.16f), 0.5f * a);
    }

    /** A translucent rounded chip used to seat HUD/header values (cash, level, …) over any background. */
    private void drawChip(float cx, float cy, float w, float h,
                          float r, float g, float b, float a) {
        drawRoundRect(cx, cy + 3f * us, w, h, h * 0.5f, 0f, 0f, 0f, 0.30f * a);             // soft shadow
        drawRoundRect(cx, cy, w, h, h * 0.5f, r, g, b, a);                                  // base
        drawRoundRect(cx, cy - h * 0.5f + 2.2f * us, w - h, 2.4f * us, 1.2f * us,
                      r + 0.12f, g + 0.12f, b + 0.08f, 0.45f * a);                          // top hairline
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
        // 7400/entry covers the worst case (flowering bush = 7200 floats); the guard below is the crash
        // stop the loop never had — a level whose FIRST plant rolled an oversized bush crashed on load.
        float[] d = new float[treeList.length * 7400 + 512];
        int o = 0;
        Random r = new Random(303);
        int[] flowerCells = {4, 5, 6, 7, 8, 9, 10};
        float uStem = cell(3), uCtr = cell(11);
        for (float[] tr : treeList) {
            if (o > d.length - 7300) break;
            int kind = tr.length > 3 ? (int) tr[3] : 0;        // 0 tree · 1 bush · 2 flower
            float by = terrainH(tr[0], tr[1]);
            if (kind == 1)      o = vBush(d, o, tr[0], by, tr[1], r.nextInt(6), r, flowerCells);
            else if (kind == 2) o = vFlower(d, o, tr[0], by, tr[1], 1 + r.nextInt(4), r, uStem, uCtr, flowerCells);
            else                o = vTree(d, o, tr[0], by, tr[1], Math.max(0.4f, tr[2]), true, r);
        }
        placedTreeVerts = o / 8;
        return java.util.Arrays.copyOf(d, o);
    }

    private float[] makeVegetation() {
        float[] d = new float[6000000];
        int o = 0;
        Random r = new Random(404);
        float ug0 = cell(0), ug1 = cell(1), ug2 = cell(2), uStem = cell(3), uCtr = cell(11), uBush = cell(12);
        int[] flowerCells = {4, 5, 6, 7, 8, 9, 10};

        for (int n = 0, tries = 0; n < 1200 && tries < 16000; tries++) {    // grass tufts (denser meadows)
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
        for (int n = 0, tries = 0; n < 560 && tries < 11000; tries++) {    // flowers (5 varied types)
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
            if (insideGardenXZ(x, z, 0.6f) || onSquareXZ(x, z, 0.2f) || pathDist(x, z) < 0.7f) continue;   // beds/square/paths stay clear
            o = vBush(d, o, x, terrainH(x, z), z, roundBush[r.nextInt(roundBush.length)], r, flowerCells);
            n++;
        }
        // detailed trees dotted around the meadow / arena edge — on grass only, never on paving or beds
        for (int n = 0, tries = 0; n < 55 && tries < 4000; tries++) {
            if (o > d.length - 9000) break;
            float a = r.nextFloat() * 6.2832f, rad = 20f + r.nextFloat() * 18f;
            float x = (float) Math.cos(a) * rad, z = (float) Math.sin(a) * rad;
            if (inBuildingXZ(x, z, 0.6f)) continue;
            if (roadSd(x, z) < 1.4f || onSquareXZ(x, z, 0.4f) || pathDist(x, z) < 1.0f || insideGardenXZ(x, z, 0.6f)) continue;
            o = vTree(d, o, x, terrainH(x, z), z, 1.0f + r.nextFloat() * 0.5f, true, r);
            n++;
        }
        // kitchen-garden crops: leafy sprout rows along each tilled plot's furrows (collision-free —
        // they live in the vegetation mesh, so the player wades through the beds like tall grass)
        if (gardenSoil != null) for (float[] gd2 : gardenSoil) {
            if (o > d.length - 40000) break;
            float hw = gd2[2] * 0.5f, hd = gd2[3] * 0.5f;
            double ga = Math.toRadians(gd2[4]);
            float ca = (float) Math.cos(ga), sa = (float) Math.sin(ga);
            for (float lx = -hw + 0.45f; lx < hw - 0.2f; lx += 0.55f) {
                for (float lz = -hd + 0.4f; lz < hd - 0.2f; lz += 0.42f + r.nextFloat() * 0.18f) {
                    float wx = gd2[0] + lx * ca + lz * sa, wz = gd2[1] - lx * sa + lz * ca;
                    float by = terrainH(wx, wz);
                    int kind = r.nextInt(5);
                    if (kind < 3) {                          // leafy sprout: a few short blades in a tuft
                        int blades = 3 + r.nextInt(3);
                        for (int b = 0; b < blades; b++)
                            o = vBlade(d, o, wx + (r.nextFloat() - 0.5f) * 0.10f, by, wz + (r.nextFloat() - 0.5f) * 0.10f,
                                    r.nextFloat() * 6.2832f, 0.12f + r.nextFloat() * 0.14f, 0.025f, 0.05f, r.nextBoolean() ? ug1 : ug2);
                    } else if (kind == 3) {                  // a ripening head (cabbage / pumpkin-ish dot)
                        o = vBox(d, o, wx, by + 0.07f, wz, 0.09f + r.nextFloat() * 0.05f, 0.12f, 0.09f + r.nextFloat() * 0.05f,
                                r.nextFloat() < 0.3f ? cell(8) : uBush);
                    } else {                                 // a flowering plant between the rows
                        o = vFlower(d, o, wx, by, wz, r.nextInt(5), r, uStem, uCtr, flowerCells);
                    }
                }
                if (o > d.length - 20000) break;
            }
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
            float yawB = b.length > 9 ? b[9] : 0f;
            if (yawB != 0f) {                             // rotated house/fence: test its real footprint
                if (insideYawXZ(x, z, b[0], b[2], b[3] * 0.5f + m, b[5] * 0.5f + m, yawB)) return true;
                continue;
            }
            if (x > b[0] - b[3] * 0.5f - m && x < b[0] + b[3] * 0.5f + m
             && z > b[2] - b[5] * 0.5f - m && z < b[2] + b[5] * 0.5f + m) return true;
        }
        return false;
    }

    // ---- organic village road network -----------------------------------------------------------
    // Hand-authored control points, Catmull-Rom sampled once into dense polylines. Everything keys off
    // these curves: the painted ground texture, all placement queries, house orientation along the
    // tangents, lamps/benches on the verges, and the front paths that march out to the nearest kerb.
    private static final float SQ_X = 0f, SQ_Z = 3f;   // village square centre (the spawn plaza sits on it)
    private static final float[][] ROAD_SPEC = {
        // {asphalt halfWidth,  x0,z0, x1,z1, ...}  — the main road skirts the plaza's SOUTH edge, so the
        // spawn corridor (0,9 facing the square) and its cover crates stay off the asphalt
        {2.2f, -36,-16, -26,-11, -15,-4, -4,-2, 7,-1, 17,-2, 27,-6, 36,-12},   // main road: two lanes, a lazy S west-east
        {1.8f, 9,-36, 7,-26, 4,-16, 1,-8, 0,-2},                               // north approach to the junction
        {1.6f, 0,-2, -5,3, -7,10, -4,18, 3,24, 11,28},                         // south lane, swings west of the plaza
        {1.25f, -15,-4, -19,2, -25,7, -30,10},                                 // west dead-end lane (single track)
        {1.25f, 17,-2, 20,6, 21,13, 20,19},                                    // east dead-end lane (single track)
    };
    private static float[][][] ROAD_PTS;             // sampled centrelines [road][pt][x,z] (per LEVEL, see buildRoads)
    private static float[] ROAD_HALFW;               // per-road asphalt half width
    private static float[][] ROAD_BB;                // per-road AABB {minX,minZ,maxX,maxZ} incl. width
    private static final float VERGE = 1.0f;         // gravel shoulder outside the asphalt

    // ---- story campaign: three chapters, each its own map ------------------------------------------
    // Kap. 1 spielt im vertrauten Dorf, Kap. 2 draussen auf den Hoefen, Kap. 3 in der alten Stadt.
    // The farm keeps only the two main lanes (a sparse crossroads hamlet); the old town adds a sixth
    // lane so the core packs tight. All specs keep the spawn plaza at (0,3)..(0,9) road-free.
    private static final float[][][] LV_ROADS = {
        ROAD_SPEC,
        {   // ASCHENHOF: one country road + the north track — wide fields between the farms
            {2.2f, -36,-16, -26,-11, -15,-4, -4,-2, 7,-1, 17,-2, 27,-6, 36,-12},
            {1.6f, 9,-36, 7,-26, 4,-16, 1,-8, 0,-2},
            {1.25f, 0,-2, -5,3, -7,10, -4,18, 3,24},                     // short farm track past the yard
        },
        {   // STEINMARKT: the full five-lane net plus a sixth alley — a dense old trading town
            {2.2f, -36,-16, -26,-11, -15,-4, -4,-2, 7,-1, 17,-2, 27,-6, 36,-12},
            {1.8f, 9,-36, 7,-26, 4,-16, 1,-8, 0,-2},
            {1.6f, 0,-2, -5,3, -7,10, -4,18, 3,24, 11,28},
            {1.25f, -15,-4, -19,2, -25,7, -30,10},
            {1.25f, 17,-2, 20,6, 21,13, 20,19},
            {1.25f, -30,10, -24,15, -16,18, -8,17},                      // back alley closing the west block
        },
    };
    private static final String[] LV_NAME = {"BRUNNENFELD", "ASCHENHOF", "STEINMARKT"};
    private static final int[] LV_UNLOCK = {1, 4, 8};                    // player level that opens the chapter
    private static final String[] LV_TAG = {
        "KAPITEL 1 - WO ALLES BEGANN",
        "KAPITEL 2 - DIE HOEFE IM UMLAND",
        "KAPITEL 3 - DAS NEST IN DER ALTSTADT",
    };
    private static final String[][] LV_BRIEF = {
        {"TAG 47 DER HERBSTSEUCHE. DEIN DORF, LEER.",
         "NUR DU, EINE ALTE PISTOLE - UND SIE.",
         "JEDER KILL ZAHLT. JEDER DOLLAR WIRD ZU STAHL."},
        {"DIE VORRAETE DER BAUERN RETTEN DEN WINTER.",
         "ABER ZWISCHEN DEN FELDERN WANDERN DIE HERDEN.",
         "OFFENES LAND. KEINE DECKUNG. LAUF ODER KAEMPF."},
        {"DIE ALTE HANDELSSTADT: HIER SITZT DAS NEST.",
         "ENGE GASSEN, HOHE HAEUSER, HORDEN OHNE ENDE.",
         "OHNE VOLLES ARSENAL KOMMST DU NICHT ZURUECK."},
    };

    /** Sample a road spec (Catmull-Rom control polylines) into the working ROAD_* arrays. */
    private static void buildRoads(float[][] spec) {
        ROAD_PTS = new float[spec.length][][];
        ROAD_HALFW = new float[spec.length];
        ROAD_BB = new float[spec.length][];
        for (int r = 0; r < spec.length; r++) {
            float[] s = spec[r];
            ROAD_HALFW[r] = s[0];
            int n = (s.length - 1) / 2;
            float[][] cp = new float[n][2];
            for (int i = 0; i < n; i++) { cp[i][0] = s[1 + i * 2]; cp[i][1] = s[2 + i * 2]; }
            java.util.ArrayList<float[]> out = new java.util.ArrayList<float[]>();
            for (int i = 0; i < n - 1; i++) {
                float[] p0 = cp[Math.max(0, i - 1)], p1 = cp[i], p2 = cp[i + 1], p3 = cp[Math.min(n - 1, i + 2)];
                float segLen = (float) Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
                int steps = Math.max(2, (int) (segLen / 1.1f));
                for (int k = 0; k < steps; k++) {
                    float t = k / (float) steps, t2 = t * t, t3 = t2 * t;
                    out.add(new float[]{
                        0.5f * (2f * p1[0] + (-p0[0] + p2[0]) * t + (2f * p0[0] - 5f * p1[0] + 4f * p2[0] - p3[0]) * t2 + (-p0[0] + 3f * p1[0] - 3f * p2[0] + p3[0]) * t3),
                        0.5f * (2f * p1[1] + (-p0[1] + p2[1]) * t + (2f * p0[1] - 5f * p1[1] + 4f * p2[1] - p3[1]) * t2 + (-p0[1] + 3f * p1[1] - 3f * p2[1] + p3[1]) * t3)});
                }
            }
            out.add(new float[]{cp[n - 1][0], cp[n - 1][1]});
            ROAD_PTS[r] = out.toArray(new float[0][]);
            float mnx = 1e9f, mnz = 1e9f, mxx = -1e9f, mxz = -1e9f;
            for (float[] p : ROAD_PTS[r]) { mnx = Math.min(mnx, p[0]); mnz = Math.min(mnz, p[1]); mxx = Math.max(mxx, p[0]); mxz = Math.max(mxz, p[1]); }
            float pad = ROAD_HALFW[r];
            ROAD_BB[r] = new float[]{mnx - pad, mnz - pad, mxx + pad, mxz + pad};
        }
    }
    static { buildRoads(ROAD_SPEC); }                 // default: chapter 1 (also what every harness builds)

    /** Signed distance from (x,z) to the nearest asphalt EDGE (<0 = on a carriageway). */
    private static float roadSd(float x, float z) {
        float best = 1e9f;
        for (int r = 0; r < ROAD_PTS.length; r++) {
            float[] bb = ROAD_BB[r];       // cheap reject: outside this road's box by more than `best`
            float ox = Math.max(bb[0] - x, x - bb[2]), oz = Math.max(bb[1] - z, z - bb[3]);
            if (Math.max(ox, oz) > best) continue;
            float[][] P = ROAD_PTS[r];
            float hw = ROAD_HALFW[r];
            for (int i = 0; i + 1 < P.length; i++) {
                float ax = P[i][0], az = P[i][1];
                float abx = P[i + 1][0] - ax, abz = P[i + 1][1] - az;
                float tt = ((x - ax) * abx + (z - az) * abz) / (abx * abx + abz * abz + 1e-6f);
                tt = tt < 0f ? 0f : (tt > 1f ? 1f : tt);
                float dx = x - (ax + abx * tt), dz = z - (az + abz * tt);
                float d = (float) Math.sqrt(dx * dx + dz * dz) - hw;
                if (d < best) best = d;
            }
        }
        return best;
    }
    /** True on the asphalt carriageway (NOT the verge) — keeps the traffic lane clear, allows kerbside furniture. */
    private static boolean onRoadXZ(float x, float z) { return roadSd(x, z) < 0f; }
    /** True anywhere on a street footprint incl. its gravel verges — keeps buildings off the streets. */
    private static boolean onStreetXZ(float x, float z) { return roadSd(x, z) < VERGE; }

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
        o = vBox(d, o, x, by + 0.07f, z, tw * 1.8f, 0.14f, tw * 1.8f, uTrunk);   // root collar grounds the trunk
        float cy = by + trunkH + 0.30f * scale, crad = (0.9f + r.nextFloat() * 0.5f) * scale;
        if (detail) {
            // two boughs reaching from the trunk crown into the canopy (visible from underneath)
            o = vBox(d, o, x + crad * 0.32f, by + trunkH * 0.94f, z, crad * 0.70f, tw * 0.55f, tw * 0.55f, uTrunk);
            o = vBox(d, o, x, by + trunkH * 0.80f, z - crad * 0.28f, tw * 0.55f, tw * 0.55f, crad * 0.60f, uTrunk);
            // dark under-canopy mass FIRST so the lighter core/lobes overdraw its top -- only the shaded
            // underside/skirt stays visible, which makes the blob foliage read as a volume
            o = vBox(d, o, x, cy - crad * 0.60f, z, crad * 1.15f, crad * 0.45f, crad * 1.15f, cell(0));
            o = vBox(d, o, x, cy, z, crad * 1.4f, crad * 1.3f, crad * 1.4f, gA);     // crown core
            int lobes = 10 + r.nextInt(5);
            for (int l = 0; l < lobes; l++) {
                float la = r.nextFloat() * 6.2832f, lr = crad * (0.4f + r.nextFloat() * 0.7f);
                float ly = cy + (r.nextFloat() - 0.4f) * crad * 0.9f, s = (0.40f + r.nextFloat() * 0.4f) * scale;
                o = vBox(d, o, x + (float) Math.cos(la) * lr, ly, z + (float) Math.sin(la) * lr, s, s, s, r.nextBoolean() ? gA : gB);
            }
        } else {                                                                     // distant: a few big lobes
            o = vBox(d, o, x, cy - crad * 0.55f, z, crad * 1.35f, crad * 0.5f, crad * 1.35f, cell(0));   // shaded underside
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
        java.util.List<float[]> grp = new java.util.ArrayList<float[]>();
        for (float[] s : roadSegs) {
            float x1 = s[0], z1 = s[1], x2 = s[2], z2 = s[3], width = Math.max(0.6f, s[4]);
            float dx = x2 - x1, dz = z2 - z1, len = (float) Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-3f) continue;
            float ux = dx / len, uz = dz / len, px = -uz, pz = ux, hw = width * 0.5f, y = 0.03f;
            float ax = x1 + px * hw, az = z1 + pz * hw, bx = x1 - px * hw, bz = z1 - pz * hw;
            float cx = x2 - px * hw, cz = z2 - pz * hw, ex = x2 + px * hw, ez = z2 + pz * hw;
            float v = len / width;                          // tile dashes along the length
            int start = o / 8;
            o = put(d, o, ax, y, az, 0, 1, 0, 0f, 0f);
            o = put(d, o, bx, y, bz, 0, 1, 0, 1f, 0f);
            o = put(d, o, cx, y, cz, 0, 1, 0, 1f, v);
            o = put(d, o, ax, y, az, 0, 1, 0, 0f, 0f);
            o = put(d, o, cx, y, cz, 0, 1, 0, 1f, v);
            o = put(d, o, ex, y, ez, 0, 1, 0, 0f, v);
            float cr = s.length > 7 ? s[5] : 1f, cg = s.length > 7 ? s[6] : 1f, cb = s.length > 7 ? s[7] : 1f;
            grp.add(new float[]{start, o / 8 - start, cr, cg, cb});   // tint this street's asphalt
        }
        roadGroups = grp.isEmpty() ? null : grp.toArray(new float[0][]);
        roadVerts = o / 8;
        return java.util.Arrays.copyOf(d, o);
    }

    private static Bitmap makeRoadBitmap() {
        int N = 512;                                        // was 128 across a full 3.4 m carriageway (smeared grey)
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF4A4D50);                            // asphalt
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(91);
        for (int k = 0; k < 11000; k++) {                   // fine grain
            int v = rnd.nextInt(26), s = 0x42 + v;
            p.setColor((0x44 << 24) | (s << 16) | (s << 8) | s);
            float x = rnd.nextInt(N), y = rnd.nextInt(N);
            c.drawRect(x, y, x + 2, y + 2, p);
        }
        for (int k = 0; k < 2600; k++) {                    // coarse aggregate: light stone flecks
            int s = 0x52 + rnd.nextInt(0x2E);
            p.setColor((0x30 << 24) | (s << 16) | (s << 8) | (s + 4));
            c.drawCircle(rnd.nextInt(N), rnd.nextInt(N), 0.8f + rnd.nextFloat() * 1.6f, p);
        }
        p.setColor(0x28000000);                             // dark pores between the aggregate
        for (int k = 0; k < 900; k++) c.drawCircle(rnd.nextInt(N), rnd.nextInt(N), 0.7f + rnd.nextFloat() * 1.4f, p);
        p.setColor(0xFFB9B2A6);                              // light kerb borders along both long edges (U)
        c.drawRect(0, 0, N * 0.09f, N, p);
        c.drawRect(N * 0.91f, 0, N, N, p);
        p.setColor(0x30000000);                              // kerb joints at exact N/8 pitch (wraps in V)
        for (float y = 0; y < N; y += N / 8f) { c.drawRect(0, y, N * 0.09f, y + 2f, p); c.drawRect(N * 0.91f, y, N, y + 2f, p); }
        p.setColor(0xFFE8C23A);                              // dashed yellow centre line (tiles along V)
        for (float y = 0; y < N; y += N / 4f) c.drawRect(N * 0.47f, y, N * 0.53f, y + N / 8f, p);
        // wheel-track wear: three nested translucent full-height bands per track = a soft profile that tiles
        // perfectly in V. Lane centres sit at u 0.30/0.70, tracks at 0.195/0.405/0.595/0.805. Drawn after the
        // paint so the dashes weather too.
        for (float t : new float[]{0.195f, 0.405f, 0.595f, 0.805f})
            for (int L = 0; L < 3; L++) {
                float w = N * (0.045f - 0.012f * L);
                p.setColor(0x12000000);
                c.drawRect(t * N - w, 0, t * N + w, N, p);
            }
        Paint tar = new Paint(Paint.ANTI_ALIAS_FLAG);        // meandering tar crack lines, wrap-drawn in V
        tar.setStyle(Paint.Style.STROKE); tar.setStrokeWidth(N / 200f); tar.setColor(0x9C232527);
        for (int k = 0; k < 7; k++) {
            float x = N * (0.12f + 0.76f * rnd.nextFloat()), y = rnd.nextInt(N);
            for (int s = 0; s < 4; s++) {
                float x2 = x + (rnd.nextFloat() - 0.5f) * N * 0.06f, y2 = y + N * 0.05f + rnd.nextFloat() * N * 0.06f;
                for (int off = -N; off <= N; off += N) c.drawLine(x, y + off, x2, y2 + off, tar);
                x = x2; y = y2;
            }
        }
        return b;
    }

    /** Top-down village ground: meadow, a cobbled square, curved lanes with gravel verges + centre
     *  dashes, worn dirt footpaths to every door, and tilled soil inside the kitchen gardens. */
    private Bitmap makeCityBitmap() {
        int N = 2048;                                      // authored natively at POT: crisp kerbs/dashes, no resample
        float half = 35f;
        float s = N / (2f * half);                         // world metres -> texels
        Bitmap bmp = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF3C6E24);                           // lawn / grass base
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(31);
        for (int k = 0; k < 750; k++) {                    // mottled green so the lawn isn't flat
            int g = rnd.nextInt(3);
            int col = g == 0 ? 0x2E6B1F : (g == 1 ? 0x4A8E2A : 0x356016);
            p.setColor(0x66000000 | col);
            c.drawCircle(rnd.nextInt(N), rnd.nextInt(N), 12 + rnd.nextInt(64), p);
        }
        // the village square: a cobbled apron centred on the market ring, under the road junction
        float sqx = (SQ_X + 0.8f + half) * s, sqz = (SQ_Z + 1.9f + half) * s, sqr = 9.8f * s;
        p.setColor(0xFF98928A);                            // cobble base
        c.drawCircle(sqx, sqz, sqr, p);
        p.setColor(0xFF8A8479);                            // outer ring wear
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.5f * s);
        c.drawCircle(sqx, sqz, sqr - 0.25f * s, p);
        p.setStyle(Paint.Style.FILL);
        for (int k = 0; k < 420; k++) {                    // cobble speckle
            float a = rnd.nextFloat() * 6.2832f, rr = (float) Math.sqrt(rnd.nextFloat()) * (sqr - 2f);
            p.setColor((rnd.nextBoolean() ? 0x2E000000 : 0x24FFFFFF));
            c.drawCircle(sqx + (float) Math.cos(a) * rr, sqz + (float) Math.sin(a) * rr, 1.5f + rnd.nextInt(3), p);
        }
        // curved lanes: gravel shoulder, kerb shade, asphalt — stroked along each sampled centreline
        Paint rp = new Paint(Paint.ANTI_ALIAS_FLAG);
        rp.setStyle(Paint.Style.STROKE);
        rp.setStrokeCap(Paint.Cap.ROUND);
        rp.setStrokeJoin(Paint.Join.ROUND);
        for (int pass = 0; pass < 3; pass++) {
            for (int r = 0; r < ROAD_PTS.length; r++) {
                android.graphics.Path path = new android.graphics.Path();
                float[][] P = ROAD_PTS[r];
                path.moveTo((P[0][0] + half) * s, (P[0][1] + half) * s);
                for (int i = 1; i < P.length; i++) path.lineTo((P[i][0] + half) * s, (P[i][1] + half) * s);
                float hw = ROAD_HALFW[r];
                if (pass == 0)      { rp.setColor(0xFFA79E8B); rp.setStrokeWidth((hw + 0.85f) * 2f * s); }  // gravel verge
                else if (pass == 1) { rp.setColor(0xFF7E8076); rp.setStrokeWidth((hw + 0.14f) * 2f * s); }  // kerb shade
                else                { rp.setColor(0xFF34373A); rp.setStrokeWidth(hw * 2f * s); }            // asphalt
                c.drawPath(path, rp);
            }
            if (pass == 0 && pathStrokes != null) {        // worn dirt footpaths, under the kerb/asphalt passes
                for (float[] st : pathStrokes) {
                    rp.setColor(0xFF95815C); rp.setStrokeWidth(st[4] * s);
                    c.drawLine((st[0] + half) * s, (st[1] + half) * s, (st[2] + half) * s, (st[3] + half) * s, rp);
                    rp.setColor(0x2E4A3A22); rp.setStrokeWidth(st[4] * 0.45f * s);
                    c.drawLine((st[0] + half) * s, (st[1] + half) * s, (st[2] + half) * s, (st[3] + half) * s, rp);
                }
            }
        }
        // White dashed centre line splitting each two-lane road into two carriageways. The dashes are a
        // DashPathEffect stroked ALONG the curve, so they bend with it perfectly. Junction zones (where
        // lanes meet) and the cobbled square get NO markings — dash lines never cross each other.
        java.util.List<float[]> junctions = new java.util.ArrayList<float[]>();
        for (int r = 0; r < ROAD_PTS.length; r++) {
            float[][] P = ROAD_PTS[r];
            for (int e = 0; e < 2; e++) {
                float ex = P[e == 0 ? 0 : P.length - 1][0], ez = P[e == 0 ? 0 : P.length - 1][1];
                for (int r2 = 0; r2 < ROAD_PTS.length; r2++) {
                    if (r2 == r) continue;
                    float[][] Q = ROAD_PTS[r2];
                    for (int i = 0; i + 1 < Q.length; i++) {
                        float ax = Q[i][0], az = Q[i][1], abx = Q[i + 1][0] - ax, abz = Q[i + 1][1] - az;
                        float tt = ((ex - ax) * abx + (ez - az) * abz) / (abx * abx + abz * abz + 1e-6f);
                        tt = tt < 0f ? 0f : (tt > 1f ? 1f : tt);
                        float dx = ex - (ax + abx * tt), dz = ez - (az + abz * tt);
                        if (dx * dx + dz * dz < (ROAD_HALFW[r2] + 0.6f) * (ROAD_HALFW[r2] + 0.6f)) {
                            junctions.add(new float[]{ex, ez, Math.max(ROAD_HALFW[r], ROAD_HALFW[r2])});
                            i = Q.length; // one hit is enough for this endpoint/road pair
                        }
                    }
                }
            }
        }
        Paint dashP = new Paint(Paint.ANTI_ALIAS_FLAG);
        dashP.setStyle(Paint.Style.STROKE);
        dashP.setStrokeCap(Paint.Cap.BUTT);
        dashP.setColor(0xFFEFF1EC);                        // white lane divider
        dashP.setStrokeWidth(0.14f * s);
        dashP.setPathEffect(new android.graphics.DashPathEffect(new float[]{1.7f * s, 1.9f * s}, 0.6f * s));
        for (int r = 0; r < ROAD_PTS.length; r++) {
            if (ROAD_HALFW[r] < 1.4f) continue;            // single-track lanes carry no centre line
            float[][] P = ROAD_PTS[r];
            android.graphics.Path dpth = new android.graphics.Path();
            boolean pen = false;
            for (float[] pt : P) {
                boolean excl = Math.hypot(pt[0] - SQ_X, pt[1] - SQ_Z) < 10.5f;
                for (int j = 0; !excl && j < junctions.size(); j++) {
                    float[] jn = junctions.get(j);
                    if (Math.hypot(pt[0] - jn[0], pt[1] - jn[1]) < jn[2] + 2.6f) excl = true;
                }
                if (excl) { pen = false; continue; }
                float xx = (pt[0] + half) * s, zz = (pt[1] + half) * s;
                if (!pen) { dpth.moveTo(xx, zz); pen = true; } else dpth.lineTo(xx, zz);
            }
            c.drawPath(dpth, dashP);
        }
        // tilled garden soil: dark plot + row furrows, corner-transformed with each garden's yaw
        if (gardenSoil != null) for (float[] gd : gardenSoil) {
            float gx = gd[0], gz = gd[1], hw = gd[2] * 0.5f, hd = gd[3] * 0.5f;
            double a = Math.toRadians(gd[4]);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            android.graphics.Path soil = new android.graphics.Path();
            for (int i = 0; i < 4; i++) {
                float lx = (i == 0 || i == 3) ? -hw : hw, lz = i < 2 ? -hd : hd;
                float wx = (gx + lx * ca + lz * sa + half) * s, wz = (gz - lx * sa + lz * ca + half) * s;
                if (i == 0) soil.moveTo(wx, wz); else soil.lineTo(wx, wz);
            }
            soil.close();
            p.setColor(0xFF6A5033); c.drawPath(soil, p);
            rp.setColor(0xFF54402A); rp.setStrokeWidth(0.16f * s);   // furrow rows across the plot
            for (float lx = -hw + 0.45f; lx < hw - 0.2f; lx += 0.55f) {
                float x0 = (gx + lx * ca + (-hd + 0.3f) * sa + half) * s, z0 = (gz - lx * sa + (-hd + 0.3f) * ca + half) * s;
                float x1 = (gx + lx * ca + (hd - 0.3f) * sa + half) * s, z1 = (gz - lx * sa + (hd - 0.3f) * ca + half) * s;
                c.drawLine(x0, z0, x1, z1, rp);
            }
        }
        int[] bed = {0xE03A3A, 0xF7D43A, 0xF06FB0, 0x9B4FE0, 0x4F7BF0, 0xF58A20, 0xF4F0E6};
        for (int k = 0; k < 70; k++) {                     // painted flower beds on the lawn (off the roads)
            float wx = rnd.nextInt(N), wy = rnd.nextInt(N);
            float worldX = wx / N * (2f * half) - half, worldZ = wy / N * (2f * half) - half;
            if (onStreetXZ(worldX, worldZ)) continue;
            int col = bed[rnd.nextInt(bed.length)];
            for (int j = 0; j < 9; j++) {
                p.setColor(0xCC000000 | col);
                c.drawCircle(wx + rnd.nextInt(32) - 16, wy + rnd.nextInt(32) - 16, 3 + rnd.nextInt(4), p);
            }
        }
        for (int k = 0; k < 7500; k++) {                   // fine grass grain
            int v = rnd.nextInt(40);
            p.setColor((0x22 << 24) | ((0x22 + v) << 16) | ((0x44 + v) << 8) | (0x18 + v / 2));
            float x = rnd.nextInt(N), y = rnd.nextInt(N);
            c.drawRect(x, y, x + 1 + rnd.nextInt(2), y + 1, p);
        }
        return bmp;
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
            java.util.List<float[]> furns = new java.util.ArrayList<float[]>();
            java.util.List<float[]> ehouses = new java.util.ArrayList<float[]>();   // editor-placed houses (editable overlay)
            StringBuilder sceneSb = new StringBuilder();        // scenery lines kept verbatim on save (all but FU/T/EH)
            StringBuilder rawSb = new StringBuilder();          // the whole file (for the H/R custom-level path)
            br = new java.io.BufferedReader(new java.io.FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() > 0) { rawSb.append(line).append('\n');
                    if (!line.startsWith("FU ") && !line.startsWith("T ") && !line.startsWith("EH ")) sceneSb.append(line).append('\n'); }
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
                                       pfDef(t,34,1f),                                      // [33] storeys (1 = no dividers)
                                       pfDef(t,35,0f),                                      // [34] facade material 0 plaster·1 brick·2 stone·3 timber
                                       pfDef(t,36,0f),                                      // [35] house yaw in degrees (0 = axis-aligned)
                                       pfDef(t,37,1f)});                                    // [36] auto-furnish (1 = built-in furniture, 0 = place your own FU pieces)
                } else if (t[0].equals("B") && t.length >= 9) {
                    // cx cz w h d r g b [yawDeg]  -> ground box centred at y = h/2 (yaw = visual rotation)
                    float w = pf(t[3]), h = pf(t[4]), d = pf(t[5]);
                    props.add(new float[]{pf(t[1]), h * 0.5f, pf(t[2]), w, h, d, pf(t[6]), pf(t[7]), pf(t[8]), pfDef(t, 9, 0f)});
                } else if (t[0].equals("R") && t.length >= 5) {
                    // R x1 z1 x2 z2 [width] [r g b]  -> a street segment (visual only, no collision; rgb tints the asphalt)
                    roadList.add(new float[]{pf(t[1]), pf(t[2]), pf(t[3]), pf(t[4]), pfDef(t, 5, 3.4f),
                                             pfDef(t, 6, 1f), pfDef(t, 7, 1f), pfDef(t, 8, 1f)});
                } else if (t[0].equals("FU") && t.length >= 4) {
                    // FU kind x z [yawDeg] [scale] [storeyY]  -> a placed furniture piece (visual + collision)
                    furns.add(new float[]{pf(t[1]), pf(t[2]), pf(t[3]), pfDef(t, 4, 0f), pfDef(t, 5, 1f), pfDef(t, 6, 0f)});
                } else if (t[0].equals("T") && t.length >= 3) {
                    // T x z [scale] [kind: 0 tree · 1 bush · 2 flower]  -> a placed plant
                    trees.add(new float[]{pf(t[1]), pf(t[2]), pfDef(t, 3, 1f), pfDef(t, 4, 0f)});
                } else if (t[0].equals("EH") && t.length >= 4) {
                    // EH kind x z [yawDeg] [scale] [furnished]  -> an editor-placed house (editable overlay)
                    ehouses.add(new float[]{pf(t[1]), pf(t[2]), pf(t[3]), pfDef(t, 4, 0f), pfDef(t, 5, 1f), pfDef(t, 6, 0f)});
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
            // ehouses counts too: a level consisting ONLY of editor-placed houses used to be rejected here,
            // silently discarding them and letting the next save overwrite the file (permanent data loss).
            if (hs.isEmpty() && props.isEmpty() && roadList.isEmpty() && trees.isEmpty() && furns.isEmpty() && ehouses.isEmpty()) return false;
            // No houses/roads = an "overlay" save on top of the procedural town: build that base, then load
            // the file's furniture + plants as the EDITABLE overlay (so an in-game save round-trips + re-edits).
            if (hs.isEmpty() && roadList.isEmpty()) {
                buildWorldInto(L, doors, houses);                  // procedural scenery (keeps its own trees/furniture)
                for (float[] fu : furns) editObjs.add(new float[]{0f, fu[0], fu[1], fu[2], fu[3], fu[4], fu.length > 5 ? fu[5] : 0f});
                for (float[] t : trees) editObjs.add(new float[]{1f, (t.length > 3 ? t[3] : 0f), t[0], t[1], (t.length > 4 ? t[4] : 0f), (t.length > 2 ? t[2] : 1f)});
                for (float[] eh : ehouses) editObjs.add(new float[]{3f, eh[0], eh[1], eh[2], eh[3], eh[4], eh.length > 5 ? eh[5] : 0f});
                for (float[] p : props) L.add(p);                  // arbitrary boxes stay as (non-editable) base scenery
                this.baseLevelText = sceneSb.toString();
                return true;
            }
            // A full custom level (has houses/roads): build the houses/roads as before, but route its FU/T
            // lines into the SAME editable overlay as the no-houses case, so a hand-authored level (built in
            // the web editor) stays fully editable in-game too, not just objects newly placed on-device.
            this.roadSegs = roadList.isEmpty() ? null : roadList.toArray(new float[0][]);
            this.hasCustomRoads = this.roadSegs != null;
            this.baseLevelText = sceneSb.toString();
            for (float[] fu : furns) editObjs.add(new float[]{0f, fu[0], fu[1], fu[2], fu[3], fu[4], fu.length > 5 ? fu[5] : 0f});
            for (float[] t : trees) editObjs.add(new float[]{1f, (t.length > 3 ? t[3] : 0f), t[0], t[1], (t.length > 4 ? t[4] : 0f), (t.length > 2 ? t[2] : 1f)});
            for (float[] eh : ehouses) editObjs.add(new float[]{3f, eh[0], eh[1], eh[2], eh[3], eh[4], eh.length > 5 ? eh[5] : 0f});
            for (float[] p : props) L.add(p);                       // props first -> they get the shadow blobs
            numCover = Math.min(COVER_BOXES, props.size());
            for (float[] hh : hs) {
                int roof = (int) hh[5], door = (int) hh[6], chim = (int) hh[17];
                float rr = hh[10], rg = hh[11], rb = hh[12];
                if (rr < 0f) { rr = 0.69f; rg = 0.31f; rb = 0.16f; }     // default terracotta roof
                float gr = hh[18], gg = hh[19], gb = hh[20];
                if (gr < 0f) { gr = GLASS_DEF[0]; gg = GLASS_DEF[1]; gb = GLASS_DEF[2]; }   // default muted glass
                int mat = (int) hh[34];                                  // facade material from the level (0 = plaster)
                int dStyle = mat == 0 ? 1 : (mat == 1 ? 0 : (mat == 2 ? 2 : 3));   // door style follows the material, like the procedural town
                addBuilding(L, doors, hh[0], hh[1], hh[2], hh[3], hh[4], door, roof, hh[7], hh[8], hh[9], chim,
                            hh[21], hh[22], hh[23], hh[24], hh[25], dStyle, mat, hh[35], // doorW doorH doorR doorG doorB | doorStyle material | yaw
                            (int) hh[15], hh[16], (int) hh[33], hh[26], houseFurnishable(hh[2], hh[3], hh[4]) && hh[36] != 0f);  // dens wsc storeys foundH cutHoles
                houses.add(new float[]{hh[0], hh[1], hh[2], hh[3], hh[4], door, rr, rg, rb, hh[13], hh[14], hh[15], hh[16],
                            gr, gg, gb, hh[26], hh[27], hh[28], hh[29], hh[30], hh[31], hh[32], hh[33], hh[21], hh[35], hh[36]});  // [24] doorW, [25] yaw, [26] autoFurnish
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

    /** True inside the spawn corridor (player start + first steps) — kept free of solid props. */
    private static boolean inSpawnLane(float x, float z) {
        return Math.abs(x) < 3.6f && z > 0.8f && z < 13.5f;
    }

    private void buildWorldInto(List<float[]> L, List<float[]> doors, List<float[]> houses) {
        this.clutterPts = new java.util.ArrayList<float[]>();   // record props so benches don't spawn on top of them
        this.pathStrokes = new java.util.ArrayList<float[]>();
        this.gardenSoil = new java.util.ArrayList<float[]>();
        this.gardenTreePts = new java.util.ArrayList<float[]>();
        this.waterDiscs = null; this.waterStreams = null;       // re-registered by this build's fountain (if any)
        lmPolice = false; lmFire = false; lmChapel = false;     // one landmark of each kind per town
        // No more loose cover blocks on the plaza — the market (fountain centrepiece + a ring of
        // stalls, see addAccessories) provides the spawn cover now.
        numCover = 0;

        // ---- houses strung along the curved lanes, each turned to face its road -------------------
        // March each road by arclength; at irregular intervals drop a lot on one side (sometimes both),
        // with a jittered setback and a small yaw wobble, so nothing lines up or repeats.
        Random rc = new Random(73);
        java.util.List<float[]> gardens = new java.util.ArrayList<float[]>();   // {cx,cz,hw,hd,yaw}
        for (int r = 0; r < ROAD_PTS.length; r++) {
            float[][] P = ROAD_PTS[r];
            float acc = 5.5f + rc.nextFloat() * 4f;                 // distance until the next lot
            int lastSide = rc.nextBoolean() ? 1 : -1;
            for (int i = 0; i + 1 < P.length; i++) {
                float ax = P[i][0], az = P[i][1];
                float tx = P[i + 1][0] - ax, tz = P[i + 1][1] - az;
                float sl = (float) Math.sqrt(tx * tx + tz * tz);
                acc -= sl;
                if (acc > 0f) continue;
                // lot spacing tells each chapter's story: farms sit far apart, the old town packs tight
                acc = levelId == 1 ? 6.4f + rc.nextFloat() * 4.2f
                    : levelId == 2 ? 3.9f + rc.nextFloat() * 2.4f
                    : 4.1f + rc.nextFloat() * 2.6f;   // denser: the big landmark/barn lots must not thin the towns
                tx /= sl; tz /= sl;
                float nxr = -tz, nzr = tx;                          // road normal (left of travel)
                int nSides = rc.nextFloat() < (levelId == 1 ? 0.35f : levelId == 2 ? 0.80f : 0.7f) ? 2 : 1;
                int side = rc.nextFloat() < 0.72f ? -lastSide : lastSide;   // mostly alternate sides
                for (int sI = 0; sI < nSides; sI++, side = -side) {
                    lastSide = side;
                    placeVillageHouse(L, doors, houses, gardens, rc, ax, az, tx, tz, nxr * side, nzr * side,
                            ROAD_HALFW[r]);
                }
            }
        }
        buildGardens(L, houses, gardens, rc);
        buildFrontPaths(L, houses);
        // trodden shortcuts BETWEEN the lanes: painted dirt polylines wandering through the green.
        // Each polyline slides sideways until it clears every house + garden fence (or is dropped),
        // so the layout can change without a path ever running through a building.
        float[][] shortcuts = {
            {-3.5f, 18.5f, 4f, 16.5f, 11f, 14f, 19f, 13.5f},        // south lane -> east lane, across the meadow
            {-18.5f, 3.5f, -13f, 8f, -6.5f, 11.5f},                 // west lane -> south lane
            {5.5f, -3.5f, 10f, -7f, 14.5f, -5f},                    // main road inner bend cut-off
        };
        for (float[] sc : shortcuts) {
            for (float off : new float[]{0f, 2.2f, -2.2f, 4.4f, -4.4f, 6.6f, -6.6f}) {
                boolean clear = true;
                for (int i = 0; clear && i + 3 < sc.length; i += 2) {
                    float x0 = sc[i], z0 = sc[i + 1], x1 = sc[i + 2], z1 = sc[i + 3];
                    float dx = x1 - x0, dz = z1 - z0, ln = (float) Math.sqrt(dx * dx + dz * dz);
                    float pxn = -dz / ln, pzn = dx / ln;
                    for (float u = 0f; clear && u <= ln; u += 0.8f) {
                        float x = x0 + dx * u / ln + pxn * off, z = z0 + dz * u / ln + pzn * off;
                        if (!clearOfHouses(houses, x, z, 0.55f)) clear = false;
                        for (int gi = 0; clear && gi < gardens.size(); gi++) {
                            float[] g = gardens.get(gi);
                            if (insideYawXZ(x, z, g[0], g[1], g[2] + 0.4f, g[3] + 0.4f, g[4])) clear = false;
                        }
                    }
                }
                if (!clear) continue;
                for (int i = 0; i + 3 < sc.length; i += 2) {
                    float dx = sc[i + 2] - sc[i], dz = sc[i + 3] - sc[i + 1];
                    float ln = (float) Math.sqrt(dx * dx + dz * dz);
                    float pxn = -dz / ln, pzn = dx / ln;
                    pathStrokes.add(new float[]{sc[i] + pxn * off, sc[i + 1] + pzn * off,
                            sc[i + 2] + pxn * off, sc[i + 3] + pzn * off, 1.05f});
                }
                break;
            }
        }
        addAccessories(L, houses, rc);                              // lamps, benches, market square, scattered trees
    }

    /** One lot: pick an archetype by distance from the square, size it with real variance, face the road. */
    private void placeVillageHouse(List<float[]> L, List<float[]> doors, List<float[]> houses,
                                   List<float[]> gardens, Random rc,
                                   float ax, float az, float tx, float tz, float nx, float nz, float roadHw) {
        float distSq = (float) Math.hypot(ax - SQ_X, az - SQ_Z);
        float roll = rc.nextFloat();
        // 0 cottage · 1 townhouse · 2 block · 3 shop · 4 farmhouse ·
        // 5 POLIZEI · 6 FEUERWEHR · 7 KAPELLE · 8 SCHEUNE · 9 WIRTSHAUS
        int arch;
        if (levelId == 1) {                                         // ASCHENHOF: barns + farmhouses + squat cottages
            arch = roll < 0.28f ? 8 : (roll < 0.62f ? 4 : (roll < 0.90f ? 0 : 1));
        } else if (levelId == 2) {                                  // STEINMARKT: tall stone town, shops + tavern at the core
            if (distSq < 13f) arch = roll < 0.40f ? 3 : (roll < 0.56f ? 9 : (roll < 0.92f ? 1 : 2));
            else              arch = roll < 0.40f ? 1 : (roll < 0.66f ? 2 : (roll < 0.88f ? 0 : 3));
        } else if (distSq < 11f) arch = roll < 0.40f ? 3 : (roll < 0.55f ? 9 : (roll < 0.84f ? 1 : 0));
        else if (distSq < 21f)  arch = roll < 0.52f ? 0 : (roll < 0.72f ? 1 : (roll < 0.88f ? 4 : 3));
        else                    arch = roll < 0.38f ? 0 : (roll < 0.58f ? 4 : (roll < 0.70f ? 8 : (roll < 0.90f ? 1 : 2)));
        // public-building landmarks claim a mid-ring lot once per town (the farm gets only a field chapel)
        if (levelId != 1 && distSq > 13f) {
            if (!lmPolice && roll >= 0.30f && roll < 0.42f) arch = 5;
            else if (!lmFire && roll >= 0.42f && roll < 0.54f) arch = 6;
            else if (!lmChapel && distSq > 16f && roll >= 0.90f) arch = 7;
        } else if (levelId == 1 && !lmChapel && distSq > 15f && roll >= 0.93f) arch = 7;
        float w, d, h, pitch = -1f, winSize = 1f, foundH, doorW = 1.9f, doorH = 2.3f;
        int storeys, winDens; boolean chimney, trim; float[] rf; int mat; int doorStyle;
        if (arch == 0) {                                            // cottage: low, gabled, squat-to-snug
            w = 2.9f + rc.nextFloat() * 1.4f; d = 2.8f + rc.nextFloat() * 1.0f; h = 2.7f + rc.nextFloat() * 0.8f;
            storeys = 1; foundH = 0.22f + rc.nextFloat() * 0.10f; winDens = 2; chimney = true; trim = false;
            rf = ROOFS[new int[]{0, 0, 4, 7}[rc.nextInt(4)]]; mat = rc.nextFloat() < 0.7f ? 0 : 3; doorStyle = 1;
        } else if (arch == 1) {                                     // townhouse: tall + narrow
            w = 2.7f + rc.nextFloat() * 0.7f; d = 2.9f + rc.nextFloat() * 0.6f; h = 4.6f + rc.nextFloat() * 1.5f;
            storeys = 2 + (rc.nextFloat() < 0.55f ? 1 : 0); foundH = 0.28f + rc.nextFloat() * 0.10f; winDens = 3;
            chimney = true; trim = rc.nextFloat() < 0.45f; pitch = 1.3f + rc.nextFloat() * 0.5f;
            rf = ROOFS[new int[]{0, 1, 1, 7}[rc.nextInt(4)]]; mat = 1; doorStyle = 0;
        } else if (arch == 2) {                                     // block: rare big flat-roofed outskirt building
            w = 3.4f + rc.nextFloat() * 0.9f; d = 3.2f + rc.nextFloat() * 0.7f; h = 5.4f + rc.nextFloat() * 1.5f;
            storeys = 3 + (rc.nextFloat() < 0.4f ? 1 : 0); foundH = 0.30f + rc.nextFloat() * 0.10f; winDens = 3;
            chimney = false; trim = rc.nextFloat() < 0.5f; pitch = 0.5f + rc.nextFloat() * 0.4f;
            rf = ROOFS[new int[]{1, 2, 5, 6}[rc.nextInt(4)]]; mat = 2; doorStyle = 2;
        } else if (arch == 3) {                                     // shop: wide front, big door, awning
            w = 3.3f + rc.nextFloat() * 1.0f; d = 3.0f + rc.nextFloat() * 0.5f; h = 3.2f + rc.nextFloat() * 0.7f;
            storeys = 1; foundH = 0.24f + rc.nextFloat() * 0.10f; winDens = 3; winSize = 1.2f; chimney = false;
            trim = true; doorW = 2.2f + rc.nextFloat() * 0.4f; rf = ROOFS[rc.nextFloat() < 0.6f ? 4 : 3];
            mat = rc.nextFloat() < 0.5f ? 1 : 0; doorStyle = 3;
        } else if (arch == 5) {                                     // POLIZEI: sturdy two-storey stone station
            w = 5.2f + rc.nextFloat() * 0.8f; d = 4.2f + rc.nextFloat() * 0.5f; h = 4.9f + rc.nextFloat() * 0.5f;
            storeys = 2; foundH = 0.34f; winDens = 3; winSize = 1.05f; chimney = false; trim = true;
            pitch = 0.6f + rc.nextFloat() * 0.3f;
            rf = new float[]{0.30f, 0.32f, 0.38f}; mat = 2; doorStyle = 2; doorW = 2.3f;
        } else if (arch == 6) {                                     // FEUERWEHR: wide engine hall, huge red gate
            w = 6.6f + rc.nextFloat() * 0.9f; d = 4.8f + rc.nextFloat() * 0.5f; h = 3.9f + rc.nextFloat() * 0.4f;
            storeys = 1; foundH = 0.30f; winDens = 2; winSize = 1.1f; chimney = false; trim = true;
            pitch = 0.55f + rc.nextFloat() * 0.2f;
            rf = new float[]{0.44f, 0.17f, 0.13f}; mat = 1; doorStyle = 3; doorW = 3.4f; doorH = 2.9f;
        } else if (arch == 7) {                                     // KAPELLE: narrow, tall, steep slate roof
            w = 3.3f + rc.nextFloat() * 0.4f; d = 5.0f + rc.nextFloat() * 0.8f; h = 5.0f + rc.nextFloat() * 0.5f;
            storeys = 2; foundH = 0.36f; winDens = 1; winSize = 1.5f; chimney = false; trim = true;
            pitch = 2.0f;
            rf = new float[]{0.24f, 0.26f, 0.31f}; mat = 2; doorStyle = 0; doorW = 1.7f; doorH = 2.6f;
        } else if (arch == 8) {                                     // SCHEUNE: big dark-timber barn, few windows
            w = 6.2f + rc.nextFloat() * 1.0f; d = 4.6f + rc.nextFloat() * 0.7f; h = 4.3f + rc.nextFloat() * 0.6f;
            storeys = 1; foundH = 0.24f; winDens = 1; winSize = 0.9f; chimney = false; trim = false;
            pitch = 1.7f + rc.nextFloat() * 0.4f;
            rf = new float[]{0.32f, 0.22f, 0.13f}; mat = 3; doorStyle = 1; doorW = 3.0f; doorH = 2.7f;
        } else if (arch == 9) {                                     // WIRTSHAUS: warm two-storey inn with a hanging sign
            w = 4.8f + rc.nextFloat() * 0.6f; d = 4.0f + rc.nextFloat() * 0.5f; h = 4.8f + rc.nextFloat() * 0.5f;
            storeys = 2; foundH = 0.28f; winDens = 3; winSize = 1.1f; chimney = true; trim = true;
            pitch = 1.2f + rc.nextFloat() * 0.4f;
            rf = ROOFS[new int[]{0, 7, 7, 4}[rc.nextInt(4)]]; mat = 3; doorStyle = 0; doorW = 2.1f;
        } else {                                                    // farmhouse: long, low, timbered
            w = 4.6f + rc.nextFloat() * 1.5f; d = 3.0f + rc.nextFloat() * 0.7f; h = 3.0f + rc.nextFloat() * 0.9f;
            storeys = 1 + (rc.nextFloat() < 0.35f ? 1 : 0); foundH = 0.26f + rc.nextFloat() * 0.10f; winDens = 2;
            chimney = true; trim = false; pitch = 1.1f + rc.nextFloat() * 0.5f;
            rf = ROOFS[new int[]{0, 4, 7, 7}[rc.nextInt(4)]]; mat = 3; doorStyle = 1;
        }
        // chapter material/height accents: farm timber everywhere; old town stone/brick and taller.
        // Landmarks (arch >= 5) keep their signature materials in every chapter.
        if (levelId == 1 && arch < 5) {
            if (rc.nextFloat() < 0.70f) mat = 3;                          // weathered timber
            if (arch == 1) storeys = 2;                                   // no towers on a farm
        } else if (levelId == 2 && arch < 5) {
            if (mat == 0 && rc.nextFloat() < 0.65f) mat = rc.nextBoolean() ? 1 : 2;   // plaster -> brick/stone
            if (mat == 3) mat = 2;                                        // no timber in the stone town
            if (arch == 1 && storeys < 3 && rc.nextFloat() < 0.5f) { storeys++; h += 1.3f; }
        }
        // door wall (+z) faces the road: centre = station + normal * (verge + setback + half depth)
        float setback = VERGE + (levelId == 1 ? 1.4f + rc.nextFloat() * 3.6f
                              : levelId == 2 ? 0.5f + rc.nextFloat() * 1.6f
                              : 0.7f + rc.nextFloat() * 2.8f);
        float cx = ax + nx * (roadHw + setback + d * 0.5f);
        float cz = az + nz * (roadHw + setback + d * 0.5f);
        float yawDeg = (float) Math.toDegrees(Math.atan2(-nx, -nz)) + (rc.nextFloat() - 0.5f) * 12f;
        // rejection tests: core disc, spawn corridor, square keep-clear, road + neighbour clearance
        if (cx * cx + cz * cz > 30.2f * 30.2f) return;
        if (inSpawnLane(cx, cz)) return;
        float dSq = (float) Math.hypot(cx - SQ_X, cz - SQ_Z);
        if (dSq < (arch == 3 ? 6.8f : 8.2f)) return;                // shops may ring the square more tightly
        double ya = Math.toRadians(yawDeg);
        float ca = (float) Math.cos(ya), sa = (float) Math.sin(ya);
        for (int cIx = 0; cIx < 9; cIx++) {                         // 8 perimeter points + centre stay off the streets
            float lx = (cIx % 3 - 1) * (w * 0.5f + 0.35f), lz = (cIx / 3 - 1) * (d * 0.5f + 0.35f);
            float wx = cx + lx * ca + lz * sa, wz = cz - lx * sa + lz * ca;
            if (roadSd(wx, wz) < 0.45f || wx * wx + wz * wz > 32.5f * 32.5f || inSpawnLane(wx, wz)) return;
        }
        for (float[] o : houses)                                    // never overlap a neighbour (OBB + breathing room)
            if (obbOverlap(cx, cz, w * 0.5f, d * 0.5f, yawDeg, o[0], o[1], o[2] * 0.5f, o[3] * 0.5f,
                    o.length > 25 ? o[25] : 0f, 1.1f)) return;
        for (float[] g : gardens)
            if (obbOverlap(cx, cz, w * 0.5f, d * 0.5f, yawDeg, g[0], g[1], g[2], g[3], g[4], 0.8f)) return;

        float[] p = PALETTE[rc.nextInt(PALETTE.length)];
        float[] dc = DOORS[rc.nextInt(DOORS.length)];
        float fR = 0.40f + rc.nextFloat() * 0.06f, fG = 0.38f + rc.nextFloat() * 0.06f, fB = 0.36f + rc.nextFloat() * 0.06f;
        float tR = trim ? 0.86f : -1f, tG = trim ? 0.84f : -1f, tB = trim ? 0.78f : -1f;
        if (arch == 5)      { p = new float[]{0.86f, 0.88f, 0.92f}; dc = new float[]{0.10f, 0.22f, 0.55f};   // POLIZEI: white + blue
                              tR = 0.16f; tG = 0.30f; tB = 0.62f; }
        else if (arch == 6) { p = new float[]{0.78f, 0.30f, 0.24f}; dc = new float[]{0.70f, 0.12f, 0.10f};   // FEUERWEHR: signal red
                              tR = 0.94f; tG = 0.91f; tB = 0.84f; }
        else if (arch == 7) { p = new float[]{0.93f, 0.92f, 0.88f}; dc = new float[]{0.36f, 0.24f, 0.14f};   // KAPELLE: white stone
                              tR = 0.80f; tG = 0.78f; tB = 0.72f; }
        else if (arch == 8) { p = new float[]{0.48f, 0.26f, 0.18f}; dc = new float[]{0.30f, 0.19f, 0.11f}; } // SCHEUNE: ox-blood timber
        else if (arch == 9) { p = new float[]{0.84f, 0.72f, 0.52f}; dc = new float[]{0.30f, 0.18f, 0.10f};   // WIRTSHAUS: warm cream
                              tR = 0.55f; tG = 0.40f; tB = 0.26f; }
        addBuilding(L, doors, cx, cz, w, d, h, 0, 1, p[0], p[1], p[2], chimney ? 1 : 0,
                    doorW, doorH, dc[0], dc[1], dc[2], doorStyle, mat, yawDeg,
                    winDens, winSize, storeys, foundH, houseFurnishable(w, d, h));   // cut real window openings
        houses.add(new float[]{cx, cz, w, d, h, 0f, rf[0], rf[1], rf[2], pitch, -1f, (float) winDens, winSize,
                GLASS_DEF[0], GLASS_DEF[1], GLASS_DEF[2], foundH, fR, fG, fB, tR, tG, tB, (float) storeys, doorW,
                yawDeg});                                            // [25] = house yaw (spins roof/windows/interior)
        // landmark signage/rooftop extras (local frame, door faces +z; spun with the house below)
        int exStart = L.size();
        if (arch == 5) {                                            // POLIZEI: rooftop light bar over the entrance
            L.add(new float[]{cx, h + 0.44f, cz + d * 0.5f - 0.35f, 1.10f, 0.10f, 0.16f, 0.20f, 0.22f, 0.26f, 0f});
            L.add(new float[]{cx - 0.28f, h + 0.56f, cz + d * 0.5f - 0.35f, 0.22f, 0.15f, 0.15f, 0.25f, 0.45f, 0.98f, 0f});
            L.add(new float[]{cx + 0.28f, h + 0.56f, cz + d * 0.5f - 0.35f, 0.22f, 0.15f, 0.15f, 0.92f, 0.16f, 0.12f, 0f});
            lmPolice = true;
        } else if (arch == 6) {                                     // FEUERWEHR: hose tower + siren dome
            L.add(new float[]{cx - w * 0.5f + 0.7f, h + 0.85f, cz - d * 0.5f + 0.7f, 1.15f, 1.7f, 1.15f, p[0] * 0.90f, p[1] * 0.90f, p[2] * 0.90f, 0f});
            L.add(new float[]{cx - w * 0.5f + 0.7f, h + 1.80f, cz - d * 0.5f + 0.7f, 1.35f, 0.14f, 1.35f, 0.30f, 0.24f, 0.20f, 0f});
            L.add(new float[]{cx + 0.4f, h + 0.90f, cz, 0.30f, 0.22f, 0.30f, 0.90f, 0.22f, 0.16f, 45f});
            lmFire = true;
        } else if (arch == 7) {                                     // KAPELLE: ridge bell cote + cross
            float ridgeY = h + 0.30f + 2.0f, bz2 = cz + d * 0.5f - 0.85f;
            L.add(new float[]{cx - 0.26f, ridgeY + 0.48f, bz2, 0.11f, 0.95f, 0.11f, 0.90f, 0.89f, 0.85f, 0f});   // cote posts
            L.add(new float[]{cx + 0.26f, ridgeY + 0.48f, bz2, 0.11f, 0.95f, 0.11f, 0.90f, 0.89f, 0.85f, 0f});
            L.add(new float[]{cx, ridgeY + 0.98f, bz2, 0.74f, 0.13f, 0.52f, 0.28f, 0.24f, 0.20f, 0f});           // cote cap
            L.add(new float[]{cx, ridgeY + 0.58f, bz2, 0.26f, 0.30f, 0.26f, 0.62f, 0.50f, 0.26f, 45f});          // brass bell
            L.add(new float[]{cx, ridgeY + 1.42f, bz2, 0.065f, 0.66f, 0.065f, 0.86f, 0.83f, 0.75f, 0f});         // cross |
            L.add(new float[]{cx, ridgeY + 1.52f, bz2, 0.32f, 0.065f, 0.065f, 0.86f, 0.83f, 0.75f, 0f});         // cross -
            lmChapel = true;
        } else if (arch == 8) {                                     // SCHEUNE: hayloft door + hoist beam on the gable
            L.add(new float[]{cx, h - 0.75f, cz + d * 0.5f + 0.04f, 1.05f, 1.15f, 0.10f, 0.24f, 0.155f, 0.09f, 0f});
            L.add(new float[]{cx, h + 0.10f, cz + d * 0.5f + 0.35f, 0.13f, 0.13f, 0.95f, 0.35f, 0.24f, 0.14f, 0f});
        } else if (arch == 9) {                                     // WIRTSHAUS: hanging sign beside the door
            L.add(new float[]{cx + doorW * 0.5f + 0.55f, 2.66f, cz + d * 0.5f + 0.26f, 0.60f, 0.07f, 0.07f, 0.30f, 0.20f, 0.12f, 0f});
            L.add(new float[]{cx + doorW * 0.5f + 0.74f, 2.30f, cz + d * 0.5f + 0.28f, 0.46f, 0.52f, 0.06f, 0.52f, 0.36f, 0.16f, 0f});
            L.add(new float[]{cx + doorW * 0.5f + 0.74f, 2.32f, cz + d * 0.5f + 0.315f, 0.30f, 0.32f, 0.045f, 0.82f, 0.64f, 0.20f, 0f});
        }
        for (int bi = exStart; bi < L.size(); bi++) rotateBoxAbout(L, bi, cx, cz, yawDeg);
        if (rc.nextFloat() < 0.45f) {                               // a barrel / crate / planter against a side wall
            float lxo = (rc.nextBoolean() ? 1f : -1f) * (w * 0.5f + 0.4f);
            float lzo = (rc.nextFloat() - 0.3f) * d * 0.5f;
            float axc = cx + lxo * ca + lzo * sa, azc = cz - lxo * sa + lzo * ca;
            if (roadSd(axc, azc) > 0.9f && !inSpawnLane(axc, azc)   // 0.9m off the asphalt: crate CORNERS stay clear
                    && clearOfHouses(houses, axc, azc, 0.28f) && !blocksAnyDoor(houses, axc, azc)) {
                addClutter(L, rc, axc, azc);
                clutterPts.add(new float[]{axc, azc});
            }
        }
    }

    /** Fenced kitchen gardens tucked behind ~half the houses: post-and-rail fence with a gate gap, a
     *  tilled soil plot (painted into the ground + sprouted by the vegetation bake), maybe a fruit tree. */
    private void buildGardens(List<float[]> L, List<float[]> houses, List<float[]> gardens, Random rc) {
        for (float[] h : houses) {
            if (rc.nextFloat() > 0.78f) continue;
            float w = h[2], d = h[3], yawDeg = h[25];
            double ya = Math.toRadians(yawDeg);
            float ca = (float) Math.cos(ya), sa = (float) Math.sin(ya);
            float gw = Math.max(2.6f, w * 0.85f + rc.nextFloat() * 1.2f), gd = 2.4f + rc.nextFloat() * 1.8f;
            float lox = (rc.nextFloat() - 0.5f) * 1.2f, loz = -(d * 0.5f + 0.45f + gd * 0.5f);   // behind the house
            float gx = h[0] + lox * ca + loz * sa, gz = h[1] - lox * sa + loz * ca;
            boolean ok = gx * gx + gz * gz < 30.5f * 30.5f && !inSpawnLane(gx, gz)
                    && (float) Math.hypot(gx - SQ_X, gz - SQ_Z) > 8f;
            for (int cIx = 0; ok && cIx < 9; cIx++) {               // 8 perimeter points + centre (curves can bulge into edges)
                float lx = (cIx % 3 - 1) * gw * 0.5f, lz = (cIx / 3 - 1) * gd * 0.5f;
                float wx = gx + lx * ca + lz * sa, wz = gz - lx * sa + lz * ca;
                if (roadSd(wx, wz) < 0.55f || wx * wx + wz * wz > 32.5f * 32.5f || inSpawnLane(wx, wz)) ok = false;
            }
            for (int oi = 0; ok && oi < houses.size(); oi++) {      // fence must not clip any OTHER house
                float[] o = houses.get(oi);
                if (o == h) continue;
                if (obbOverlap(gx, gz, gw * 0.5f, gd * 0.5f, yawDeg, o[0], o[1], o[2] * 0.5f, o[3] * 0.5f,
                        o.length > 25 ? o[25] : 0f, 0.5f)) ok = false;
            }
            for (int gi = 0; ok && gi < gardens.size(); gi++) {
                float[] g = gardens.get(gi);
                if (obbOverlap(gx, gz, gw * 0.5f, gd * 0.5f, yawDeg, g[0], g[1], g[2], g[3], g[4], 0.5f)) ok = false;
            }
            if (!ok) continue;
            gardens.add(new float[]{gx, gz, gw * 0.5f, gd * 0.5f, yawDeg});
            // post-and-rail fence: 4 corner + 2 gate posts, two rails per side, gate gap on one side wall
            float fr = 0.42f, fg = 0.33f, fb = 0.24f;
            int gateSide = rc.nextBoolean() ? 1 : -1;               // gate on the +x or -x side (near the house corner)
            float gateAt = -gd * 0.5f + 0.9f + rc.nextFloat() * (gd - 1.8f), gateHalf = 0.55f;
            for (int sIx = 0; sIx < 4; sIx++) {                     // 0 +x · 1 -x · 2 +z(house side) · 3 -z
                boolean xSide = sIx < 2;
                float sgn = sIx % 2 == 0 ? 1f : -1f;
                float len = xSide ? gd : gw;
                boolean gated = xSide && sgn == gateSide;
                for (int rail = 0; rail < 2; rail++) {
                    float ry = rail == 0 ? 0.26f : 0.56f;
                    if (!gated) {
                        float lx = xSide ? sgn * gw * 0.5f : 0f, lz = xSide ? 0f : sgn * gd * 0.5f;
                        addYawBox(L, gx, gz, ca, sa, lx, lz, ry, xSide ? 0.06f : len, 0.055f, xSide ? len : 0.06f, fr, fg, fb, yawDeg);
                    } else {                                        // two rail runs leaving the gate gap
                        float a0 = -gd * 0.5f, a1 = gateAt - gateHalf, b0 = gateAt + gateHalf, b1 = gd * 0.5f;
                        addYawBox(L, gx, gz, ca, sa, sgn * gw * 0.5f, (a0 + a1) * 0.5f, ry, 0.06f, 0.055f, Math.max(0.15f, a1 - a0), fr, fg, fb, yawDeg);
                        addYawBox(L, gx, gz, ca, sa, sgn * gw * 0.5f, (b0 + b1) * 0.5f, ry, 0.06f, 0.055f, Math.max(0.15f, b1 - b0), fr, fg, fb, yawDeg);
                    }
                }
            }
            for (int cIx = 0; cIx < 4; cIx++) {                     // corner posts, each with a pointed cap
                float plx = (cIx % 2 == 0 ? -1 : 1) * gw * 0.5f, plz = (cIx < 2 ? -1 : 1) * gd * 0.5f;
                addYawBox(L, gx, gz, ca, sa, plx, plz, 0.375f, 0.09f, 0.75f, 0.09f, fr * 0.9f, fg * 0.9f, fb * 0.9f, yawDeg);
                addYawBox(L, gx, gz, ca, sa, plx, plz, 0.775f, 0.075f, 0.06f, 0.075f, fr * 0.75f, fg * 0.75f, fb * 0.75f, yawDeg + 45f);
            }
            addYawBox(L, gx, gz, ca, sa, gateSide * gw * 0.5f, gateAt - gateHalf, 0.375f, 0.09f, 0.75f, 0.09f, fr * 0.9f, fg * 0.9f, fb * 0.9f, yawDeg);
            addYawBox(L, gx, gz, ca, sa, gateSide * gw * 0.5f, gateAt + gateHalf, 0.375f, 0.09f, 0.75f, 0.09f, fr * 0.9f, fg * 0.9f, fb * 0.9f, yawDeg);
            addYawBox(L, gx, gz, ca, sa, gateSide * gw * 0.5f, gateAt - gateHalf, 0.775f, 0.075f, 0.06f, 0.075f, fr * 0.75f, fg * 0.75f, fb * 0.75f, yawDeg + 45f);
            addYawBox(L, gx, gz, ca, sa, gateSide * gw * 0.5f, gateAt + gateHalf, 0.775f, 0.075f, 0.06f, 0.075f, fr * 0.75f, fg * 0.75f, fb * 0.75f, yawDeg + 45f);
            gardenSoil.add(new float[]{gx, gz, gw - 0.75f, gd - 0.75f, yawDeg});   // tilled plot (texture + sprouts)
            if (rc.nextFloat() < 0.30f) {                           // a fruit tree in one back corner
                float lx = (rc.nextBoolean() ? 1f : -1f) * (gw * 0.5f - 0.8f), lz = -gd * 0.5f + 0.8f;
                gardenTreePts.add(new float[]{gx + lx * ca + lz * sa, gz - lx * sa + lz * ca, 0.55f + rc.nextFloat() * 0.3f});
            }
        }
    }

    /** Add one yawed box given the garden/house local frame (centre gx,gz + precomputed cos/sin). */
    private static void addYawBox(List<float[]> L, float gx, float gz, float ca, float sa,
                                  float lx, float lz, float cy, float sx, float sy, float sz,
                                  float r, float g, float b, float yawDeg) {
        L.add(new float[]{gx + lx * ca + lz * sa, cy, gz - lx * sa + lz * ca, sx, sy, sz, r, g, b, yawDeg, 0f, 0f});
    }

    /** Flagstone front paths: door face -> nearest kerb, following the house yaw; recorded as a dirt
     *  stroke so the ground texture shows a worn path underneath the slabs. */
    private void buildFrontPaths(List<float[]> L, List<float[]> houses) {
        java.util.Random pathRnd = new java.util.Random(99173);
        for (float[] h : houses) {
            float d = h[3], yawDeg = h[25];
            double ya = Math.toRadians(yawDeg);
            float ca = (float) Math.cos(ya), sa = (float) Math.sin(ya);
            float dirX = sa, dirZ = ca;                             // local +z (door normal) in world
            float fx = h[0] + dirX * d * 0.5f, fz = h[1] + dirZ * d * 0.5f;
            float plen = pathLenToRoad(fx, fz, dirX, dirZ);
            float u0 = 0.22f, span = Math.max(0.3f, (plen - 0.18f) - u0), pw = 1.06f;
            int nslab = Math.max(1, Math.round(span / 0.72f));
            float seg = span / nslab;
            for (int sIx = 0; sIx < nslab; sIx++) {
                float u = u0 + (sIx + 0.5f) * seg;
                float jx = (pathRnd.nextFloat() - 0.5f) * 0.06f;    // slight hand-laid lateral jitter
                float scx = fx + dirX * u + ca * jx, scz = fz + dirZ * u - sa * jx;
                float slLen = seg * 0.80f;                          // ~20% grass gap between slabs
                float g = 0.55f + pathRnd.nextFloat() * 0.17f;
                L.add(new float[]{scx, 0.02f, scz, pw, 0.05f, slLen, g, g * 0.98f, g * 0.93f, yawDeg, 0f, 0f});
            }
            pathStrokes.add(new float[]{fx + dirX * 0.1f, fz + dirZ * 0.1f,
                    fx + dirX * (plen + 0.25f), fz + dirZ * (plen + 0.25f), 1.25f});
        }
    }

    /** True if (x,z) sits on/near a painted dirt footpath — trees and bushes keep off the paths. */
    private float pathDist(float x, float z) {
        if (pathStrokes == null) return 1e9f;
        float best = 1e9f;
        for (float[] st : pathStrokes) {
            float abx = st[2] - st[0], abz = st[3] - st[1];
            float tt = ((x - st[0]) * abx + (z - st[1]) * abz) / (abx * abx + abz * abz + 1e-6f);
            tt = tt < 0f ? 0f : (tt > 1f ? 1f : tt);
            float dx = x - (st[0] + abx * tt), dz = z - (st[1] + abz * tt);
            best = Math.min(best, (float) Math.sqrt(dx * dx + dz * dz) - st[4] * 0.5f);
        }
        return best;
    }
    /** True inside a tilled garden plot (inflated) — bushes/meadow trees stay out of the beds. */
    private boolean insideGardenXZ(float x, float z, float inflate) {
        if (gardenSoil == null) return false;
        for (float[] g : gardenSoil)
            if (insideYawXZ(x, z, g[0], g[1], g[2] * 0.5f + inflate, g[3] * 0.5f + inflate, g[4])) return true;
        return false;
    }
    /** True on the cobbled village square (no trees/bushes through the paving). */
    private static boolean onSquareXZ(float x, float z, float inflate) {
        return Math.hypot(x - (SQ_X + 0.8f), z - (SQ_Z + 1.9f)) < 9.8f + inflate;
    }
    /** A tree may only stand on grass or dirt: off the asphalt+verge, the square, the paths, the beds. */
    private boolean treeSpotOk(float x, float z) {
        return roadSd(x, z) > 1.15f && !onSquareXZ(x, z, 0.4f) && pathDist(x, z) > 0.9f && !insideGardenXZ(x, z, 0.5f);
    }

    /** Village furniture: the market circle FIRST (so nothing spawns in front of the stands), then
     *  lamps on the verges + trees on the grass, benches beside some doors, loose meadow trees. */
    private void addAccessories(List<float[]> L, List<float[]> houses, Random rc) {
        java.util.List<float[]> placed = new java.util.ArrayList<float[]>();    // prop positions, for spacing
        java.util.List<float[]> lampPts = new java.util.ArrayList<float[]>();
        java.util.List<float[]> marketPts = new java.util.ArrayList<float[]>();
        java.util.List<float[]> trees = new java.util.ArrayList<float[]>();
        if (levelId == 1) {                                 // ASCHENHOF: a farmyard well instead of a market
            addWell(L, 0.8f, 5.4f);
            clutterPts.add(new float[]{0.8f, 5.4f});
            marketPts.add(new float[]{0.8f, 5.4f});
            addWoodpile(L, rc, -2.6f, 6.8f); clutterPts.add(new float[]{-2.6f, 6.8f}); marketPts.add(new float[]{-2.6f, 6.8f});
            addBarrel(L, 3.9f, 6.4f); clutterPts.add(new float[]{3.9f, 6.4f}); marketPts.add(new float[]{3.9f, 6.4f});
        } else {
            placeMarket(L, houses, rc, marketPts);          // village + old town keep their market ring
        }
        for (float[] mp : marketPts) placed.add(mp);
        // 1. lamps on the verge + trees fully on the GRASS behind it: walk each lane by arclength
        for (int r = 0; r < ROAD_PTS.length; r++) {
            float[][] P = ROAD_PTS[r];
            float acc = 3f;
            for (int i = 0; i + 1 < P.length; i++) {
                float ax = P[i][0], az = P[i][1];
                float tx = P[i + 1][0] - ax, tz = P[i + 1][1] - az;
                float sl = (float) Math.sqrt(tx * tx + tz * tz);
                acc -= sl;
                if (acc > 0f) continue;
                acc = 6.0f + rc.nextFloat() * 3.5f;
                tx /= sl; tz /= sl;
                for (int side = -1; side <= 1; side += 2) {
                    float pick = rc.nextFloat();
                    boolean lamp = pick < (levelId == 1 ? 0.12f : levelId == 2 ? 0.40f : 0.30f);
                    if (!lamp && pick >= 0.82f) continue;          // a deliberate gap
                    float off = ROAD_HALFW[r] + (lamp ? 0.55f : VERGE + 0.9f);   // lamp on the verge, tree on grass
                    float sx = ax - tz * off * side, sz = az + tx * off * side;
                    if (sx * sx + sz * sz > 30f * 30f) continue;
                    if (inSpawnLane(sx, sz)) continue;
                    // 0.30m off EVERY lane, not just this one: at junctions the 45deg lamp-base corner
                    // (0.23m diagonal) otherwise clips the crossing road's asphalt
                    if (roadSd(sx, sz) < 0.30f || !clearOfHouses(houses, sx, sz, 0.15f)) continue;
                    if (blocksAnyDoor(houses, sx, sz)) continue;
                    if (!clearOfPts(placed, sx, sz, 3f) || !clearOfPts(marketPts, sx, sz, 2.8f)) continue;
                    if (lamp) {
                        placed.add(new float[]{sx, sz});
                        addLamp(L, sx, sz); lampPts.add(new float[]{sx, sz});
                    } else {
                        if (!treeSpotOk(sx, sz)) continue;         // grass/dirt only — never verge, square, path or bed
                        float ts = Math.min(0.8f + rc.nextFloat() * 0.7f, houseClearance(houses, sx, sz) / 1.9f);
                        if (ts >= 0.5f && !blocksAnyDoorCrown(houses, sx, sz, 1.45f * ts)) {
                            placed.add(new float[]{sx, sz});
                            trees.add(new float[]{sx, sz, ts});
                        }
                    }
                }
            }
        }
        // 2. a bench beside ~1 in 4 doors (turned with the house), never across the doorway
        java.util.List<float[]> benchPts = new java.util.ArrayList<float[]>();
        for (float[] h : houses) {
            if (rc.nextFloat() >= 0.26f) continue;
            float yawDeg = h[25];
            double ya = Math.toRadians(yawDeg);
            float ca = (float) Math.cos(ya), sa = (float) Math.sin(ya);
            float doorW = h.length > 24 ? h[24] : 1.9f;
            for (float sgn : new float[]{1f, -1f}) {
                float lx = sgn * (doorW * 0.5f + 1.15f), lz = h[3] * 0.5f + 0.62f;   // beside the door, on the path apron
                float bx = h[0] + lx * ca + lz * sa, bz = h[1] - lx * sa + lz * ca;
                if (inSpawnLane(bx, bz) || bx * bx + bz * bz > 30f * 30f) continue;
                if (roadSd(bx, bz) < 1.05f || blocksDoor(h, bx, bz)) continue;   // whole bench off the asphalt
                if (clutterPts != null && !clearOfPts(clutterPts, bx, bz, 1.15f)) continue;
                if (!clearOfPts(trees, bx, bz, 1.0f) || !clearOfPts(benchPts, bx, bz, 1.7f) || !clearOfPts(lampPts, bx, bz, 0.85f)) continue;
                int b0 = L.size();
                addBench(L, bx, bz, 0);                            // backrest toward the wall, seat faces the road
                for (int bi = b0; bi < L.size(); bi++) rotateBoxAbout(L, bi, bx, bz, yawDeg);
                benchPts.add(new float[]{bx, bz});
                break;
            }
        }
        // 3. garden fruit trees recorded during buildGardens + loose meadow trees between the lanes
        if (gardenTreePts != null) trees.addAll(gardenTreePts);
        int meadowTrees = levelId == 1 ? 42 : levelId == 2 ? 14 : 26;   // farms wooded, the town paved
        for (int n = 0, tries = 0; n < meadowTrees && tries < 900; tries++) {
            float a = rc.nextFloat() * 6.2832f, rad = 6f + rc.nextFloat() * 24f;
            float x = SQ_X + (float) Math.cos(a) * rad, z = SQ_Z + (float) Math.sin(a) * rad;
            if (x * x + z * z > 29f * 29f || inSpawnLane(x, z)) continue;
            if (roadSd(x, z) < 2.3f || !treeSpotOk(x, z)) continue;   // grass/dirt only
            if (!clearOfHouses(houses, x, z, 1.3f) || blocksAnyDoorCrown(houses, x, z, 1.6f)) continue;
            if (!clearOfPts(trees, x, z, 3.4f) || !clearOfPts(lampPts, x, z, 1.2f) || !clearOfPts(benchPts, x, z, 1.4f)) continue;
            if (!clearOfPts(marketPts, x, z, 3.0f)) continue;         // never in front of the market stands
            trees.add(new float[]{x, z, 0.8f + rc.nextFloat() * 0.6f});
            n++;
        }
        this.treeList = trees.isEmpty() ? null : trees.toArray(new float[0][]);
        this.lampWorld = lampPts.isEmpty() ? null : lampPts.toArray(new float[0][]);   // dusk lights + glowing bulbs
    }

    /** The market: a fountain centrepiece on the square with stalls, the well and goods crates
     *  arranged in a RING around it, every front turned toward the middle — a proper market circle.
     *  Placed FIRST so no tree/lamp/bench ever spawns in front of a stand. */
    private void placeMarket(List<float[]> L, List<float[]> houses, Random rc, List<float[]> marketPts) {
        float mcx = 0.8f, mcz = 5.4f, mrad = 5.4f;
        addFountain(L, mcx, mcz);
        clutterPts.add(new float[]{mcx, mcz});
        marketPts.add(new float[]{mcx, mcz});
        int[] kinds = {0, 3, 1, 4, 2, 5};   // stall red · well · stall green · barrels · stall blue · planter
        int placedK = 0;
        float lastA = -999f;
        for (float aDeg = -170f; aDeg <= 180f && placedK < kinds.length; aDeg += 10f) {
            if (aDeg - lastA < 42f) continue;               // breathing room between stands
            double aa = Math.toRadians(aDeg);
            float mx = mcx + (float) Math.sin(aa) * mrad, mz = mcz + (float) Math.cos(aa) * mrad;
            if (roadSd(mx, mz) < 1.15f || mx * mx + mz * mz > 30f * 30f) continue;   // never on/next to the asphalt
            if (Math.hypot(mx - 0f, mz - 9f) < 2.1f) continue;                        // keep the spawn point free
            if (!clearOfHouses(houses, mx, mz, 0.9f) || blocksAnyDoor(houses, mx, mz)) continue;
            float face = aDeg + 180f;                       // local +z (the stall front) points at the fountain
            int b0 = L.size();
            int kind = kinds[placedK];
            if (kind <= 2)      addStall(L, mx, mz, kind == 0 ? 0.78f : (kind == 1 ? 0.34f : 0.30f),
                                          kind == 0 ? 0.32f : (kind == 1 ? 0.46f : 0.38f),
                                          kind == 0 ? 0.27f : (kind == 1 ? 0.32f : 0.62f));
            else if (kind == 3) addWell(L, mx, mz);
            else if (kind == 4) { addBarrel(L, mx - 0.4f, mz); addBarrel(L, mx + 0.35f, mz + 0.3f); addCrate(L, rc, mx + 0.1f, mz - 0.55f); }
            else                { addPlanter(L, mx - 0.4f, mz); addWoodpile(L, rc, mx + 0.55f, mz); }
            for (int bi = b0; bi < L.size(); bi++) rotateBoxAbout(L, bi, mx, mz, face);
            clutterPts.add(new float[]{mx, mz});
            marketPts.add(new float[]{mx, mz});
            placedK++;
            lastA = aDeg;
        }
    }

    /** A stone fountain: a REAL octagonal basin (eight rim segments around a sunken pool), a pedestal
     *  carrying an overflowing upper bowl, and falling water streams. The water itself is no longer
     *  boxes — the pools/streams are registered here and rendered as animated translucent shader
     *  surfaces (drawWater). Rim/bowl heights are staggered so no two stone tops are ever coplanar
     *  (the old two-box basin z-fought itself AND its flush water slabs). */
    private void addFountain(List<float[]> L, float x, float z) {
        float sr = 0.73f, sg = 0.72f, sb = 0.68f;
        for (int k = 0; k < 8; k++) {                        // octagonal rim: 8 wall segments, alternating course height
            double a = Math.toRadians(k * 45f);
            float wx = x + (float) Math.sin(a) * 1.08f, wz = z + (float) Math.cos(a) * 1.08f;
            float hgt = (k % 2 == 0) ? 0.54f : 0.52f;
            float tint = (k % 2 == 0) ? 0f : -0.035f;
            L.add(new float[]{wx, hgt * 0.5f, wz, 1.02f, hgt, 0.24f, sr + tint, sg + tint, sb + tint, k * 45f});
        }
        L.add(new float[]{x, 0.86f, z, 0.42f, 1.1f, 0.42f, sr, sg, sb, 0f});             // pedestal
        L.add(new float[]{x, 1.42f, z, 0.95f, 0.16f, 0.95f, sr, sg, sb, 0f});            // upper bowl — tops AND
        L.add(new float[]{x, 1.425f, z, 0.95f, 0.13f, 0.95f, sr - 0.04f, sg - 0.04f, sb - 0.04f, 45f});   // bottoms staggered
        L.add(new float[]{x, 1.74f, z, 0.16f, 0.55f, 0.16f, sr, sg, sb, 0f});            // spout
        if (waterDiscs == null) waterDiscs = new java.util.ArrayList<float[]>();
        if (waterStreams == null) waterStreams = new java.util.ArrayList<float[]>();
        waterDiscs.add(new float[]{x, 0.42f, z, 1.05f});     // basin pool, sunk 10-12 cm below the rim courses
        waterDiscs.add(new float[]{x, 1.515f, z, 0.36f});    // bowl filled to the brim
        for (int k = 0; k < 4; k++) {                        // overflow: bowl rim -> basin pool
            double a = Math.toRadians(45f + k * 90f);
            waterStreams.add(new float[]{x + (float) Math.sin(a) * 0.40f, 1.47f,
                                         z + (float) Math.cos(a) * 0.40f, 0.40f, 0.055f});
        }
    }

    /** Rotate (x,z) into a house's local frame (about its centre, by -yaw). out = {lx,lz} relative to centre. */
    private static void houseLocal(float[] h, float x, float z, float[] out) {
        float yawH = h.length > 25 ? h[25] : 0f;
        float dx = x - h[0], dz = z - h[1];
        if (yawH != 0f) {
            double a = Math.toRadians(yawH);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            out[0] = dx * ca - dz * sa;         // inverse of the box rotation in rotateBoxAbout
            out[1] = dx * sa + dz * ca;
        } else { out[0] = dx; out[1] = dz; }
    }
    private static final float[] hlTmp = new float[2];   // scratch for build-time queries (single-threaded)

    /** True if (x,z) sits in the approach + swing corridor in front of a house's door (yaw-aware). */
    private static boolean blocksDoor(float[] h, float x, float z) {
        return blocksDoorCrown(h, x, z, 0f);
    }
    private static boolean blocksAnyDoor(List<float[]> houses, float x, float z) {
        for (float[] h : houses) if (blocksDoor(h, x, z)) return true;
        return false;
    }
    /** Like blocksDoor but inflated by a crown radius cr — true if a plant's CROWN would reach the doorway. */
    private static boolean blocksDoorCrown(float[] h, float x, float z, float cr) {
        houseLocal(h, x, z, hlTmp);
        float hw = h[2] * 0.5f, hd = h[3] * 0.5f; int ds = (int) h[5];
        float nx = ds == 2 ? 1f : (ds == 3 ? -1f : 0f), nz = ds == 0 ? 1f : (ds == 1 ? -1f : 0f);
        float fx = nx * hw, fz = nz * hd;                        // door-wall face centre (local)
        float outD = (hlTmp[0] - fx) * nx + (hlTmp[1] - fz) * nz;
        float lat  = (hlTmp[0] - fx) * (-nz) + (hlTmp[1] - fz) * nx;
        float doorW = h.length > 24 ? h[24] : 1.9f;
        return outD > -0.25f - cr && outD < doorW + 0.35f + cr && Math.abs(lat) < doorW * 0.5f + 0.2f + cr;
    }
    private static boolean blocksAnyDoorCrown(List<float[]> houses, float x, float z, float cr) {
        for (float[] h : houses) if (blocksDoorCrown(h, x, z, cr)) return true;
        return false;
    }
    /** True if (x,z) is at least `margin` (Chebyshev) from every already-placed point (lamps / street trees). */
    private static boolean clearOfPts(java.util.List<float[]> pts, float x, float z, float margin) {
        for (float[] p : pts) if (Math.abs(p[0] - x) < margin && Math.abs(p[1] - z) < margin) return false;
        return true;
    }
    /** Gap from (x,z) to the nearest house footprint (>0 = outside), yaw-aware — caps a tree's crown at the wall. */
    private static float houseClearance(List<float[]> houses, float x, float z) {
        float m = 1e9f;
        for (float[] h : houses) {
            houseLocal(h, x, z, hlTmp);
            float dx = Math.abs(hlTmp[0]) - h[2] * 0.5f, dz = Math.abs(hlTmp[1]) - h[3] * 0.5f;
            m = Math.min(m, Math.max(dx, dz));
        }
        return m;
    }
    private static boolean clearOfHouses(List<float[]> houses, float x, float z, float margin) {
        for (float[] h : houses) {
            houseLocal(h, x, z, hlTmp);
            if (Math.abs(hlTmp[0]) < h[2] * 0.5f + margin && Math.abs(hlTmp[1]) < h[3] * 0.5f + margin) return false;
        }
        return true;
    }

    /** Do two yawed rectangles (centre, half sizes, yaw°) overlap when the first is inflated by margin?
     *  Tested by sampling each rect's corners + edge midpoints + centre against the other (build-time only). */
    private static boolean obbOverlap(float cx1, float cz1, float hw1, float hd1, float yaw1,
                                      float cx2, float cz2, float hw2, float hd2, float yaw2, float margin) {
        float rr = (float) Math.hypot(hw1 + margin, hd1 + margin) + (float) Math.hypot(hw2, hd2);
        float dcx = cx2 - cx1, dcz = cz2 - cz1;
        if (dcx * dcx + dcz * dcz > rr * rr) return false;                     // circumradius reject
        return obbPtsInside(cx2, cz2, hw2, hd2, yaw2, cx1, cz1, hw1 + margin, hd1 + margin, yaw1)
            || obbPtsInside(cx1, cz1, hw1 + margin, hd1 + margin, yaw1, cx2, cz2, hw2 + margin * 0.5f, hd2 + margin * 0.5f, yaw2);
    }
    private static boolean obbPtsInside(float cxA, float czA, float hwA, float hdA, float yawA,
                                        float cxB, float czB, float hwB, float hdB, float yawB) {
        double a = Math.toRadians(yawA);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        for (int i = 0; i < 9; i++) {                                          // corners, edge mids, centre of A
            float lx = (i % 3 - 1) * hwA, lz = (i / 3 - 1) * hdA;
            float wx = cxA + lx * ca + lz * sa, wz = czA - lx * sa + lz * ca;  // A-local -> world (box convention)
            if (insideYawXZ(wx, wz, cxB, czB, hwB, hdB, yawB)) return true;
        }
        return false;
    }

    /** March from the door face outward to the nearest asphalt edge: length of this house's front path. */
    private static float pathLenToRoad(float fx, float fz, float dirX, float dirZ) {
        for (float u = 0.4f; u <= 7.5f; u += 0.25f)
            if (roadSd(fx + dirX * u, fz + dirZ * u) < 0.12f) return Math.max(1.0f, u);
        return 2.2f;                                        // no road ahead within reach: a short stub path
    }

    private static void addClutter(List<float[]> L, Random r, float x, float z) {
        int k = r.nextInt(4);
        if (k == 0)      addBarrel(L, x, z);
        else if (k == 1) addCrate(L, r, x, z);
        else if (k == 2) addPlanter(L, x, z);
        else             addWoodpile(L, r, x, z);
    }

    // A wooden barrel: a square + a 45°-rotated twin make a round-ish 8-sided body, three iron
    // hoops (the brighter belly band widest), a lid and a little bung block on top.
    private static void addBarrel(List<float[]> L, float x, float z) {
        L.add(new float[]{x, 0.42f, z, 0.42f, 0.66f, 0.42f, 0.46f, 0.33f, 0.21f, 0f});
        L.add(new float[]{x, 0.42f, z, 0.42f, 0.66f, 0.42f, 0.44f, 0.31f, 0.19f, 45f});
        L.add(new float[]{x, 0.25f, z, 0.50f, 0.07f, 0.50f, 0.24f, 0.22f, 0.20f, 0f});   // lower hoop
        L.add(new float[]{x, 0.43f, z, 0.52f, 0.05f, 0.52f, 0.28f, 0.26f, 0.24f, 0f});   // belly band (widest, catches light)
        L.add(new float[]{x, 0.60f, z, 0.50f, 0.07f, 0.50f, 0.24f, 0.22f, 0.20f, 0f});   // upper hoop
        L.add(new float[]{x, 0.78f, z, 0.34f, 0.05f, 0.34f, 0.40f, 0.28f, 0.18f, 0f});   // lid
        L.add(new float[]{x + 0.09f, 0.815f, z - 0.06f, 0.09f, 0.04f, 0.09f, 0.30f, 0.20f, 0.12f, 0f});  // bung block
    }

    // A crate: lighter plank panels framed by four dark corner posts plus a bottom rail and a lid.
    // The corner posts are placed at the body's rotated corners (and carry the same yaw) so the frame stays aligned.
    private static void addCrate(List<float[]> L, Random r, float x, float z) {
        float yaw = r.nextInt(40) - 20f;
        double a = Math.toRadians(yaw), ca = Math.cos(a), sa = Math.sin(a);
        L.add(new float[]{x, 0.26f, z, 0.52f, 0.52f, 0.52f, 0.58f, 0.46f, 0.30f, yaw});   // plank body
        L.add(new float[]{x, 0.05f, z, 0.58f, 0.09f, 0.58f, 0.34f, 0.24f, 0.15f, yaw});   // bottom rail
        L.add(new float[]{x, 0.54f, z, 0.56f, 0.06f, 0.56f, 0.36f, 0.26f, 0.17f, yaw});   // top lid
        float c = 0.24f;                                                                  // corner posts at the body's rotated corners
        for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) {
            float ox = (float) (c * sx * ca + c * sz * sa), oz = (float) (-c * sx * sa + c * sz * ca);
            L.add(new float[]{x + ox, 0.28f, z + oz, 0.10f, 0.56f, 0.10f, 0.34f, 0.24f, 0.15f, yaw});
        }
    }

    // A stone planter: a rimmed pot of dark soil with three leafy lobes and a scatter of flowers (axis-aligned, so offsets are safe).
    private static void addPlanter(List<float[]> L, float x, float z) {
        L.add(new float[]{x, 0.24f, z, 0.56f, 0.44f, 0.50f, 0.52f, 0.49f, 0.45f, 0f});           // stone pot
        L.add(new float[]{x, 0.46f, z, 0.62f, 0.07f, 0.56f, 0.58f, 0.55f, 0.51f, 0f});           // rim
        L.add(new float[]{x, 0.49f, z, 0.48f, 0.05f, 0.42f, 0.20f, 0.14f, 0.10f, 0f});           // dark soil
        L.add(new float[]{x, 0.62f, z, 0.34f, 0.26f, 0.30f, 0.27f, 0.43f, 0.25f, 0f});           // foliage — centre mound
        L.add(new float[]{x - 0.16f, 0.57f, z + 0.04f, 0.24f, 0.20f, 0.24f, 0.31f, 0.47f, 0.29f, 0f});   // leafy lobe (l)
        L.add(new float[]{x + 0.16f, 0.58f, z - 0.05f, 0.24f, 0.18f, 0.24f, 0.25f, 0.41f, 0.24f, 0f});   // leafy lobe (r)
        L.add(new float[]{x - 0.12f, 0.71f, z + 0.07f, 0.09f, 0.09f, 0.09f, 0.86f, 0.32f, 0.30f, 0f});   // red bloom
        L.add(new float[]{x + 0.11f, 0.72f, z - 0.04f, 0.09f, 0.09f, 0.09f, 0.93f, 0.82f, 0.33f, 0f});   // yellow bloom
        L.add(new float[]{x + 0.02f, 0.73f, z + 0.13f, 0.08f, 0.08f, 0.08f, 0.82f, 0.50f, 0.78f, 0f});   // violet bloom
    }

    // A woodpile: three stacked logs with pale end-grain caps, a short splitting log on top and a
    // leaning axe-handle stick — reads as a working pile, not three brown bars.
    private static void addWoodpile(List<float[]> L, Random r, float x, float z) {
        float yaw = r.nextInt(30) - 15f;
        double ya = Math.toRadians(yaw);
        float ca = (float) Math.cos(ya), sa = (float) Math.sin(ya);
        L.add(new float[]{x, 0.16f, z, 0.95f, 0.20f, 0.24f, 0.50f, 0.36f, 0.22f, yaw});
        L.add(new float[]{x, 0.37f, z, 0.95f, 0.20f, 0.24f, 0.46f, 0.33f, 0.20f, yaw});
        L.add(new float[]{x, 0.30f, z + 0.26f, 0.95f, 0.20f, 0.24f, 0.48f, 0.35f, 0.21f, yaw});
        for (int side = -1; side <= 1; side += 2) {           // pale end-grain discs on the two long logs
            float ox = 0.47f * side * ca, oz = -0.47f * side * sa;
            L.add(new float[]{x + ox, 0.16f, z + oz, 0.045f, 0.16f, 0.20f, 0.72f, 0.62f, 0.46f, yaw});
            L.add(new float[]{x + ox, 0.37f, z + oz, 0.045f, 0.16f, 0.20f, 0.70f, 0.60f, 0.44f, yaw});
        }
        L.add(new float[]{x + 0.12f * ca, 0.53f, z - 0.12f * sa, 0.26f, 0.13f, 0.20f, 0.55f, 0.42f, 0.27f, yaw});  // chopping block on top
        L.add(new float[]{x - 0.28f * ca, 0.50f, z + (0.26f + 0.28f * sa) * 1f, 0.06f, 0.55f, 0.06f, 0.38f, 0.28f, 0.17f, yaw + 24f});  // leaning stick
    }

    // A street lamp: stepped stone plinth, fluted post with a mid ring, lantern cage bars around the
    // warm glass, a pyramid-ish cap and a finial ball (head parts sit overhead -> no collision).
    private static void addLamp(List<float[]> L, float x, float z) {
        L.add(new float[]{x, 0.05f, z, 0.44f, 0.10f, 0.44f, 0.17f, 0.17f, 0.19f, 0f});   // plinth step
        L.add(new float[]{x, 0.18f, z, 0.32f, 0.20f, 0.32f, 0.20f, 0.20f, 0.22f, 45f});  // base (turned octagon)
        L.add(new float[]{x, 1.55f, z, 0.12f, 2.60f, 0.12f, 0.23f, 0.23f, 0.26f, 0f});   // post
        L.add(new float[]{x, 1.55f, z, 0.10f, 2.50f, 0.10f, 0.20f, 0.20f, 0.23f, 45f});  // flute (8-sided shaft)
        L.add(new float[]{x, 2.05f, z, 0.18f, 0.06f, 0.18f, 0.17f, 0.17f, 0.19f, 45f});  // mid ring
        L.add(new float[]{x, 2.92f, z, 0.30f, 0.10f, 0.30f, 0.20f, 0.20f, 0.22f, 0f});   // collar
        L.add(new float[]{x, 3.14f, z, 0.26f, 0.34f, 0.26f, 0.99f, 0.90f, 0.62f, 0f});   // warm lantern glass
        L.add(new float[]{x, 3.14f, z, 0.30f, 0.30f, 0.30f, 0.16f, 0.16f, 0.18f, 45f});  // cage bars catch the corners
        L.add(new float[]{x, 3.38f, z, 0.36f, 0.12f, 0.36f, 0.19f, 0.19f, 0.21f, 0f});   // cap
        L.add(new float[]{x, 3.47f, z, 0.24f, 0.08f, 0.24f, 0.18f, 0.18f, 0.20f, 45f});  // cap taper
        L.add(new float[]{x, 3.56f, z, 0.09f, 0.10f, 0.09f, 0.24f, 0.24f, 0.27f, 0f});   // finial ball
    }

    // A park bench: two solid end frames, a slatted seat, and a two-rail backrest carried on vertical end posts.
    private static void addBench(List<float[]> L, float x, float z, int dir) {
        boolean ax = (dir == 0 || dir == 1);                    // bench length runs along X for +z/-z fronts
        float ex = ax ? 0.62f : 0f, ez = ax ? 0f : 0.62f;       // end-frame offset along the length
        float esx = ax ? 0.10f : 0.50f, esz = ax ? 0.50f : 0.10f;
        L.add(new float[]{x - ex, 0.24f, z - ez, esx, 0.48f, esz, 0.34f, 0.25f, 0.16f, 0f});    // end frames
        L.add(new float[]{x + ex, 0.24f, z + ez, esx, 0.48f, esz, 0.34f, 0.25f, 0.16f, 0f});
        L.add(new float[]{x, 0.46f, z, ax ? 1.45f : 0.48f, 0.09f, ax ? 0.48f : 1.45f, 0.48f, 0.34f, 0.22f, 0f});  // seat
        float bX = 0f, bZ = 0f;                                 // backrest sits behind the seat (toward the wall)
        if (dir == 0) bZ = -0.18f; else if (dir == 1) bZ = 0.18f; else if (dir == 2) bX = -0.18f; else bX = 0.18f;
        float ep = ax ? 0.58f : 0f, epz = ax ? 0f : 0.58f;      // upright posts at each end carry the backrest off the seat
        float psx = ax ? 0.09f : 0.10f, psz = ax ? 0.10f : 0.09f;
        L.add(new float[]{x - ep + bX, 0.70f, z - epz + bZ, psx, 0.50f, psz, 0.40f, 0.28f, 0.18f, 0f});  // back posts
        L.add(new float[]{x + ep + bX, 0.70f, z + epz + bZ, psx, 0.50f, psz, 0.40f, 0.28f, 0.18f, 0f});
        float rw = ax ? 1.45f : 0.06f, rd = ax ? 0.06f : 1.45f;
        L.add(new float[]{x + bX, 0.68f, z + bZ, rw, 0.10f, rd, 0.48f, 0.34f, 0.22f, 0f});       // back rails
        L.add(new float[]{x + bX, 0.88f, z + bZ, rw, 0.10f, rd, 0.48f, 0.34f, 0.22f, 0f});
        // armrests at both ends (bridging backrest to seat front) + a pale seat-edge slat in front
        if (ax) {
            L.add(new float[]{x - 0.58f, 0.575f, z + bZ * 0.5f, 0.07f, 0.045f, 0.30f, 0.52f, 0.38f, 0.25f, 0f});
            L.add(new float[]{x + 0.58f, 0.575f, z + bZ * 0.5f, 0.07f, 0.045f, 0.30f, 0.52f, 0.38f, 0.25f, 0f});
            L.add(new float[]{x, 0.515f, z - bZ * 1.1f, 1.40f, 0.035f, 0.045f, 0.56f, 0.42f, 0.28f, 0f});
        } else {
            L.add(new float[]{x + bX * 0.5f, 0.575f, z - 0.58f, 0.30f, 0.045f, 0.07f, 0.52f, 0.38f, 0.25f, 0f});
            L.add(new float[]{x + bX * 0.5f, 0.575f, z + 0.58f, 0.30f, 0.045f, 0.07f, 0.52f, 0.38f, 0.25f, 0f});
            L.add(new float[]{x - bX * 1.1f, 0.515f, z, 0.045f, 0.035f, 1.40f, 0.56f, 0.42f, 0.28f, 0f});
        }
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
        L.add(new float[]{x, 2.06f, z + 0.66f, 2.10f, 0.16f, 0.05f, ar * 0.80f, ag * 0.80f, ab * 0.80f, 0f});  // scalloped hem
        L.add(new float[]{x, 0.30f, z + 0.52f, 1.80f, 0.50f, 0.06f, 0.44f, 0.33f, 0.21f, 0f});  // apron board under the counter
        // goods on the counter: two produce crates + a stacked cheese/bread board (front = +z, spun by the caller)
        L.add(new float[]{x - 0.55f, 1.20f, z + 0.10f, 0.42f, 0.16f, 0.34f, 0.40f, 0.29f, 0.18f, 0f});  // crate L
        L.add(new float[]{x - 0.55f, 1.30f, z + 0.10f, 0.34f, 0.10f, 0.26f, 0.82f, 0.31f, 0.24f, 0f});  //   tomatoes
        L.add(new float[]{x + 0.45f, 1.20f, z - 0.06f, 0.42f, 0.16f, 0.34f, 0.40f, 0.29f, 0.18f, 8f});  // crate R
        L.add(new float[]{x + 0.45f, 1.30f, z - 0.06f, 0.34f, 0.10f, 0.26f, 0.42f, 0.62f, 0.26f, 8f});  //   greens
        L.add(new float[]{x + 0.02f, 1.17f, z + 0.28f, 0.30f, 0.07f, 0.22f, 0.62f, 0.48f, 0.28f, -6f}); // bread board
        L.add(new float[]{x + 0.02f, 1.24f, z + 0.28f, 0.20f, 0.08f, 0.14f, 0.86f, 0.68f, 0.34f, -6f}); //   loaf
        L.add(new float[]{x - 0.90f, 1.62f, z + 0.50f, 0.05f, 0.30f, 0.05f, 0.30f, 0.24f, 0.20f, 0f});  // hanging scale arm
    }

    // A roofed stone well: a square stone kerb ring, two posts and a little gable roof (roof overhead -> no collision).
    private static void addWell(List<float[]> L, float x, float z) {
        L.add(new float[]{x, 0.40f, z + 0.55f, 1.20f, 0.80f, 0.18f, 0.55f, 0.54f, 0.51f, 0f});  // kerb +z
        L.add(new float[]{x, 0.40f, z - 0.55f, 1.20f, 0.80f, 0.18f, 0.55f, 0.54f, 0.51f, 0f});  // kerb -z
        L.add(new float[]{x + 0.55f, 0.40f, z, 0.18f, 0.80f, 1.20f, 0.52f, 0.51f, 0.48f, 0f});  // kerb +x
        L.add(new float[]{x - 0.55f, 0.40f, z, 0.18f, 0.80f, 1.20f, 0.52f, 0.51f, 0.48f, 0f});  // kerb -x
        L.add(new float[]{x - 0.50f, 1.50f, z, 0.10f, 1.40f, 0.10f, 0.46f, 0.33f, 0.21f, 0f});  // post
        L.add(new float[]{x + 0.50f, 1.50f, z, 0.10f, 1.40f, 0.10f, 0.46f, 0.33f, 0.21f, 0f});  // post
        L.add(new float[]{x, 1.72f, z, 1.10f, 0.16f, 0.16f, 0.42f, 0.30f, 0.19f, 0f});          // windlass drum
        L.add(new float[]{x, 1.72f, z, 1.06f, 0.20f, 0.12f, 0.38f, 0.27f, 0.17f, 45f});         //   (rounded by the twin)
        L.add(new float[]{x + 0.62f, 1.62f, z + 0.12f, 0.05f, 0.26f, 0.05f, 0.24f, 0.22f, 0.20f, 0f});  // crank arm
        L.add(new float[]{x + 0.62f, 1.50f, z + 0.20f, 0.05f, 0.05f, 0.18f, 0.30f, 0.26f, 0.22f, 0f});  // crank handle
        L.add(new float[]{x, 1.28f, z, 0.035f, 0.75f, 0.035f, 0.60f, 0.54f, 0.42f, 0f});        // rope down the shaft
        L.add(new float[]{x, 0.94f, z, 0.26f, 0.20f, 0.26f, 0.34f, 0.26f, 0.18f, 22.5f});       // hanging bucket
        L.add(new float[]{x, 1.05f, z, 0.30f, 0.04f, 0.30f, 0.22f, 0.20f, 0.18f, 22.5f});       //   bucket rim hoop
        L.add(new float[]{x, 1.95f, z, 0.10f, 0.10f, 0.70f, 0.30f, 0.24f, 0.20f, 0f});          // crossbeam
        L.add(new float[]{x, 2.25f, z, 1.50f, 0.12f, 0.95f, 0.34f, 0.27f, 0.22f, 0f});          // roof
        L.add(new float[]{x, 2.36f, z, 1.10f, 0.12f, 0.60f, 0.31f, 0.245f, 0.20f, 0f});         // upper roof course (gable-ish)
        L.add(new float[]{x, 2.47f, z, 0.62f, 0.12f, 0.28f, 0.28f, 0.22f, 0.18f, 0f});          // ridge cap
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
        addBuilding(L, doors, cx, cz, w, d, h, doorSide, roofStyle, r, g, b, chimney, doorW, doorH, dr, dg, db, doorStyle, material, 0f);
    }

    private static void addBuilding(List<float[]> L, List<float[]> doors, float cx, float cz, float w, float d, float h,
                                    int doorSide, int roofStyle, float r, float g, float b, int chimney,
                                    float doorW, float doorH, float dr, float dg, float db, int doorStyle, int material, float yaw) {
        // no window info -> solid walls (used by editor stand-ins / legacy callers). The gameplay + .lvl paths
        // call the deeper overload with dens/storeys so their walls get real cut openings.
        addBuilding(L, doors, cx, cz, w, d, h, doorSide, roofStyle, r, g, b, chimney,
                    doorW, doorH, dr, dg, db, doorStyle, material, yaw, 0, 1f, 1, 0.3f, false);
    }

    /** Deepest overload: yaw (degrees) spins the whole house about (cx,cz); dens/wsc/storeys/foundH describe the
     *  window layout and, when cutHoles, cut REAL openings through the walls (so the interior shows through). */
    private static void addBuilding(List<float[]> L, List<float[]> doors, float cx, float cz, float w, float d, float h,
                                    int doorSide, int roofStyle, float r, float g, float b, int chimney,
                                    float doorW, float doorH, float dr, float dg, float db, int doorStyle, int material, float yaw,
                                    int dens, float wsc, int storeys, float foundH, boolean cutHoles) {
        int boxStart = L.size(), doorStart = doors.size();
        float t = 0.3f;
        doorW = clamp(doorW, 0.8f, Math.min(w, d) - 0.6f);     // keep the gap inside the wall
        doorH = clamp(doorH, 1.6f, h - 0.3f);                  // keep the lintel under the eave
        long hSeed = houseSeedOf(cx, cz);
        buildWallSide(L, cx, cz + d * 0.5f, w, t, h, true,  doorSide == 0, doorW, doorH, 0, dens, wsc, storeys, foundH, cutHoles, hSeed, r, g, b, material);   // +z
        buildWallSide(L, cx, cz - d * 0.5f, w, t, h, true,  doorSide == 1, doorW, doorH, 1, dens, wsc, storeys, foundH, cutHoles, hSeed, r, g, b, material);   // -z
        buildWallSide(L, cx + w * 0.5f, cz, d, t, h, false, doorSide == 2, doorW, doorH, 2, dens, wsc, storeys, foundH, cutHoles, hSeed, r, g, b, material);   // +x
        buildWallSide(L, cx - w * 0.5f, cz, d, t, h, false, doorSide == 3, doorW, doorH, 3, dens, wsc, storeys, foundH, cutHoles, hSeed, r, g, b, material);   // -x
        L.add(new float[]{cx, h + 0.15f, cz, w + t, 0.3f, d + t, r * 0.9f, g * 0.9f, b * 0.95f}); // roof
        float dcx, dcz; int axis;                          // openable door leaf in the gap
        if (doorSide == 0)      { dcx = cx; dcz = cz + d * 0.5f; axis = 0; }
        else if (doorSide == 1) { dcx = cx; dcz = cz - d * 0.5f; axis = 0; }
        else if (doorSide == 2) { dcx = cx + w * 0.5f; dcz = cz; axis = 1; }
        else                    { dcx = cx - w * 0.5f; dcz = cz; axis = 1; }
        float swing = (doorSide == 1 || doorSide == 2) ? -1f : 1f;   // sign that makes the leaf swing OUTWARD (away from interior furniture)
        float leafGap = 0.06f;                                       // small gap under the leaf so it clears the doorstep when it swings
        doors.add(new float[]{dcx, (leafGap + doorH) * 0.5f, dcz, doorW * 0.5f - 0.05f, (doorH - leafGap) * 0.5f, 0.07f, axis, 1f,
                dr, dg, db, doorStyle, swing});
        // door surround (architrave: jambs + lintel), slightly proud, in a light stone trim — frames the opening
        // a low stone doorstep only (no proud architrave bars — they read as beams left behind when the door swings)
        float jb = 0.13f;
        float nnx = doorSide == 2 ? 1 : (doorSide == 3 ? -1 : 0), nnz = doorSide == 0 ? 1 : (doorSide == 1 ? -1 : 0);
        if (axis == 0) {
            float fcz = dcz + nnz * (t * 0.5f - 0.02f);
            L.add(new float[]{dcx, 0.015f, fcz, doorW + jb * 2f, 0.03f, t + 0.06f, 0.55f, 0.53f, 0.49f}); // flush low doorstep
        } else {
            float fcx = dcx + nnx * (t * 0.5f - 0.02f);
            L.add(new float[]{fcx, 0.015f, dcz, t + 0.06f, 0.03f, doorW + jb * 2f, 0.55f, 0.53f, 0.49f}); // flush low doorstep
        }
        // wooden door canopy over non-shop entrances: a flat slab projecting over the doorway in a darkened
        // door colour. Collision-safe: with the default 2.3 m door its underside sits at ~2.5 m, above the
        // 1.8 m overhead-ignore band of collide(); the 1.95 gate skips only extreme low-door custom levels.
        float canY = Math.min(doorH + 0.26f, h - 0.12f), canProj = 0.55f;
        if (doorStyle != 3 && canY - 0.05f > 1.95f) {
            if (axis == 0) L.add(new float[]{dcx, canY, dcz + nnz * (t * 0.5f + canProj * 0.5f), doorW + 0.5f, 0.09f, canProj, dr * 0.72f, dg * 0.72f, db * 0.72f, 0f});
            else           L.add(new float[]{dcx + nnx * (t * 0.5f + canProj * 0.5f), canY, dcz, canProj, 0.09f, doorW + 0.5f, dr * 0.72f, dg * 0.72f, db * 0.72f, 0f});
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
            // crown slab + clay pot: the textbook chimney silhouette. Both sit 2.6 m+ above the roofline,
            // far inside collide()'s overhead-ignore band -- zero collision change.
            L.add(new float[]{cx + w * 0.28f, h + 2.66f, cz - d * 0.28f, 0.46f, 0.12f, 0.46f, 0.60f, 0.58f, 0.54f, 0f});
            L.add(new float[]{cx + w * 0.28f, h + 2.81f, cz - d * 0.28f, 0.18f, 0.20f, 0.18f, 0.26f, 0.21f, 0.18f, 0f});
        }
        if (yaw != 0f) {                                   // spin every box + the door of THIS house about (cx,cz)
            for (int i = boxStart; i < L.size(); i++) rotateBoxAbout(L, i, cx, cz, yaw);
            for (int i = doorStart; i < doors.size(); i++) rotateDoorAbout(doors, i, cx, cz, yaw);
        }
    }

    /** Rotate box L[idx] about (px,pz) by yawDeg and tag it with that yaw so it renders + collides oriented.
     *  Boxes shorter than the {…,yaw,renderSkip,material} layout are padded (pad = 0 = visible, plaster). */
    private static void rotateBoxAbout(List<float[]> L, int idx, float px, float pz, float yawDeg) {
        float[] b = L.get(idx);
        double a = Math.toRadians(yawDeg);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float x = b[0] - px, z = b[2] - pz;
        if (b.length < 12) { float[] nb = new float[12]; System.arraycopy(b, 0, nb, 0, b.length); b = nb; L.set(idx, nb); }
        b[0] = px + x * ca + z * sa;                       // Ry(yaw): new centre
        b[2] = pz - x * sa + z * ca;
        b[9] = yawDeg;                                      // visual + collision orientation
    }

    /** Rotate the door leaf record about (px,pz) and stash the house yaw in slot 13 (drawn + collided oriented). */
    private static void rotateDoorAbout(List<float[]> doors, int idx, float px, float pz, float yawDeg) {
        float[] dd = doors.get(idx);
        double a = Math.toRadians(yawDeg);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float x = dd[0] - px, z = dd[2] - pz;
        float[] nd = new float[14];
        System.arraycopy(dd, 0, nd, 0, Math.min(dd.length, 13));
        nd[0] = px + x * ca + z * sa;
        nd[2] = pz - x * sa + z * ca;
        nd[13] = yawDeg;
        doors.set(idx, nd);
    }

    /** Rotate a span of baked vertices (pos+normal, stride 8) about (px,pz) by yawDeg — used to spin a house's
     *  roof / windows / bands / interior with its walls. No-op when yawDeg=0 so unrotated houses are untouched. */
    private static void rotateVertSpanY(float[] d, int firstVert, int vertCount, float px, float pz, float yawDeg) {
        if (yawDeg == 0f) return;
        double a = Math.toRadians(yawDeg);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        for (int v = 0; v < vertCount; v++) {
            int o = (firstVert + v) * 8;
            float x = d[o] - px, z = d[o + 2] - pz;
            d[o]     = px + x * ca + z * sa;
            d[o + 2] = pz - x * sa + z * ca;
            float nx = d[o + 3], nz = d[o + 5];
            d[o + 3] = nx * ca + nz * sa;
            d[o + 5] = -nx * sa + nz * ca;
        }
    }

    // ---- real window openings: one shared layout function feeds BOTH the wall cutter and the pane placer ----

    /** Geometry-derived house seed so the window-omission pattern is reproducible by any caller. */
    static long houseSeedOf(float cx, float cz) {
        return ((long) Float.floatToIntBits(cx)) * 0x9E3779B1L ^ (Float.floatToIntBits(cz) & 0xffffffffL);
    }
    /** Matches furnishHouse's early-return conditions — a house only gets a real interior (hence cut holes)
     *  when it is big + tall enough to furnish. */
    static boolean houseFurnishable(float w, float d, float h) {
        float inx = w * 0.5f - 0.28f, inz = d * 0.5f - 0.28f;
        if (inx < 0.7f || inz < 0.7f) return false;
        return Math.min(2.55f, h - 0.22f) >= 2.0f;
    }
    /** Deterministic per-column omission (splitmix64 on the geometry seed) — no shared advancing Random, so
     *  the wall cutter and the pane placer independently agree on which columns are dropped. */
    static boolean windowOmit(long seed, int side, int fi, int ci, float omit) {
        long z = seed ^ ((long) (side + 1) * 0x9E3779B97F4A7C15L)
                      ^ ((long) (fi + 1)   * 0xC2B2AE3D27D4EB4FL)
                      ^ ((long) (ci + 1)   * 0x165667B19E3779F9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (float) ((z >>> 11) * 0x1.0p-53) < omit;
    }
    /** The single source of truth for where windows sit on a wall of span `span`, height `h`. Reproduces the
     *  old wallWindows column/row maths bit-for-bit; appends {t, wy, winW, winH, floor, reachable} per opening.
     *  `side`: +z=0,-z=1,+x=2,-x=3 (drives the omission hash — MUST match across all callers). */
    static void windowSlots(float span, float h, int dens, float wsc, int storeys, float foundH,
                            boolean doorWall, long houseSeed, int side, java.util.List<float[]> out) {
        wsc = clamp(wsc <= 0f ? 1f : wsc, 0.5f, 1.6f);
        if (storeys < 1) storeys = 1;
        if (storeys > 8) storeys = 8;
        float floorH = h / storeys;
        float winW = 0.85f * wsc, winH = Math.min(1.05f * wsc, floorH * 0.62f);
        float colDiv, omit;
        if (dens == 1)      { colDiv = 2.3f; omit = 0.10f; }
        else if (dens >= 3) { colDiv = 1.25f; omit = 0.04f; }
        else                { colDiv = 1.7f; omit = 0.10f; }
        int cols = Math.max(1, (int) ((span - 0.6f) / colDiv));
        float colGap = span / cols;
        for (int fi = 0; fi < storeys; fi++) {
            float wy = floorH * (fi + 0.5f);
            if (wy + winH * 0.5f > h - 0.2f) continue;               // top floor clears the eave (no hole in the gable)
            if (wy - winH * 0.5f < foundH + 0.12f) wy = foundH + 0.12f + winH * 0.5f;   // clear the plinth
            for (int ci = 0; ci < cols; ci++) {
                float t = -span * 0.5f + colGap * (ci + 0.5f);
                if (doorWall && Math.abs(t) < 1.4f * Math.max(1f, wsc) && wy - winH * 0.5f < 2.45f) continue;
                if (windowOmit(houseSeed, side, fi, ci, omit)) continue;
                boolean reach = (fi == 0) && (wy - winH * 0.5f <= 1.5f);   // ground floor + reachable sill -> openable sash
                out.add(new float[]{t, wy, winW, winH, fi, reach ? 1f : 0f});
            }
        }
    }

    /** Build one wall side as a set of solid boxes tiling the rectangle EXCEPT its openings (door + windows).
     *  A provably-complete Y-band / X-interval decomposition: cut Y at every opening edge, then per band take
     *  the union of the covering openings' t-intervals and emit the complement as solid segments. That yields
     *  the below-sill spandrel, the header/lintel strips, inter-storey strips and mullion piers for free — and
     *  never leaves a gap or an overlap. hole = {t0,t1,y0,y1} in wall-local coords (t along the wall). */
    static void addWallHoles(List<float[]> L, float wcx, float wcz, float span, float thick, float h,
                             boolean spanX, float[][] holes, float r, float g, float b, int material) {
        float half = span * 0.5f;
        java.util.ArrayList<Float> ys = new java.util.ArrayList<Float>();
        ys.add(0f); ys.add(h);
        for (float[] ho : holes) { ys.add(clamp(ho[2], 0f, h)); ys.add(clamp(ho[3], 0f, h)); }
        java.util.Collections.sort(ys);
        java.util.ArrayList<Float> yl = new java.util.ArrayList<Float>();
        for (float y : ys) if (yl.isEmpty() || y - yl.get(yl.size() - 1) > 1e-3f) yl.add(y);
        for (int bi = 0; bi + 1 < yl.size(); bi++) {
            float yA = yl.get(bi), yB = yl.get(bi + 1), bandH = yB - yA;
            if (bandH < 1e-3f) continue;
            float midY = (yA + yB) * 0.5f;
            java.util.ArrayList<float[]> iv = new java.util.ArrayList<float[]>();
            for (float[] ho : holes)
                if (ho[2] <= yA + 1e-3f && ho[3] >= yB - 1e-3f) {     // hole fully covers this band
                    float t0 = Math.max(-half, ho[0]), t1 = Math.min(half, ho[1]);
                    if (t1 > t0) iv.add(new float[]{t0, t1});
                }
            java.util.Collections.sort(iv, new java.util.Comparator<float[]>() {
                public int compare(float[] a, float[] bb) { return Float.compare(a[0], bb[0]); }
            });
            java.util.ArrayList<float[]> un = new java.util.ArrayList<float[]>();
            for (float[] i2 : iv) {                                   // union of the void intervals (no thin double piers)
                if (!un.isEmpty() && i2[0] <= un.get(un.size() - 1)[1] + 1e-4f)
                    un.get(un.size() - 1)[1] = Math.max(un.get(un.size() - 1)[1], i2[1]);
                else un.add(new float[]{i2[0], i2[1]});
            }
            float cursor = -half;                                     // complement of the union = solid segments
            for (float[] u2 : un) { emitWallSeg(L, wcx, wcz, cursor, u2[0], midY, bandH, thick, spanX, r, g, b, material); cursor = Math.max(cursor, u2[1]); }
            emitWallSeg(L, wcx, wcz, cursor, half, midY, bandH, thick, spanX, r, g, b, material);
        }
    }
    private static void emitWallSeg(List<float[]> L, float wcx, float wcz, float s0, float s1, float midY,
                                    float bandH, float thick, boolean spanX, float r, float g, float b, int material) {
        float segLen = s1 - s0;
        if (segLen < 0.05f) return;                                   // sliver guard: no zero-width z-fighting/junk-collision boxes
        float midT = (s0 + s1) * 0.5f;
        if (spanX) L.add(new float[]{wcx + midT, midY, wcz, segLen, bandH, thick, r, g, b, 0f, 0f, (float) material});
        else       L.add(new float[]{wcx, midY, wcz + midT, thick, bandH, segLen, r, g, b, 0f, 0f, (float) material});
    }
    /** One wall side: gather its openings (the door on the door side + real window holes when the house is
     *  furnished) and tile the solid remainder. `side` matches windowSlots (+z=0,-z=1,+x=2,-x=3). */
    private static void buildWallSide(List<float[]> L, float wcx, float wcz, float span, float thick, float h,
                                      boolean spanX, boolean isDoor, float doorW, float doorH, int side,
                                      int dens, float wsc, int storeys, float foundH, boolean cutHoles,
                                      long houseSeed, float r, float g, float b, int material) {
        java.util.ArrayList<float[]> holes = new java.util.ArrayList<float[]>();
        if (isDoor) holes.add(new float[]{-doorW * 0.5f, doorW * 0.5f, 0f, doorH});
        if (cutHoles && dens > 0) {
            java.util.ArrayList<float[]> slots = new java.util.ArrayList<float[]>();
            windowSlots(span, h, dens, wsc, storeys, foundH, isDoor, houseSeed, side, slots);
            for (float[] s : slots)
                holes.add(new float[]{s[0] - s[2] * 0.5f, s[0] + s[2] * 0.5f, s[1] - s[3] * 0.5f, s[1] + s[3] * 0.5f});
        }
        addWallHoles(L, wcx, wcz, span, thick, h, spanX, holes.toArray(new float[0][]), r, g, b, material);
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
        // 6 tris + 5 box6 (ridge cap + 4 soffit boxes closing the overhang ring) per house
        float[] d = new float[houseRects.size() * (6 * 3 + 5 * 36) * 8 + 64];
        int o = 0, gi = 0;
        roofGroups = new float[houseRects.size()][];
        for (float[] hh : houseRects) {
            int start = o / 8;
            o = roofPrism(d, o, hh[0], hh[1], hh[2], hh[3], hh[4], hh[9], hh[10]);     // cx cz w d h pitch overhang
            rotateVertSpanY(d, start, o / 8 - start, hh[0], hh[1], hh.length > 25 ? hh[25] : 0f);   // spin with the house
            // Guard against washed-out almost-white roofs: pull a BRIGHT + LOW-SATURATION roof colour toward
            // terracotta. Terracotta and grey slate (either saturated or not bright) pass through unchanged.
            float rr = hh[6], rg = hh[7], rb = hh[8];
            float rmx = Math.max(rr, Math.max(rg, rb)), rmn = Math.min(rr, Math.min(rg, rb));
            float rsat = rmx > 0.01f ? (rmx - rmn) / rmx : 0f;
            float rt = clamp((rmx - 0.70f) / 0.25f, 0f, 1f) * clamp((0.22f - rsat) / 0.22f, 0f, 1f);
            roofGroups[gi++] = new float[]{start, o / 8 - start, rr + (0.69f - rr) * rt, rg + (0.31f - rg) * rt, rb + (0.16f - rb) * rt};
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
            // ridge cap + soffit boxes: the soffits CLOSE the whole overhang ring between the wall top and
            // the roof edge. Culling is off, so from below the bare roof planes rendered sky-bright (lit by
            // their upward normals) -- the "gap" between roof and house. The soffit's down-facing underside
            // shades correctly and reads as a real closed eave.
            o = box6(d, o, cx, ridgeY + 0.015f, cz, hw, 0.05f, 0.10f);
            if (ov > 0.10f) {
                float sHalf = (ov + 0.045f) * 0.5f, sOff = (ov - 0.055f) * 0.5f, sy = baseY - 0.075f;
                o = box6(d, o, cx, sy, cz + dd * 0.5f + sOff, hw, 0.075f, sHalf);          // long-eave soffits
                o = box6(d, o, cx, sy, cz - dd * 0.5f - sOff, hw, 0.075f, sHalf);
                o = box6(d, o, cx + w * 0.5f + sOff, sy, cz, sHalf, 0.075f, hd);           // gable-end soffits
                o = box6(d, o, cx - w * 0.5f - sOff, sy, cz, sHalf, 0.075f, hd);
            }
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
            o = box6(d, o, cx, ridgeY + 0.015f, cz, 0.10f, 0.05f, hd);          // ridge cap
            if (ov > 0.10f) {                                                    // soffits close the overhang ring
                float sHalf = (ov + 0.045f) * 0.5f, sOff = (ov - 0.055f) * 0.5f, sy = baseY - 0.075f;
                o = box6(d, o, cx + w * 0.5f + sOff, sy, cz, sHalf, 0.075f, hd);           // long-eave soffits
                o = box6(d, o, cx - w * 0.5f - sOff, sy, cz, sHalf, 0.075f, hd);
                o = box6(d, o, cx, sy, cz + dd * 0.5f + sOff, hw, 0.075f, sHalf);          // gable-end soffits
                o = box6(d, o, cx, sy, cz - dd * 0.5f - sOff, hw, 0.075f, sHalf);
            }
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
        float[] td = new float[3200000];   // 4 trim boxes per window now (sill + lintel + 2 jambs)
        float[] rv = new float[1200000];   // dark recessed interior behind every pane
        int[] off = {0, 0, 0};
        java.util.List<float[]> winG = new java.util.ArrayList<float[]>();
        for (float[] hh : houseRects) {
            float cx = hh[0], cz = hh[1], w = hh[2], dd = hh[3], h = hh[4];
            int ds = (int) hh[5], dens = (int) hh[11], storeys = Math.round(hh[23]);
            float wsc = hh[12], foundH = hh[16];
            if (dens == 0) continue;                       // "none" -> this house has no windows
            // Only the base-town/.lvl walls are actually cut (addWallHoles); the editor overlay builds SOLID
            // walls, so there it must keep the fake reveal panel + bake every pane (no interactive sash).
            boolean cut = houseCuts(hh) && !bakingOverlay;
            boolean skipSash = cut;                         // reachable panes are drawn as animated sashes instead
            long hSeed = houseSeedOf(cx, cz);
            int wstart = off[0] / 8, tstart = off[1] / 8, rstart = off[2] / 8;
            wallWindows(wd, td, rv, off, cx, cz + dd * 0.5f, w, h, 0, 1, ds == 0, hSeed, 0, dens, wsc, storeys, foundH, cut, skipSash);
            wallWindows(wd, td, rv, off, cx, cz - dd * 0.5f, w, h, 0, -1, ds == 1, hSeed, 1, dens, wsc, storeys, foundH, cut, skipSash);
            wallWindows(wd, td, rv, off, cx + w * 0.5f, cz, dd, h, 1, 1, ds == 2, hSeed, 2, dens, wsc, storeys, foundH, cut, skipSash);
            wallWindows(wd, td, rv, off, cx - w * 0.5f, cz, dd, h, 1, -1, ds == 3, hSeed, 3, dens, wsc, storeys, foundH, cut, skipSash);
            float yaw = hh.length > 25 ? hh[25] : 0f;      // spin panes + sills + reveals with the house
            if (yaw != 0f) {
                rotateVertSpanY(wd, wstart, off[0] / 8 - wstart, cx, cz, yaw);
                rotateVertSpanY(td, tstart, off[1] / 8 - tstart, cx, cz, yaw);
                rotateVertSpanY(rv, rstart, off[2] / 8 - rstart, cx, cz, yaw);
            }
            int wcount = off[0] / 8 - wstart;
            if (wcount > 0) winG.add(new float[]{wstart, wcount, hh[13], hh[14], hh[15]});  // glass tint
        }
        winGroups = winG.toArray(new float[0][]);
        windowVerts = off[0] / 8;
        trimVerts = off[1] / 8;
        trimData = java.util.Arrays.copyOf(td, off[1]);
        revealVerts = off[2] / 8;
        revealData = java.util.Arrays.copyOf(rv, off[2]);
        return java.util.Arrays.copyOf(wd, off[0]);
    }

    /** Per-house foundation plinth, eave trim band and storey divider bands (baked, world-space, no collision).
     *  Each band is a perimeter ring hugging the four walls (never a solid slab) so house interiors stay clear. */
    private float[] makeBandMesh() {
        // 88 boxes/house: worst case ~45 band boxes (8-storey level house) + up to 40 corner quoins
        float[] d = new float[houseRects.size() * 88 * 36 * 8 + 1024];
        int o = 0;
        java.util.List<float[]> bandG = new java.util.ArrayList<float[]>();
        for (float[] hh : houseRects) {
            float cx = hh[0], cz = hh[1], w = hh[2], dd = hh[3], h = hh[4];
            int houseStart = o / 8;
            float foundH = hh[16];
            int ds = (int) hh[5]; float doorW = hh.length > 24 ? hh[24] : 1.9f;   // gap the bands at the doorway
            float tr = hh[20], tg = hh[21], tb = hh[22];
            int storeys = Math.round(hh[23]);
            if (storeys < 1) storeys = 1;
            if (storeys > 8) storeys = 8;
            if (foundH > 0.01f) {                              // stone plinth poking proud of the walls
                int s = o / 8;
                o = bandRing(d, o, cx, foundH * 0.5f, cz, w, dd, foundH * 0.5f, 0.07f, ds, doorW);
                bandG.add(new float[]{s, o / 8 - s, hh[17], hh[18], hh[19]});
            }
            if (tr >= 0f) {                                    // painted eave trim band just under the roof
                int s = o / 8;
                o = bandRing(d, o, cx, Math.max(0.2f, h - 0.14f), cz, w, dd, 0.11f, 0.05f, ds, doorW);
                bandG.add(new float[]{s, o / 8 - s, tr, tg, tb});
            }
            if (storeys >= 2) {                                // horizontal storey divider lines
                int s = o / 8;
                for (int k = 1; k < storeys; k++) o = bandRing(d, o, cx, h * k / (float) storeys, cz, w, dd, 0.06f, 0.04f, ds, doorW);
                float br = tr >= 0f ? tr : 0.86f, bg = tr >= 0f ? tg : 0.83f, bb = tr >= 0f ? tb : 0.77f;
                bandG.add(new float[]{s, o / 8 - s, br, bg, bb});
            }
            {   // corner quoins: alternating proud blocks up all four corners break the bare wall seam
                // (the classic European facade tell). Doors never reach a corner (doorW clamp keeps the
                // gap >= 0.3 m inside the wall; quoins reach only 0.20 m in).
                int s = o / 8;
                float hw = w * 0.5f, hd = dd * 0.5f, y0 = Math.max(foundH, 0.05f) + 0.08f, top = h - 0.28f;
                int nq = Math.min(10, (int) ((top - y0) / 0.42f));
                for (int q = 0; q < nq; q++) {
                    float qy = y0 + 0.21f + q * 0.42f, qs = (q % 2 == 0) ? 0.20f : 0.155f;   // toothed 0.05-proud / flush rhythm vs the 0.15 wall
                    o = box6(d, o, cx - hw, qy, cz - hd, qs, 0.155f, qs);
                    o = box6(d, o, cx + hw, qy, cz - hd, qs, 0.155f, qs);
                    o = box6(d, o, cx - hw, qy, cz + hd, qs, 0.155f, qs);
                    o = box6(d, o, cx + hw, qy, cz + hd, qs, 0.155f, qs);
                }
                if (o / 8 > s) bandG.add(new float[]{s, o / 8 - s,
                        Math.min(1f, hh[17] * 1.3f + 0.12f), Math.min(1f, hh[18] * 1.3f + 0.12f), Math.min(1f, hh[19] * 1.3f + 0.12f)});
            }
            rotateVertSpanY(d, houseStart, o / 8 - houseStart, cx, cz, hh.length > 25 ? hh[25] : 0f);   // spin all bands with the house
        }
        bandGroups = bandG.isEmpty() ? null : bandG.toArray(new float[0][]);
        bandVerts = o / 8;
        return java.util.Arrays.copyOf(d, o);
    }

    /** Four thin boxes wrapping the 0.30 m walls of a w x dd house at height cy, sticking `proud` past each face. */
    private static int bandRing(float[] d, int o, float cx, float cy, float cz, float w, float dd, float halfH, float proud, int ds, float doorW) {
        float hw = w * 0.5f, hd = dd * 0.5f, perp = 0.15f + proud;
        boolean cut = (cy - halfH) < 2.45f;                          // band low enough to cross the doorway -> gap it there
        float g = doorW * 0.5f + 0.12f;                              // gap half-width
        o = (ds == 0 && cut) ? bandFaceGap(d, o, cx, cy, cz + hd, hw + proud, halfH, perp, g, 0)
                             : box6(d, o, cx, cy, cz + hd, hw + proud, halfH, perp);    // +z face
        o = (ds == 1 && cut) ? bandFaceGap(d, o, cx, cy, cz - hd, hw + proud, halfH, perp, g, 0)
                             : box6(d, o, cx, cy, cz - hd, hw + proud, halfH, perp);    // -z face
        o = (ds == 2 && cut) ? bandFaceGap(d, o, cx + hw, cy, cz, perp, halfH, hd + proud, g, 1)
                             : box6(d, o, cx + hw, cy, cz, perp, halfH, hd + proud);    // +x face
        o = (ds == 3 && cut) ? bandFaceGap(d, o, cx - hw, cy, cz, perp, halfH, hd + proud, g, 1)
                             : box6(d, o, cx - hw, cy, cz, perp, halfH, hd + proud);    // -x face
        return o;
    }
    /** A band wall-face split by a centred doorway gap of half-width g. wallAxis 0 = wall runs along X, 1 = along Z. */
    private static int bandFaceGap(float[] d, int o, float cx, float cy, float cz, float hx, float hy, float hz, float g, int wallAxis) {
        if (wallAxis == 0) {                                          // long axis = X -> two segments left/right of the gap
            if (hx > g + 0.05f) { float sh = (hx - g) * 0.5f;
                o = box6(d, o, cx - (g + sh), cy, cz, sh, hy, hz);
                o = box6(d, o, cx + (g + sh), cy, cz, sh, hy, hz); }
        } else {                                                     // long axis = Z
            if (hz > g + 0.05f) { float sh = (hz - g) * 0.5f;
                o = box6(d, o, cx, cy, cz - (g + sh), hx, hy, sh);
                o = box6(d, o, cx, cy, cz + (g + sh), hx, hy, sh); }
        }
        return o;
    }

    // --- walk-in interiors: a furnished ground-floor room per house, baked into one mesh + collision proxies ---

    private float fCx, fCz, fYaw;   // house currently being furnished, so addPiece can spin each piece with it

    /** Furnish every house once: store visual pieces for the baked mesh, add solid colliders to L. */
    private void furnishAll(List<float[]> L, List<float[]> houses) {
        interiorPieces = new java.util.ArrayList<float[]>();
        for (float[] h : houses) {
            if (h.length > 26 && h[26] == 0f) continue;        // auto-furnish off -> the level supplies its own FU pieces
            furnishHouse(L, h[0], h[1], h[2], h[3], h[4], (int) h[5], h.length > 25 ? h[25] : 0f);
        }
    }

    /** A cosy ground-floor room: wood floor, ceiling, rug, hanging lamp, a bed, a wardrobe and a table + chairs,
     *  laid out away from the doorway. Solid pieces also get a non-rendered collision box in L.
     *  Layout is computed axis-aligned; addPiece spins each piece + collider about (cx,cz) by yaw. */
    private void furnishHouse(List<float[]> L, float cx, float cz, float w, float dd, float h, int ds, float yaw) {
        fCx = cx; fCz = cz; fYaw = yaw;
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
        addPiece(L, cx, ceilH - 0.36f, cz, 0.14f, 0.10f, 0.14f, 13, false);                    // warm bulb glow
        addPiece(L, cx, ceilH - 0.42f, cz, 0.34f, 0.16f, 0.34f, 7, false);                     // lampshade
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
            // headboard behind the pillow end + a folded blanket accent across the foot
            if (bedAlongX) {
                addPiece(L, bx - sgx[bedC] * (bedW * 0.5f - 0.035f), 0.56f, bz, 0.07f, 0.50f, bedD, 3, false);
                addPiece(L, bx + sgx[bedC] * bedW * 0.24f, 0.505f, bz, 0.30f, 0.05f, bedD - 0.12f, 9, false);
            } else {
                addPiece(L, bx, 0.56f, bz - sgz[bedC] * (bedD * 0.5f - 0.035f), bedW, 0.50f, 0.07f, 3, false);
                addPiece(L, bx, 0.505f, bz + sgz[bedC] * bedD * 0.24f, bedW - 0.12f, 0.05f, 0.30f, 9, false);
            }
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
            // plinth + cornice + a pair of door panels with handles on the room-facing side
            addPiece(L, wx, 0.05f, wz, 0.68f, 0.08f, 0.48f, 3, false);
            addPiece(L, wx, wardH + 0.06f, wz, 0.68f, 0.06f, 0.48f, 3, false);
            float wfx = cx - wx, wfz = cz - wz;
            boolean wFrontX = Math.abs(wfx) > Math.abs(wfz);
            float wsx2 = wFrontX ? Math.signum(wfx) : 0f, wsz2 = wFrontX ? 0f : Math.signum(wfz);
            float pfx = wx + wsx2 * (wFrontX ? 0.325f : 0f), pfz = wz + wsz2 * (wFrontX ? 0f : 0.225f);
            for (int dp = -1; dp <= 1; dp += 2) {
                float ox2 = wFrontX ? 0f : dp * 0.145f, oz2 = wFrontX ? dp * 0.10f : 0f;
                addPiece(L, pfx + ox2, wardH * 0.52f, pfz + oz2,
                        wFrontX ? 0.025f : 0.25f, wardH - 0.28f, wFrontX ? 0.17f : 0.025f, 2, false);
                addPiece(L, pfx + ox2 * 0.30f + wsx2 * 0.014f, wardH * 0.52f, pfz + oz2 * 0.30f + wsz2 * 0.014f,
                        wFrontX ? 0.02f : 0.03f, 0.13f, wFrontX ? 0.03f : 0.02f, 6, false);
            }
            occ.add(new float[]{wx, wz, 0.62f, 0.42f});
            break;
        }
        // TABLE + chairs: centred, pushed away from the door, only if the room is roomy and the spot is clear
        if (inx > 1.1f && inz > 1.05f) {
            float tx = cx - dox * (inx * 0.35f), tz = cz - doz * (inz * 0.35f);
            if (!aabbOverlap(tx, tz, 0.95f, 0.75f, koX, koZ, koW, koD)
             && !(bedPlaced && aabbOverlap(tx, tz, 0.95f, 0.75f, bx, bz, bedW, bedD))) {
                addPiece(L, tx, 0.70f, tz, 0.80f, 0.07f, 0.60f, 2, true);                       // table top (solid)
                addPiece(L, tx, 0.625f, tz, 0.66f, 0.06f, 0.46f, 3, false);                    // apron under the top
                addPiece(L, tx, 0.765f, tz, 0.20f, 0.07f, 0.20f, 9, false);                    // bowl on top
                for (int lg = 0; lg < 4; lg++)
                    addPiece(L, tx + ((lg & 1) == 0 ? -0.32f : 0.32f), 0.33f, tz + ((lg & 2) == 0 ? -0.24f : 0.24f), 0.06f, 0.62f, 0.06f, 3, false);
                for (int s2 = -1; s2 <= 1; s2 += 2) {                                          // proper chairs: seat + legs + backrest
                    float chx = tx + s2 * 0.52f;
                    addPiece(L, chx, 0.43f, tz, 0.38f, 0.05f, 0.38f, 2, false);
                    for (int lg = 0; lg < 4; lg++)
                        addPiece(L, chx + ((lg & 1) == 0 ? -0.15f : 0.15f), 0.205f, tz + ((lg & 2) == 0 ? -0.15f : 0.15f), 0.05f, 0.41f, 0.05f, 3, false);
                    addPiece(L, chx + s2 * 0.185f, 0.69f, tz, 0.05f, 0.50f, 0.38f, 2, false);
                }
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
            float cLen = (alongX ? sw : sd) * 0.5f - 0.06f;                                     // 2 accent seat cushions
            for (int q2 = -1; q2 <= 1; q2 += 2)
                addPiece(L, sx2 + (alongX ? q2 * cLen * 0.5f : bdx2 * -0.04f), 0.40f, sz2 + (alongX ? bdz2 * -0.04f : q2 * cLen * 0.5f),
                        alongX ? cLen - 0.05f : sd - 0.16f, 0.09f, alongX ? sd - 0.16f : cLen - 0.05f, 9, false);
            occ.add(new float[]{sx2, sz2, sw, sd});
            break;
        }
        // BOOKSHELF: a tall thin unit in a free corner, with a colourful book band
        for (int ci = 0; ci < 4; ci++) {
            float shx = cx + sgx[ci] * (inx - 0.22f), shz = cz + sgz[ci] * (inz - 0.16f);
            if (!clearSpot(occ, shx, shz, 0.6f, 0.34f)) continue;
            float shH = Math.min(1.85f, ceilH - 0.2f);
            addPiece(L, shx, shH * 0.5f + 0.04f, shz, 0.56f, shH, 0.3f, 3, true);               // case
            addPiece(L, shx, shH * 0.62f, shz, 0.48f, 0.16f, 0.24f, 9, false);                  // varied book rows
            addPiece(L, shx, shH * 0.40f, shz, 0.48f, 0.16f, 0.24f, 8, false);
            addPiece(L, shx, shH * 0.20f, shz, 0.48f, 0.16f, 0.24f, 15, false);
            addPiece(L, shx + 0.12f, shH + 0.10f, shz, 0.12f, 0.13f, 0.12f, 7, false);          // trinket on top
            occ.add(new float[]{shx, shz, 0.6f, 0.34f});
            break;
        }
        // POTTED PLANT: a small green accent in any remaining corner
        for (int ci = 3; ci >= 0; ci--) {
            float pxp = cx + sgx[ci] * (inx - 0.20f), pzp = cz + sgz[ci] * (inz - 0.20f);
            if (!clearSpot(occ, pxp, pzp, 0.32f, 0.32f)) continue;
            addPiece(L, pxp, 0.02f, pzp, 0.32f, 0.04f, 0.32f, 3, false);                        // saucer
            addPiece(L, pxp, 0.16f, pzp, 0.26f, 0.30f, 0.26f, 9, false);                        // terracotta pot
            addPiece(L, pxp, 0.46f, pzp, 0.42f, 0.38f, 0.42f, 10, false);                       // layered foliage
            addPiece(L, pxp, 0.68f, pzp, 0.28f, 0.22f, 0.28f, 10, false);
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
        float wx = x, wz = z;                              // spin the piece centre about the house centre (yaw=0 -> unchanged)
        if (fYaw != 0f) {
            double a = Math.toRadians(fYaw);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float rx = x - fCx, rz = z - fCz;
            wx = fCx + rx * ca + rz * sa;
            wz = fCz - rx * sa + rz * ca;
        }
        interiorPieces.add(new float[]{wx, y, wz, w, h, d, colorIdx, fYaw});
        if (solid) L.add(new float[]{wx, y, wz, w, h, d, 0.5f, 0.5f, 0.5f, fYaw, 1f});   // collision proxy (idx 9 = yaw, idx 10 = no-render)
    }

    // Furniture recipes shared with the editor (kind -> pieces of {dx,dy,dz, w,h,d, colorIdx, solid}).
    // dx,dz are local offsets, dy the piece-centre height; all multiplied by the placed scale. KEEP IN SYNC with FURN in level-editor.html.
    static final float[][][] FURN = {
        // 0 Bett: frame(solid) + 4 legs + headboard + duvet + 2 pillows + folded blanket
        {{0,0.22f,0, 1.5f,0.30f,0.95f, 2,1},
         {-0.68f,0.05f,-0.40f, 0.10f,0.10f,0.10f, 3,0}, {0.68f,0.05f,-0.40f, 0.10f,0.10f,0.10f, 3,0},
         {-0.68f,0.05f,0.40f, 0.10f,0.10f,0.10f, 3,0}, {0.68f,0.05f,0.40f, 0.10f,0.10f,0.10f, 3,0},
         {0,0.58f,-0.505f, 1.50f,0.55f,0.07f, 3,0},                         // headboard
         {0,0.42f,0.10f, 1.42f,0.14f,0.66f, 4,0},                           // duvet
         {-0.35f,0.47f,-0.32f, 0.50f,0.11f,0.26f, 5,0}, {0.35f,0.47f,-0.32f, 0.50f,0.11f,0.26f, 5,0},
         {0,0.505f,0.30f, 1.44f,0.05f,0.26f, 9,0}},                         // folded blanket accent
        // 1 Schrank: body(solid) + plinth + cornice + 2 door panels + handles
        {{0,0.85f,0, 0.62f,1.66f,0.45f, 3,1},
         {0,0.04f,0, 0.68f,0.08f,0.50f, 3,0}, {0,1.70f,0, 0.68f,0.06f,0.50f, 3,0},
         {-0.145f,0.88f,0.235f, 0.25f,1.44f,0.025f, 2,0}, {0.145f,0.88f,0.235f, 0.25f,1.44f,0.025f, 2,0},
         {-0.05f,0.88f,0.252f, 0.03f,0.14f,0.02f, 6,0}, {0.05f,0.88f,0.252f, 0.03f,0.14f,0.02f, 6,0}},
        // 2 Tisch: top(solid) + apron + 4 legs + bowl
        {{0,0.70f,0, 0.90f,0.07f,0.65f, 2,1}, {0,0.62f,0, 0.76f,0.07f,0.51f, 3,0},
         {-0.36f,0.33f,-0.26f, 0.07f,0.62f,0.07f, 3,0}, {0.36f,0.33f,-0.26f, 0.07f,0.62f,0.07f, 3,0},
         {-0.36f,0.33f,0.26f, 0.07f,0.62f,0.07f, 3,0}, {0.36f,0.33f,0.26f, 0.07f,0.62f,0.07f, 3,0},
         {0,0.765f,0, 0.22f,0.07f,0.22f, 9,0}},
        // 3 Stuhl: thin seat(solid) + 4 legs + backrest + top rail
        {{0,0.44f,0, 0.40f,0.06f,0.40f, 2,1},
         {-0.16f,0.21f,-0.16f, 0.05f,0.42f,0.05f, 3,0}, {0.16f,0.21f,-0.16f, 0.05f,0.42f,0.05f, 3,0},
         {-0.16f,0.21f,0.16f, 0.05f,0.42f,0.05f, 3,0}, {0.16f,0.21f,0.16f, 0.05f,0.42f,0.05f, 3,0},
         {0,0.71f,-0.185f, 0.40f,0.48f,0.05f, 2,0}, {0,0.985f,-0.185f, 0.42f,0.07f,0.06f, 3,0}},
        // 4 Sofa: seat(solid) + back + 2 arms + 2 seat cushions + 2 back cushions + feet
        {{0,0.24f,0, 1.50f,0.32f,0.62f, 8,1}, {0,0.50f,-0.25f, 1.50f,0.40f,0.16f, 8,0},
         {-0.72f,0.34f,0, 0.12f,0.48f,0.62f, 8,0}, {0.72f,0.34f,0, 0.12f,0.48f,0.62f, 8,0},
         {-0.34f,0.44f,0.05f, 0.60f,0.10f,0.48f, 9,0}, {0.34f,0.44f,0.05f, 0.60f,0.10f,0.48f, 9,0},
         {-0.34f,0.62f,-0.20f, 0.58f,0.26f,0.12f, 9,0}, {0.34f,0.62f,-0.20f, 0.58f,0.26f,0.12f, 9,0},
         {-0.66f,0.045f,0.22f, 0.08f,0.09f,0.08f, 3,0}, {0.66f,0.045f,0.22f, 0.08f,0.09f,0.08f, 3,0}},
        // 5 Regal: case(solid) + varied book rows + top trinket
        {{0,0.90f,0, 0.60f,1.80f,0.32f, 3,1},
         {0,1.45f,0, 0.50f,0.16f,0.26f, 9,0}, {0,1.18f,0, 0.50f,0.16f,0.26f, 8,0},
         {0,0.78f,0, 0.50f,0.16f,0.26f, 9,0}, {0,0.45f,0, 0.50f,0.16f,0.26f, 15,0},
         {0.14f,1.87f,0, 0.12f,0.14f,0.12f, 7,0}},
        // 6 Pflanze: saucer + terracotta pot + stem + layered foliage
        {{0,0.02f,0, 0.36f,0.04f,0.36f, 3,0}, {0,0.18f,0, 0.30f,0.34f,0.30f, 9,0},
         {0,0.42f,0, 0.08f,0.26f,0.08f, 3,0},
         {0,0.60f,0, 0.50f,0.36f,0.50f, 10,0}, {0,0.82f,0, 0.34f,0.26f,0.34f, 10,0}},
        // 7 Teppich: border + inner field (two-tone)
        {{0,0.025f,0, 1.60f,0.03f,1.10f, 4,0}, {0,0.045f,0, 1.20f,0.015f,0.74f, 9,0}},
        // 8 Lampe: base + pole + warm bulb + shade
        {{0,0.06f,0, 0.30f,0.10f,0.30f, 2,0}, {0,0.78f,0, 0.07f,1.40f,0.07f, 6,0},
         {0,1.44f,0, 0.16f,0.12f,0.16f, 13,0}, {0,1.55f,0, 0.42f,0.34f,0.42f, 7,0}},
        // 9 Kommode: body(solid) + plinth + top + 3 drawer fronts + handles
        {{0,0.44f,0, 0.90f,0.80f,0.42f, 2,1},
         {0,0.04f,0, 0.94f,0.08f,0.46f, 3,0}, {0,0.865f,0, 0.96f,0.05f,0.46f, 3,0},
         {0,0.22f,0.215f, 0.78f,0.17f,0.03f, 3,0}, {0,0.44f,0.215f, 0.78f,0.17f,0.03f, 3,0}, {0,0.66f,0.215f, 0.78f,0.17f,0.03f, 3,0},
         {0,0.22f,0.235f, 0.16f,0.04f,0.02f, 6,0}, {0,0.44f,0.235f, 0.16f,0.04f,0.02f, 6,0}, {0,0.66f,0.235f, 0.16f,0.04f,0.02f, 6,0}},
        // 10 Truhe: body(solid) + lid + 2 metal bands + lock
        {{0,0.28f,0, 0.80f,0.48f,0.50f, 3,1}, {0,0.555f,0, 0.84f,0.11f,0.54f, 2,0},
         {-0.22f,0.30f,0, 0.05f,0.62f,0.55f, 12,0}, {0.22f,0.30f,0, 0.05f,0.62f,0.55f, 12,0},
         {0,0.38f,0.265f, 0.10f,0.13f,0.04f, 6,0}},
        // 11 Schreibtisch: top(solid) + side panels + drawer block + knobs + paper
        {{0,0.72f,0, 1.10f,0.06f,0.60f, 2,1},
         {-0.51f,0.36f,0, 0.06f,0.66f,0.54f, 3,0}, {0.51f,0.36f,0, 0.06f,0.66f,0.54f, 3,0},
         {0.30f,0.50f,0, 0.40f,0.36f,0.52f, 3,0},
         {0.30f,0.58f,0.27f, 0.10f,0.04f,0.02f, 6,0}, {0.30f,0.44f,0.27f, 0.10f,0.04f,0.02f, 6,0},
         {-0.18f,0.76f,0.02f, 0.30f,0.02f,0.22f, 15,0}},
        // 12 Ofen: dark body(solid) + worktop + 2 rings + oven door + handle
        {{0,0.45f,0, 0.70f,0.86f,0.60f, 12,1}, {0,0.895f,0, 0.74f,0.05f,0.64f, 6,0},
         {-0.16f,0.935f,0, 0.22f,0.03f,0.22f, 3,0}, {0.17f,0.935f,-0.02f, 0.26f,0.03f,0.26f, 3,0},
         {0,0.38f,0.315f, 0.50f,0.44f,0.03f, 6,0}, {0,0.62f,0.325f, 0.46f,0.05f,0.04f, 5,0}},
        // 13 Kamin: stone body(solid) + dark firebox + ember glow + mantel shelf
        {{0,0.60f,0, 1.20f,1.20f,0.52f, 14,1},
         {0,0.42f,0.245f, 0.64f,0.62f,0.08f, 12,0},
         {0,0.26f,0.22f, 0.44f,0.26f,0.12f, 13,0},
         {0,1.26f,0, 1.32f,0.09f,0.60f, 3,0}},
        // 14 Bank: seat(solid) + 2 leg slabs
        {{0,0.26f,0, 1.20f,0.09f,0.38f, 2,1},
         {-0.50f,0.11f,0, 0.09f,0.22f,0.34f, 3,0}, {0.50f,0.11f,0, 0.09f,0.22f,0.34f, 3,0}},
        // 15 Nachttisch: body(solid) + top + drawer + knob
        {{0,0.24f,0, 0.38f,0.44f,0.34f, 2,1}, {0,0.475f,0, 0.42f,0.03f,0.38f, 3,0},
         {0,0.30f,0.175f, 0.30f,0.13f,0.025f, 3,0}, {0,0.30f,0.195f, 0.05f,0.04f,0.02f, 6,0}},
        // 16 Spiegel: frame(solid) + glass + 2 feet
        {{0,0.85f,0, 0.50f,1.60f,0.10f, 3,1}, {0,0.90f,0.056f, 0.38f,1.30f,0.02f, 11,0},
         {-0.18f,0.05f,0, 0.10f,0.10f,0.30f, 3,0}, {0.18f,0.05f,0, 0.10f,0.10f,0.30f, 3,0}},
    };

    /** Place one level-authored furniture piece (visual + collider), spun about its own centre by yaw and scaled. */
    private void addFurniture(List<float[]> L, int kind, float cx, float cz, float yaw, float s) {
        if (kind < 0 || kind >= FURN.length) return;
        if (s <= 0f) s = 1f;
        fCx = cx; fCz = cz; fYaw = yaw;
        for (float[] p : FURN[kind])
            addPiece(L, cx + p[0] * s, p[1] * s, cz + p[2] * s, p[3] * s, p[4] * s, p[5] * s, (int) p[6], p[7] != 0f);
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
                int ps = o / 8;
                o = box6(d, o, p[0], p[1], p[2], p[3] * 0.5f, p[4] * 0.5f, p[5] * 0.5f);
                if (p.length > 7 && p[7] != 0f) rotateVertSpanY(d, ps, o / 8 - ps, p[0], p[2], p[7]);   // orient with the house
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
    /** Does this house get real cut openings? (furnished + auto-furnish on). Both the wall cutter and the
     *  pane placer key off this so glass exactly fills every hole. */
    static boolean houseCuts(float[] hh) {
        boolean autoFurn = hh.length <= 26 || hh[26] != 0f;
        return autoFurn && houseFurnishable(hh[2], hh[3], hh[4]);
    }

    private static void wallWindows(float[] wd, float[] td, float[] rv, int[] off, float a, float b, float span, float h,
                                    int axis, int sign, boolean doorWall, long houseSeed, int side,
                                    int dens, float wsc, int storeys, float foundH, boolean cutHoles, boolean skipReachablePane) {
        // Positions come from the shared windowSlots() so panes exactly fill the cut holes.
        float wscC = clamp(wsc <= 0f ? 1f : wsc, 0.5f, 1.6f), proud = 0.18f;
        java.util.ArrayList<float[]> slots = new java.util.ArrayList<float[]>();
        windowSlots(span, h, dens, wscC, storeys, foundH, doorWall, houseSeed, side, slots);
        for (float[] s : slots) {
            float t = s[0], wy = s[1], winW = s[2], winH = s[3];
            boolean reachable = s[5] != 0f;
            if (off[1] > td.length - 5000) continue;             // trim scratch nearly full: skip gracefully (never overflow)
            // Reachable ground-floor panes are drawn as animated sashes (drawWindows), so skip baking them for
            // the base town. Overlay houses (skipReachablePane=false) keep every pane baked (no interactive sash).
            if (!(reachable && skipReachablePane))
                off[0] = windowQuad(wd, off[0], a, b, t, wy, winW, winH, axis, sign, proud);
            // The fake dark reveal panel only makes sense on UNCUT (unfurnished) houses; a cut house shows its
            // real interior straight through the hole.
            if (!cutHoles)
                off[2] = windowQuad(rv, off[2], a, b, t, wy, winW * 0.86f, winH * 0.86f, axis, sign, 0.06f);
            float sy = wy - winH * 0.5f - 0.04f;                     // sill ledge just below the pane
            float hy = wy + winH * 0.5f + 0.045f;                    // head lintel just above it
            if (axis == 0) {
                off[1] = box6(td, off[1], a + t, sy, b + sign * 0.11f, winW * 0.5f + 0.1f, 0.05f, 0.11f);
                // complete the stone surround: lintel on top + jamb strips flanking the pane (these also
                // face the cut reveal so the opening reads as a framed window, not a raw hole)
                off[1] = box6(td, off[1], a + t, hy, b + sign * 0.10f, winW * 0.5f + 0.09f, 0.045f, 0.10f);
                off[1] = box6(td, off[1], a + t - winW * 0.5f - 0.035f, wy, b + sign * 0.10f, 0.035f, winH * 0.5f + 0.02f, 0.10f);
                off[1] = box6(td, off[1], a + t + winW * 0.5f + 0.035f, wy, b + sign * 0.10f, 0.035f, winH * 0.5f + 0.02f, 0.10f);
            } else {
                off[1] = box6(td, off[1], a + sign * 0.11f, sy, b + t, 0.11f, 0.05f, winW * 0.5f + 0.1f);
                off[1] = box6(td, off[1], a + sign * 0.10f, hy, b + t, 0.10f, 0.045f, winW * 0.5f + 0.09f);
                off[1] = box6(td, off[1], a + sign * 0.10f, wy, b + t - winW * 0.5f - 0.035f, 0.10f, winH * 0.5f + 0.02f, 0.035f);
                off[1] = box6(td, off[1], a + sign * 0.10f, wy, b + t + winW * 0.5f + 0.035f, 0.10f, winH * 0.5f + 0.02f, 0.035f);
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
        int N = 512;
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFEDEDED);                                    // near-white grey base -> uColor sets the hue
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(77);
        for (int k = 0; k < 5000; k++) {                            // neutral tile grain (no hue)
            int v = rnd.nextInt(40), s = 0xC4 + v;
            p.setColor((0x33 << 24) | (s << 16) | (s << 8) | s);
            float x = rnd.nextInt(N), y = rnd.nextInt(N);
            c.drawRect(x, y, x + 2, y + 2, p);
        }
        // Per-tile detail keyed on the WRAPPED cell (col and col+8 are the same tile split by the border):
        // tone, curl shading and chips match on both halves, killing the tone seam at every 2 m boundary.
        for (int row = 0; row < 12; row++) {
            float y0 = row * N / 12f, off = (row % 2) * (N / 16f);
            for (int col = -1; col <= 8; col++) {
                float x0 = col * N / 8f + off;
                int wc = ((col % 8) + 8) % 8;
                Random rt = new Random((row * 97L + wc) * 4241L + 77);
                int v = rt.nextInt(3);
                if (v != 2) {                                        // ~2/3 of tiles get a tone shift
                    p.setColor(v == 0 ? 0x18000000 : 0x12FFFFFF);
                    c.drawRect(x0 + 3f, y0 + 5f, x0 + N / 8f - 3f, y0 + N / 12f, p);
                }
                p.setColor(0x12000000);                              // barrel curl: soft dark bands at the tile flanks
                c.drawRect(x0 + 2f, y0 + 5f, x0 + 6f, y0 + N / 12f, p);
                c.drawRect(x0 + N / 8f - 6f, y0 + 5f, x0 + N / 8f - 2f, y0 + N / 12f, p);
                if (rt.nextInt(4) == 0) {                            // chipped lower edge
                    p.setColor(0x2E000000);
                    float chx = x0 + 4 + rt.nextInt(18);
                    c.drawRect(chx, y0 + N / 12f - 3f, chx + 3f + rt.nextInt(3), y0 + N / 12f, p);
                }
            }
        }
        p.setColor(0x55000000);                                     // horizontal tile rows
        for (int row = 0; row <= 12; row++) { float y = row * N / 12f; c.drawRect(0, y, N, y + 5f, p); }
        p.setColor(0x1A000000);                                     // soft shadow lip under each row (tiles overlap)
        for (int row = 0; row <= 12; row++) { float y = row * N / 12f; c.drawRect(0, y + 5f, N, y + 11f, p); }
        p.setColor(0x33000000);                                     // staggered vertical seams
        for (int row = 0; row < 12; row++) {
            float y = row * N / 12f, off = (row % 2) * (N / 16f);
            for (int col = 0; col <= 8; col++) { float x = col * N / 8f + off; c.drawRect(x, y, x + 3f, y + N / 12f, p); }
        }
        p.setColor(0x12000000);                                     // rain streaks, each starting at a row line (wrap-safe)
        for (int k = 0; k < 36; k++) {
            float sy = rnd.nextInt(12) * N / 12f, sx = rnd.nextInt(N);
            c.drawRect(sx, sy, sx + 2f, sy + N / 12f * 0.7f, p);
        }
        return b;
    }

    private static Bitmap makeWindowBitmap() {
        int N = 256;                                                // windows are focal points -- give the frame real depth
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFF2F0EA);                                    // bright frame (tints lightly with the glass colour)
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float m = N * 0.17f, gl = m, gr = N - m, gt = m, gb = N - m, gw = gr - gl;
        // light, clean glass — the shader adds the reflection/see-through, so the texture stays bright (no painted-on dark room)
        p.setColor(0xFFD2DAE2); c.drawRect(gl, gt, gr, gb, p);      // light neutral glass (per-house uColor sets the hue)
        p.setColor(0x66FFFFFF); c.drawRect(gl, gt, gr, N * 0.5f, p);    // sky sheen across the upper panes
        p.setColor(0x22203040); c.drawRect(gl, N * 0.5f, gr, gb, p);    // faint depth on the lower panes (kept light)
        p.setColor(0x4AFFD9A0); c.drawRect(gl + gw * 0.30f, N * 0.62f, gr - gw * 0.30f, N * 0.74f, p);  // warm interior hint
        p.setColor(0x40000000); c.drawRect(gl, gt, gr, gt + 7f, p);     // rebate shadow: frame overhangs the glass (light from above)
        p.setColor(0x22000000); c.drawRect(gl, gt + 7f, gr, gt + 14f, p);
        p.setColor(0x2E000000); c.drawRect(gl, gt, gl + 5f, gb, p);     // left rebate edge
        p.setColor(0x18000000);                                     // corner AO, pulled inside the glass so the frame stays clean
        c.drawCircle(gl + 6f, gt + 6f, 10f, p); c.drawCircle(gr - 6f, gt + 6f, 10f, p);
        c.drawCircle(gl + 6f, gb - 6f, 10f, p); c.drawCircle(gr - 6f, gb - 6f, 10f, p);
        // mullion bars sized to EXACTLY match the shader's opaque zone (vUV 0.47-0.53): no more glass sliver
        // rendering opaque at the bar edges
        p.setColor(0xFFF2F0EA);
        c.drawRect(N * 0.47f, gt, N * 0.53f, gb, p);
        c.drawRect(gl, N * 0.47f, gr, N * 0.53f, p);
        p.setColor(0x28000000);                                     // mullion relief lines
        c.drawRect(N * 0.47f - 1.5f, gt, N * 0.47f, gb, p); c.drawRect(N * 0.53f, gt, N * 0.53f + 1.5f, gb, p);
        c.drawRect(gl, N * 0.47f - 1.5f, gr, N * 0.47f, p); c.drawRect(gl, N * 0.53f, gr, N * 0.53f + 1.5f, p);
        p.setColor(0x2EFFFFFF); c.drawRect(gl - 4f, gb + 4f, gr + 4f, gb + 12f, p);   // lit sill ledge under the glass
        p.setColor(0x30000000); c.drawRect(gl, gb, gr, gb + 2f, p);
        p.setColor(0xFFF2F0EA);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(10f); c.drawRect(gl, gt, gr, gb, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4f); p.setColor(0x36000000);
        c.drawRect(m - 6f, m - 6f, N - m + 6f, N - m + 6f, p);      // wall-contact shadow around the whole frame
        p.setStyle(Paint.Style.FILL);
        return b;
    }

    /** The dim room seen THROUGH the glass: a dark gradient with a faint warm glow + a furniture silhouette. */
    private static Bitmap makeWinBackBitmap() {
        int N = 128;                                               // a readable room instead of 64 px mush
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int y = 0; y < N; y++) {                              // darker near the ceiling, a touch warmer low down
            float f = y / (float) N;
            int rr = cl255((int) (0x1C + 0x1E * f)), gg = cl255((int) (0x1E + 0x18 * f)), bbl = cl255((int) (0x28 + 0x12 * f));
            p.setColor(0xFF000000 | (rr << 16) | (gg << 8) | bbl);
            c.drawRect(0, y, N, y + 1, p);
        }
        p.setColor(0x30FFB25E); c.drawCircle(N * 0.52f, N * 0.60f, N * 0.30f, p);   // wide lamp halo
        p.setColor(0x46FFC880); c.drawCircle(N * 0.52f, N * 0.60f, N * 0.20f, p);   // mid glow
        p.setColor(0x7DFFD996); c.drawCircle(N * 0.52f, N * 0.60f, N * 0.11f, p);   // hot lamp core
        p.setColor(0x1AFFFFFF); c.drawRect(0, N * 0.70f, N, N * 0.70f + 2f, p);     // wall/floor split line
        p.setColor(0xFF14171F); c.drawRect(N * 0.16f, N * 0.74f, N * 0.86f, N, p);   // dark furniture/sill silhouette
        p.setColor(0xFF10141C); c.drawRect(N * 0.60f, N * 0.34f, N * 0.88f, N, p);   // second furniture mass (a wardrobe)
        return b;
    }

    private static int cl255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /** Light stucco/plaster facade so houses read as houses (tinted by the per-house colour). */
    private static Bitmap makeHouseBitmap() {
        int N = 512;                                                // the default facade deserves the finest grain
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFC8C4BC);                                    // near-NEUTRAL mid plaster -- keeps each house's hue, just darker + textured
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(55);
        for (int k = 0; k < 90; k++) {                              // low-frequency mottle, drawn 9x so it WRAPS
            boolean lite = rnd.nextBoolean(); int v = 12 + rnd.nextInt(30);
            int t = lite ? 0xC8 + v : 0xC8 - v;                     // neutral: shift brightness, not hue
            p.setColor((0x3C << 24) | (cl255(t) << 16) | (cl255(t - 3) << 8) | cl255(t - 10));
            float x = rnd.nextInt(N), y = rnd.nextInt(N), r = 32 + rnd.nextInt(100);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) c.drawCircle(x + dx * N, y + dy * N, r, p);
        }
        for (int k = 0; k < 12000; k++) {                           // fine stucco speckle with real contrast
            int v = rnd.nextInt(50) - 25;
            p.setColor((0x40 << 24) | (cl255(0xC4 + v) << 16) | (cl255(0xC0 + v) << 8) | cl255(0xB6 + v));
            float x = rnd.nextInt(N), y = rnd.nextInt(N);
            c.drawRect(x, y, x + 2, y + 2, p);
        }
        Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);                 // hairline settlement cracks with a lit lower lip
        s.setStyle(Paint.Style.STROKE);
        for (int cr = 0; cr < 4; cr++) {
            float x = N * 0.12f + rnd.nextInt((int) (N * 0.76f)), y = N * 0.08f + rnd.nextInt((int) (N * 0.3f));
            for (int seg = 0; seg < 26; seg++) {
                float nx = clamp(x - 3 + rnd.nextInt(7), N * 0.06f, N * 0.94f);
                float ny = clamp(y + 4 + rnd.nextInt(9), N * 0.06f, N * 0.94f);
                s.setStrokeWidth(1.6f); s.setColor(0x30000000); c.drawLine(x, y, nx, ny, s);
                s.setStrokeWidth(1f); s.setColor(0x16FFFFFF); c.drawLine(x + 1.2f, y + 1.2f, nx + 1.2f, ny + 1.2f, s);
                x = nx; y = ny;
            }
        }
        p.setColor(0x24000000);                                     // horizontal coat lines (visible)
        for (int row = 1; row < 6; row++) {
            float y = row * N / 6f;
            c.drawRect(0, y, N, y + 2.6f, p);
        }
        p.setColor(0x14FFFFFF);                                     // bevel light directly under each coat line
        for (int row = 1; row < 6; row++) { float y = row * N / 6f; c.drawRect(0, y + 2.6f, N, y + 3.8f, p); }
        p.setColor(0x16000000);                                     // vertical weather streaks
        for (int k = 0; k < 34; k++) { float x = rnd.nextInt(N); c.drawRect(x, 0, x + 2.5f, N, p); }
        p.setColor(0x0C000000);                                     // a few soft wide streaks
        for (int k = 0; k < 8; k++) { float x = rnd.nextInt(N); c.drawRect(x, 0, x + 6f, N, p); }
        return b;
    }

    /** Red-brown running-bond brick (carries its own colour; drawn with white tint). */
    private static Bitmap makeBrickBitmap() {
        int N = 512;                                                // 256 px/m: room for mortar grain + per-brick pitting
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFB9AE9C);                                    // mortar
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(91);
        for (int k = 0; k < 5000; k++) {                            // sandy mortar grain
            int m = rnd.nextInt(40) - 20;
            p.setColor((0x34 << 24) | (cl255(0xB9 + m) << 16) | (cl255(0xAE + m) << 8) | cl255(0x9C + m));
            float x = rnd.nextInt(N), y = rnd.nextInt(N);
            c.drawRect(x, y, x + 2, y + 2, p);
        }
        int rows = 12; float rh = (float) N / rows, gap = 4.4f, bw = N / 4f;
        for (int row = 0; row < rows; row++) {
            float y0 = row * rh + gap, y1 = (row + 1) * rh - gap, off = (row % 2) * (bw * 0.5f);
            for (int col = -1; col <= 4; col++) {
                float x0 = col * bw + off + gap, x1 = (col + 1) * bw + off - gap;
                // Deterministic per-brick RNG keyed on the WRAPPED cell (col and col+4 are the same brick
                // split by the tile border): both halves now roll identical tone -- no more visible tone
                // discontinuity at every 2 m tiling boundary.
                int wc = ((col % 4) + 4) % 4;
                Random rb = new Random((row * 47L + wc) * 7919L + 91);
                int v = rb.nextInt(34);
                p.setColor(0xFF000000 | (cl255(0x9a - v + rb.nextInt(18)) << 16) | (cl255(0x55 - v / 2) << 8) | cl255(0x42 - v / 2));
                c.drawRect(x0, y0, x1, y1, p);
                if (rb.nextInt(9) == 0) {                           // the odd over-fired dark header brick
                    p.setColor(0x3C2A1812);
                    c.drawRect(x0, y0, x1, y1, p);
                }
                p.setColor(0x0CFFFFFF);                             // kiln-skin gradient: upper brick face catches light
                c.drawRect(x0, y0, x1, y0 + (y1 - y0) * 0.4f, p);
                int np = 5 + rb.nextInt(5);                         // per-brick pitting (strictly inside the brick)
                for (int q = 0; q < np; q++) {
                    p.setColor(0x26000000);
                    c.drawCircle(x0 + 3 + rb.nextInt((int) (x1 - x0 - 6)), y0 + 3 + rb.nextInt((int) (y1 - y0 - 6)), 1f + rb.nextFloat() * 1.5f, p);
                }
                for (int q = 0; q < 2; q++) {
                    p.setColor(0x1EFFFFFF);
                    c.drawCircle(x0 + 3 + rb.nextInt((int) (x1 - x0 - 6)), y0 + 3 + rb.nextInt((int) (y1 - y0 - 6)), 1.3f, p);
                }
                p.setColor(0x2EFFFFFF);                             // lit top edge — bricks read as raised
                c.drawRect(x0, y0, x1, y0 + 2.8f, p);
                p.setColor(0x30000000);                             // shadowed bottom edge into the mortar bed
                c.drawRect(x0, y1 - 3.2f, x1, y1, p);
            }
        }
        p.setColor(0x22000000);                                     // weathering speckle
        for (int k = 0; k < 2600; k++) { float x = rnd.nextInt(N), y = rnd.nextInt(N); c.drawRect(x, y, x + 2, y + 2, p); }
        return b;
    }

    /** Ashlar grey stone / rendered-concrete blocks (carries its own colour). */
    private static Bitmap makeStoneBitmap() {
        int N = 512;                                                // blocks are 66 cm in world -- give them real texels
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF6E6E70);                                    // dark mortar
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint s2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        s2.setStyle(Paint.Style.STROKE);
        Random rnd = new Random(43);
        int rows = 5; float rh = (float) N / rows, gap = 6.0f, bw = N / 3f;
        for (int row = 0; row < rows; row++) {
            float y0 = row * rh + gap, y1 = (row + 1) * rh - gap, off = (row % 2) * (bw * 0.5f);
            for (int col = -1; col <= 3; col++) {
                float x0 = col * bw + off + gap, x1 = (col + 1) * bw + off - gap;
                // deterministic per-block RNG (col and col+3 are the same wrapped block): matching tones
                // on both clipped halves kill the 2 m tiling seam
                int wc = ((col % 3) + 3) % 3;
                Random rb = new Random((row * 61L + wc) * 6151L + 43);
                int s = 0x9c - rb.nextInt(34) + rb.nextInt(18);
                p.setColor(0xFF000000 | (cl255(s) << 16) | (cl255(s - 2) << 8) | cl255(s - 6));
                c.drawRect(x0, y0, x1, y1, p);
                int veins = 1 + rb.nextInt(2);                      // crack veining, clamped inside the block
                s2.setStrokeWidth(1.4f);
                for (int vn = 0; vn < veins; vn++) {
                    float vx = x0 + 8 + rb.nextInt((int) (x1 - x0 - 16)), vy = y0 + 6 + rb.nextInt((int) ((y1 - y0) * 0.4f));
                    int segs = 5 + rb.nextInt(4);
                    for (int sg = 0; sg < segs; sg++) {
                        float nx2 = Math.min(x1 - 4, Math.max(x0 + 4, vx + 6 + rb.nextInt(10)));
                        float ny2 = Math.min(y1 - 4, Math.max(y0 + 4, vy - 4 + rb.nextInt(12)));
                        s2.setColor(0x2C000000); c.drawLine(vx, vy, nx2, ny2, s2);
                        if (rb.nextBoolean()) { s2.setColor(0x12FFFFFF); c.drawLine(vx + 1, vy + 1, nx2 + 1, ny2 + 1, s2); }
                        vx = nx2; vy = ny2;
                    }
                }
                s2.setStrokeWidth(1.3f);                            // chisel dressing: short diagonal strokes
                for (int ch = 0; ch < 10; ch++) {
                    float chx = x0 + 4 + rb.nextInt(Math.max(1, (int) (x1 - x0 - 15))), chy = y0 + 4 + rb.nextInt(Math.max(1, (int) (y1 - y0 - 15)));
                    s2.setColor(ch % 2 == 0 ? 0x14FFFFFF : 0x18000000);
                    c.drawLine(chx, chy, chx + 7, chy + 7, s2);
                }
                p.setColor(0x26FFFFFF);                             // chiselled lit top edge
                c.drawRect(x0, y0, x1, y0 + 3.5f, p);
                p.setColor(0x2E000000);                             // recessed shadowed bottom edge
                c.drawRect(x0, y1 - 4f, x1, y1, p);
            }
        }
        p.setColor(0x30000000);                                     // recessed mortar core lines in the joints
        for (int row = 0; row <= rows; row++) { float y = row * rh; c.drawRect(0, y - 1f, N, y + 1f, p); }
        p.setColor(0x18FFFFFF);                                     // aggregate highlight speckle
        for (int k = 0; k < 3200; k++) { float x = rnd.nextInt(N), y = rnd.nextInt(N); c.drawRect(x, y, x + 1.5f, y + 1.5f, p); }
        return b;
    }

    /** Warm horizontal timber siding for shops (carries its own colour). */
    private static Bitmap makeSidingBitmap() {
        int N = 512;
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
            // grain runs ALONG the board (the old pass drew it vertically ACROSS the planks); streaks that
            // would cross the right border are split so the grain wraps exactly
            for (int g = 0; g < 12; g++) {
                float gy = y0 + 3 + rnd.nextInt(Math.max(1, (int) (ph - 6)));
                float gx = rnd.nextInt(N); float len = 40 + rnd.nextInt(80);
                p.setColor((g % 2 == 0) ? 0x1A000000 : 0x14FFFFFF);
                if (gx + len <= N) c.drawRect(gx, gy, gx + len, gy + 1.5f, p);
                else { c.drawRect(gx, gy, N, gy + 1.5f, p); c.drawRect(0, gy, gx + len - N, gy + 1.5f, p); }
            }
            for (float sx : new float[]{N * 0.125f, N * 0.375f, N * 0.625f, N * 0.875f}) {   // nail heads at stud spacing
                p.setColor(0x44000000); c.drawCircle(sx, (y0 + y1) * 0.5f, 3f, p);
                p.setColor(0x30FFFFFF); c.drawCircle(sx + 1f, (y0 + y1) * 0.5f + 1f, 1.5f, p);
            }
            p.setColor(0x55000000); c.drawRect(0, y1 - 4f, N, y1, p);       // shadow line between boards
            p.setColor(0x26FFFFFF); c.drawRect(0, y1, N, y1 + 3f, p);       // lit top edge of next board
        }
        Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);                 // ring knots with grain deflection
        for (int k = 0; k < 12; k++) {
            float kx = 12 + rnd.nextInt(N - 24), ky = 12 + rnd.nextInt(N - 24), kr = 3f + rnd.nextFloat() * 2f;
            s.setStyle(Paint.Style.FILL); s.setColor(0x50241505); c.drawCircle(kx, ky, kr, s);
            s.setStyle(Paint.Style.STROKE); s.setStrokeWidth(1.5f); s.setColor(0x28241505); c.drawCircle(kx, ky, kr + 3f, s);
            s.setStyle(Paint.Style.FILL); s.setColor(0x16000000);
            c.drawRect(kx - 7, ky - kr - 3.5f, kx + 7, ky - kr - 2f, s);
            c.drawRect(kx - 7, ky + kr + 2f, kx + 7, ky + kr + 3.5f, s);
        }
        return b;
    }

    /** Grey gravel for the front paths (carries its own colour; world-UV tiled). */
    private static Bitmap makeGravelBitmap() {
        int N = 256;
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF6F6E6A);                                    // dark grey substrate between the stones
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(31);
        for (int k = 0; k < 1500; k++) {                            // packed pebbles, varied grey
            int g = 0x82 + rnd.nextInt(58) - 24;
            p.setColor(0xFF000000 | (cl255(g + 6) << 16) | (cl255(g) << 8) | cl255(g - 8));
            c.drawCircle(rnd.nextInt(N), rnd.nextInt(N), 1.6f + rnd.nextFloat() * 3.4f, p);
        }
        p.setColor(0x33000000);                                     // dark gaps
        for (int k = 0; k < 320; k++) c.drawCircle(rnd.nextInt(N), rnd.nextInt(N), 0.8f + rnd.nextFloat() * 1.8f, p);
        p.setColor(0x44FFFFFF);                                     // bright flecks
        for (int k = 0; k < 320; k++) c.drawCircle(rnd.nextInt(N), rnd.nextInt(N), 0.7f + rnd.nextFloat() * 1.4f, p);
        return b;
    }

    /** Draw a circle 9 times at +-N offsets so blotches WRAP at the tile border (the grass tile repeats
     *  every 4 m; clipped blotches used to print a faint 4 m grid of seam lines across the whole meadow). */
    private static void wrapCircle(Canvas c, Paint p, int N, float x, float y, float r) {
        for (int ox = -1; ox <= 1; ox++) for (int oy = -1; oy <= 1; oy++) c.drawCircle(x + ox * N, y + oy * N, r, p);
    }

    private static Bitmap makeTerrainBitmap() {
        int N = 512;
        Bitmap bmp = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF3A4A2A);                        // base grass-green
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(23);
        for (int k = 0; k < 60; k++) {                  // soft dirt/grass blotches (wrap-drawn: no tile seams)
            int shade = rnd.nextInt(3);
            int col = shade == 0 ? 0x2F3D22 : (shade == 1 ? 0x4A5C30 : 0x5A4A30);
            p.setColor(0x55000000 | col);
            wrapCircle(c, p, N, rnd.nextInt(N), rnd.nextInt(N), 18 + rnd.nextInt(70));
        }
        for (int k = 0; k < 26; k++) {                  // sun-dried warm patches (meadow, not golf lawn)
            p.setColor(0x2C6E6430);
            wrapCircle(c, p, N, rnd.nextInt(N), rnd.nextInt(N), 10 + rnd.nextInt(34));
        }
        for (int k = 0; k < 5000; k++) {                // grass speckle
            int v = rnd.nextInt(70);
            p.setColor((0x40 << 24) | ((0x30 + v) << 16) | ((0x44 + v) << 8) | (0x22 + v / 2));
            float x = rnd.nextInt(N), y = rnd.nextInt(N);
            c.drawRect(x, y, x + 1 + rnd.nextInt(2), y + 1 + rnd.nextInt(3), p);
        }
        for (int k = 0; k < 900; k++) {                 // dark blade clusters — adds depth between the speckle
            p.setColor(0x30263A18);
            float x = rnd.nextInt(N), y = rnd.nextInt(N);
            c.drawRect(x, y, x + 1 + rnd.nextInt(2), y + 2 + rnd.nextInt(3), p);
        }
        p.setColor(0x66556055);                          // scattered pebbles
        for (int k = 0; k < 120; k++) c.drawCircle(rnd.nextInt(N), rnd.nextInt(N), 1 + rnd.nextInt(2), p);
        Random r2 = new Random(47);
        for (int k = 0; k < 22; k++) {                   // clover/weed clusters: the missing middle octave
            float ccx = r2.nextInt(N), ccy = r2.nextInt(N); p.setColor(0x50274418);
            for (int j = 0; j < 12; j++) wrapCircle(c, p, N, ccx + r2.nextInt(22) - 11, ccy + r2.nextInt(22) - 11, 1.5f + r2.nextFloat() * 1.5f);
        }
        for (int k = 0; k < 14; k++) {                   // sparse wildflower flecks (dark base dot + pale head)
            float x = r2.nextInt(N), y = r2.nextInt(N);
            p.setColor(0x66203618); wrapCircle(c, p, N, x, y + 1.5f, 2f);
            p.setColor(r2.nextInt(3) == 0 ? 0x9AD8C24A : 0x8ADCD8C8);
            wrapCircle(c, p, N, x, y, 1.3f);
        }
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
        "uniform mat4 uMVP; uniform mat4 uModel; uniform float uSway; uniform mediump float uTime;" +   // mediump: must match FRAG3's default precision or the link fails
        "attribute vec4 aPos; attribute vec3 aNormal; attribute vec2 aUV;" +
        "varying vec3 vNormal; varying vec3 vWorld; varying vec2 vUV;" +
        "void main(){" +
        "  vec4 p=aPos;" +
        // Wind: vegetation/tree vertices lean with height (roots pinned) on two beating sine bands —
        // grass tips shiver, bush tops rock, tree crowns sway. All frequencies are multiples of 0.2
        // so the 10*PI uTime wrap stays seamless. uSway is 0 for everything that is not a plant.
        "  if(uSway>0.5){" +
        "    float w=clamp((aPos.y-0.15)*0.5,0.0,1.3);" +
        "    float ph=aPos.x*0.35+aPos.z*0.45;" +
        "    p.x+=(sin(uTime*1.6+ph)+0.35*sin(uTime*3.4+ph*1.7))*w*0.045;" +
        "    p.z+=cos(uTime*1.4+ph*1.3)*w*0.03;" +
        "  }" +
        "  vWorld=(uModel*p).xyz; vNormal=aNormal; vUV=aUV; gl_Position=uMVP*p; }";

    private static final String FRAG3_SRC =
        "precision mediump float;" +
        "varying vec3 vNormal; varying vec3 vWorld; varying vec2 vUV;" +
        "uniform vec3 uColor; uniform float uMode; uniform vec3 uLightDir;" +
        "uniform vec3 uCamPos; uniform vec3 uFogColor; uniform float uTime; uniform sampler2D uTex;" +
        "uniform float uSunInt; uniform float uAmbient; uniform vec2 uFogRange; uniform float uWorldUV;" +
        "uniform vec4 uPt[4]; uniform vec3 uPtC[4];" +   // dynamic point lights: xyz + 1/r^2, premultiplied colour (0 = off)
        "uniform float uChar;" +                          // 1 = character (zombie): wrap-lit so turning never blacks out
        "vec3 aces(vec3 x){ return clamp((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14),0.0,1.0); }" +
        "vec3 grade(vec3 c){ float l=dot(c,vec3(0.299,0.587,0.114)); c=mix(vec3(l),c,1.20); return clamp((c-0.5)*1.08+0.5,0.0,1.0); }" +  // richer + a little more contrast
        "void main(){" +
        "  vec3 N=normalize(vNormal); vec3 col;" +
        "  if(uMode<0.5){" +
        "    vec2 uv=vUV; float ao=1.0; vec3 bT=vec3(0.0); vec3 bB=vec3(0.0);" +
        "    if(uWorldUV>0.5){" +                                        // facades: tile by world position (not the stretched 0..1 cube UV)
        "      vec3 an=abs(N);" +
        "      if(an.y>an.x&&an.y>an.z){ uv=vWorld.xz; bT=vec3(1.0,0.0,0.0); bB=vec3(0.0,0.0,1.0); }" +   // top/bottom
        "      else if(an.x>an.z)      { uv=vWorld.zy; bT=vec3(0.0,0.0,1.0); bB=vec3(0.0,1.0,0.0); }" +   // +/-X wall
        "      else                    { uv=vWorld.xy; bT=vec3(1.0,0.0,0.0); bB=vec3(0.0,1.0,0.0); }" +   // +/-Z wall
        "      uv*=0.5;" +                                               // ~2 m per texture tile
        "      if(abs(N.y)<0.5) ao=mix(0.55,1.0,smoothstep(0.0,0.6,vWorld.y));" +   // contact shade on WALLS only (not floors/paths)
        "    }" +
        "    vec3 tex=texture2D(uTex,uv).rgb;" +
        "    if(uWorldUV>0.5){" +
        // Fake relief: the facade texture's OWN luminance gradient tilts the normal, so brick joints
        // sink and stones catch the sun — normal-map look for two extra taps, no normal maps needed.
        "      float h0=dot(tex,vec3(0.333));" +
        "      float hx=dot(texture2D(uTex,uv+vec2(0.03,0.0)).rgb,vec3(0.333));" +
        "      float hy=dot(texture2D(uTex,uv+vec2(0.0,0.03)).rgb,vec3(0.333));" +
        "      N=normalize(N+(bT*(h0-hx)+bB*(h0-hy))*1.8);" +
        "    }" +
        "    vec3 V=normalize(uCamPos-vWorld); vec3 L=normalize(uLightDir);" +
        // Characters get WRAP lighting: a zombie constantly turns, so hard N.L swung its skin from
        // sun-bleached to near-black several times a second ("mal zu blass, mal zu dunkel"). The wrap
        // carries warm key light around the form (back side keeps ~35%), capped slightly below the
        // architectural key so skin never washes out.
        "    float ndl=dot(N,L);" +
        "    float df=mix(max(ndl,0.0), max((ndl+0.65)/1.65,0.0)*0.82, uChar);" +
        "    float fl=max(dot(N,normalize(vec3(0.5,0.25,0.6))),0.0);" +
        "    float rim=pow(1.0-max(dot(N,V),0.0),3.0);" +
        // Rim is a VIEW-dependent term, and on a flat facade every fragment shares one normal — so at a
        // grazing view the whole wall flipped to pale blue-grey at once and slid back as you moved
        // ("je nach Bewegung mal normal, mal blass": measured 183/128/98 side-on vs 147/141/160 grazing).
        // Facades keep only 15% rim; props/characters (mesh UVs) keep their full edge sheen.
        "    rim*=1.0-0.85*uWorldUV;" +
        "    vec3 H=normalize(L+V); float sp=pow(max(dot(N,H),0.0),48.0);" +
        // Schlick fresnel on the Blinn lobe: on a FLAT wall every fragment shares one normal, so with the
        // sun anywhere near the view axis the pow-48 lobe covered the WHOLE face and washed it out pale
        // ("die Seite die ich anschaue ist viel zu blass"). Dielectric plaster/brick reflects ~5% at
        // normal incidence — keep the grazing-angle sheen, kill the face-on mirror.
        "    sp*=0.05+0.95*pow(1.0-max(dot(N,V),0.0),5.0);" +
        "    vec3 base=tex*uColor;" +
        // Shaded facades used to wash out to pale lavender: the only light on a sun-less face was the ambient +
        // sky-fill + rim terms, and all three were strongly blue, so they cancelled the material's own hue. Keep
        // the warm sun key, but make the non-sun light near-neutral (a whisper of cool) so a shaded brick/plaster
        // wall stays a DARKER version of itself instead of a washed-out different-looking material. Rim is halved
        // too — at the grazing angles of the overhead build view it was haloing the far walls bright.
        // Hemispheric ambient: endpoints average EXACTLY to the old flat (0.165,0.17,0.185), so vertical
        // walls are bit-identical -- only up-facing surfaces gain a sky tint and undersides a warm bounce.
        "    vec3 hemi=mix(vec3(0.155,0.150,0.140),vec3(0.175,0.190,0.230),N.y*0.5+0.5);" +
        // Overcast desaturation: uSunInt runs 1.0 (sun) .. 0.55 (rain); below full sun the warm key/spec
        // colours slide toward neutral-grey so storms stop lighting facades golden. Luminance matched.
        "    float sw=clamp((uSunInt-0.55)*2.222,0.0,1.0);" +
        "    vec3 sunC=mix(vec3(1.08,1.11,1.16),vec3(1.30,1.17,0.95),sw);" +
        "    vec3 spC=mix(vec3(0.55,0.58,0.64),vec3(0.90,0.81,0.67),sw);" +
        "    col=(base*(uAmbient*hemi+uSunInt*df*sunC+fl*vec3(0.26,0.27,0.30))" +
        "        +sp*spC*tex.r+rim*vec3(0.12,0.13,0.16))*ao;" +
        // Dynamic point lights: soft wrap-diffuse falloff — the muzzle flash licks warm light over the
        // nearest walls/ground, and the ABENDROT lanterns pool warm circles onto the street.
        "    vec3 pl=vec3(0.0);" +
        "    for(int i=0;i<4;i++){" +
        "      vec3 dl=uPt[i].xyz-vWorld;" +
        "      float at=max(0.0,1.0-dot(dl,dl)*uPt[i].w); at*=at;" +
        "      pl+=uPtC[i]*(at*(0.35+0.65*max(dot(N,normalize(dl+vec3(0.0,0.001,0.0))),0.0)));" +
        "    }" +
        "    col+=base*pl;" +
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
        "  } else if(uMode<5.5){" +                             // mode 5: real glass — reflects the sky, slightly see-through, frame stays solid
        "    vec3 tex=texture2D(uTex,vUV).rgb; vec3 N2=normalize(vNormal);" +
        "    vec3 V=normalize(uCamPos-vWorld); vec3 L=normalize(uLightDir);" +
        "    float gx=step(0.20,vUV.x)*step(vUV.x,0.80)*step(0.20,vUV.y)*step(vUV.y,0.80);" +
        "    float glass=gx*(1.0-step(0.47,vUV.x)*step(vUV.x,0.53))*(1.0-step(0.47,vUV.y)*step(vUV.y,0.53));" +   // panes only, not frame/glazing bars
        "    float fres=pow(1.0-max(dot(N2,V),0.0),3.0);" +
        "    vec3 H=normalize(L+V); float sp=pow(max(dot(N2,H),0.0),80.0); float df=max(dot(N2,L),0.0);" +
        "    vec3 col2=tex*uColor*(0.45+0.55*df);" +
        // Panes mirror the sky the player actually sees: vertically-graded blue on clear days (pale at the
        // reflected horizon, deeper toward the zenith), sliding to the grey fog colour as weather rolls in.
        "    float clr=clamp((uSunInt-0.55)*2.2,0.0,1.0);" +
        "    vec3 R=reflect(-V,N2);" +
        "    vec3 skyR=mix(vec3(0.86,0.90,0.97),vec3(0.55,0.68,0.94),clamp(R.y*1.3+0.25,0.0,1.0));" +
        "    col2=mix(col2, mix(uFogColor,skyR,clr)*max(uSunInt,0.85), glass*(0.16+0.55*fres));" +
        "    col2+=sp*glass*vec3(1.1)*uSunInt;" +                                       // crisp glass highlight
        "    float dist=length(uCamPos-vWorld); float fog=clamp((dist-uFogRange.x)/max(uFogRange.y-uFogRange.x,0.001),0.0,0.82);" +
        "    float sunAmt=pow(max(dot(normalize(vWorld-uCamPos),normalize(uLightDir)),0.0),3.0);" +
        "    col2=mix(col2,uFogColor+vec3(0.12,0.05,0.0)*sunAmt*uSunInt,fog);" +
        "    gl_FragColor=vec4(pow(grade(aces(col2)),vec3(0.4545)), mix(1.0,0.42,glass)); return;" +   // frame opaque, glass see-through (shows the dim room behind)
        "  } else {" +                                          // mode 6: animated fountain water (pools / streams / basin floor by UV.y)
        "    float d=vUV.x; float t=uTime;" +
        "    float dist=length(uCamPos-vWorld); float fog=clamp((dist-uFogRange.x)/max(uFogRange.y-uFogRange.x,0.001),0.0,0.82);" +
        "    if(vUV.y>1.5){" +                                  // basin floor: painted stone seen through the pool, darker at the rim
        "      vec3 fc=uColor*(2.3-1.1*d);" +
        "      fc=mix(fc,uFogColor,fog);" +
        "      gl_FragColor=vec4(pow(grade(aces(fc)),vec3(0.4545)),1.0); return;" +
        "    }" +
        "    if(vUV.y>0.5){" +                                  // falling stream: bright bands sliding down, splash at the foot
        "      float band=0.35+0.65*smoothstep(-0.2,0.9,sin(d*19.0-t*15.0));" +
        "      float splash=smoothstep(0.72,1.0,d)*(0.60+0.40*sin(t*9.0+vWorld.x*37.0+vWorld.z*29.0));" +
        "      vec3 wc=mix(uColor*2.2,vec3(0.93,0.97,1.00),0.30*band+0.55*splash);" +
        "      wc=mix(wc,uFogColor,fog);" +
        "      gl_FragColor=vec4(pow(clamp(wc,0.0,1.0),vec3(0.4545)),0.30+0.28*band+0.34*splash); return;" +
        "    }" +
        // Pool surface. The vertex normal's xz is the radial direction, so concentric ripple rings can be
        // sloped analytically; a churn ring sits where the overflow streams land (d~0.38), and a soft
        // world-space chop breaks the symmetry. Shading mirrors the window glass: fresnel-weighted sky
        // reflection that follows the weather, a hard sun glitter, foam lapping at the stone.
        "    vec2 rn=vNormal.xz; float rl=length(rn); vec2 rd=rl>0.02?rn/rl:vec2(0.0,0.0);" +
        "    float rq=(d-0.38)*9.0; float ring=exp(-rq*rq);" +   // NOT pow(): a negative base is UB in GLSL ES 1.00

        "    float amp=0.12+0.22*ring;" +
        "    float slope=amp*(cos(d*26.0-t*3.0)+0.55*cos(d*47.0-t*5.2));" +
        "    float chx=0.09*cos(vWorld.x*7.0+t*1.4)*sin(vWorld.z*6.0-t*1.2);" +
        "    float chz=0.09*sin(vWorld.x*6.5-t*1.2)*cos(vWorld.z*7.5+t*1.6);" +
        "    vec3 V=normalize(uCamPos-vWorld); vec3 L=normalize(uLightDir);" +
        "    vec3 N2=normalize(vec3(slope*rd.x+chx,1.0,slope*rd.y+chz));" +
        "    float fres=pow(1.0-max(dot(N2,V),0.0),3.0);" +
        "    float clr=clamp((uSunInt-0.55)*2.2,0.0,1.0);" +
        "    vec3 R=reflect(-V,N2);" +
        "    vec3 skyR=mix(vec3(0.86,0.90,0.97),vec3(0.55,0.68,0.94),clamp(R.y*1.3+0.25,0.0,1.0));" +
        "    vec3 col2=mix(uColor,mix(uFogColor,skyR,clr)*max(uSunInt,0.85),0.05+0.75*fres);" +
        "    vec3 H=normalize(L+V);" +
        "    col2+=pow(max(dot(N2,H),0.0),520.0)*vec3(2.2,2.1,1.8)*uSunInt;" +   // pinprick glitter, not a sheet: H is near-vertical over flat water
        "    float foam=smoothstep(0.92,0.995,d)*(0.40+0.35*sin(d*70.0-t*2.2))" +
        "              +ring*(0.16+0.16*sin(t*7.0+vWorld.x*23.0+vWorld.z*19.0));" +
        "    foam=clamp(foam,0.0,1.0);" +
        "    col2=mix(col2,vec3(0.93,0.97,1.00),foam*0.60);" +
        "    float al=clamp(0.78+0.22*fres+0.25*foam,0.0,0.96);" +
        "    col2=mix(col2,uFogColor,fog);" +
        "    gl_FragColor=vec4(pow(grade(aces(col2)),vec3(0.4545)),al); return;" +
        "  }" +
        "  float dist=length(uCamPos-vWorld); float fog=clamp((dist-uFogRange.x)/max(uFogRange.y-uFogRange.x,0.001),0.0,0.82);" +
        // Sun-directional inscatter: fog warms toward the sun azimuth (matching the sky's halo) so distant
        // houses fade into a horizon that agrees with where the sun is, from every camera direction.
        "  float sunAmt=pow(max(dot(normalize(vWorld-uCamPos),normalize(uLightDir)),0.0),3.0);" +
        "  col=mix(col,uFogColor+vec3(0.12,0.05,0.0)*sunAmt*uSunInt,fog);" +
        // Hue-preserving tonemap for the LIT world: per-channel ACES lives in a shoulder that squashes
        // channel ratios, so a fully sunlit facade (df near 1 when the sun is behind you) bleached from
        // terracotta to cream — "die Seite die ich anschaue ist viel zu blass". Map the LUMINANCE through
        // ACES and carry the colour with it, then blend 55/45 with the film-like per-channel curve:
        // sunlit walls keep their paint, true highlights (flash/sky) still roll off toward white.
        "  vec3 tm=aces(col);" +
        "  float cl=dot(col,vec3(0.299,0.587,0.114));" +
        "  vec3 hp=min(col*(aces(vec3(cl)).x/max(cl,0.0001)),vec3(1.0));" +
        "  gl_FragColor=vec4(pow(grade(mix(tm,hp,0.55)),vec3(0.4545)),1.0);" +
        "}";

    // --- post-processing chain (fullscreen passes over the offscreen scene) ---

    private static final String VERT_POST_SRC =
        "attribute vec2 aP; varying vec2 vUV; void main(){ vUV=aP*0.5+0.5; gl_Position=vec4(aP,0.0,1.0); }";

    // Bright-pass into the quarter-res bloom chain: only genuinely hot pixels (sun, muzzle flash,
    // water glints, lantern bulbs, sun-struck whites) pass, with a soft knee so nothing pops.
    private static final String FRAG_BRIGHT_SRC =
        "precision mediump float; varying vec2 vUV; uniform sampler2D uTex;" +
        "void main(){ vec3 c=texture2D(uTex,vUV).rgb;" +
        "  float l=dot(c,vec3(0.299,0.587,0.114));" +
        "  gl_FragColor=vec4(c*smoothstep(0.82,1.0,l),1.0); }";

    // One dimension of a 9-tap gaussian in 5 fetches (linear-filter pair trick); run twice (H then V).
    private static final String FRAG_BLUR_SRC =
        "precision mediump float; varying vec2 vUV; uniform sampler2D uTex; uniform vec2 uDir;" +
        "void main(){" +
        "  vec3 c=texture2D(uTex,vUV).rgb*0.227027;" +
        "  vec2 o1=uDir*1.3846154; vec2 o2=uDir*3.2307692;" +
        "  c+=(texture2D(uTex,vUV+o1).rgb+texture2D(uTex,vUV-o1).rgb)*0.3162162;" +
        "  c+=(texture2D(uTex,vUV+o2).rgb+texture2D(uTex,vUV-o2).rgb)*0.0702703;" +
        "  gl_FragColor=vec4(c,1.0); }";

    // Final composite: FXAA over the scene (offscreen targets lose MSAA — this wins it back and more),
    // additive bloom, and a whisper of animated film grain to break up flat gradients.
    private static final String FRAG_POST_SRC =
        "precision mediump float; varying vec2 vUV;" +
        "uniform sampler2D uScene; uniform sampler2D uBloom; uniform vec2 uInvRes; uniform float uGrainT;" +
        "uniform vec2 uSunUV; uniform float uFlare;" +   // sun screen pos + flare window (0 = sun off-screen/overcast)
        "float lum(vec3 c){ return dot(c,vec3(0.299,0.587,0.114)); }" +
        "void main(){" +
        "  vec3 rgbNW=texture2D(uScene,vUV+vec2(-1.0,-1.0)*uInvRes).rgb;" +
        "  vec3 rgbNE=texture2D(uScene,vUV+vec2( 1.0,-1.0)*uInvRes).rgb;" +
        "  vec3 rgbSW=texture2D(uScene,vUV+vec2(-1.0, 1.0)*uInvRes).rgb;" +
        "  vec3 rgbSE=texture2D(uScene,vUV+vec2( 1.0, 1.0)*uInvRes).rgb;" +
        "  vec3 rgbM =texture2D(uScene,vUV).rgb;" +
        "  float lNW=lum(rgbNW),lNE=lum(rgbNE),lSW=lum(rgbSW),lSE=lum(rgbSE),lM=lum(rgbM);" +
        "  float lMin=min(lM,min(min(lNW,lNE),min(lSW,lSE)));" +
        "  float lMax=max(lM,max(max(lNW,lNE),max(lSW,lSE)));" +
        "  vec2 dir=vec2(-((lNW+lNE)-(lSW+lSE)),((lNW+lSW)-(lNE+lSE)));" +
        "  float dirReduce=max((lNW+lNE+lSW+lSE)*0.03125,0.0078125);" +
        "  float rcpDir=1.0/(min(abs(dir.x),abs(dir.y))+dirReduce);" +
        "  dir=clamp(dir*rcpDir,vec2(-8.0),vec2(8.0))*uInvRes;" +
        "  vec3 rgbA=0.5*(texture2D(uScene,vUV+dir*(-0.166667)).rgb+texture2D(uScene,vUV+dir*0.166667).rgb);" +
        "  vec3 rgbB=rgbA*0.5+0.25*(texture2D(uScene,vUV+dir*-0.5).rgb+texture2D(uScene,vUV+dir*0.5).rgb);" +
        "  float lB=lum(rgbB);" +
        "  vec3 col=(lB<lMin||lB>lMax)?rgbA:rgbB;" +
        "  col+=texture2D(uBloom,vUV).rgb*0.55;" +
        // Sun flare package, all screen-space: the scene texture at the sun's spot gates everything
        // (a building in front of the sun = dark pixel = no flare/rays); the quarter-res bloom texture
        // (already bright-passed + blurred) is the radiance source for the god-ray march.
        "  if(uFlare>0.002){" +
        "    float occ=smoothstep(0.50,0.85,lum(texture2D(uScene,uSunUV).rgb));" +
        "    float fl=uFlare*occ;" +
        "    if(fl>0.002){" +
        "      vec2 d=uSunUV-vUV;" +
        "      vec2 stp=d*0.0833; vec2 sp=vUV+stp; float ray=0.0; float wgt=1.0;" +   // 12-tap march toward the sun
        "      for(int i=0;i<12;i++){ ray+=lum(texture2D(uBloom,sp).rgb)*wgt; sp+=stp; wgt*=0.84; }" +
        "      col+=vec3(1.0,0.82,0.55)*(ray*0.050*fl);" +
        "      vec2 asp=vec2(uInvRes.y/uInvRes.x,1.0);" +                              // isotropic screen distances
        "      float ds=length(d*asp);" +
        "      col+=vec3(1.0,0.88,0.66)*exp(-ds*ds*9.0)*0.22*fl;" +                    // broad halo around the sun
        "      vec2 ax=vec2(0.5)-uSunUV;" +                                            // ghost chain: sun -> centre -> beyond
        "      float g1=exp(-pow(length((vUV-(uSunUV+ax*0.8))*asp)*9.0,2.0));" +
        "      float g2=exp(-pow(length((vUV-(uSunUV+ax*1.4))*asp)*16.0,2.0));" +
        "      float g3=exp(-pow(length((vUV-(uSunUV+ax*1.9))*asp)*22.0,2.0));" +
        "      col+=(vec3(0.9,0.55,0.30)*g1*0.10+vec3(0.35,0.65,0.9)*g2*0.08+vec3(0.55,0.9,0.6)*g3*0.06)*fl;" +
        "    }" +
        "  }" +
        "  float g=fract(sin(dot(vUV*vec2(917.0,533.0),vec2(12.9898,78.233))+uGrainT*7.0)*43758.547);" +
        "  col+=(g-0.5)*0.016;" +
        "  gl_FragColor=vec4(col,1.0); }";

    private static final String VERT_SKY_SRC =
        "attribute vec2 aP; varying vec2 vP; void main(){ vP=aP; gl_Position=vec4(aP,0.999,1.0); }";

    // Daytime sky: rebuild a per-pixel view ray from the camera basis, then paint a blue gradient,
    // a warm sun disc + halo, and animated FBM clouds projected onto a sky plane.
    private static final String FRAG_SKY_SRC =
        "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
        "varying vec2 vP;" +
        "uniform vec3 uFwd; uniform vec3 uRight; uniform vec3 uUp; uniform vec3 uSun;" +
        "uniform vec2 uHalfFov; uniform float uTime; uniform vec3 uHorizon; uniform vec3 uZenith; uniform float uOvercast;" +
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
        "  float halo=(pow(sd,5.0)*0.45 + pow(sd,180.0)*0.7)*(1.0-0.85*uOvercast);" +   // sun veiled by cloud
        "  col+=halo*vec3(1.0,0.86,0.58);" +
        "  col+=vec3(0.09,0.045,0.0)*pow(sd,2.0)*pow(1.0-t,3.0)*(1.0-0.85*uOvercast);" +   // warm horizon on the sun side, matches the fog inscatter
        "  col=mix(col,vec3(1.05,1.0,0.92),smoothstep(0.9988,0.9994,sd)*(1.0-uOvercast));" +   // sun disc (hidden when overcast)
        "  float fade=smoothstep(0.05,0.25,h);" +
        "  if(fade>0.001){" +
        "    vec2 cp=dir.xz/max(h,0.07);" +
        "    float d=fbm(cp*1.3+vec2(uTime*0.010,uTime*0.004));" +
        "    d=d*1.7-0.45;" +
        "    float cov=smoothstep(mix(0.30,-0.05,uOvercast),mix(0.95,0.55,uOvercast),d)*fade;" +   // overcast -> denser cover
        "    cov=max(cov,uOvercast*0.78*fade);" +                                       // heavy overcast fills the sky
        "    float shade=smoothstep(0.2,1.0,d);" +
        "    vec3 cloud=mix(vec3(0.72,0.75,0.82),vec3(1.0,1.0,1.0),shade);" +
        // Seen from below, dense cumulus cores are the DARK part and thin fringes the bright part: pull the
        // densest third toward a flat grey underside and add an analytic silver lining where cover is partial.
        "    cloud=mix(cloud,vec3(0.63,0.66,0.73),smoothstep(0.70,1.25,d)*0.55*(1.0-0.5*uOvercast));" +
        "    cloud+=vec3(0.09,0.075,0.05)*cov*(1.0-cov)*3.0*(1.0-0.8*uOvercast);" +
        "    cloud=mix(cloud,vec3(0.60,0.63,0.68),uOvercast*0.7)+halo*0.6*vec3(1.0,0.9,0.7);" +   // greyer storm cloud
        "    col=mix(col,cloud,cov*mix(0.92,0.98,uOvercast));" +
        "  }" +
        "  col+=vec3((hash(vP*913.7)-0.5)*0.008);" +   // tiny dither breaks up mediump banding in the gradient
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
        // flat-ish umbra core + smoothstep penumbra fringe (the old linear tent read as an airbrush smudge)
        "void main(){ gl_FragColor=vec4(0.0,0.0,0.0,(1.0-smoothstep(0.40,1.0,vR))*uA); }";

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
