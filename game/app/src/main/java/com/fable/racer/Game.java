package com.fable.racer;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Renderer + input + screen flow for the top-down racer. All gameplay lives in
 * {@link Sim}; this class draws the simulation, plays procedural audio, runs
 * the level-select / countdown / results screens and owns the camera.
 */
final class Game {

    private static final int TITLE = 0, COUNTDOWN = 1, RACING = 2, FINISHED = 3, SHOP = 4, TUNE = 5, TUTORIAL = 6, STYLE = 7, CHAMP = 8;
    private int state = TITLE;

    // unlockable cosmetics (bought with coins)
    private static final String[] THEME_NAME = {"CLASSIC", "SAKURA", "DESERT", "SYNTHWAVE", "ARCTIC", "TOXIC"};
    private static final int[] THEME_COST = {0, 250, 300, 450, 450, 400};
    // {grass, asphalt, centre-line, tree-tint (0 = default green)}
    private static final int[][] THEME = {
            {0xFF2E7D52, 0xFF3C3F55, 0xFFFFE34D, 0},
            {0xFF3A8C6A, 0xFF4A4458, 0xFFFFC0E0, 0xFFFF6FA8},
            {0xFFB5894E, 0xFF534C44, 0xFFFFE08A, 0xFF8A7A3A},
            {0xFF241A3A, 0xFF2A2350, 0xFFFF2E88, 0xFF6A3AA0},
            {0xFFBFD8E8, 0xFF5A6470, 0xFFFFFFFF, 0xFF8FB0C0},
            {0xFF2F6B2A, 0xFF3A4A30, 0xFF9CFF3D, 0xFF4FA030},
    };
    private static final String[] SKIN_NAME = {"SOLID", "STRIPE", "SPLIT", "CARBON"};
    private static final int[] SKIN_COST = {0, 150, 200, 250};
    private int theme, carSkin, ownedThemes = 1, ownedSkins = 1, styleSel;
    private final RectF[] themeCards = new RectF[6];
    private final RectF[] skinChips = new RectF[4];
    private final RectF btnStyle = new RectF();

    // race modes
    private static final int MODE_GP = 0, MODE_TT = 1, MODE_ELIM = 2, MODE_DRIFT = 3;
    private static final String[] MODE_NAME = {"GRAND PRIX", "TIME TRIAL", "ELIMINATION", "DRIFT TRIAL"};
    private int mode = MODE_GP;
    private boolean solo() { return mode == MODE_TT || mode == MODE_DRIFT; }

    // current track (fixed level or procedural)
    private Tracks.Def curDef;
    private boolean randomTrack;
    private int trackCode = 1234;
    private final RectF btnRandom = new RectF(), btnReroll = new RectF(), btnGo = new RectF();
    private final RectF[] codeUp = new RectF[5], codeDn = new RectF[5];

    // championship season
    private static final int[] CHAMP_TRACKS = {0, 2, 4, 6, 7};
    private static final int[] CHAMP_PTS = {25, 18, 15, 12};
    private boolean champ;
    private int champRound;
    private final int[] champScore = new int[4];   // 0=YOU,1=BLAZE,2=NOVA,3=SAGE
    private final RectF btnChamp = new RectF();

    // elimination + drift
    private double elimTimer;
    private boolean playerOut;
    private String elimBanner;
    private double elimBannerT;
    private double driftScore, driftCombo;

    // juice
    private double zoomMul = 1, finishTimer, flash;
    private int prevPos = 1;
    private String overtakeBanner;
    private double overtakeT;

    // atmosphere
    private boolean musicOn = true;
    private final RectF btnMusic = new RectF();
    private float shX, shY, shAlpha;     // directional shadow offset (sun)
    private android.os.Vibrator vibrator;
    // car tuning sliders (-100..100), trade-offs applied on top of upgrades
    private int tuneSpeed, tuneHandling, tuneStab;
    private final RectF[] tuneBars = new RectF[3];
    private int tuneSel;
    private final RectF btnTune = new RectF(), btnHelp = new RectF(), btnMode = new RectF();
    // session best lap (for the leaderboard) + tutorial paging
    private double raceBestLap;
    private int tutorialPage;
    private boolean seenTutorial;

    // ---- shop / upgrades ----
    private static final int SHOP_N = 6;
    private static final String[] SHOP_NAME = {
            "TURBO ENGINE", "SLICK TYRES", "NITROUS TANK", "LIGHT FRAME", "RALLY KIT", "SPORT BRAKES"};
    private static final String[] SHOP_PRO = {
            "+ top speed & accel", "+ grip & cornering", "+ bigger boost & nitro",
            "+ accel & turn-in", "+ off-road & wet grip", "+ braking & drift"};
    private static final String[] SHOP_CON = {
            "- less grip", "- poor in the rain", "- heavier, slow accel",
            "- knocked around easily", "- lower top speed", "- lower top speed"};
    private static final int[] SHOP_COST = {300, 400, 450, 500, 500, 350};
    private static final int[] SHOP_REQ = {2, 3, 4, 5, 6, 7};

    // meta progression (persisted)
    private int coins, xp;
    private final boolean[] owned = new boolean[SHOP_N];
    private final boolean[] equipped = new boolean[SHOP_N];
    private boolean awarded;
    private int gainCoins, gainXp;
    private boolean leveledUp;

    // ghost of the best lap on the current track
    private float[][] ghost;
    private final List<float[]> lapRec = new ArrayList<>();
    private double recTimer;
    private final double[] trackBest = new double[Tracks.LEVELS.length];

    private final RectF btnShop = new RectF(), btnBack = new RectF();
    private final RectF[] shopCards = new RectF[SHOP_N];

    // tyre compound strategy: 0 soft, 1 medium, 2 wet
    private int tire = 1;
    private final RectF[] tireBtns = new RectF[3];
    private static final String[] TIRE_NAME = {"SOFT", "MED", "WET"};

    // daily challenge (deterministic from the date)
    private long today;
    private int dailyTrack, dailyType;        // 0 win, 1 clean lap-set, 2 no spinouts
    private boolean dailyDoneToday, dailyJustWon, offTrackEver, hitEver;
    private final RectF dailyCard = new RectF();
    private static final int DAILY_REWARD = 350;

    // controller state (merged with touch); separate button vs axis sources
    private boolean kLeft, kRight, kGas, kBrake, aLeft, aRight, aGas, aBrake;
    private int shopSel;

    private int width, height;
    private double zoom = 1.2;

    private final Sim sim = new Sim();
    private int level = 0;
    private int unlocked = 1;
    private int playerColorIdx = 0;

    private double camX, camY, shake = 0;
    private double countdown = 0, dayTime = 0.2, titlePulse = 0, dash = 0;
    private int prevLaps = 0;
    private double bestLap = 0;
    private boolean lastFinishWon = false;

    private final float[][] rain = new float[120][3];
    private final float[][] stars = new float[90][3];
    private final float[][] menuDots = new float[44][4];   // x,y,speed,size — animated menu bg
    private int renderedState = -1;
    private double transT;                                  // screen-transition fade
    private final List<Particle> particles = new ArrayList<>();
    private final List<float[]> skidMarks = new ArrayList<>();
    private final List<float[]> scenery = new ArrayList<>();  // x,y,r,type
    private android.graphics.RadialGradient vignette;

    boolean keyLeft, keyRight, keyGas, keyBrake;
    private boolean tapBoost, tapItem;

    private final RectF btnLeft = new RectF(), btnRight = new RectF();
    private final RectF btnGas = new RectF(), btnBrake = new RectF();
    private final RectF btnBoost = new RectF(), btnItem = new RectF();
    private final RectF[] swatches = new RectF[6];
    private final RectF[] levelBtns = new RectF[Tracks.LEVELS.length];

    private final Paint road = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path roadPath = new Path();
    private final Path tmpPath = new Path();
    private final RectF tmp = new RectF(), tmp2 = new RectF();
    private final Random rnd = new Random();
    private final PorterDuffXfermode ADD = new PorterDuffXfermode(PorterDuff.Mode.ADD);
    private final PorterDuffXfermode MUL = new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY);

    // procedurally generated, seamlessly tiling textures (no asset files, no licences)
    private BitmapShader asphaltShader, grassShaderTex, metalShader;
    private final Paint texPaint = new Paint();

    // perf: cache GPU/native objects so the render loop allocates nothing
    private final java.util.HashMap<Integer, LinearGradient> bodyGrad = new java.util.HashMap<>();
    private LinearGradient backdropShader;
    private int bdTop, bdBot, bdH;
    private final java.util.ArrayDeque<Particle> particlePool = new java.util.ArrayDeque<>();

    private final SharedPreferences prefs;
    private EngineAudio audio;

    private static final int[] CAR_COLORS = {
            0xFF19E0FF, 0xFFFF4D9D, 0xFF8CFF45, 0xFFFFC23D, 0xFFB66BFF, 0xFFFFFFFF
    };

    Game(Context ctx, int w, int h) {
        prefs = ctx == null ? null : ctx.getSharedPreferences("turbo", Context.MODE_PRIVATE);
        if (prefs != null) {
            unlocked = Math.max(1, prefs.getInt("unlocked", 1));
            bestLap = prefs.getFloat("bestLap", 0f);
            playerColorIdx = prefs.getInt("color", 0);
            coins = prefs.getInt("coins", 0);
            xp = prefs.getInt("xp", 0);
            int own = prefs.getInt("owned", 0), eq = prefs.getInt("equip", 0);
            for (int i = 0; i < SHOP_N; i++) {
                owned[i] = (own & (1 << i)) != 0;
                equipped[i] = (eq & (1 << i)) != 0;
                trackBest[i] = prefs.getFloat("tb" + i, 0f);
            }
            tire = prefs.getInt("tire", 1);
            tuneSpeed = prefs.getInt("tuneSpeed", 0);
            tuneHandling = prefs.getInt("tuneHandling", 0);
            tuneStab = prefs.getInt("tuneStab", 0);
            seenTutorial = prefs.getBoolean("seenTutorial", false);
            theme = prefs.getInt("theme", 0);
            carSkin = prefs.getInt("skin", 0);
            ownedThemes = prefs.getInt("ownedThemes", 1) | 1;
            ownedSkins = prefs.getInt("ownedSkins", 1) | 1;
            musicOn = prefs.getBoolean("music", true);
        }
        try {
            if (ctx != null) vibrator = (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Throwable t) { vibrator = null; }
        computeDaily();
        if (!seenTutorial) { state = TUTORIAL; tutorialPage = 0; }
        text.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        for (int i = 0; i < stars.length; i++) {
            stars[i][0] = rnd.nextFloat(); stars[i][1] = rnd.nextFloat() * 0.7f;
            stars[i][2] = 0.6f + rnd.nextFloat() * 1.8f;
        }
        for (int i = 0; i < menuDots.length; i++) {
            menuDots[i][0] = rnd.nextFloat();
            menuDots[i][1] = rnd.nextFloat();
            menuDots[i][2] = 0.02f + rnd.nextFloat() * 0.06f;
            menuDots[i][3] = 1.5f + rnd.nextFloat() * 3.5f;
        }
        try { audio = new EngineAudio(); } catch (Throwable t) { audio = null; }
        buildTextures();
        loadLevel(0);
        resize(w, h);
    }

    /** Build tiny, seamlessly-tiling grayscale textures once. They are blended
     *  with MULTIPLY over the themed flat colours, so theming + day/night still
     *  work and nothing is allocated per frame. */
    private void buildTextures() {
        asphaltShader = new BitmapShader(grainTile(128, 232, 255, 1700, 120, 70),
                Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        grassShaderTex = new BitmapShader(grassTile(128),
                Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        metalShader = new BitmapShader(grainTile(48, 224, 255, 360, 170, 60),
                Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        texPaint.setAntiAlias(false);
        texPaint.setXfermode(MUL);
    }

    private Bitmap grainTile(int s, int lo, int hi, int speckles, int spLo, int spRange) {
        int[] px = new int[s * s];
        int span = hi - lo + 1;
        for (int i = 0; i < px.length; i++) { int v = lo + rnd.nextInt(span); px[i] = 0xFF000000 | (v << 16) | (v << 8) | v; }
        for (int k = 0; k < speckles; k++) { int v = spLo + rnd.nextInt(spRange); px[rnd.nextInt(px.length)] = 0xFF000000 | (v << 16) | (v << 8) | v; }
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, s, 0, 0, s, s);
        return b;
    }

    private Bitmap grassTile(int s) {
        int[] px = new int[s * s];
        for (int i = 0; i < px.length; i++) { int v = 234 + rnd.nextInt(22); px[i] = 0xFF000000 | (v << 16) | (v << 8) | v; }
        for (int k = 0; k < s * 6; k++) {            // short vertical blades
            int x = rnd.nextInt(s), y = rnd.nextInt(s), len = 2 + rnd.nextInt(5), v = 196 + rnd.nextInt(34);
            for (int j = 0; j < len; j++) { int yy = (y + j) % s; px[yy * s + x] = 0xFF000000 | (v << 16) | (v << 8) | v; }
        }
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, s, 0, 0, s, s);
        return b;
    }

    void release() { if (audio != null) audio.release(); }

    private void loadLevel(int lvl) {
        level = Math.max(0, Math.min(Tracks.LEVELS.length - 1, lvl));
        curDef = randomTrack ? Tracks.random(trackCode) : Tracks.LEVELS[level];
        sim.load(curDef, randomTrack ? trackCode : System.nanoTime(), solo());
        rebuildRoadPath();
        dayTime = curDef.dayTime < 0 ? 0.25 : curDef.dayTime;
        for (int i = 0; i < rain.length; i++) {
            rain[i][0] = rnd.nextFloat(); rain[i][1] = rnd.nextFloat(); rain[i][2] = 0.5f + rnd.nextFloat();
        }
        particles.clear();
        skidMarks.clear();
        buildScenery();
        prevLaps = 0;
        lapRec.clear();
        recTimer = 0;
        ghost = randomTrack ? null : loadGhost(level);
        applyLoadout();
        camX = (sim.minX + sim.maxX) / 2;
        camY = (sim.minY + sim.maxY) / 2;
    }

    /** Procedural off-track scenery: trees, bushes, rocks and flower patches. */
    private void buildScenery() {
        scenery.clear();
        Random sr = new Random(sim.N * 31L + level);
        int guard = 0;
        while (scenery.size() < 46 && guard++ < 6000) {
            float x = sim.minX - 320 + sr.nextFloat() * (sim.maxX - sim.minX + 640);
            float y = sim.minY - 320 + sr.nextFloat() * (sim.maxY - sim.minY + 640);
            float d = sim.distToTrack(x, y);
            if (d < Sim.ROAD_HALF + 50) continue;             // keep off the road
            float roll = sr.nextFloat();
            int type = roll < 0.45f ? 0 : (roll < 0.7f ? 1 : (roll < 0.88f ? 2 : 3)); // tree/bush/rock/flowers
            float r = (type == 0 ? 26 : type == 1 ? 18 : type == 2 ? 14 : 22) + sr.nextFloat() * 14;
            scenery.add(new float[]{x, y, r, type, sr.nextFloat()});
        }
    }

    private void rebuildRoadPath() {
        roadPath.reset();
        roadPath.moveTo(sim.cx[0], sim.cy[0]);
        for (int i = 1; i < sim.N; i++) roadPath.lineTo(sim.cx[i], sim.cy[i]);
        roadPath.close();
    }

    void resize(int w, int h) {
        width = w; height = h;
        zoom = Math.min(w, h) / 640.0;
        vignette = new android.graphics.RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.72f,
                new int[]{0x00000000, 0x00000000, 0x66000000}, new float[]{0f, 0.6f, 1f},
                Shader.TileMode.CLAMP);
        layoutControls();
    }

    private void layoutControls() {
        float r = Math.min(width, height) * 0.10f;
        float pad = Math.min(width, height) * 0.04f;
        float by = height - pad - r * 2;
        btnLeft.set(pad, by, pad + r * 2, by + r * 2);
        btnRight.set(pad + r * 2.2f, by, pad + r * 4.2f, by + r * 2);
        btnGas.set(width - pad - r * 2, by, width - pad, by + r * 2);
        btnBrake.set(width - pad - r * 4.2f, by, width - pad - r * 2.2f, by + r * 2);
        btnBoost.set(width - pad - r * 1.7f, by - r * 1.9f, width - pad + r * 0.3f, by - r * 1.9f + r * 1.6f);
        btnItem.set(width - pad - r * 3.9f, by - r * 1.9f, width - pad - r * 2.3f, by - r * 1.9f + r * 1.6f);

        float sw = Math.min(width, height) * 0.066f;
        float sx = width / 2f - sw * 3.3f, sy = height * 0.74f;
        for (int i = 0; i < swatches.length; i++)
            swatches[i] = new RectF(sx + i * sw * 1.1f, sy, sx + i * sw * 1.1f + sw, sy + sw);

        // tyre compound selector (row below the colour swatches)
        float tw0 = Math.min(width * 0.12f, 150), th0 = Math.min(width, height) * 0.07f;
        float tx = width / 2f - (tw0 * 3 + 2 * tw0 * 0.1f) / 2f, ty = height * 0.84f;
        for (int i = 0; i < 3; i++)
            tireBtns[i] = new RectF(tx + i * tw0 * 1.1f, ty, tx + i * tw0 * 1.1f + tw0, ty + th0);

        // daily challenge card (wide bar)
        float dcw = Math.min(width * 0.62f, 760), dch = Math.min(width, height) * 0.1f;
        dailyCard.set(width / 2f - dcw / 2f, height * 0.6f, width / 2f + dcw / 2f, height * 0.6f + dch);

        int cols = 4, rows = (levelBtns.length + cols - 1) / cols;
        float bw = Math.min(width * 0.21f, 320), bh = height * 0.125f;
        float gapX = bw * 0.12f, gapY = bh * 0.22f;
        float totalW = cols * bw + (cols - 1) * gapX;
        float startX = width / 2f - totalW / 2f, startY = height * 0.27f;
        for (int i = 0; i < levelBtns.length; i++) {
            int col = i % cols, row = i / cols;
            float x = startX + col * (bw + gapX), y = startY + row * (bh + gapY);
            levelBtns[i] = new RectF(x, y, x + bw, y + bh);
        }

        // top-right pill buttons: ? · STYLE · TUNE · SHOP
        float shh = Math.min(width, height) * 0.09f;
        float pw = Math.min(width * 0.13f, 180), gpw = pw * 0.1f;
        btnShop.set(width - pad - pw, pad, width - pad, pad + shh);
        btnTune.set(btnShop.left - gpw - pw, pad, btnShop.left - gpw, pad + shh);
        btnStyle.set(btnTune.left - gpw - pw, pad, btnTune.left - gpw, pad + shh);
        btnHelp.set(btnStyle.left - gpw - pw * 0.55f, pad, btnStyle.left - gpw, pad + shh);
        btnBack.set(pad, pad, pad + pw * 0.9f, pad + shh);
        // style screen: 6 theme cards (3x2) + 4 skin chips
        float tcw = Math.min(width * 0.27f, 360), tch = height * 0.2f;
        float tgx = tcw * 0.07f, tgy = tch * 0.14f;
        float ttw = 3 * tcw + 2 * tgx, tsx = width / 2f - ttw / 2f, tsy = height * 0.2f;
        for (int i = 0; i < 6; i++) {
            int col = i % 3, row = i / 3;
            float x = tsx + col * (tcw + tgx), y = tsy + row * (tch + tgy);
            themeCards[i] = new RectF(x, y, x + tcw, y + tch);
        }
        float chw = Math.min(width * 0.16f, 220), chh2 = height * 0.1f;
        float ctw = 4 * chw + 3 * (chw * 0.08f), csx = width / 2f - ctw / 2f, csy = height * 0.78f;
        for (int i = 0; i < 4; i++)
            skinChips[i] = new RectF(csx + i * (chw + chw * 0.08f), csy, csx + i * (chw + chw * 0.08f) + chw, csy + chh2);
        // menu second row: MODE · CHAMPIONSHIP · RANDOM · MUSIC
        float prow = height * 0.185f, pwid = Math.min(width * 0.18f, 250), pgap = pwid * 0.05f;
        float totalP = 4 * pwid + 3 * pgap, px0 = width / 2f - totalP / 2f;
        btnMode.set(px0, prow, px0 + pwid, prow + shh);
        btnChamp.set(px0 + (pwid + pgap), prow, px0 + (pwid + pgap) + pwid, prow + shh);
        btnRandom.set(px0 + 2 * (pwid + pgap), prow, px0 + 2 * (pwid + pgap) + pwid, prow + shh);
        btnMusic.set(px0 + 3 * (pwid + pgap), prow, px0 + 3 * (pwid + pgap) + pwid, prow + shh);
        // random-track code steppers + reroll + go (shown when RANDOM is active)
        float cw0 = Math.min(width * 0.055f, 70), ccx = width / 2f - 5 * cw0 * 1.2f / 2f, ccy = height * 0.46f;
        for (int i = 0; i < 5; i++) {
            float dx0 = ccx + i * cw0 * 1.2f;
            codeUp[i] = new RectF(dx0, ccy - cw0 * 1.0f, dx0 + cw0, ccy - cw0 * 0.1f);
            codeDn[i] = new RectF(dx0, ccy + cw0 * 1.0f, dx0 + cw0, ccy + cw0 * 1.9f);
        }
        btnReroll.set(width / 2f - pwid - pgap, height * 0.6f, width / 2f - pgap, height * 0.6f + shh);
        btnGo.set(width / 2f + pgap, height * 0.6f, width / 2f + pgap + pwid, height * 0.6f + shh);
        // tuning sliders
        float tbw = Math.min(width * 0.5f, 660), tbh = Math.min(width, height) * 0.05f;
        float tbx = width / 2f - tbw / 2f, tby = height * 0.36f;
        for (int i = 0; i < 3; i++)
            tuneBars[i] = new RectF(tbx, tby + i * tbh * 2.4f, tbx + tbw, tby + i * tbh * 2.4f + tbh);

        // shop cards: 3 x 2 grid
        int sc = 3;
        float cw = Math.min(width * 0.28f, 380), chh = height * 0.27f;
        float sgx = cw * 0.07f, sgy = chh * 0.1f;
        float stw = sc * cw + (sc - 1) * sgx;
        float ssx = width / 2f - stw / 2f, ssy = height * 0.26f;
        for (int i = 0; i < SHOP_N; i++) {
            int col = i % sc, row = i / sc;
            float x = ssx + col * (cw + sgx), y = ssy + row * (chh + sgy);
            shopCards[i] = new RectF(x, y, x + cw, y + chh);
        }
    }

    // --------------------------------------------------------------- update

    void update(double dt) {
        titlePulse += dt;
        dayTime += dt / 90.0; if (dayTime >= 1) dayTime -= 1;
        overtakeT = Math.max(0, overtakeT - dt);
        elimBannerT = Math.max(0, elimBannerT - dt);
        flash = Math.max(0, flash - dt * 2);
        transT = Math.max(0, transT - dt);

        if (state == TITLE || state == SHOP || state == TUNE || state == STYLE || state == TUTORIAL || state == CHAMP) {
            idleAudio(); return;
        }
        if (state == COUNTDOWN) {
            int prev = (int) Math.ceil(countdown);
            countdown -= dt;
            int now = (int) Math.ceil(countdown);
            if (now != prev && now >= 1 && now <= 3 && audio != null) audio.blip(EngineAudio.BLIP_COUNT);
            zoomMul = 1 + 0.5 * Math.max(0, countdown / 3.99);     // start zoomed in
            if (countdown <= 0) { state = RACING; if (audio != null) audio.blip(EngineAudio.BLIP_GO); vibrate(40); }
            idleAudio();
            return;
        }
        if (state == FINISHED) { idleAudio(); updateParticles(dt); return; }

        // ---- RACING ----
        boolean lockInput = finishTimer > 0 || playerOut;
        boolean L = !lockInput && (keyLeft || kLeft || aLeft);
        boolean R = !lockInput && (keyRight || kRight || aRight);
        boolean G = !lockInput && (keyGas || kGas || aGas);
        boolean B = !lockInput && (keyBrake || kBrake || aBrake);
        sim.update(dt, L, R, G, B, !lockInput && tapBoost, !lockInput && tapItem);
        tapBoost = tapItem = false;

        if (!sim.onTrack) { offTrackEver = true; shake = Math.min(shake + dt * 22, 4); }

        if (sim.evBoost && audio != null) audio.blip(EngineAudio.BLIP_BOOST);
        if (sim.evPickup && audio != null) audio.blip(EngineAudio.BLIP_PICKUP);
        if (sim.evHit) { shake = 8; hitEver = true; vibrate(60); if (audio != null) audio.blip(EngineAudio.BLIP_HIT); }
        if (sim.evJump) { vibrate(25); if (audio != null) audio.blip(EngineAudio.BLIP_BOOST); }
        if (sim.evLand) { shake = Math.max(shake, 4); vibrate(35); }
        sim.clearEvents();

        if (sim.drifting) {
            emitSmoke(); addSkid();
            if (mode == MODE_DRIFT) driftScore += sim.speed() * dt * 0.04;
        }

        // overtake banner (position improved)
        int pos = sim.position();
        if (pos < prevPos && raceBestLap >= 0) { overtakeBanner = "OVERTAKE!  P" + pos; overtakeT = 1.6; }
        prevPos = pos;

        // best lap + ghost recording (skip on random tracks)
        if (sim.lapsDone > prevLaps) {
            prevLaps = sim.lapsDone;
            double lt = sim.lastLapTime;
            if (lt > 0.5 && (raceBestLap == 0 || lt < raceBestLap)) raceBestLap = lt;
            if (lt > 0.5 && (bestLap == 0 || lt < bestLap)) {
                bestLap = lt;
                if (prefs != null) prefs.edit().putFloat("bestLap", (float) bestLap).apply();
            }
            if (!randomTrack && lt > 0.5 && (trackBest[level] == 0 || lt < trackBest[level]) && lapRec.size() > 4) {
                trackBest[level] = lt;
                saveGhost(level, lapRec);
            }
            lapRec.clear();
            recTimer = 0;
        }
        recTimer += dt;
        if (recTimer >= 0.05) {
            recTimer = 0;
            lapRec.add(new float[]{(float) sim.lapTime, (float) sim.carX, (float) sim.carY, (float) sim.carAngle});
        }

        // elimination mode: knock out the last-placed racer on a timer
        if (mode == MODE_ELIM && !sim.finished && finishTimer <= 0) {
            elimTimer -= dt;
            if (elimTimer <= 0 && activeRacers() > 1) { eliminateLast(); elimTimer = 7; }
        }

        // camera (with finish punch-in easing back to normal)
        double sp = sim.speed();
        double look = 120 * (sp / Sim.MAX_SPEED);
        double tx = sim.carX + Math.cos(sim.carAngle) * look;
        double ty = sim.carY + Math.sin(sim.carAngle) * look;
        camX += (tx - camX) * Math.min(1, dt * 6);
        camY += (ty - camY) * Math.min(1, dt * 6);
        zoomMul += (1 - zoomMul) * Math.min(1, dt * 4);
        shake = Math.max(0, shake - dt * 12);
        updateParticles(dt);

        if (audio != null) {
            float rpmF = (float) Math.min(1, sp / Sim.MAX_SPEED + 0.05);
            audio.set(rpmF, 1f, sim.boostTime > 0, sim.drifting ? 0.9f : 0f);
            audio.setMusic(musicOn, (float) (0.4 + 0.45 * (sp / Sim.MAX_SPEED)));
        }

        // finish flourish then results
        if ((sim.finished || playerOut) && finishTimer <= 0 && !awarded) {
            finishTimer = 1.2; flash = 1; zoomMul = 1.3; vibrate(playerOut ? 120 : 50);
        }
        if (finishTimer > 0) {
            finishTimer -= dt;
            if (finishTimer <= 0) finalizeFinish();
        }
    }

    private int activeRacers() {
        int n = playerOut ? 0 : 1;
        for (Sim.Ai a : sim.ais) if (!a.eliminated) n++;
        return n;
    }

    private void eliminateLast() {
        // find the lowest progress among active racers
        double playerProg = sim.lapsDone * (double) sim.N + sim.prevIdx;
        Sim.Ai worst = null;
        double worstProg = playerOut ? Double.MAX_VALUE : playerProg;
        for (Sim.Ai a : sim.ais) {
            if (a.eliminated) continue;
            double p = a.lap * (double) sim.N + a.t;
            if (p < worstProg) { worstProg = p; worst = a; }
        }
        if (worst == null) {                 // the player is last -> out
            playerOut = true;
            elimBanner = "YOU'RE ELIMINATED";
            elimBannerT = 3;
        } else {
            worst.eliminated = true;
            elimBanner = worst.name + " ELIMINATED";
            elimBannerT = 2.2;
            vibrate(30);
            if (activeRacers() <= 1) { lastFinishWon = true; }   // player is the survivor
        }
    }

    private void finalizeFinish() {
        lastFinishWon = !playerOut && (sim.position() == 1 || (mode == MODE_ELIM && activeRacers() <= 1));
        if (!randomTrack && !champ) {
            int next = level + 1;
            if (next < Tracks.LEVELS.length && next + 1 > unlocked) {
                unlocked = next + 1;
                if (prefs != null) prefs.edit().putInt("unlocked", unlocked).apply();
            }
        }
        if (!awarded) {
            if (!randomTrack) addLeaderboard(level, (float) raceBestLap);
            awardRace();
            dailyJustWon = false;
            if (!randomTrack && !champ && !dailyDoneToday && dailyMet()) {
                coins += DAILY_REWARD;
                dailyDoneToday = true;
                dailyJustWon = true;
                if (prefs != null) prefs.edit().putLong("dailyDone", today).apply();
                saveProfile();
            }
            if (champ) awardChampPoints();
            awarded = true;
        }
        state = FINISHED;
    }

    private void awardChampPoints() {
        // rank YOU + rivals by progress, hand out F1-style points
        int[] idx = {0, 1, 2, 3};
        final double[] prog = new double[4];
        prog[0] = playerOut ? -1 : sim.lapsDone * (double) sim.N + sim.prevIdx;
        for (int i = 0; i < sim.ais.size() && i < 3; i++) {
            Sim.Ai a = sim.ais.get(i);
            prog[i + 1] = a.eliminated ? -1 : a.lap * (double) sim.N + a.t;
        }
        Integer[] order = {0, 1, 2, 3};
        java.util.Arrays.sort(order, (x, y) -> Double.compare(prog[y], prog[x]));
        for (int rank = 0; rank < 4; rank++) champScore[order[rank]] += CHAMP_PTS[rank];
    }

    @SuppressWarnings("deprecation")
    private void vibrate(int ms) {
        if (vibrator == null) return;
        try { vibrator.vibrate((long) ms); } catch (Throwable ignored) {}
    }

    private void idleAudio() {
        if (audio == null) return;
        audio.set(0.06f, state == TITLE ? 0.5f : 0.7f, false, 0f);
        audio.setMusic(musicOn, 0.42f);
    }

    private void emitSmoke() {
        if (particles.size() > 160) return;
        Particle p = particlePool.poll();        // reuse a pooled particle if available
        if (p == null) p = new Particle();
        p.x = sim.carX - Math.cos(sim.carAngle) * 28 + (rnd.nextDouble() - 0.5) * 14;
        p.y = sim.carY - Math.sin(sim.carAngle) * 28 + (rnd.nextDouble() - 0.5) * 14;
        p.vx = (rnd.nextDouble() - 0.5) * 30; p.vy = (rnd.nextDouble() - 0.5) * 30;
        p.life = p.maxLife = 0.6 + rnd.nextDouble() * 0.4;
        p.size = 10 + rnd.nextFloat() * 10;
        particles.add(p);
    }

    private void addSkid() {
        if (skidMarks.size() > 400) skidMarks.remove(0);
        skidMarks.add(new float[]{(float) sim.carX, (float) sim.carY, (float) sim.carAngle});
    }

    private void updateParticles(double dt) {
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life -= dt; p.x += p.vx * dt; p.y += p.vy * dt; p.size += dt * 18;
            if (p.life <= 0) {
                particles.remove(i);
                if (particlePool.size() < 200) particlePool.add(p);   // recycle
            }
        }
    }

    // --------------------------------------------------------------- render

    void render(Canvas g) {
        if (state != renderedState) { transT = 0.35; renderedState = state; }
        float darkness = nightAmount();
        int grass = lerp(THEME[theme][0], 0xFF0A2238, darkness * 0.85f);
        if (sim.wetness > 0.1) grass = lerp(grass, 0xFF20465A, 0.4f * (float) sim.wetness);
        g.drawColor(grass);

        // directional shadow offset from a sun that moves with the time of day
        double sunA = dayTime * 2 * Math.PI;
        float slen = (float) (10 + 16 * Math.abs(Math.cos(sunA)));
        shX = (float) Math.cos(sunA + Math.PI) * slen;
        shY = (float) Math.sin(sunA + Math.PI) * slen * 0.6f;
        shAlpha = 0.4f * (1 - darkness * 0.6f);

        g.save();
        float sx = (float) (shake * (rnd.nextDouble() - 0.5));
        float sy = (float) (shake * (rnd.nextDouble() - 0.5));
        g.translate(width / 2f + sx, height / 2f + sy);
        g.scale((float) (zoom * zoomMul), (float) (zoom * zoomMul));
        g.translate((float) -camX, (float) -camY);

        // grass texture grain over the themed ground (tiles + scrolls in world space)
        float hw = (float) (width / (zoom * zoomMul)) * 0.6f + 64;
        float hh = (float) (height / (zoom * zoomMul)) * 0.6f + 64;
        texPaint.setStyle(Paint.Style.FILL);
        texPaint.setShader(grassShaderTex);
        g.drawRect((float) camX - hw, (float) camY - hh, (float) camX + hw, (float) camY + hh, texPaint);

        drawScenery(g, false);          // ground shadows first
        drawSkidMarks(g);
        drawShortcuts(g);
        drawRoad(g);
        drawPads(g);
        drawJumps(g);
        drawFinishLine(g);
        drawOils(g);
        drawBoxes(g);
        drawScenery(g, true);           // tree canopies above the road edge
        for (Sim.Ai a : sim.ais) if (!a.eliminated) drawCar(g, a.x, a.y, a.angle, a.color, false, 1f, 0f);
        drawGhost(g);
        float js = 1f + (float) sim.jumpZ * 0.012f;
        drawCar(g, sim.carX, sim.carY, sim.carAngle, CAR_COLORS[playerColorIdx], true, js, (float) sim.jumpZ * 1.4f);
        drawParticles(g);
        g.restore();

        if (darkness > 0.02f) drawNightLighting(g, darkness);
        drawColorGrade(g, darkness);
        if (sim.wetness > 0.12) drawRain(g, (float) sim.wetness);
        if (sim.boostTime > 0) drawSpeedLines(g);
        drawVignette(g);
        if (flash > 0) { ui.setColor(((int) (flash * 120) << 24) | 0xFFFFFF); g.drawRect(0, 0, width, height, ui); }

        drawRadar(g);
        renderHud(g);
        renderControls(g);
        renderBanners(g);

        if (state == TITLE) renderTitle(g);
        else if (state == CHAMP) renderChamp(g);
        else if (state == SHOP) renderShop(g);
        else if (state == STYLE) renderStyle(g);
        else if (state == TUNE) renderTune(g);
        else if (state == TUTORIAL) renderTutorial(g);
        else if (state == COUNTDOWN) renderCountdown(g);
        else if (state == FINISHED) renderResults(g);

        if (transT > 0) {                          // screen-transition fade
            ui.setColor(((int) (Math.min(1, transT / 0.35) * 200) << 24));
            g.drawRect(0, 0, width, height, ui);
        }
    }

    private float nightAmount() {
        double d = Math.cos((dayTime - 0.25) * 2 * Math.PI);
        return (float) Math.max(0, Math.min(1, (-d + 0.35) / 1.1));
    }

    /** Allocation-free additive bloom: a few stacked translucent rings. */
    private void glow(Canvas g, float x, float y, float r, int rgb, int alpha) {
        ui.setXfermode(ADD);
        rgb &= 0xFFFFFF;
        for (int k = 4; k >= 1; k--) {
            ui.setColor(((alpha / (k + 1)) << 24) | rgb);
            g.drawCircle(x, y, r * k / 4f, ui);
        }
        ui.setXfermode(null);
    }

    private void drawScenery(Canvas g, boolean canopy) {
        ui.setStyle(Paint.Style.FILL);
        int tint = THEME[theme][3];
        for (float[] s : scenery) {
            float x = s[0], y = s[1], r = s[2];
            int type = (int) s[3];
            float v = s[4];
            if (!canopy) {
                ui.setColor((int) (shAlpha * 150) << 24);
                g.drawCircle(x + shX * 0.6f, y + shY * 0.6f, r * 0.95f, ui);
                continue;
            }
            switch (type) {
                case 0: // tree
                    ui.setColor(tcol(0xFF14532B, tint)); g.drawCircle(x, y, r, ui);
                    ui.setColor(tcol(0xFF1E7A40, tint)); g.drawCircle(x - r * 0.22f, y - r * 0.22f, r * 0.78f, ui);
                    ui.setColor(tcol(0xFF35A95C, tint)); g.drawCircle(x - r * 0.34f, y - r * 0.34f, r * 0.5f, ui);
                    break;
                case 1: // bush
                    ui.setColor(tcol(0xFF1C6E3A, tint)); g.drawCircle(x, y, r, ui);
                    ui.setColor(tcol(0xFF2A9150, tint)); g.drawCircle(x - r * 0.3f, y - r * 0.2f, r * 0.6f, ui);
                    break;
                case 2: // rock
                    ui.setColor(0xFF6B6F7A); tmp.set(x - r, y - r * 0.8f, x + r, y + r * 0.8f);
                    g.drawRoundRect(tmp, r * 0.4f, r * 0.4f, ui);
                    ui.setColor(0xFF9094A0); tmp.set(x - r * 0.6f, y - r * 0.7f, x + r * 0.3f, y + r * 0.1f);
                    g.drawRoundRect(tmp, r * 0.3f, r * 0.3f, ui);
                    break;
                default: // flower patch
                    ui.setColor(0xFF2A9150); g.drawCircle(x, y, r, ui);
                    int[] fc = {0xFFFFE36B, 0xFFFF8FB1, 0xFFFFFFFF, 0xFF8AD8FF};
                    for (int k = 0; k < 5; k++) {
                        double a = v * 6.28 + k * 1.256;
                        float fx = x + (float) Math.cos(a) * r * 0.5f, fy = y + (float) Math.sin(a) * r * 0.5f;
                        ui.setColor(fc[(k + (int) (v * 4)) % fc.length]);
                        g.drawCircle(fx, fy, r * 0.18f, ui);
                    }
            }
        }
    }

    private void drawColorGrade(Canvas g, float darkness) {
        double h = (dayTime * 24 + 6) % 24;
        int tint, alpha;
        if (darkness > 0.25f) { tint = 0x1B3A6A; alpha = (int) (130 * darkness); }
        else if (h < 9 || h > 16) { tint = 0xFF7A3A; alpha = 50; }
        else return;
        ui.setColor((alpha << 24) | (tint & 0xFFFFFF));
        g.drawRect(0, 0, width, height, ui);
    }

    private void drawVignette(Canvas g) {
        if (vignette == null) return;
        ui.setShader(vignette);
        g.drawRect(0, 0, width, height, ui);
        ui.setShader(null);
    }

    private void renderBanners(Canvas g) {
        if (state != RACING) return;
        float unit = Math.min(width, height);
        text.clearShadowLayer();
        text.setTextAlign(Paint.Align.CENTER);
        if (mode == MODE_DRIFT) {
            text.setColor(0xFFFF8A1E);
            text.setTextSize(unit * 0.06f);
            g.drawText("DRIFT  " + (int) driftScore, width / 2f, height * 0.1f, text);
        } else if (mode == MODE_ELIM) {
            text.setColor(0xFFFF4D6B);
            text.setTextSize(unit * 0.034f);
            g.drawText("NEXT OUT IN " + (int) Math.ceil(Math.max(0, elimTimer)) + "s    ·    " + activeRacers() + " LEFT",
                    width / 2f, height * 0.1f, text);
        }
        if (overtakeT > 0 && overtakeBanner != null) {
            text.setColor(0xFF42E2B8);
            text.setTextSize(unit * 0.07f);
            text.setAlpha((int) (Math.min(1, overtakeT) * 255));
            g.drawText(overtakeBanner, width / 2f, height * 0.32f, text);
            text.setAlpha(255);
        }
        if (elimBannerT > 0 && elimBanner != null) {
            text.setColor(0xFFFFC23D);
            text.setTextSize(unit * 0.06f);
            text.setAlpha((int) (Math.min(1, elimBannerT) * 255));
            g.drawText(elimBanner, width / 2f, height * 0.42f, text);
            text.setAlpha(255);
        }
    }

    private void renderChamp(Canvas g) {
        drawMenuBackdrop(g, 0xF21A0E33, 0xF2070518);
        float unit = Math.min(width, height);
        boolean done = champRound >= CHAMP_TRACKS.length - 1;
        String[] names = {"YOU", "BLAZE", "NOVA", "SAGE"};
        int[] cols = {CAR_COLORS[playerColorIdx], 0xFFFF4D6B, 0xFFFFC23D, 0xFF6CE0FF};
        Integer[] order = {0, 1, 2, 3};
        java.util.Arrays.sort(order, (a, b) -> champScore[b] - champScore[a]);

        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(16, 0, 0, 0xFFFF2E88);
        text.setColor(0xFF19E0FF);
        text.setTextSize(unit * 0.09f);
        g.drawText(done ? "SEASON COMPLETE" : "STANDINGS  ·  ROUND " + (champRound + 1) + "/" + CHAMP_TRACKS.length, width / 2f, height * 0.2f, text);
        text.clearShadowLayer();
        if (done) {
            text.setColor(0xFFFFE34D);
            text.setTextSize(unit * 0.05f);
            g.drawText("CHAMPION:  " + names[order[0]], width / 2f, height * 0.3f, text);
        }
        float y = height * 0.4f;
        for (int i = 0; i < 4; i++) {
            int d = order[i];
            text.setTextAlign(Paint.Align.LEFT);
            ui.setColor(cols[d]);
            float cx = width / 2f - unit * 0.2f;
            g.drawCircle(cx, y - unit * 0.015f, unit * 0.02f, ui);
            text.setColor(d == 0 ? 0xFFFFE34D : 0xFFFFFFFF);
            text.setTextSize(unit * 0.05f);
            g.drawText((i + 1) + ".  " + names[d], cx + unit * 0.04f, y, text);
            text.setTextAlign(Paint.Align.RIGHT);
            g.drawText(champScore[d] + " pts", width / 2f + unit * 0.2f, y, text);
            y += unit * 0.07f;
        }
        float pulse = (float) (0.5 + 0.5 * Math.sin(titlePulse * 3));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFFFFFFF);
        text.setAlpha((int) (120 + 135 * pulse));
        text.setTextSize(unit * 0.04f);
        g.drawText(done ? "TAP TO FINISH" : "TAP FOR NEXT ROUND", width / 2f, height * 0.88f, text);
        text.setAlpha(255);
    }

    private void drawShortcuts(Canvas g) {
        road.setStyle(Paint.Style.STROKE);
        road.setStrokeJoin(Paint.Join.ROUND);
        road.setStrokeCap(Paint.Cap.ROUND);
        road.setPathEffect(null);
        for (float[] sc : sim.shortcuts) {
            tmpPath.reset();
            tmpPath.moveTo(sc[0], sc[1]);
            for (int i = 2; i < sc.length; i += 2) tmpPath.lineTo(sc[i], sc[i + 1]);
            road.setColor(0xFF12121C);
            road.setStrokeWidth(Sim.ROAD_HALF * 1.5f + 18);
            g.drawPath(tmpPath, road);
            road.setColor(0xFF3A3550);
            road.setStrokeWidth(Sim.ROAD_HALF * 1.5f);
            g.drawPath(tmpPath, road);
            // dashed "shortcut" hint stripes
            road.setColor(0x66FFE34D);
            road.setStrokeWidth(5);
            road.setPathEffect(new DashPathEffect(new float[]{20, 22}, 0));
            g.drawPath(tmpPath, road);
            road.setPathEffect(null);
        }
    }

    private void drawRoad(Canvas g) {
        road.setStyle(Paint.Style.STROKE);
        road.setStrokeJoin(Paint.Join.ROUND);
        road.setStrokeCap(Paint.Cap.ROUND);
        road.setPathEffect(null);
        // soft drop shadow on the grass
        road.setColor(0x33000000);
        road.setStrokeWidth(Sim.ROAD_HALF * 2 + 34);
        g.drawPath(roadPath, road);
        // dark outer edge
        road.setColor(0xFF101019);
        road.setStrokeWidth(Sim.ROAD_HALF * 2 + 16);
        g.drawPath(roadPath, road);
        // white edge lines
        road.setColor(0xFFEDEDF5);
        road.setStrokeWidth(Sim.ROAD_HALF * 2 + 4);
        g.drawPath(roadPath, road);
        // asphalt (theme colour, darkened when wet)
        int asphalt = lerp(THEME[theme][1], 0xFF1B2030, 0.35f * (float) sim.wetness);
        road.setColor(asphalt);
        road.setStrokeWidth(Sim.ROAD_HALF * 2 - 6);
        g.drawPath(roadPath, road);
        // asphalt grain (multiplied over the flat colour, scrolls with the world)
        texPaint.setStyle(Paint.Style.STROKE);
        texPaint.setStrokeWidth(Sim.ROAD_HALF * 2 - 6);
        texPaint.setShader(asphaltShader);
        g.drawPath(roadPath, texPaint);
        texPaint.setStyle(Paint.Style.FILL);
        // wet sheen / dry sheen down the middle
        road.setColor(sim.wetness > 0.3 ? (((int) (0x33 * sim.wetness) << 24) | 0x9FC8E0) : 0x14FFFFFF);
        road.setStrokeWidth(Sim.ROAD_HALF * 1.1f);
        g.drawPath(roadPath, road);
        // animated dashed centre line
        road.setColor(THEME[theme][2]);
        road.setStrokeWidth(6);
        road.setPathEffect(new DashPathEffect(new float[]{34, 30}, (float) dash));
        g.drawPath(roadPath, road);
        road.setPathEffect(null);
    }

    private void drawSkidMarks(Canvas g) {
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(0x33000000);
        for (float[] s : skidMarks) {
            float a = s[2];
            float ox = (float) Math.cos(a) * 16, oy = (float) Math.sin(a) * 16;
            float px = (float) -Math.sin(a) * 9, py = (float) Math.cos(a) * 9;
            g.drawCircle(s[0] + px - ox, s[1] + py - oy, 4, ui);
            g.drawCircle(s[0] - px - ox, s[1] - py - oy, 4, ui);
        }
    }

    private void drawPads(Canvas g) {
        for (int[] p : sim.pads) {
            glow(g, sim.cx[p[0]], sim.cy[p[0]], 120, 0xFF8A1E, 80);
            drawChevrons(g, sim.cx[p[0]], sim.cy[p[0]],
                    Math.atan2(sim.dirY[p[0]], sim.dirX[p[0]]), 0xCCFF8A1E);
        }
    }

    private void drawJumps(Canvas g) {
        for (double[] j : sim.jumps) {
            glow(g, (float) j[0], (float) j[1], 130, 0x2B6BFF, 80);
            g.save();
            g.translate((float) j[0], (float) j[1]);
            g.rotate((float) Math.toDegrees(j[2]));
            float w = Sim.ROAD_HALF * 1.7f;
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(0xDD2B6BFF);
            tmp.set(-26, -w / 2, 30, w / 2);
            g.drawRect(tmp, ui);
            ui.setColor(0xFFBFE0FF);
            for (int c = 0; c < 3; c++) {
                tmpPath.reset();
                float bx = -18 + c * 18;
                tmpPath.moveTo(bx, -w * 0.32f);
                tmpPath.lineTo(bx + 14, 0);
                tmpPath.lineTo(bx, w * 0.32f);
                tmpPath.close();
                g.drawPath(tmpPath, ui);
            }
            g.restore();
        }
    }

    private void drawChevrons(Canvas g, float x, float y, double ang, int color) {
        g.save();
        g.translate(x, y);
        g.rotate((float) Math.toDegrees(ang));
        float w = Sim.ROAD_HALF * 1.4f;
        ui.setStyle(Paint.Style.FILL);
        for (int c = 0; c < 3; c++) {
            ui.setColor(color);
            tmpPath.reset();
            float bx = -30 + c * 22;
            tmpPath.moveTo(bx, -w / 2);
            tmpPath.lineTo(bx + 16, 0);
            tmpPath.lineTo(bx, w / 2);
            tmpPath.lineTo(bx - 8, w / 2);
            tmpPath.lineTo(bx + 8, 0);
            tmpPath.lineTo(bx - 8, -w / 2);
            tmpPath.close();
            g.drawPath(tmpPath, ui);
        }
        g.restore();
    }

    private void drawFinishLine(Canvas g) {
        int i = 0;
        g.save();
        g.translate(sim.cx[i], sim.cy[i]);
        g.rotate((float) Math.toDegrees(Math.atan2(sim.dirY[i], sim.dirX[i])));
        int cols = 8;
        float cell = (Sim.ROAD_HALF * 2) / cols, depth = cell;
        for (int c = 0; c < cols; c++)
            for (int rr = 0; rr < 2; rr++) {
                fill.setColor(((c + rr) % 2 == 0) ? 0xFFFFFFFF : 0xFF111111);
                g.drawRect(-depth + rr * depth, -Sim.ROAD_HALF + c * cell,
                        -depth + rr * depth + depth, -Sim.ROAD_HALF + c * cell + cell, fill);
            }
        g.restore();
    }

    private void drawOils(Canvas g) {
        ui.setStyle(Paint.Style.FILL);
        for (Sim.Oil o : sim.oils) {
            ui.setColor(0xAA15151E);
            g.drawCircle((float) o.x, (float) o.y, 30, ui);
            ui.setColor(0x55503070);
            g.drawCircle((float) o.x - 6, (float) o.y - 6, 16, ui);
        }
    }

    private void drawBoxes(Canvas g) {
        for (Sim.ItemBox b : sim.boxes) {
            if (!b.active) continue;
            float s = 18 + (float) Math.sin(titlePulse * 3 + b.idx) * 3;
            glow(g, b.x, b.y, 46, 0x2BD4C8, 95);
            g.save();
            g.translate(b.x, b.y);
            g.rotate((float) (titlePulse * 80 % 360));
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(0xEE2BD4C8);
            tmp.set(-s, -s, s, s);
            g.drawRoundRect(tmp, 6, 6, ui);
            ui.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(s * 1.4f);
            text.clearShadowLayer();
            g.drawText("?", 0, s * 0.5f, text);
            g.restore();
        }
    }

    private void drawCar(Canvas g, double x, double y, double angle, int color,
                         boolean player, float scale, float shadowGap) {
        float L = 72 * scale, W = 36 * scale;
        // world-space directional shadow (cast away from the sun, longer when airborne)
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(((int) (shAlpha * 130) << 24));
        tmp.set((float) x - L / 2 + shX + shadowGap, (float) y - W / 2 + shY + shadowGap,
                (float) x + L / 2 + shX + shadowGap, (float) y + W / 2 + shY + shadowGap);
        g.drawRoundRect(tmp, 16, 16, ui);

        g.save();
        g.translate((float) x, (float) y);
        g.rotate((float) Math.toDegrees(angle));

        if (player && sim.boostTime > 0) {           // allocation-free boost bloom
            for (int k = 3; k >= 1; k--) { ui.setColor((0x33 / k << 24) | 0xFF8A1E); g.drawCircle(0, 0, L * 1.3f * k / 3f, ui); }
        }
        if (player && sim.shield > 0) {
            ui.setColor(0x553BE0FF);
            g.drawCircle(0, 0, L * 0.85f, ui);
        }

        ui.setShader(bodyGradient(color));
        tmp.set(-L / 2, -W / 2, L / 2, W / 2);
        g.drawRoundRect(tmp, 16, 16, ui);
        ui.setShader(null);
        // metallic flake on the paint (subtle multiply)
        texPaint.setStyle(Paint.Style.FILL);
        texPaint.setShader(metalShader);
        g.drawRoundRect(tmp, 16, 16, texPaint);

        // player paint scheme (skin)
        if (player && carSkin == 1) {            // racing stripe
            ui.setColor(0xEEFFFFFF);
            tmp.set(-L / 2, -W * 0.16f, L / 2, W * 0.16f);
            g.drawRect(tmp, ui);
        } else if (player && carSkin == 2) {     // two-tone split (front half lighter)
            ui.setColor(0x55FFFFFF);
            tmp.set(0, -W / 2, L / 2, W / 2);
            g.drawRoundRect(tmp, 16, 16, ui);
        } else if (player && carSkin == 3) {     // carbon
            ui.setColor(0x66101018);
            tmp.set(-L / 2, -W / 2, L / 2, W / 2);
            g.drawRoundRect(tmp, 16, 16, ui);
            ui.setColor(0x22FFFFFF);
            for (int s2 = -2; s2 <= 2; s2++) g.drawRect(s2 * L * 0.12f, -W / 2, s2 * L * 0.12f + 2, W / 2, ui);
        }

        // glossy specular highlight along the top flank
        ui.setColor(0x40FFFFFF);
        tmp.set(-L * 0.42f, -W * 0.42f, L * 0.42f, -W * 0.12f);
        g.drawRoundRect(tmp, 10, 10, ui);

        ui.setColor(0xCC0E1420);
        tmp.set(-L * 0.05f, -W * 0.34f, L * 0.34f, W * 0.34f);
        g.drawRoundRect(tmp, 8, 8, ui);
        // windshield (flat tint + a brighter leading edge — no per-frame shader)
        ui.setColor(0x99203040);
        tmp.set(L * 0.02f, -W * 0.28f, L * 0.30f, W * 0.28f);
        g.drawRoundRect(tmp, 6, 6, ui);
        ui.setColor(0x88BFE9FF);
        tmp.set(L * 0.22f, -W * 0.26f, L * 0.30f, W * 0.26f);
        g.drawRoundRect(tmp, 4, 4, ui);

        // headlights with a soft glow when dark
        ui.setColor(0xFFFFF3B0);
        g.drawCircle(L * 0.44f, -W * 0.28f, 4.5f * scale, ui);
        g.drawCircle(L * 0.44f, W * 0.28f, 4.5f * scale, ui);
        ui.setColor(0xFFFF3355);
        g.drawRect(-L * 0.5f, -W * 0.34f, -L * 0.42f, -W * 0.06f, ui);
        g.drawRect(-L * 0.5f, W * 0.06f, -L * 0.42f, W * 0.34f, ui);

        ui.setColor(0xFF0C0C14);
        drawWheel(g, -L * 0.28f, -W * 0.52f);
        drawWheel(g, -L * 0.28f, W * 0.52f - 12 * scale);
        drawWheel(g, L * 0.30f, -W * 0.52f);
        drawWheel(g, L * 0.30f, W * 0.52f - 12 * scale);

        // bright rim light around the body
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(player ? 2.5f : 1.8f);
        ui.setColor(player ? 0xCCFFFFFF : (0x66000000 | (lighten(color, 1.4f) & 0xFFFFFF)));
        tmp.set(-L / 2, -W / 2, L / 2, W / 2);
        g.drawRoundRect(tmp, 16, 16, ui);
        ui.setStyle(Paint.Style.FILL);
        g.restore();
    }

    private void drawWheel(Canvas g, float cx, float cy) {
        tmp.set(cx - 7, cy, cx + 7, cy + 12);
        g.drawRoundRect(tmp, 3, 3, ui);
    }

    private void drawParticles(Canvas g) {
        for (Particle p : particles) {
            float a = (float) (p.life / p.maxLife);
            ui.setColor(((int) (a * 150) << 24) | 0xDDDDDD);
            g.drawCircle((float) p.x, (float) p.y, p.size, ui);
        }
    }

    // ---- screen-space overlays ----

    private void drawNightLighting(Canvas g, float darkness) {
        ui.setColor(((int) (darkness * 165) << 24) | 0x0A1430);
        g.drawRect(0, 0, width, height, ui);
        ui.setXfermode(ADD);
        drawHeadlight(g, sim.carX, sim.carY, sim.carAngle, darkness);
        for (Sim.Ai a : sim.ais) drawHeadlight(g, a.x, a.y, a.angle, darkness);
        ui.setXfermode(null);
    }

    private void drawHeadlight(Canvas g, double wx, double wy, double angle, float darkness) {
        float sxp = (float) ((wx - camX) * zoom + width / 2);
        float syp = (float) ((wy - camY) * zoom + height / 2);
        float ahead = (float) (40 * zoom);
        float cx = sxp + (float) Math.cos(angle) * ahead;
        float cy = syp + (float) Math.sin(angle) * ahead;
        float r = (float) (150 * zoom);
        int glow = (int) (60 * darkness);
        for (int k = 4; k >= 1; k--) {           // stacked rings instead of a shader
            ui.setColor(((glow / k) << 24) | 0xFFF0C0);
            g.drawCircle(cx, cy, r * k / 4f, ui);
        }
    }

    private void drawRain(Canvas g, float intensity) {
        ui.setColor(((int) (0x22 * intensity) << 24) | 0x3A6A8A);
        g.drawRect(0, 0, width, height, ui);
        ui.setStrokeWidth(2);
        int a = (int) (0x88 * intensity);
        ui.setColor((a << 24) | 0xBFE0FF);
        double t = titlePulse;
        int n = (int) (rain.length * Math.min(1, intensity + 0.2));
        for (int i = 0; i < n; i++) {
            float[] r = rain[i];
            float x = r[0] * width;
            float y = (float) ((r[1] + t * (0.8 + r[2])) % 1) * height;
            g.drawLine(x, y, x - 6, y + 18 * r[2], ui);
        }
    }

    private void drawSpeedLines(Canvas g) {
        ui.setColor(0x55FFFFFF);
        ui.setStrokeWidth(3);
        float cx = width / 2f, cy = height / 2f;
        for (int i = 0; i < 14; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            float r1 = Math.min(width, height) * 0.42f, r2 = r1 + 60;
            g.drawLine(cx + (float) Math.cos(ang) * r1, cy + (float) Math.sin(ang) * r1,
                    cx + (float) Math.cos(ang) * r2, cy + (float) Math.sin(ang) * r2, ui);
        }
    }

    private void drawRadar(Canvas g) {
        if (state == TITLE) return;
        float size = Math.min(width, height) * 0.22f, pad = Math.min(width, height) * 0.03f;
        float ox = width / 2f + Math.min(width, height) * 0.18f, oy = pad;
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(0x66101826);
        tmp.set(ox, oy, ox + size, oy + size);
        g.drawRoundRect(tmp, 10, 10, ui);

        float tw = sim.maxX - sim.minX, th = sim.maxY - sim.minY;
        float scale = (size - 16) / Math.max(tw, th);
        float offx = ox + 8 + (size - 16 - tw * scale) / 2;
        float offy = oy + 8 + (size - 16 - th * scale) / 2;

        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(Math.max(2, Sim.ROAD_HALF * scale));
        ui.setColor(0x88FFFFFF);
        tmpPath.reset();
        for (int i = 0; i <= sim.N; i += 2) {
            int k = i % sim.N;
            float mx = offx + (sim.cx[k] - sim.minX) * scale, my = offy + (sim.cy[k] - sim.minY) * scale;
            if (i == 0) tmpPath.moveTo(mx, my); else tmpPath.lineTo(mx, my);
        }
        g.drawPath(tmpPath, ui);
        ui.setStyle(Paint.Style.FILL);
        for (Sim.Ai a : sim.ais) {
            ui.setColor(a.color);
            g.drawCircle(offx + ((float) a.x - sim.minX) * scale, offy + ((float) a.y - sim.minY) * scale, 4, ui);
        }
        ui.setColor(CAR_COLORS[playerColorIdx]);
        g.drawCircle(offx + ((float) sim.carX - sim.minX) * scale, offy + ((float) sim.carY - sim.minY) * scale, 5, ui);
    }

    private void renderHud(Canvas g) {
        if (state == TITLE) return;
        float unit = Math.min(width, height), pad = unit * 0.035f;
        int kmh = (int) Math.abs(sim.speed() / Sim.MAX_SPEED * 240);

        // translucent HUD panels for readability
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(0x3C0A1020);
        tmp.set(pad * 0.4f, pad * 0.4f, pad * 0.4f + width * 0.24f, pad * 0.4f + unit * 0.31f);
        g.drawRoundRect(tmp, 16, 16, ui);
        tmp.set(width - pad * 0.4f - width * 0.2f, pad * 0.4f, width - pad * 0.4f, pad * 0.4f + unit * 0.16f);
        g.drawRoundRect(tmp, 16, 16, ui);

        text.setShadowLayer(8, 0, 2, 0xCC000000);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(sim.boostTime > 0 ? 0xFFFF8A1E : 0xFFFFFFFF);
        text.setTextSize(unit * 0.08f);
        g.drawText(String.format(Locale.US, "%03d", kmh), pad, pad + text.getTextSize(), text);
        text.setTextSize(unit * 0.028f);
        text.setColor(0xFFFFFFFF);
        g.drawText("KM/H", pad, pad + unit * 0.11f, text);

        float barW = width * 0.2f, barH = unit * 0.022f, barY = pad + unit * 0.13f;
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(0x55FFFFFF);
        tmp.set(pad, barY, pad + barW, barY + barH);
        g.drawRoundRect(tmp, barH / 2, barH / 2, ui);
        ui.setColor(sim.nitro >= 0.25 ? 0xFF42E2B8 : 0xFFFFC23D);
        tmp.set(pad, barY, pad + barW * (float) sim.nitro, barY + barH);
        g.drawRoundRect(tmp, barH / 2, barH / 2, ui);
        text.setColor(0xFF9FFFE0);
        text.setTextSize(unit * 0.024f);
        g.drawText("NITRO", pad, barY + barH + unit * 0.03f, text);

        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(0xFFFFE34D);
        text.setTextSize(unit * 0.05f);
        int showLap = Math.min(sim.lapsDone + 1, sim.totalLaps);
        g.drawText("LAP " + showLap + "/" + sim.totalLaps, width - pad, pad + text.getTextSize(), text);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(unit * 0.04f);
        g.drawText("P" + sim.position() + "/" + (sim.ais.size() + 1), width - pad, pad + unit * 0.115f, text);

        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(0xFFCFC8FF);
        text.setTextSize(unit * 0.032f);
        g.drawText("LAP " + fmt(sim.lapTime), pad, pad + unit * 0.23f, text);
        if (bestLap > 0) {
            text.setColor(0xFF8AF0FF);
            g.drawText("BEST " + fmt(bestLap), pad, pad + unit * 0.275f, text);
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(unit * 0.03f);
        g.drawText((sim.weather == 1 ? "RAIN " : "") + timeLabel(), width / 2f, pad + unit * 0.04f, text);
        text.clearShadowLayer();

        if (!sim.onTrack && state == RACING) {
            text.setColor(0xFFFF4D6D);
            text.setTextSize(unit * 0.045f);
            g.drawText("OFF TRACK!", width / 2f, height * 0.16f, text);
        }
        if (sim.airborne && state == RACING) {
            text.setColor(0xFF8CFF45);
            text.setTextSize(unit * 0.05f);
            g.drawText("JUMP!", width / 2f, height * 0.16f, text);
        }
    }

    private String timeLabel() {
        double h = (dayTime * 24 + 6) % 24;
        if (h < 6 || h > 20) return "NIGHT";
        if (h < 9) return "DAWN";
        if (h > 17) return "DUSK";
        return "DAY";
    }

    private static String fmt(double s) {
        int m = (int) (s / 60);
        return String.format(Locale.US, "%d:%05.2f", m, s - m * 60);
    }

    // ---- controls + overlays ----

    private void renderControls(Canvas g) {
        if (state == TITLE || state == FINISHED) return;
        drawButton(g, btnLeft, "◀", keyLeft, 0xFF19E0FF);
        drawButton(g, btnRight, "▶", keyRight, 0xFF19E0FF);
        drawButton(g, btnBrake, "DRIFT", keyBrake, 0xFFFF4D6D);
        drawButton(g, btnGas, "▲", keyGas, 0xFF42E2B8);
        drawButton(g, btnBoost, "NITRO", sim.nitro >= 0.25, 0xFFFF8A1E);
        drawButton(g, btnItem, itemLabel(sim.heldItem), sim.heldItem != Sim.IT_NONE, 0xFF2BD4C8);
    }

    private String itemLabel(int it) {
        switch (it) {
            case Sim.IT_TURBO: return "TURBO";
            case Sim.IT_OIL: return "OIL";
            case Sim.IT_SHIELD: return "SHIELD";
            default: return "ITEM";
        }
    }

    private void drawButton(Canvas g, RectF r, String label, boolean active, int accent) {
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(active ? 0x66102038 : 0x33101830);
        g.drawOval(r, ui);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(r.width() * 0.045f);
        ui.setColor(active ? accent : 0x88FFFFFF);
        g.drawOval(r, ui);
        ui.setStyle(Paint.Style.FILL);
        text.clearShadowLayer();
        text.setColor(active ? accent : 0xCCFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(r.height() * (label.length() > 2 ? 0.22f : 0.42f));
        g.drawText(label, r.centerX(), r.centerY() + r.height() * 0.1f, text);
    }

    private void drawTrackThumb(Canvas g, float[][] wp, RectF rect, int accent) {
        float mnx = Float.MAX_VALUE, mny = Float.MAX_VALUE, mxx = -Float.MAX_VALUE, mxy = -Float.MAX_VALUE;
        for (float[] p : wp) {
            mnx = Math.min(mnx, p[0]); mxx = Math.max(mxx, p[0]);
            mny = Math.min(mny, p[1]); mxy = Math.max(mxy, p[1]);
        }
        float tw = Math.max(1, mxx - mnx), th = Math.max(1, mxy - mny);
        float pad = rect.width() * 0.16f;
        float sc = Math.min((rect.width() - 2 * pad) / tw, (rect.height() - 2 * pad) / th);
        float offx = rect.centerX() - (mnx + tw / 2) * sc;
        float offy = rect.centerY() - (mny + th / 2) * sc;
        int n = wp.length;
        tmpPath.reset();
        tmpPath.moveTo(offx + (wp[n - 1][0] + wp[0][0]) / 2 * sc, offy + (wp[n - 1][1] + wp[0][1]) / 2 * sc);
        for (int i = 0; i < n; i++) {
            float mx = offx + (wp[i][0] + wp[(i + 1) % n][0]) / 2 * sc;
            float my = offy + (wp[i][1] + wp[(i + 1) % n][1]) / 2 * sc;
            tmpPath.quadTo(offx + wp[i][0] * sc, offy + wp[i][1] * sc, mx, my);
        }
        tmpPath.close();
        road.setStyle(Paint.Style.STROKE);
        road.setPathEffect(null);
        road.setStrokeJoin(Paint.Join.ROUND);
        road.setColor(0x66000000);
        road.setStrokeWidth(7);
        g.drawPath(tmpPath, road);
        road.setColor(accent);
        road.setStrokeWidth(4);
        g.drawPath(tmpPath, road);
    }

    private void renderTitle(Canvas g) {
        drawMenuBackdrop(g, 0xF21A0E33, 0xF2090518);

        text.setTextAlign(Paint.Align.CENTER);
        float pulse = (float) (0.5 + 0.5 * Math.sin(titlePulse * 2));
        text.setShadowLayer(16 + 16 * pulse, 0, 0, 0xFFFF2E88);
        text.setColor(0xFF19E0FF);
        text.setTextSize(Math.min(width, height) * 0.12f);
        g.drawText("TURBO CIRCUIT", width / 2f, height * 0.17f, text);
        text.clearShadowLayer();

        text.setColor(0xFFB9B2E6);
        text.setTextSize(Math.min(width, height) * 0.03f);
        g.drawText("SELECT A LEVEL", width / 2f, height * 0.27f, text);

        // coins + driver level header (top-left)
        float unit0 = Math.min(width, height), pad0 = unit0 * 0.04f;
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(0xFFFFD24D);
        text.setTextSize(unit0 * 0.04f);
        g.drawText(coins + " ¢", pad0, pad0 + unit0 * 0.04f, text);
        text.setColor(0xFF8AF0FF);
        g.drawText("LV " + driverLevel(), pad0, pad0 + unit0 * 0.095f, text);
        float xbw = width * 0.16f, xbh = unit0 * 0.016f, xby = pad0 + unit0 * 0.12f;
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(0x55FFFFFF);
        tmp.set(pad0, xby, pad0 + xbw, xby + xbh);
        g.drawRoundRect(tmp, xbh / 2, xbh / 2, ui);
        ui.setColor(0xFF8AF0FF);
        tmp.set(pad0, xby, pad0 + xbw * ((xp % 150) / 150f), xby + xbh);
        g.drawRoundRect(tmp, xbh / 2, xbh / 2, ui);

        // top-right buttons
        drawPillButton(g, btnShop, "SHOP", 0xFFFFC23D, true);
        drawPillButton(g, btnTune, "TUNE", 0xFF8CFF45, true);
        drawPillButton(g, btnStyle, "STYLE", 0xFFFF6FB1, true);
        drawPillButton(g, btnHelp, "?", 0xFF19E0FF, true);
        // second row: mode / championship / random / music
        drawPillButton(g, btnMode, MODE_NAME[mode], 0xFFFF8A1E, true);
        drawPillButton(g, btnChamp, champ ? "SEASON R" + (champRound + 1) : "CHAMPIONSHIP", 0xFFFFE34D, true);
        drawPillButton(g, btnRandom, randomTrack ? "RANDOM: ON" : "RANDOM: OFF", 0xFF6CE0FF, true);
        drawPillButton(g, btnMusic, musicOn ? "MUSIC: ON" : "MUSIC: OFF", 0xFFB66BFF, true);

        if (randomTrack) { renderRandomPanel(g); }
        else for (int i = 0; i < levelBtns.length; i++) {
            boolean locked = i >= unlocked;
            RectF r = levelBtns[i];
            int accent = i == level ? 0xFF19E0FF : 0xFF7E8AD0;
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(locked ? 0x33101830 : (i == level ? 0x5519E0FF : 0x44102038));
            g.drawRoundRect(r, 14, 14, ui);

            if (!locked) {
                // mini track preview
                tmp2.set(r.left, r.top + r.height() * 0.06f, r.right, r.bottom - r.height() * 0.34f);
                drawTrackThumb(g, Tracks.LEVELS[i].wp, tmp2, accent);
            } else {
                float cxL = r.centerX(), cyL = r.centerY() - r.height() * 0.06f, s = r.height() * 0.2f;
                ui.setColor(0x77FFFFFF);
                ui.setStyle(Paint.Style.STROKE);
                ui.setStrokeWidth(s * 0.2f);
                tmp.set(cxL - s * 0.4f, cyL - s * 0.95f, cxL + s * 0.4f, cyL - s * 0.05f);
                g.drawArc(tmp, 180, 180, false, ui);
                ui.setStyle(Paint.Style.FILL);
                tmp.set(cxL - s * 0.55f, cyL - s * 0.3f, cxL + s * 0.55f, cyL + s * 0.6f);
                g.drawRoundRect(tmp, s * 0.16f, s * 0.16f, ui);
            }

            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(i == level ? 4 : 3);
            ui.setColor(locked ? 0x55FFFFFF : accent);
            g.drawRoundRect(r, 14, 14, ui);
            ui.setStyle(Paint.Style.FILL);

            text.setShadowLayer(6, 0, 1, 0xCC000000);
            text.setColor(locked ? 0x66FFFFFF : 0xFFFFFFFF);
            String ln = locked ? "LOCKED" : Tracks.LEVELS[i].name;
            fitText(ln, r.width() * 0.9f, r.width() * 0.11f);
            g.drawText(ln, r.centerX(), r.bottom - r.height() * 0.1f, text);
            text.clearShadowLayer();

            if (!locked) {
                int wb = Tracks.LEVELS[i].weatherBias;
                String wt = wb == 1 ? "RAIN" : (wb == 2 ? "VARIES" : "DRY");
                text.setTextAlign(Paint.Align.RIGHT);
                text.setColor(wb == 1 ? 0xFF8AD8FF : 0xFFB9B2E6);
                text.setTextSize(r.width() * 0.06f);
                g.drawText(wt, r.right - r.width() * 0.06f, r.top + r.height() * 0.18f, text);
                text.setTextAlign(Paint.Align.CENTER);
            }
            if (i == dailyTrack && !dailyDoneToday) {           // daily-challenge marker
                ui.setStyle(Paint.Style.FILL);
                ui.setColor(0xFFFFC23D);
                g.drawCircle(r.left + r.width() * 0.1f, r.top + r.height() * 0.16f, r.width() * 0.03f, ui);
            }
        }

        // daily challenge bar (free play only)
        if (!randomTrack && !champ) {
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(dailyDoneToday ? 0x3342E2B8 : 0x44FFC23D);
            g.drawRoundRect(dailyCard, 14, 14, ui);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(3);
            ui.setColor(dailyDoneToday ? 0xFF42E2B8 : 0xFFFFC23D);
            g.drawRoundRect(dailyCard, 14, 14, ui);
            ui.setStyle(Paint.Style.FILL);
            text.clearShadowLayer();
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(0xFFFFD24D);
            text.setTextSize(dailyCard.height() * 0.26f);
            g.drawText("DAILY CHALLENGE", dailyCard.left + dailyCard.width() * 0.03f, dailyCard.top + dailyCard.height() * 0.34f, text);
            text.setColor(0xFFFFFFFF);
            fitText(dailyDesc(), dailyCard.width() * 0.74f, dailyCard.height() * 0.22f);
            g.drawText(dailyDesc(), dailyCard.left + dailyCard.width() * 0.03f, dailyCard.top + dailyCard.height() * 0.68f, text);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setColor(dailyDoneToday ? 0xFF42E2B8 : 0xFFFFD24D);
            text.setTextSize(dailyCard.height() * 0.26f);
            g.drawText(dailyDoneToday ? "DONE" : "+" + DAILY_REWARD + " ¢",
                    dailyCard.right - dailyCard.width() * 0.03f, dailyCard.centerY() + dailyCard.height() * 0.08f, text);
            text.setTextAlign(Paint.Align.CENTER);
        }

        // tyre compound selector
        for (int i = 0; i < 3; i++) {
            boolean sel = tire == i;
            int acc = i == 0 ? 0xFFFF6B6B : (i == 1 ? 0xFFFFD24D : 0xFF6CE0FF);
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(sel ? (0x55000000 | (acc & 0xFFFFFF)) : 0x33101830);
            g.drawRoundRect(tireBtns[i], 10, 10, ui);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(sel ? 4 : 2);
            ui.setColor(sel ? acc : 0x88FFFFFF);
            g.drawRoundRect(tireBtns[i], 10, 10, ui);
            ui.setStyle(Paint.Style.FILL);
            text.clearShadowLayer();
            text.setColor(sel ? acc : 0xCCFFFFFF);
            text.setTextSize(tireBtns[i].height() * 0.42f);
            g.drawText(TIRE_NAME[i], tireBtns[i].centerX(), tireBtns[i].centerY() + tireBtns[i].height() * 0.15f, text);
        }
        text.setColor(0xFF7F88B0);
        text.setTextSize(Math.min(width, height) * 0.024f);
        g.drawText("TYRES", tireBtns[0].left - Math.min(width, height) * 0.05f, tireBtns[1].centerY() + tireBtns[1].height() * 0.12f, text);

        for (int i = 0; i < swatches.length; i++) {
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(CAR_COLORS[i]);
            g.drawOval(swatches[i], ui);
            if (i == playerColorIdx) {
                ui.setStyle(Paint.Style.STROKE);
                ui.setStrokeWidth(4);
                ui.setColor(0xFFFFFFFF);
                g.drawOval(swatches[i], ui);
            }
        }
        ui.setStyle(Paint.Style.FILL);

        text.setColor(0xFF7F88B0);
        text.setTextSize(Math.min(width, height) * 0.022f);
        g.drawText("drift = nitro  •  ? = item  •  blue = jump  •  shortcuts  •  ghost = your best lap",
                width / 2f, height * 0.95f, text);
    }

    private void renderRandomPanel(Canvas g) {
        float unit = Math.min(width, height);
        text.setTextAlign(Paint.Align.CENTER);
        text.clearShadowLayer();
        text.setColor(0xFF6CE0FF);
        text.setTextSize(unit * 0.035f);
        g.drawText("RANDOM TRACK  —  share this code to race the same circuit", width / 2f, height * 0.26f, text);

        if (curDef != null) {
            tmp2.set(width / 2f - unit * 0.16f, height * 0.28f, width / 2f + unit * 0.16f, height * 0.28f + unit * 0.16f);
            drawTrackThumb(g, curDef.wp, tmp2, 0xFF6CE0FF);
        }

        // 5-digit code steppers
        for (int i = 0; i < 5; i++) {
            int place = (int) Math.pow(10, 4 - i);
            int digit = (trackCode / place) % 10;
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(0x33101830);
            g.drawRoundRect(codeUp[i], 6, 6, ui);
            g.drawRoundRect(codeDn[i], 6, 6, ui);
            ui.setColor(0xFF6CE0FF);
            // up triangle
            tmpPath.reset();
            tmpPath.moveTo(codeUp[i].centerX(), codeUp[i].top + codeUp[i].height() * 0.25f);
            tmpPath.lineTo(codeUp[i].right - codeUp[i].width() * 0.25f, codeUp[i].bottom - codeUp[i].height() * 0.25f);
            tmpPath.lineTo(codeUp[i].left + codeUp[i].width() * 0.25f, codeUp[i].bottom - codeUp[i].height() * 0.25f);
            tmpPath.close();
            g.drawPath(tmpPath, ui);
            tmpPath.reset();
            tmpPath.moveTo(codeDn[i].centerX(), codeDn[i].bottom - codeDn[i].height() * 0.25f);
            tmpPath.lineTo(codeDn[i].right - codeDn[i].width() * 0.25f, codeDn[i].top + codeDn[i].height() * 0.25f);
            tmpPath.lineTo(codeDn[i].left + codeDn[i].width() * 0.25f, codeDn[i].top + codeDn[i].height() * 0.25f);
            tmpPath.close();
            g.drawPath(tmpPath, ui);
            text.setColor(0xFFFFFFFF);
            text.setTextSize(unit * 0.06f);
            g.drawText(String.valueOf(digit), codeUp[i].centerX(), (codeUp[i].bottom + codeDn[i].top) / 2 + unit * 0.02f, text);
        }

        drawPillButton(g, btnReroll, "REROLL", 0xFFFFC23D, true);
        drawPillButton(g, btnGo, "RACE", 0xFF42E2B8, true);
    }

    private void changeDigit(int i, int delta) {
        int place = (int) Math.pow(10, 4 - i);
        int digit = (trackCode / place) % 10;
        int nd = (digit + delta + 10) % 10;
        trackCode += (nd - digit) * place;
        trackCode = Math.max(0, Math.min(99999, trackCode));
        loadLevel(0);   // refresh procedural preview
    }

    /** Sets the text paint size so {@code s} fits within {@code maxW}. */
    private void fitText(String s, float maxW, float size) {
        text.setTextSize(size);
        float w = text.measureText(s);
        if (w > maxW && w > 0) text.setTextSize(size * maxW / w);
    }

    private void drawPillButton(Canvas g, RectF r, String label, int accent, boolean enabled) {
        float pulse = (float) (0.5 + 0.5 * Math.sin(titlePulse * 2.5));
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(enabled ? 0x66201433 : 0x22202030);
        g.drawRoundRect(r, r.height() / 2, r.height() / 2, ui);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(3 + (enabled ? pulse * 1.5f : 0));
        ui.setColor(enabled ? accent : 0x55FFFFFF);
        g.drawRoundRect(r, r.height() / 2, r.height() / 2, ui);
        ui.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(6, 0, 1, 0xCC000000);
        text.setColor(enabled ? 0xFFFFFFFF : 0x77FFFFFF);
        fitText(label, r.width() * 0.86f, r.height() * 0.4f);
        g.drawText(label, r.centerX(), r.centerY() + text.getTextSize() * 0.34f, text);
        text.clearShadowLayer();
    }

    private void drawMenuBackdrop(Canvas g, int top, int bottom) {
        if (backdropShader == null || bdTop != top || bdBot != bottom || bdH != height) {
            backdropShader = new LinearGradient(0, 0, 0, height, new int[]{top, bottom}, null, Shader.TileMode.CLAMP);
            bdTop = top; bdBot = bottom; bdH = height;
        }
        ui.setStyle(Paint.Style.FILL);
        ui.setShader(backdropShader);
        g.drawRect(0, 0, width, height, ui);
        ui.setShader(null);
        // drifting neon motes
        for (float[] d : menuDots) {
            float x = d[0] * width;
            float y = (float) ((d[1] - titlePulse * d[2]) % 1 + 1) % 1 * height;
            float a = (float) (0.25 + 0.25 * Math.sin(titlePulse * 2 + d[0] * 10));
            ui.setColor(((int) (a * 90) << 24) | 0x19E0FF);
            g.drawCircle(x, y, d[3], ui);
        }
    }

    private void renderShop(Canvas g) {
        drawMenuBackdrop(g, 0xF21A0E33, 0xF2090518);

        float unit = Math.min(width, height), pad = unit * 0.04f;
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFFFC23D);
        text.setTextSize(unit * 0.07f);
        g.drawText("GARAGE  &  SHOP", width / 2f, pad + unit * 0.07f, text);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(0xFFFFD24D);
        text.setTextSize(unit * 0.045f);
        g.drawText(coins + " ¢    LV " + driverLevel(), width - pad, pad + unit * 0.05f, text);

        drawPillButton(g, btnBack, "BACK", 0xFF19E0FF, true);

        for (int i = 0; i < SHOP_N; i++) drawShopCard(g, i);
    }

    private void drawShopCard(Canvas g, int i) {
        RectF r = shopCards[i];
        boolean own = owned[i];
        boolean canBuy = !own && coins >= SHOP_COST[i] && driverLevel() >= SHOP_REQ[i];
        boolean lockedByLevel = !own && driverLevel() < SHOP_REQ[i];
        int accent = own && equipped[i] ? 0xFF42E2B8 : (own ? 0xFF7E8AD0 : (lockedByLevel ? 0xFF555B70 : 0xFFFFC23D));

        ui.setStyle(Paint.Style.FILL);
        ui.setColor(own && equipped[i] ? 0x3342E2B8 : 0x33101830);
        g.drawRoundRect(r, 16, 16, ui);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(i == shopSel ? 5 : 3);
        ui.setColor(i == shopSel ? 0xFFFFFFFF : accent);
        g.drawRoundRect(r, 16, 16, ui);
        ui.setStyle(Paint.Style.FILL);

        float u = r.height();
        float maxW = r.width() - u * 0.24f;
        text.setTextAlign(Paint.Align.LEFT);
        text.setShadowLayer(5, 0, 1, 0xCC000000);
        text.setColor(0xFFFFFFFF);
        fitText(SHOP_NAME[i], maxW, u * 0.15f);
        g.drawText(SHOP_NAME[i], r.left + u * 0.12f, r.top + u * 0.2f, text);
        text.setColor(0xFF8CFF8C);
        fitText(SHOP_PRO[i], maxW, u * 0.1f);
        g.drawText(SHOP_PRO[i], r.left + u * 0.12f, r.top + u * 0.4f, text);
        text.setColor(0xFFFF8A9B);
        fitText(SHOP_CON[i], maxW, u * 0.1f);
        g.drawText(SHOP_CON[i], r.left + u * 0.12f, r.top + u * 0.56f, text);
        text.clearShadowLayer();

        text.setTextAlign(Paint.Align.CENTER);
        String status;
        int sc;
        if (own && equipped[i]) { status = "EQUIPPED  (tap to remove)"; sc = 0xFF42E2B8; }
        else if (own) { status = "OWNED  (tap to equip)"; sc = 0xFFB9B2E6; }
        else if (lockedByLevel) { status = "REACH LV " + SHOP_REQ[i]; sc = 0xFFFF8A9B; }
        else { status = "BUY  " + SHOP_COST[i] + " ¢" + (canBuy ? "" : "  (need coins)"); sc = canBuy ? 0xFFFFD24D : 0xFF888EA0; }
        text.setColor(sc);
        text.setShadowLayer(5, 0, 1, 0xCC000000);
        fitText(status, r.width() * 0.92f, u * 0.13f);
        g.drawText(status, r.centerX(), r.bottom - u * 0.12f, text);
        text.clearShadowLayer();
    }

    private static final String[] TUNE_LABEL = {"ACCEL  <->  TOP SPEED", "BALANCED  <->  HANDLING", "NIMBLE  <->  STABLE"};

    private int tuneVal(int i) { return i == 0 ? tuneSpeed : i == 1 ? tuneHandling : tuneStab; }

    private void setTune(int i, int v) {
        v = Math.max(-100, Math.min(100, v));
        if (i == 0) tuneSpeed = v; else if (i == 1) tuneHandling = v; else tuneStab = v;
        applyLoadout();
        saveTuning();
    }

    private void renderTune(Canvas g) {
        drawMenuBackdrop(g, 0xF2102A1A, 0xF2070518);
        float unit = Math.min(width, height), pad = unit * 0.04f;
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFF8CFF45);
        text.setTextSize(unit * 0.07f);
        g.drawText("CAR TUNING", width / 2f, pad + unit * 0.07f, text);
        drawPillButton(g, btnBack, "BACK", 0xFF19E0FF, true);

        for (int i = 0; i < 3; i++) {
            RectF b = tuneBars[i];
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(i == tuneSel ? 0xFFFFFFFF : 0xFFB9B2E6);
            text.setTextSize(b.height() * 0.7f);
            g.drawText(TUNE_LABEL[i], b.left, b.top - b.height() * 0.4f, text);
            // track
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(0x44101830);
            g.drawRoundRect(b, b.height() / 2, b.height() / 2, ui);
            ui.setColor(0x55FFFFFF);
            g.drawRect(b.centerX() - 2, b.top, b.centerX() + 2, b.bottom, ui);   // centre mark
            // knob
            float kx = b.left + (tuneVal(i) + 100) / 200f * b.width();
            ui.setColor(i == tuneSel ? 0xFF8CFF45 : 0xFF6CE0FF);
            g.drawCircle(kx, b.centerY(), b.height() * 0.7f, ui);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setColor(0xFFFFFFFF);
            g.drawText((tuneVal(i) > 0 ? "+" : "") + tuneVal(i), b.right, b.bottom + b.height() * 0.9f, text);
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFF7F88B0);
        text.setTextSize(unit * 0.026f);
        g.drawText("drag sliders (or D-pad: pick / adjust).  every gain costs something elsewhere.",
                width / 2f, height * 0.9f, text);
    }

    private void renderStyle(Canvas g) {
        drawMenuBackdrop(g, 0xF22A0E2A, 0xF2070518);
        float unit = Math.min(width, height), pad = unit * 0.04f;
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFFF6FB1);
        text.setTextSize(unit * 0.07f);
        g.drawText("STYLE  &  THEMES", width / 2f, pad + unit * 0.07f, text);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(0xFFFFD24D);
        text.setTextSize(unit * 0.045f);
        g.drawText(coins + " ¢", width - pad, pad + unit * 0.05f, text);
        drawPillButton(g, btnBack, "BACK", 0xFF19E0FF, true);

        for (int i = 0; i < 6; i++) {
            RectF r = themeCards[i];
            boolean own = (ownedThemes & (1 << i)) != 0;
            boolean active = theme == i;
            // mini preview: grass fill + road swatch + tree dot
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(THEME[i][0]);
            g.drawRoundRect(r, 14, 14, ui);
            ui.setColor(THEME[i][1]);
            tmp.set(r.left + r.width() * 0.1f, r.centerY() - r.height() * 0.12f, r.right - r.width() * 0.1f, r.centerY() + r.height() * 0.12f);
            g.drawRoundRect(tmp, 8, 8, ui);
            ui.setColor(THEME[i][2]);
            g.drawRect(r.centerX() - 3, tmp.top, r.centerX() + 3, tmp.bottom, ui);
            ui.setColor(THEME[i][3] == 0 ? 0xFF1E7A40 : THEME[i][3]);
            g.drawCircle(r.left + r.width() * 0.18f, r.top + r.height() * 0.22f, r.width() * 0.05f, ui);

            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(i == styleSel ? 6 : (active ? 4 : 2));
            ui.setColor(i == styleSel ? 0xFFFFFFFF : (active ? 0xFF42E2B8 : 0x88FFFFFF));
            g.drawRoundRect(r, 14, 14, ui);
            ui.setStyle(Paint.Style.FILL);

            text.setTextAlign(Paint.Align.CENTER);
            fitText(THEME_NAME[i], r.width() * 0.84f, r.height() * 0.17f);
            text.setColor(0xFF000000);
            g.drawText(THEME_NAME[i], r.centerX() + 1.5f, r.top + r.height() * 0.92f + 1.5f, text);
            text.setColor(0xFFFFFFFF);
            g.drawText(THEME_NAME[i], r.centerX(), r.top + r.height() * 0.92f, text);
            text.setColor(active ? 0xFF42E2B8 : (own ? 0xFFB9B2E6 : 0xFFFFD24D));
            text.setTextSize(r.height() * 0.13f);
            g.drawText(active ? "ACTIVE" : (own ? "TAP TO USE" : THEME_COST[i] + " ¢"),
                    r.centerX(), r.top + r.height() * 0.16f, text);
        }

        // skin chips
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFB9B2E6);
        text.setTextSize(unit * 0.03f);
        g.drawText("CAR FINISH", width / 2f, skinChips[0].top - unit * 0.02f, text);
        for (int i = 0; i < 4; i++) {
            RectF r = skinChips[i];
            boolean own = (ownedSkins & (1 << i)) != 0;
            boolean active = carSkin == i;
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(active ? 0x5542E2B8 : 0x33101830);
            g.drawRoundRect(r, 10, 10, ui);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(active ? 4 : 2);
            ui.setColor(active ? 0xFF42E2B8 : 0x88FFFFFF);
            g.drawRoundRect(r, 10, 10, ui);
            ui.setStyle(Paint.Style.FILL);
            text.clearShadowLayer();
            text.setColor(0xFFFFFFFF);
            text.setTextSize(r.height() * 0.3f);
            g.drawText(SKIN_NAME[i], r.centerX(), r.centerY(), text);
            text.setColor(active ? 0xFF42E2B8 : (own ? 0xFFB9B2E6 : 0xFFFFD24D));
            text.setTextSize(r.height() * 0.22f);
            g.drawText(active ? "ACTIVE" : (own ? "USE" : SKIN_COST[i] + " ¢"), r.centerX(), r.bottom - r.height() * 0.12f, text);
        }
    }

    private void tapTheme(int i) {
        if ((ownedThemes & (1 << i)) != 0) { theme = i; }
        else if (coins >= THEME_COST[i]) { coins -= THEME_COST[i]; ownedThemes |= (1 << i); theme = i; }
        else return;
        if (prefs != null) prefs.edit().putInt("theme", theme).putInt("ownedThemes", ownedThemes).putInt("coins", coins).apply();
    }

    private void tapSkin(int i) {
        if ((ownedSkins & (1 << i)) != 0) { carSkin = i; }
        else if (coins >= SKIN_COST[i]) { coins -= SKIN_COST[i]; ownedSkins |= (1 << i); carSkin = i; }
        else return;
        if (prefs != null) prefs.edit().putInt("skin", carSkin).putInt("ownedSkins", ownedSkins).putInt("coins", coins).apply();
    }

    private static final String[][] TUTORIAL_PAGES = {
            {"WELCOME TO TURBO CIRCUIT", "A top-down arcade racer.", "Let's cover the basics."},
            {"STEER & GO", "Hold ▲ to accelerate, ◀ ▶ to steer.", "DRIFT (handbrake) slides you through bends."},
            {"DRIFT = NITRO", "Drifting fills your NITRO meter.", "Tap NITRO for a burst of speed."},
            {"ITEMS & ? BOXES", "Grab ? boxes for TURBO, OIL or SHIELD.", "Use them with the ITEM button."},
            {"RAMPS & SHORTCUTS", "Blue ramps launch you; yellow-dashed", "side roads cut the lap. Mind the weather!"},
            {"PROGRESS", "Win coins & XP, buy upgrades in the SHOP,", "TUNE your car and chase ghost lap records."},
    };

    private void renderTutorial(Canvas g) {
        drawMenuBackdrop(g, 0xF21A0E33, 0xF2070518);
        float unit = Math.min(width, height);
        String[] p = TUTORIAL_PAGES[Math.min(tutorialPage, TUTORIAL_PAGES.length - 1)];
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(14, 0, 0, 0xFFFF2E88);
        text.setColor(0xFF19E0FF);
        text.setTextSize(unit * 0.08f);
        g.drawText(p[0], width / 2f, height * 0.34f, text);
        text.clearShadowLayer();
        text.setColor(0xFFFFFFFF);
        text.setTextSize(unit * 0.04f);
        g.drawText(p[1], width / 2f, height * 0.48f, text);
        g.drawText(p[2], width / 2f, height * 0.55f, text);

        // page dots
        ui.setStyle(Paint.Style.FILL);
        float dy = height * 0.68f, dgap = unit * 0.045f, dx = width / 2f - (TUTORIAL_PAGES.length - 1) * dgap / 2f;
        for (int i = 0; i < TUTORIAL_PAGES.length; i++) {
            ui.setColor(i == tutorialPage ? 0xFF19E0FF : 0x55FFFFFF);
            g.drawCircle(dx + i * dgap, dy, unit * 0.01f, ui);
        }
        float pulse = (float) (0.5 + 0.5 * Math.sin(titlePulse * 3));
        text.setColor(0xFFFFFFFF);
        text.setAlpha((int) (120 + 135 * pulse));
        text.setTextSize(unit * 0.04f);
        g.drawText(tutorialPage < TUTORIAL_PAGES.length - 1 ? "TAP / (A) : NEXT" : "TAP / (A) : START RACING", width / 2f, height * 0.8f, text);
        text.setAlpha(255);
        text.setColor(0xFF7F88B0);
        text.setTextSize(unit * 0.026f);
        g.drawText("(skip anytime — find this again via ? on the menu)", width / 2f, height * 0.9f, text);
    }

    private void advanceTutorial() {
        tutorialPage++;
        if (tutorialPage >= TUTORIAL_PAGES.length) {
            seenTutorial = true;
            if (prefs != null) prefs.edit().putBoolean("seenTutorial", true).apply();
            state = TITLE;
        }
    }

    private void renderCountdown(Canvas g) {
        g.drawColor(0x55000000);
        text.setTextAlign(Paint.Align.CENTER);
        int c = (int) Math.ceil(countdown);
        String s = c >= 1 ? String.valueOf(c) : "GO!";
        text.setShadowLayer(18, 0, 0, 0xFFFF2E88);
        text.setColor(c >= 1 ? 0xFFFFFFFF : 0xFF42E2B8);
        text.setTextSize(Math.min(width, height) * 0.22f);
        g.drawText(s, width / 2f, height * 0.55f, text);
        text.clearShadowLayer();
        text.setColor(0xFFB9B2E6);
        text.setTextSize(Math.min(width, height) * 0.04f);
        g.drawText(Tracks.LEVELS[level].name, width / 2f, height * 0.3f, text);
    }

    private void renderResults(Canvas g) {
        g.drawColor(0xCC050314);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(16, 0, 0, 0xFFFF2E88);
        text.setColor(0xFF19E0FF);
        text.setTextSize(Math.min(width, height) * 0.1f);
        String title = playerOut ? "ELIMINATED" : (mode == MODE_DRIFT ? "DRIFT TRIAL"
                : (mode == MODE_TT ? "TIME TRIAL" : (lastFinishWon ? "YOU WIN!" : "FINISH")));
        g.drawText(title, width / 2f, height * 0.16f, text);
        text.clearShadowLayer();

        if (mode == MODE_DRIFT) {
            text.setColor(0xFFFF8A1E);
            text.setTextSize(Math.min(width, height) * 0.07f);
            g.drawText("SCORE  " + (int) driftScore, width / 2f, height * 0.27f, text);
        }

        List<Standing> st = new ArrayList<>();
        st.add(new Standing("YOU", sim.lapsDone * (double) sim.N + sim.prevIdx, CAR_COLORS[playerColorIdx], sim.finishTime));
        for (Sim.Ai a : sim.ais) st.add(new Standing(a.name, a.lap * (double) sim.N + a.t, a.color, 0));
        Collections.sort(st, new Comparator<Standing>() {
            public int compare(Standing a, Standing b) { return Double.compare(b.prog, a.prog); }
        });
        float unit = Math.min(width, height), y = height * 0.34f;
        for (int i = 0; i < st.size(); i++) {
            Standing s = st.get(i);
            text.setTextAlign(Paint.Align.LEFT);
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(s.color);
            float cx = width / 2f - unit * 0.32f;
            g.drawCircle(cx, y - unit * 0.015f, unit * 0.022f, ui);
            text.setColor(i == 0 ? 0xFFFFE34D : 0xFFFFFFFF);
            text.setTextSize(unit * 0.05f);
            g.drawText((i + 1) + ".  " + s.name, cx + unit * 0.05f, y, text);
            if (s.name.equals("YOU") && sim.finishTime > 0) {
                text.setTextAlign(Paint.Align.RIGHT);
                g.drawText(fmt(sim.finishTime), width / 2f + unit * 0.34f, y, text);
            }
            y += unit * 0.07f;
        }

        // track records (leaderboard) on the right
        float[] lb = loadLeaderboard(level);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(0xFF8CFF45);
        text.setTextSize(unit * 0.04f);
        float lx = width / 2f + unit * 0.12f, ly = height * 0.34f;
        g.drawText("TRACK RECORDS", lx, ly, text);
        text.setTextSize(unit * 0.038f);
        for (int i = 0; i < Math.min(5, lb.length); i++) {
            boolean mine = Math.abs(lb[i] - raceBestLap) < 0.005f;
            text.setColor(mine ? 0xFFFFE34D : 0xFFCFC8FF);
            g.drawText((i + 1) + ".  " + fmt(lb[i]) + (mine ? "  <" : ""), lx, ly + unit * 0.06f * (i + 1), text);
        }
        if (lb.length == 0) {
            text.setColor(0xFF7F88B0);
            g.drawText("set your first record!", lx, ly + unit * 0.06f, text);
        }

        // rewards
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFFFD24D);
        text.setTextSize(unit * 0.045f);
        g.drawText("+ " + gainCoins + " ¢      + " + gainXp + " XP", width / 2f, height * 0.74f, text);
        if (leveledUp) {
            text.setColor(0xFF42E2B8);
            text.setTextSize(unit * 0.05f);
            g.drawText("LEVEL UP!  NOW LV " + driverLevel(), width / 2f, height * 0.79f, text);
        }
        if (dailyJustWon) {
            text.setColor(0xFFFFC23D);
            text.setTextSize(unit * 0.045f);
            g.drawText("DAILY CHALLENGE COMPLETE!  +" + DAILY_REWARD + " ¢", width / 2f, height * 0.84f, text);
        }

        boolean hasNext = !champ && !randomTrack && level + 1 < Tracks.LEVELS.length && level + 1 < unlocked;
        float pulse = (float) (0.5 + 0.5 * Math.sin(titlePulse * 3));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFFFFFFF);
        text.setAlpha((int) (120 + 135 * pulse));
        text.setTextSize(unit * 0.038f);
        String prompt = champ ? "TAP FOR SEASON STANDINGS"
                : (hasNext ? "TAP RIGHT: NEXT LEVEL    •    TAP LEFT: MENU" : "TAP TO RETURN TO MENU");
        g.drawText(prompt, width / 2f, height * 0.89f, text);
        text.setAlpha(255);
    }

    // --------------------------------------------------------------- helpers

    private static int lerp(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private static int tcol(int base, int tint) {
        return tint == 0 ? base : lerp(base, tint, 0.55f);
    }

    /** Cached glossy body gradient per car colour (built once, reused every frame). */
    private LinearGradient bodyGradient(int color) {
        LinearGradient lg = bodyGrad.get(color);
        if (lg == null) {
            lg = new LinearGradient(0, -18, 0, 18,
                    new int[]{lighten(color, 1.35f), color, darken(color, 0.7f)}, null, Shader.TileMode.CLAMP);
            bodyGrad.put(color, lg);
        }
        return lg;
    }

    private static int lighten(int c, float f) {
        int r = Math.min(255, (int) (((c >> 16) & 0xff) * f));
        int gg = Math.min(255, (int) (((c >> 8) & 0xff) * f));
        int b = Math.min(255, (int) ((c & 0xff) * f));
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }

    private static int darken(int c, float f) {
        int r = (int) (((c >> 16) & 0xff) * f);
        int gg = (int) (((c >> 8) & 0xff) * f);
        int b = (int) ((c & 0xff) * f);
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }

    // ----------------------------------------------------------------- input

    void handleTouch(MotionEvent e) {
        int action = e.getActionMasked();

        if (state == TITLE) {
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                float x = e.getX(e.getActionIndex()), y = e.getY(e.getActionIndex());
                if (btnShop.contains(x, y)) { state = SHOP; shopSel = 0; return; }
                if (btnTune.contains(x, y)) { state = TUNE; tuneSel = 0; return; }
                if (btnStyle.contains(x, y)) { state = STYLE; styleSel = theme; return; }
                if (btnHelp.contains(x, y)) { state = TUTORIAL; tutorialPage = 0; return; }
                if (btnMode.contains(x, y)) { mode = (mode + 1) % 4; loadLevel(level); return; }
                if (btnChamp.contains(x, y)) { startChampionship(); return; }
                if (btnRandom.contains(x, y)) { randomTrack = !randomTrack; loadLevel(level); return; }
                if (btnMusic.contains(x, y)) {
                    musicOn = !musicOn;
                    if (prefs != null) prefs.edit().putBoolean("music", musicOn).apply();
                    return;
                }
                for (int i = 0; i < 3; i++)
                    if (tireBtns[i].contains(x, y)) {
                        tire = i; applyLoadout();
                        if (prefs != null) prefs.edit().putInt("tire", i).apply();
                        return;
                    }
                for (int i = 0; i < swatches.length; i++)
                    if (swatches[i].contains(x, y)) {
                        playerColorIdx = i;
                        if (prefs != null) prefs.edit().putInt("color", i).apply();
                        return;
                    }
                if (randomTrack) {
                    for (int i = 0; i < 5; i++) {
                        if (codeUp[i].contains(x, y)) { changeDigit(i, 1); return; }
                        if (codeDn[i].contains(x, y)) { changeDigit(i, -1); return; }
                    }
                    if (btnReroll.contains(x, y)) { trackCode = rnd.nextInt(100000); loadLevel(0); return; }
                    if (btnGo.contains(x, y)) { startRace(0); return; }
                } else {
                    for (int i = 0; i < levelBtns.length; i++)
                        if (levelBtns[i].contains(x, y) && i < unlocked) { startRace(i); return; }
                }
            }
            return;
        }
        if (state == CHAMP) {
            if (action == MotionEvent.ACTION_DOWN) advanceChampionship();
            return;
        }
        if (state == SHOP) {
            if (action == MotionEvent.ACTION_DOWN) {
                float x = e.getX(e.getActionIndex()), y = e.getY(e.getActionIndex());
                if (btnBack.contains(x, y)) { state = TITLE; return; }
                for (int i = 0; i < SHOP_N; i++)
                    if (shopCards[i].contains(x, y)) { shopSel = i; tapShop(i); return; }
            }
            return;
        }
        if (state == TUNE) {
            if (action == MotionEvent.ACTION_DOWN && btnBack.contains(e.getX(e.getActionIndex()), e.getY(e.getActionIndex()))) {
                state = TITLE; return;
            }
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                float x = e.getX(e.getActionIndex()), y = e.getY(e.getActionIndex());
                for (int i = 0; i < 3; i++) {
                    RectF b = tuneBars[i];
                    if (x >= b.left - 30 && x <= b.right + 30 && y >= b.top - b.height() && y <= b.bottom + b.height()) {
                        tuneSel = i;
                        setTune(i, Math.round((x - b.left) / b.width() * 200 - 100));
                        return;
                    }
                }
            }
            return;
        }
        if (state == STYLE) {
            if (action == MotionEvent.ACTION_DOWN) {
                float x = e.getX(e.getActionIndex()), y = e.getY(e.getActionIndex());
                if (btnBack.contains(x, y)) { state = TITLE; return; }
                for (int i = 0; i < 6; i++) if (themeCards[i].contains(x, y)) { styleSel = i; tapTheme(i); return; }
                for (int i = 0; i < 4; i++) if (skinChips[i].contains(x, y)) { tapSkin(i); return; }
            }
            return;
        }
        if (state == TUTORIAL) {
            if (action == MotionEvent.ACTION_DOWN) advanceTutorial();
            return;
        }
        if (state == FINISHED) {
            if (action == MotionEvent.ACTION_DOWN) {
                float x = e.getX(e.getActionIndex());
                if (champ) { state = CHAMP; return; }
                boolean hasNext = !randomTrack && level + 1 < Tracks.LEVELS.length && level + 1 < unlocked;
                if (hasNext && x > width * 0.5f) startRace(level + 1);
                else { state = TITLE; loadLevel(level); }
            }
            return;
        }

        boolean l = false, r = false, gas = false, brake = false;
        for (int i = 0; i < e.getPointerCount(); i++) {
            boolean up = (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP)
                    && i == e.getActionIndex();
            if (up) continue;
            float x = e.getX(i), y = e.getY(i);
            if (btnLeft.contains(x, y)) l = true;
            if (btnRight.contains(x, y)) r = true;
            if (btnGas.contains(x, y)) gas = true;
            if (btnBrake.contains(x, y)) brake = true;
        }
        keyLeft = l; keyRight = r; keyGas = gas; keyBrake = brake;
        if (action == MotionEvent.ACTION_UP) keyLeft = keyRight = keyGas = keyBrake = false;

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(e.getActionIndex()), y = e.getY(e.getActionIndex());
            if (btnBoost.contains(x, y)) tapBoost = true;
            if (btnItem.contains(x, y)) tapItem = true;
        }
    }

    // ----------------------------------------------------- controller input

    boolean onKey(int code, boolean down) {
        if (state == RACING || state == COUNTDOWN) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: kLeft = down; return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: kRight = down; return true;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_SPACE: kGas = down; return true;
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_BUTTON_R1:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_SHIFT_LEFT: kBrake = down; return true;
                case KeyEvent.KEYCODE_BUTTON_X:
                case KeyEvent.KEYCODE_BUTTON_L1: if (down) tapBoost = true; return true;
                case KeyEvent.KEYCODE_BUTTON_Y: if (down) tapItem = true; return true;
                default: return false;
            }
        }
        if (!down) return false;
        if (state == TITLE) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: navLevel(-1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: navLevel(1); return true;
                case KeyEvent.KEYCODE_DPAD_UP: navLevel(-4); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: navLevel(4); return true;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER: if (level < unlocked) startRace(level); return true;
                case KeyEvent.KEYCODE_BUTTON_Y: state = SHOP; shopSel = 0; return true;
                case KeyEvent.KEYCODE_BUTTON_X:
                    tire = (tire + 1) % 3; applyLoadout();
                    if (prefs != null) prefs.edit().putInt("tire", tire).apply(); return true;
                case KeyEvent.KEYCODE_BUTTON_L1: mode = 1 - mode; loadLevel(level); return true;
                case KeyEvent.KEYCODE_BUTTON_R1: state = TUNE; tuneSel = 0; return true;
                case KeyEvent.KEYCODE_BUTTON_L2: state = STYLE; styleSel = theme; return true;
                case KeyEvent.KEYCODE_BUTTON_START: state = TUTORIAL; tutorialPage = 0; return true;
                default: return false;
            }
        }
        if (state == TUTORIAL) { advanceTutorial(); return true; }
        if (state == STYLE) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: styleSel = (styleSel + 5) % 6; return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: styleSel = (styleSel + 1) % 6; return true;
                case KeyEvent.KEYCODE_DPAD_UP: styleSel = (styleSel + 3) % 6; return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: styleSel = (styleSel + 3) % 6; return true;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER: tapTheme(styleSel); return true;
                case KeyEvent.KEYCODE_BUTTON_X: tapSkin((carSkin + 1) % 4); return true;
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_BACK: state = TITLE; return true;
                default: return false;
            }
        }
        if (state == TUNE) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: tuneSel = (tuneSel + 2) % 3; return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: tuneSel = (tuneSel + 1) % 3; return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: setTune(tuneSel, tuneVal(tuneSel) - 10); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: setTune(tuneSel, tuneVal(tuneSel) + 10); return true;
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_BUTTON_A: state = TITLE; return true;
                default: return false;
            }
        }
        if (state == SHOP) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: shopSel = (shopSel + SHOP_N - 1) % SHOP_N; return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: shopSel = (shopSel + 1) % SHOP_N; return true;
                case KeyEvent.KEYCODE_DPAD_UP: shopSel = (shopSel + SHOP_N - 3) % SHOP_N; return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: shopSel = (shopSel + 3) % SHOP_N; return true;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_CENTER: tapShop(shopSel); return true;
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_BACK: state = TITLE; return true;
                default: return false;
            }
        }
        if (state == CHAMP) { advanceChampionship(); return true; }
        if (state == FINISHED) {
            if (champ) { state = CHAMP; return true; }
            boolean hasNext = !randomTrack && level + 1 < Tracks.LEVELS.length && level + 1 < unlocked;
            switch (code) {
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    if (hasNext) startRace(level + 1); else { state = TITLE; loadLevel(level); } return true;
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_BACK: state = TITLE; loadLevel(level); return true;
                default: return false;
            }
        }
        return false;
    }

    boolean onMotion(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != InputDevice.SOURCE_CLASS_JOYSTICK) return false;
        float x = e.getAxisValue(MotionEvent.AXIS_X);
        float hat = e.getAxisValue(MotionEvent.AXIS_HAT_X);
        float steer = Math.abs(hat) > Math.abs(x) ? hat : x;
        aLeft = steer < -0.4f;
        aRight = steer > 0.4f;
        float rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
        float lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
        aGas = rt > 0.3f;
        aBrake = lt > 0.3f;
        return true;
    }

    private void navLevel(int d) {
        int ni = level + d;
        ni = Math.max(0, Math.min(ni, Math.min(unlocked, Tracks.LEVELS.length) - 1));
        if (ni != level) loadLevel(ni);
    }

    private void tapShop(int i) {
        if (owned[i]) {
            equipped[i] = !equipped[i];
        } else if (coins >= SHOP_COST[i] && driverLevel() >= SHOP_REQ[i]) {
            coins -= SHOP_COST[i];
            owned[i] = true;
            equipped[i] = true;
        } else {
            return;
        }
        applyLoadout();
        saveProfile();
    }

    private void startChampionship() {
        champ = true; randomTrack = false; mode = MODE_GP; champRound = 0;
        for (int i = 0; i < 4; i++) champScore[i] = 0;
        startRace(CHAMP_TRACKS[0]);
    }

    private void advanceChampionship() {
        if (champRound < CHAMP_TRACKS.length - 1) { champRound++; startRace(CHAMP_TRACKS[champRound]); }
        else { champ = false; state = TITLE; loadLevel(level); }
    }

    private void startRace(int lvl) {
        loadLevel(lvl);
        camX = sim.carX; camY = sim.carY;
        keyLeft = keyRight = keyGas = keyBrake = false;
        kLeft = kRight = kGas = kBrake = aLeft = aRight = aGas = aBrake = false;
        tapBoost = tapItem = false;
        awarded = false;
        offTrackEver = hitEver = false;
        raceBestLap = 0;
        playerOut = false;
        driftScore = 0;
        elimTimer = 7;
        finishTimer = 0;
        prevPos = sim.ais.size() + 1;
        zoomMul = 1.5;
        overtakeT = elimBannerT = 0;
        state = COUNTDOWN;
        countdown = 3.99;
    }

    // ----------------------------------------------------- progression/shop

    private int driverLevel() { return 1 + xp / 150; }

    private void applyItem(Sim.Mods m, int i) {
        switch (i) {
            case 0: m.topSpeed *= 1.12; m.accel *= 1.10; m.grip *= 0.90; break;
            case 1: m.grip *= 1.18; m.turn *= 1.10; m.rainGrip *= 0.6; break;
            case 2: m.boost *= 1.35; m.nitroCharge *= 1.3; m.accel *= 0.92; break;
            case 3: m.accel *= 1.15; m.turn *= 1.08; m.bumpResist *= 0.7; break;
            case 4: m.offRoad *= 0.5; m.rainGrip *= 1.6; m.topSpeed *= 0.93; break;
            case 5: m.brake *= 1.4; m.turn *= 1.05; m.topSpeed *= 0.96; break;
        }
    }

    private void applyLoadout() {
        sim.mods.reset();
        for (int i = 0; i < SHOP_N; i++) if (owned[i] && equipped[i]) applyItem(sim.mods, i);
        // tyre compound strategy
        if (tire == 0) { sim.mods.grip *= 1.10; sim.mods.rainGrip *= 0.72; }      // soft: dry grip, poor wet
        else if (tire == 2) { sim.mods.grip *= 0.95; sim.mods.rainGrip *= 1.70; } // wet: great in rain
        // tuning sliders (zero-sum-ish trade-offs)
        double s = tuneSpeed / 100.0, h = tuneHandling / 100.0, st = tuneStab / 100.0;
        sim.mods.topSpeed *= 1 + 0.08 * s - 0.05 * h;
        sim.mods.accel *= 1 - 0.06 * s;
        sim.mods.grip *= 1 + 0.12 * h;
        sim.mods.turn *= 1 + 0.10 * h - 0.08 * st;
        sim.mods.bumpResist *= 1 + 0.30 * st;
    }

    private void saveTuning() {
        if (prefs != null) prefs.edit().putInt("tuneSpeed", tuneSpeed)
                .putInt("tuneHandling", tuneHandling).putInt("tuneStab", tuneStab).apply();
    }

    private float[] loadLeaderboard(int t) {
        if (prefs == null) return new float[0];
        String s = prefs.getString("lb" + t, "");
        if (s.isEmpty()) return new float[0];
        String[] p = s.split(",");
        java.util.ArrayList<Float> v = new java.util.ArrayList<>();
        for (String q : p) try { v.add(Float.parseFloat(q)); } catch (NumberFormatException ignored) {}
        float[] out = new float[v.size()];
        for (int i = 0; i < out.length; i++) out[i] = v.get(i);
        return out;
    }

    private void addLeaderboard(int t, float lap) {
        if (prefs == null || lap <= 0) return;
        java.util.ArrayList<Float> v = new java.util.ArrayList<>();
        for (float f : loadLeaderboard(t)) v.add(f);
        v.add(lap);
        java.util.Collections.sort(v);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, v.size()); i++) { if (i > 0) sb.append(','); sb.append(v.get(i)); }
        prefs.edit().putString("lb" + t, sb.toString()).apply();
    }

    private void computeDaily() {
        today = System.currentTimeMillis() / 86400000L;
        long s = today * 2654435761L;
        dailyTrack = (int) Math.floorMod(s, Tracks.LEVELS.length);
        dailyType = (int) Math.floorMod(s / 7, 3);
        dailyDoneToday = prefs != null && prefs.getLong("dailyDone", -1) == today;
    }

    private String dailyDesc() {
        String where = Tracks.LEVELS[dailyTrack].name.replaceAll("^\\d+ · ", "");
        switch (dailyType) {
            case 0: return "Win on " + where;
            case 1: return "Clean race on " + where + " (stay on track)";
            default: return "No spin-outs on " + where;
        }
    }

    private boolean dailyMet() {
        if (level != dailyTrack) return false;
        switch (dailyType) {
            case 0: return sim.position() == 1;
            case 1: return !offTrackEver;
            default: return !hitEver;
        }
    }

    private void awardRace() {
        int oldLevel = driverLevel();
        int diff = randomTrack ? 3 : level;
        if (mode == MODE_DRIFT) {
            gainCoins = 50 + (int) (driftScore);
            gainXp = 35 + (int) (driftScore / 4);
        } else if (mode == MODE_TT) {
            gainCoins = 60 + diff * 8;
            gainXp = 35 + diff * 4;
        } else {
            int p = playerOut ? 4 : Math.min(sim.position(), 4);
            int[] coinByPlace = {0, 150, 90, 55, 30};
            int[] xpByPlace = {0, 100, 65, 40, 25};
            int champBonus = champ ? 60 : 0;
            gainCoins = 80 + coinByPlace[p] + diff * 12 + champBonus;
            gainXp = 50 + xpByPlace[p] + diff * 6 + champBonus;
        }
        coins += gainCoins;
        xp += gainXp;
        leveledUp = driverLevel() > oldLevel;
        saveProfile();
    }

    private void saveProfile() {
        if (prefs == null) return;
        int own = 0, eq = 0;
        for (int i = 0; i < SHOP_N; i++) {
            if (owned[i]) own |= (1 << i);
            if (equipped[i]) eq |= (1 << i);
        }
        prefs.edit().putInt("coins", coins).putInt("xp", xp)
                .putInt("owned", own).putInt("equip", eq).apply();
    }

    // ----------------------------------------------------------- ghost car

    private void saveGhost(int lvl, List<float[]> rec) {
        // downsample to <=120 samples and store as "t:x:y:a;..."
        int max = 120;
        int step = Math.max(1, rec.size() / max);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rec.size(); i += step) {
            float[] s = rec.get(i);
            sb.append(s[0]).append(':').append(s[1]).append(':').append(s[2]).append(':').append(s[3]).append(';');
        }
        ghost = parseGhost(sb.toString());
        if (prefs != null) prefs.edit().putString("g" + lvl, sb.toString()).putFloat("tb" + lvl, (float) trackBest[lvl]).apply();
    }

    private float[][] loadGhost(int lvl) {
        if (prefs == null) return null;
        return parseGhost(prefs.getString("g" + lvl, ""));
    }

    private float[][] parseGhost(String s) {
        if (s == null || s.length() < 4) return null;
        String[] parts = s.split(";");
        java.util.ArrayList<float[]> list = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            String[] f = p.split(":");
            if (f.length != 4) continue;
            try {
                list.add(new float[]{Float.parseFloat(f[0]), Float.parseFloat(f[1]),
                        Float.parseFloat(f[2]), Float.parseFloat(f[3])});
            } catch (NumberFormatException ignored) {}
        }
        return list.isEmpty() ? null : list.toArray(new float[0][]);
    }

    private void drawGhost(Canvas g) {
        if (ghost == null || ghost.length < 2 || state != RACING) return;
        double t = sim.lapTime;
        // find bracketing samples by time
        float[] a = ghost[0], b = ghost[ghost.length - 1];
        for (int i = 0; i < ghost.length - 1; i++) {
            if (ghost[i][0] <= t && ghost[i + 1][0] >= t) { a = ghost[i]; b = ghost[i + 1]; break; }
        }
        float span = b[0] - a[0];
        float f = span > 0.0001f ? (float) ((t - a[0]) / span) : 0f;
        f = Math.max(0, Math.min(1, f));
        float gx = a[1] + (b[1] - a[1]) * f;
        float gy = a[2] + (b[2] - a[2]) * f;
        float ga = a[3] + (b[3] - a[3]) * f;

        g.save();
        g.translate(gx, gy);
        g.rotate((float) Math.toDegrees(ga));
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(0x66B0E8FF);
        tmp.set(-36, -18, 36, 18);
        g.drawRoundRect(tmp, 14, 14, ui);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(2);
        ui.setColor(0x99E8F6FF);
        g.drawRoundRect(tmp, 14, 14, ui);
        ui.setStyle(Paint.Style.FILL);
        g.restore();
    }

    private static final class Particle {
        double x, y, vx, vy, life, maxLife; float size;
    }

    private static final class Standing {
        String name; double prog; int color; double time;
        Standing(String n, double p, int c, double t) { name = n; prog = p; color = c; time = t; }
    }
}
