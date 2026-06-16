package com.fable.racer;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
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
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Top-down arcade racer with drift+nitro, Mario-Kart-style item boxes and
 * weapons, boost pads, dynamic day/night, weather, particles, a radar mini-map,
 * a 3-lap race with countdown + results, rubber-band AI and procedural audio.
 */
final class Game {

    // ---- circuit control points ----
    private static final float[][] WAYPOINTS = {
            {-1000, -250}, {-700, -560}, {-150, -620}, {380, -520},
            {760, -250}, {1040, 120}, {760, 430}, {300, 360},
            {40, 540}, {-420, 600}, {-840, 380}, {-1080, 60}
    };
    private static final int STEPS_PER_SEG = 26;
    private static final float ROAD_HALF = 120f;
    private static final int TOTAL_LAPS = 3;

    // ---- physics ----
    private static final double ACCEL = 470;
    private static final double BRAKE = 760;
    private static final double DRAG = 90;
    private static final double MAX_SPEED = 600;
    private static final double BOOST_SPEED = 920;
    private static final double GRIP_NORMAL = 6.5;
    private static final double GRIP_DRIFT = 1.4;
    private static final double TURN_RATE = 3.0;
    private static final double TURN_REF = 150;

    // ---- states ----
    private static final int TITLE = 0, COUNTDOWN = 1, RACING = 2, FINISHED = 3;
    private int state = TITLE;

    // ---- items ----
    private static final int IT_NONE = 0, IT_TURBO = 1, IT_OIL = 2, IT_SHIELD = 3;

    private int width, height;
    private double zoom = 1.2;

    // centerline
    private float[] cxArr, cyArr, dirX, dirY;
    private int N;
    private float minX, minY, maxX, maxY;

    // player car
    private double carX, carY, carAngle, vx, vy;
    private double camX, camY;
    private int lapsDone = 0, prevIdx = 0, startIdx = 6;
    private boolean onTrack = true, drifting = false;
    private double shake = 0, spin = 0, shield = 0, boostTime = 0;
    private double nitro = 0;            // 0..1 meter
    private int heldItem = IT_NONE;
    private int playerColorIdx = 0;

    // timing
    private double raceTime = 0, lapTime = 0, bestLap = 0, finishTime = 0;
    private double countdown = 0;
    private double dayTime = 0.18;        // 0..1 time of day
    private int weather = 0;              // 0 clear, 1 rain
    private final float[][] rain = new float[120][3];

    // world objects
    private final List<Ai> ais = new ArrayList<>();
    private final List<float[]> trees = new ArrayList<>();
    private final List<ItemBox> boxes = new ArrayList<>();
    private final List<int[]> pads = new ArrayList<>();      // {centerline index}
    private final List<Oil> oils = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<float[]> skidMarks = new ArrayList<>(); // x,y,angle,alpha

    // input flags
    boolean keyLeft, keyRight, keyGas, keyBrake;
    private boolean tapBoost, tapItem;
    private double titlePulse = 0, dash = 0;

    // control rects
    private final RectF btnLeft = new RectF(), btnRight = new RectF();
    private final RectF btnGas = new RectF(), btnBrake = new RectF();
    private final RectF btnBoost = new RectF(), btnItem = new RectF();
    private final RectF[] swatches = new RectF[6];

    // paints
    private final Paint road = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path roadPath = new Path();
    private final Path tmpPath = new Path();
    private final RectF tmp = new RectF();
    private final Random rnd = new Random();
    private final PorterDuffXfermode ADD = new PorterDuffXfermode(PorterDuff.Mode.ADD);

    private final SharedPreferences prefs;
    private EngineAudio audio;

    private static final int[] CAR_COLORS = {
            0xFF19E0FF, 0xFFFF4D9D, 0xFF8CFF45, 0xFFFFC23D, 0xFFB66BFF, 0xFFFFFFFF
    };

    Game(Context ctx, int w, int h) {
        prefs = ctx == null ? null : ctx.getSharedPreferences("turbo", Context.MODE_PRIVATE);
        if (prefs != null) bestLap = prefs.getFloat("bestLap", 0f);
        text.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        buildTrack();
        buildDecor();
        try { audio = new EngineAudio(); } catch (Throwable t) { audio = null; }
        resetRace();
        resize(w, h);
    }

    void release() { if (audio != null) audio.release(); }

    void resize(int w, int h) {
        this.width = w;
        this.height = h;
        zoom = Math.min(w, h) / 640.0;
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
        float sw = Math.min(width, height) * 0.07f;
        float sx = width / 2f - sw * 3.3f;
        float sy = height * 0.74f;
        for (int i = 0; i < swatches.length; i++) {
            swatches[i] = new RectF(sx + i * sw * 1.1f, sy, sx + i * sw * 1.1f + sw, sy + sw);
        }
    }

    // ----------------------------------------------------------- build track

    private void buildTrack() {
        int m = WAYPOINTS.length;
        N = m * STEPS_PER_SEG;
        cxArr = new float[N];
        cyArr = new float[N];
        dirX = new float[N];
        dirY = new float[N];
        int k = 0;
        for (int i = 0; i < m; i++) {
            float[] p0 = WAYPOINTS[(i - 1 + m) % m];
            float[] p1 = WAYPOINTS[i];
            float[] p2 = WAYPOINTS[(i + 1) % m];
            float[] p3 = WAYPOINTS[(i + 2) % m];
            for (int s = 0; s < STEPS_PER_SEG; s++) {
                float t = (float) s / STEPS_PER_SEG;
                cxArr[k] = catmull(p0[0], p1[0], p2[0], p3[0], t);
                cyArr[k] = catmull(p0[1], p1[1], p2[1], p3[1], t);
                k++;
            }
        }
        minX = minY = Float.MAX_VALUE;
        maxX = maxY = -Float.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            int nx = (i + 1) % N, pv = (i - 1 + N) % N;
            float dx = cxArr[nx] - cxArr[pv], dy = cyArr[nx] - cyArr[pv];
            float len = (float) Math.hypot(dx, dy);
            if (len < 0.0001f) len = 1;
            dirX[i] = dx / len;
            dirY[i] = dy / len;
            minX = Math.min(minX, cxArr[i]); maxX = Math.max(maxX, cxArr[i]);
            minY = Math.min(minY, cyArr[i]); maxY = Math.max(maxY, cyArr[i]);
        }
        roadPath.reset();
        roadPath.moveTo(cxArr[0], cyArr[0]);
        for (int i = 1; i < N; i++) roadPath.lineTo(cxArr[i], cyArr[i]);
        roadPath.close();
    }

    private static float catmull(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return 0.5f * ((2 * p1) + (-p0 + p2) * t
                + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    private void buildDecor() {
        trees.clear();
        int guard = 0;
        while (trees.size() < 18 && guard++ < 3000) {
            float x = minX - 220 + rnd.nextFloat() * (maxX - minX + 440);
            float y = minY - 220 + rnd.nextFloat() * (maxY - minY + 440);
            if (distToTrack(x, y) > ROAD_HALF + 75)
                trees.add(new float[]{x, y, 26 + rnd.nextFloat() * 24});
        }
        // boost pads along a few straights
        pads.clear();
        int[] padIdx = {STEPS_PER_SEG * 1, STEPS_PER_SEG * 5, STEPS_PER_SEG * 8, STEPS_PER_SEG * 11};
        for (int p : padIdx) pads.add(new int[]{p % N});
        // item boxes (3 across the road) at a couple of spots
        boxes.clear();
        int[] boxIdx = {STEPS_PER_SEG * 3, STEPS_PER_SEG * 7, STEPS_PER_SEG * 10};
        for (int bi : boxIdx) {
            for (int lane = -1; lane <= 1; lane++) {
                ItemBox b = new ItemBox();
                b.idx = bi % N;
                b.offset = lane * 55f;
                b.x = cxArr[b.idx] + dirY[b.idx] * b.offset;
                b.y = cyArr[b.idx] - dirX[b.idx] * b.offset;
                boxes.add(b);
            }
        }
    }

    private float distToTrack(float x, float y) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            float dx = x - cxArr[i], dy = y - cyArr[i];
            float d = dx * dx + dy * dy;
            if (d < best) best = d;
        }
        return (float) Math.sqrt(best);
    }

    private int nearestIndex(double x, double y) {
        double best = Double.MAX_VALUE;
        int bi = 0;
        for (int i = 0; i < N; i++) {
            double dx = x - cxArr[i], dy = y - cyArr[i];
            double d = dx * dx + dy * dy;
            if (d < best) { best = d; bi = i; }
        }
        return bi;
    }

    // -------------------------------------------------------------- new race

    private void resetRace() {
        carX = cxArr[startIdx];
        carY = cyArr[startIdx];
        carAngle = Math.atan2(dirY[startIdx], dirX[startIdx]);
        vx = vy = 0;
        camX = carX; camY = carY;
        lapsDone = 0; prevIdx = startIdx;
        raceTime = 0; lapTime = 0; finishTime = 0;
        nitro = 0; heldItem = IT_NONE;
        spin = shield = boostTime = shake = 0;
        oils.clear(); particles.clear(); skidMarks.clear();
        for (ItemBox b : boxes) { b.active = true; b.respawn = 0; }

        weather = rnd.nextInt(3) == 0 ? 1 : 0;       // ~1/3 chance of rain
        dayTime = rnd.nextDouble();                  // random time of day
        for (int i = 0; i < rain.length; i++) {
            rain[i][0] = rnd.nextFloat();
            rain[i][1] = rnd.nextFloat();
            rain[i][2] = 0.5f + rnd.nextFloat();
        }

        ais.clear();
        int[] cols = {0xFFFF4D9D, 0xFFFFC23D, 0xFF8CFF45};
        for (int i = 0; i < 3; i++) {
            Ai a = new Ai();
            int idx0 = (startIdx - (i + 1) * 5 + N) % N;
            a.offset = (i - 1) * 46f;
            a.t = idx0;
            a.baseSpeed = 23 + i * 1.2;
            a.color = cols[i];
            a.x = cxArr[idx0] + dirY[idx0] * a.offset;
            a.y = cyArr[idx0] - dirX[idx0] * a.offset;
            a.angle = Math.atan2(dirY[idx0], dirX[idx0]);
            ais.add(a);
        }
    }

    // --------------------------------------------------------------- update

    void update(double dt) {
        titlePulse += dt;
        dayTime += dt / 90.0;             // full day cycle ~90s
        if (dayTime >= 1) dayTime -= 1;

        if (state == TITLE) { idleAudio(); return; }

        if (state == COUNTDOWN) {
            countdown -= dt;
            int prev = (int) Math.ceil(countdown + dt);
            int now = (int) Math.ceil(countdown);
            if (now != prev && now >= 1 && now <= 3 && audio != null) audio.blip(EngineAudio.BLIP_COUNT);
            if (countdown <= 0) {
                state = RACING;
                if (audio != null) audio.blip(EngineAudio.BLIP_GO);
            }
            idleAudio();
            return;
        }

        if (state == FINISHED) { idleAudio(); updateParticles(dt); return; }

        // -------- RACING --------
        raceTime += dt;
        lapTime += dt;
        dash -= Math.hypot(vx, vy) * dt * 0.5;

        updatePlayer(dt);
        updateAis(dt);
        updateOils(dt);
        updateParticles(dt);

        // camera follow with look-ahead
        double sp = Math.hypot(vx, vy);
        double look = 120 * (sp / MAX_SPEED);
        double tx = carX + Math.cos(carAngle) * look;
        double ty = carY + Math.sin(carAngle) * look;
        camX += (tx - camX) * Math.min(1, dt * 6);
        camY += (ty - camY) * Math.min(1, dt * 6);
        shake = Math.max(0, shake - dt * 12);

        // engine audio
        if (audio != null) {
            float rpmF = (float) Math.min(1, sp / MAX_SPEED + 0.05);
            audio.set(rpmF, 1f, boostTime > 0, drifting ? 0.9f : 0f);
        }
    }

    private void idleAudio() {
        if (audio != null) audio.set(0.06f, state == TITLE ? 0.5f : 0.7f, false, 0f);
    }

    private void updatePlayer(double dt) {
        int idx = nearestIndex(carX, carY);
        onTrack = distToTrack((float) carX, (float) carY) <= ROAD_HALF;

        double steer = keyLeft ? -1 : (keyRight ? 1 : 0);

        // forward / lateral decomposition
        double ca = Math.cos(carAngle), sa = Math.sin(carAngle);
        double vForward = vx * ca + vy * sa;
        double vLat = -vx * sa + vy * ca;

        boolean spinning = spin > 0;
        if (spinning) {
            spin -= dt;
            carAngle += dt * 14;          // uncontrolled spin
            steer = 0;
            vForward *= (1 - 2.5 * dt);
        } else {
            if (keyGas) vForward += ACCEL * dt;
            else if (keyBrake) vForward -= BRAKE * dt;
            else vForward -= Math.signum(vForward) * Math.min(Math.abs(vForward), DRAG * dt);
        }

        // boost
        if (tapBoost && boostTime <= 0 && nitro > 0.25) {
            boostTime = 1.4;
            nitro = Math.max(0, nitro - 0.6);
            if (audio != null) audio.blip(EngineAudio.BLIP_BOOST);
        }
        tapBoost = false;
        double topSpeed = MAX_SPEED;
        if (boostTime > 0) { boostTime -= dt; topSpeed = BOOST_SPEED; vForward += 600 * dt; }

        // use item
        if (tapItem && heldItem != IT_NONE && !spinning) useItem();
        tapItem = false;

        // drifting: handbrake at speed while steering, lowers lateral grip
        drifting = keyBrake && Math.abs(vForward) > 120 && steer != 0 && !spinning;
        double grip = drifting ? GRIP_DRIFT : GRIP_NORMAL;
        if (weather == 1) grip *= 0.55;            // rain = slippery
        if (!onTrack) grip *= 0.7;
        vLat *= Math.exp(-grip * dt);

        // steering
        double speedAbs = Math.abs(vForward);
        double turn = steer * TURN_RATE * dt * Math.min(1, speedAbs / TURN_REF) * (vForward >= 0 ? 1 : -1);
        if (drifting) turn *= 1.7;
        carAngle += turn;

        // off-track slow + clamp
        if (!onTrack && speedAbs > MAX_SPEED * 0.35) {
            vForward -= 380 * dt * Math.signum(vForward);
            shake = Math.min(shake + dt * 22, 4);
        }
        vForward = Math.max(-0.35 * MAX_SPEED, Math.min(vForward, topSpeed));

        // recombine
        ca = Math.cos(carAngle); sa = Math.sin(carAngle);
        vx = ca * vForward - sa * vLat;
        vy = sa * vForward + ca * vLat;
        carX += vx * dt;
        carY += vy * dt;

        // nitro charge from drifting
        if (drifting) {
            nitro = Math.min(1, nitro + dt * 0.45);
            emitSmoke(carX, carY);
            addSkid();
        }

        // boost pads
        for (int[] p : pads) {
            int pi = p[0];
            double dx = carX - cxArr[pi], dy = carY - cyArr[pi];
            if (dx * dx + dy * dy < 60 * 60) {
                boostTime = Math.max(boostTime, 0.8);
                nitro = Math.min(1, nitro + 0.2);
            }
        }

        // item boxes
        for (ItemBox b : boxes) {
            if (!b.active) continue;
            double dx = carX - b.x, dy = carY - b.y;
            if (dx * dx + dy * dy < 45 * 45 && heldItem == IT_NONE) {
                b.active = false; b.respawn = 4;
                heldItem = 1 + rnd.nextInt(3);
                if (audio != null) audio.blip(EngineAudio.BLIP_PICKUP);
            }
        }

        // oil slicks
        if (shield <= 0 && spin <= 0) {
            for (Oil o : oils) {
                if (o.owner == 0 && o.grace > 0) continue;
                double dx = carX - o.x, dy = carY - o.y;
                if (dx * dx + dy * dy < 38 * 38) { spin = 1.1; if (audio != null) audio.blip(EngineAudio.BLIP_HIT); break; }
            }
        }
        if (shield > 0) shield -= dt;

        // collide with AI (bumping)
        for (Ai a : ais) {
            double dx = carX - a.x, dy = carY - a.y;
            double d2 = dx * dx + dy * dy;
            if (d2 < 52 * 52 && d2 > 0.01) {
                double d = Math.sqrt(d2);
                double push = (52 - d) / 2;
                double nxp = dx / d, nyp = dy / d;
                double f = shield > 0 ? 2.4 : 1.0;
                carX += nxp * push; carY += nyp * push;
                a.x -= nxp * push * f; a.y -= nyp * push * f;
                a.bump = 0.4;
                shake = Math.min(shake + 2, 5);
            }
        }

        // lap / finish detection
        double f = (double) idx / N, pf = (double) prevIdx / N;
        if (pf > 0.75 && f < 0.25) {
            lapsDone++;
            if (lapsDone >= 1 && lapTime > 0.5) {
                if (bestLap == 0 || lapTime < bestLap) {
                    bestLap = lapTime;
                    if (prefs != null) prefs.edit().putFloat("bestLap", (float) bestLap).apply();
                }
            }
            lapTime = 0;
            if (lapsDone >= TOTAL_LAPS) { finishTime = raceTime; state = FINISHED; }
        }
        prevIdx = idx;
    }

    private void useItem() {
        switch (heldItem) {
            case IT_TURBO:
                boostTime = Math.max(boostTime, 1.6); nitro = Math.min(1, nitro + 0.5);
                if (audio != null) audio.blip(EngineAudio.BLIP_BOOST);
                break;
            case IT_OIL:
                Oil o = new Oil();
                o.x = carX - Math.cos(carAngle) * 50;
                o.y = carY - Math.sin(carAngle) * 50;
                o.life = 12; o.owner = 0; o.grace = 0.6;
                oils.add(o);
                break;
            case IT_SHIELD:
                shield = 6;
                if (audio != null) audio.blip(EngineAudio.BLIP_PICKUP);
                break;
        }
        heldItem = IT_NONE;
    }

    private void updateAis(double dt) {
        // player progress for rubber-banding
        double playerProg = lapsDone * (double) N + prevIdx;
        for (Ai a : ais) {
            double prog = a.lap * (double) N + a.t;
            double diff = playerProg - prog;        // >0 means AI behind player
            double rubber = 1 + Math.max(-0.18, Math.min(0.22, diff / (N * 0.5)));
            double spd = a.baseSpeed * rubber;
            if (a.bump > 0) { a.bump -= dt; spd *= 0.5; }
            a.t += spd * dt;
            if (a.t >= N) { a.t -= N; a.lap++; }
            int ai = ((int) a.t) % N;
            double targetX = cxArr[ai] + dirY[ai] * a.offset;
            double targetY = cyArr[ai] - dirX[ai] * a.offset;
            a.x += (targetX - a.x) * Math.min(1, dt * 5);
            a.y += (targetY - a.y) * Math.min(1, dt * 5);
            a.angle = Math.atan2(dirY[ai], dirX[ai]);
            // AI hits oil too
            for (Oil o : oils) {
                double dx = a.x - o.x, dy = a.y - o.y;
                if (dx * dx + dy * dy < 36 * 36) { a.bump = 0.8; }
            }
        }
    }

    private void updateOils(double dt) {
        for (int i = oils.size() - 1; i >= 0; i--) {
            Oil o = oils.get(i);
            o.life -= dt; o.grace -= dt;
            if (o.life <= 0) oils.remove(i);
        }
    }

    private void emitSmoke(double x, double y) {
        if (particles.size() > 160) return;
        Particle p = new Particle();
        p.x = x - Math.cos(carAngle) * 28 + (rnd.nextDouble() - 0.5) * 14;
        p.y = y - Math.sin(carAngle) * 28 + (rnd.nextDouble() - 0.5) * 14;
        p.vx = (rnd.nextDouble() - 0.5) * 30;
        p.vy = (rnd.nextDouble() - 0.5) * 30;
        p.life = p.maxLife = 0.6 + rnd.nextDouble() * 0.4;
        p.size = 10 + rnd.nextFloat() * 10;
        particles.add(p);
    }

    private void addSkid() {
        if (skidMarks.size() > 400) skidMarks.remove(0);
        skidMarks.add(new float[]{(float) carX, (float) carY, (float) carAngle, 1f});
    }

    private void updateParticles(double dt) {
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life -= dt;
            p.x += p.vx * dt; p.y += p.vy * dt;
            p.size += dt * 18;
            if (p.life <= 0) particles.remove(i);
        }
    }

    // --------------------------------------------------------------- render

    void render(Canvas g) {
        float darkness = nightAmount();
        int grass = lerp(0xFF2E7D52, 0xFF0A2238, darkness * 0.85f);
        if (weather == 1) grass = lerp(grass, 0xFF20465A, 0.4f);
        g.drawColor(grass);

        g.save();
        float sx = (float) (shake * (rnd.nextDouble() - 0.5));
        float sy = (float) (shake * (rnd.nextDouble() - 0.5));
        g.translate(width / 2f + sx, height / 2f + sy);
        g.scale((float) zoom, (float) zoom);
        g.translate((float) -camX, (float) -camY);

        drawSkidMarks(g);
        drawTrees(g);
        drawRoad(g);
        drawPads(g);
        drawFinishLine(g);
        drawOils(g);
        drawBoxes(g);
        for (Ai a : ais) drawCarSprite(g, a.x, a.y, a.angle, a.color, false, a.bump > 0);
        drawCarSprite(g, carX, carY, carAngle, CAR_COLORS[playerColorIdx], true, false);
        drawParticles(g);
        g.restore();

        if (darkness > 0.02f) drawNightLighting(g, darkness);
        if (weather == 1) drawRain(g);
        if (boostTime > 0) drawSpeedLines(g);

        drawRadar(g);
        renderHud(g);
        renderControls(g);

        if (state == TITLE) renderTitle(g);
        else if (state == COUNTDOWN) renderCountdown(g);
        else if (state == FINISHED) renderResults(g);
    }

    private float nightAmount() {
        // darkness peaks around dayTime = 0.75 (midnight), bright at 0.25 (noon)
        double d = Math.cos((dayTime - 0.25) * 2 * Math.PI);   // 1 at noon, -1 at midnight
        return (float) Math.max(0, Math.min(1, (-d + 0.35) / 1.1));
    }

    private void drawTrees(Canvas g) {
        for (float[] t : trees) {
            ui.setColor(0x44000000);
            g.drawCircle(t[0] + t[2] * 0.25f, t[1] + t[2] * 0.3f, t[2], ui);
            ui.setColor(0xFF1C5E36);
            g.drawCircle(t[0], t[1], t[2], ui);
            ui.setColor(0xFF257A47);
            g.drawCircle(t[0] - t[2] * 0.25f, t[1] - t[2] * 0.25f, t[2] * 0.7f, ui);
        }
    }

    private void drawRoad(Canvas g) {
        road.setStyle(Paint.Style.STROKE);
        road.setStrokeJoin(Paint.Join.ROUND);
        road.setStrokeCap(Paint.Cap.ROUND);
        road.setPathEffect(null);
        road.setColor(0xFF12121C);
        road.setStrokeWidth(ROAD_HALF * 2 + 22);
        g.drawPath(roadPath, road);
        int asphalt = weather == 1 ? 0xFF34384E : 0xFF3C3F55;
        road.setColor(asphalt);
        road.setStrokeWidth(ROAD_HALF * 2);
        g.drawPath(roadPath, road);
        road.setColor(0x18FFFFFF);
        road.setStrokeWidth(ROAD_HALF * 2 - 14);
        g.drawPath(roadPath, road);
        road.setColor(asphalt);
        road.setStrokeWidth(ROAD_HALF * 2 - 18);
        g.drawPath(roadPath, road);
        road.setColor(0xFFFFE34D);
        road.setStrokeWidth(6);
        road.setPathEffect(new DashPathEffect(new float[]{34, 30}, (float) dash));
        g.drawPath(roadPath, road);
        road.setPathEffect(null);
    }

    private void drawSkidMarks(Canvas g) {
        ui.setStyle(Paint.Style.FILL);
        for (float[] s : skidMarks) {
            ui.setColor(0x33000000);
            float a = s[2];
            float ox = (float) Math.cos(a) * 16, oy = (float) Math.sin(a) * 16;
            float px = (float) -Math.sin(a) * 9, py = (float) Math.cos(a) * 9;
            g.drawCircle(s[0] + px - ox, s[1] + py - oy, 4, ui);
            g.drawCircle(s[0] - px - ox, s[1] - py - oy, 4, ui);
        }
    }

    private void drawPads(Canvas g) {
        for (int[] p : pads) {
            int i = p[0];
            g.save();
            g.translate(cxArr[i], cyArr[i]);
            g.rotate((float) Math.toDegrees(Math.atan2(dirY[i], dirX[i])));
            float w = ROAD_HALF * 1.4f;
            for (int c = 0; c < 3; c++) {
                ui.setColor(0xCCFF8A1E);
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
    }

    private void drawFinishLine(Canvas g) {
        int i = 0;
        g.save();
        g.translate(cxArr[i], cyArr[i]);
        g.rotate((float) Math.toDegrees(Math.atan2(dirY[i], dirX[i])));
        int cols = 8;
        float cell = (ROAD_HALF * 2) / cols, depth = cell;
        for (int c = 0; c < cols; c++)
            for (int rr = 0; rr < 2; rr++) {
                fill.setColor(((c + rr) % 2 == 0) ? 0xFFFFFFFF : 0xFF111111);
                g.drawRect(-depth + rr * depth, -ROAD_HALF + c * cell, -depth + rr * depth + depth, -ROAD_HALF + c * cell + cell, fill);
            }
        g.restore();
    }

    private void drawOils(Canvas g) {
        for (Oil o : oils) {
            ui.setColor(0xAA15151E);
            g.drawCircle((float) o.x, (float) o.y, 30, ui);
            ui.setColor(0x55503070);
            g.drawCircle((float) o.x - 6, (float) o.y - 6, 16, ui);
        }
    }

    private void drawBoxes(Canvas g) {
        for (ItemBox b : boxes) {
            if (!b.active) continue;
            float s = 18 + (float) Math.sin(titlePulse * 3 + b.idx) * 3;
            g.save();
            g.translate(b.x, b.y);
            g.rotate((float) (titlePulse * 80 % 360));
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

    private void drawCarSprite(Canvas g, double x, double y, double angle, int color, boolean player, boolean bumped) {
        float L = 72, W = 36;
        g.save();
        g.translate((float) x, (float) y);
        g.rotate((float) Math.toDegrees(angle));

        ui.setColor(0x55000000);
        tmp.set(-L / 2 + 4, -W / 2 + 6, L / 2 + 6, W / 2 + 8);
        g.drawRoundRect(tmp, 14, 14, ui);

        if (player && (boostTime > 0)) {
            ui.setShader(new RadialGradient(0, 0, L * 1.3f, new int[]{0x66FF8A1E, 0}, null, Shader.TileMode.CLAMP));
            g.drawCircle(0, 0, L * 1.3f, ui);
            ui.setShader(null);
        }
        if (player && shield > 0) {
            ui.setColor(0x553BE0FF);
            g.drawCircle(0, 0, L * 0.85f, ui);
        }

        ui.setShader(new LinearGradient(0, -W / 2, 0, W / 2,
                new int[]{lighten(color, 1.35f), color, darken(color, 0.7f)}, null, Shader.TileMode.CLAMP));
        tmp.set(-L / 2, -W / 2, L / 2, W / 2);
        g.drawRoundRect(tmp, 16, 16, ui);
        ui.setShader(null);

        ui.setColor(0xCC0E1420);
        tmp.set(-L * 0.05f, -W * 0.34f, L * 0.34f, W * 0.34f);
        g.drawRoundRect(tmp, 8, 8, ui);
        ui.setColor(0x886FD2FF);
        tmp.set(L * 0.02f, -W * 0.28f, L * 0.30f, W * 0.28f);
        g.drawRoundRect(tmp, 6, 6, ui);

        ui.setColor(0xFFFFF3B0);
        g.drawCircle(L * 0.44f, -W * 0.28f, 4.5f, ui);
        g.drawCircle(L * 0.44f, W * 0.28f, 4.5f, ui);
        ui.setColor(0xFFFF3355);
        g.drawRect(-L * 0.5f, -W * 0.34f, -L * 0.42f, -W * 0.06f, ui);
        g.drawRect(-L * 0.5f, W * 0.06f, -L * 0.42f, W * 0.34f, ui);

        ui.setColor(0xFF0C0C14);
        drawWheel(g, -L * 0.28f, -W * 0.52f);
        drawWheel(g, -L * 0.28f, W * 0.52f - 12);
        drawWheel(g, L * 0.30f, -W * 0.52f);
        drawWheel(g, L * 0.30f, W * 0.52f - 12);

        if (player) {
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(2.5f);
            ui.setColor(0xAAFFFFFF);
            tmp.set(-L / 2, -W / 2, L / 2, W / 2);
            g.drawRoundRect(tmp, 16, 16, ui);
            ui.setStyle(Paint.Style.FILL);
        }
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
        // headlight cones via additive glow at car screen positions
        ui.setXfermode(ADD);
        drawHeadlight(g, carX, carY, carAngle, darkness);
        for (Ai a : ais) drawHeadlight(g, a.x, a.y, a.angle, darkness);
        ui.setXfermode(null);
    }

    private void drawHeadlight(Canvas g, double wx, double wy, double angle, float darkness) {
        float sxp = (float) ((wx - camX) * zoom + width / 2);
        float syp = (float) ((wy - camY) * zoom + height / 2);
        float ahead = (float) (40 * zoom);
        float cx = sxp + (float) Math.cos(angle) * ahead;
        float cy = syp + (float) Math.sin(angle) * ahead;
        float r = (float) (150 * zoom);
        int glow = (int) (90 * darkness);
        ui.setShader(new RadialGradient(cx, cy, r,
                new int[]{(glow << 24) | 0xFFF0C0, 0}, null, Shader.TileMode.CLAMP));
        g.drawCircle(cx, cy, r, ui);
        ui.setShader(null);
    }

    private void drawRain(Canvas g) {
        ui.setColor(0x223A6A8A);
        g.drawRect(0, 0, width, height, ui);
        ui.setStrokeWidth(2);
        ui.setColor(0x88BFE0FF);
        double t = titlePulse;
        for (float[] r : rain) {
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
            float r1 = Math.min(width, height) * 0.42f;
            float r2 = r1 + 60;
            g.drawLine(cx + (float) Math.cos(ang) * r1, cy + (float) Math.sin(ang) * r1,
                    cx + (float) Math.cos(ang) * r2, cy + (float) Math.sin(ang) * r2, ui);
        }
    }

    // ------------------------------------------------------------- radar/HUD

    private void drawRadar(Canvas g) {
        float size = Math.min(width, height) * 0.22f;
        float pad = Math.min(width, height) * 0.03f;
        float ox = width - size - pad, oy = height - size - pad - Math.min(width, height) * 0.0f;
        // keep radar clear of right control cluster: place top-center-right
        ox = width / 2f + Math.min(width, height) * 0.18f;
        oy = pad;
        ui.setColor(0x66101826);
        tmp.set(ox, oy, ox + size, oy + size);
        g.drawRoundRect(tmp, 10, 10, ui);

        float tw = maxX - minX, th = maxY - minY;
        float scale = (size - 16) / Math.max(tw, th);
        float offx = ox + 8 + (size - 16 - tw * scale) / 2;
        float offy = oy + 8 + (size - 16 - th * scale) / 2;

        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(Math.max(2, ROAD_HALF * scale));
        ui.setColor(0x88FFFFFF);
        tmpPath.reset();
        for (int i = 0; i <= N; i += 2) {
            int k = i % N;
            float mx = offx + (cxArr[k] - minX) * scale;
            float my = offy + (cyArr[k] - minY) * scale;
            if (i == 0) tmpPath.moveTo(mx, my); else tmpPath.lineTo(mx, my);
        }
        g.drawPath(tmpPath, ui);
        ui.setStyle(Paint.Style.FILL);

        for (Ai a : ais) {
            ui.setColor(a.color);
            g.drawCircle(offx + ((float) a.x - minX) * scale, offy + ((float) a.y - minY) * scale, 4, ui);
        }
        ui.setColor(CAR_COLORS[playerColorIdx]);
        g.drawCircle(offx + ((float) carX - minX) * scale, offy + ((float) carY - minY) * scale, 5, ui);
    }

    private void renderHud(Canvas g) {
        float unit = Math.min(width, height), pad = unit * 0.035f;
        double sp = Math.hypot(vx, vy);
        int kmh = (int) Math.abs(sp / MAX_SPEED * 240);

        text.setShadowLayer(8, 0, 2, 0xCC000000);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(boostTime > 0 ? 0xFFFF8A1E : 0xFFFFFFFF);
        text.setTextSize(unit * 0.08f);
        g.drawText(String.format(Locale.US, "%03d", kmh), pad, pad + text.getTextSize(), text);
        text.setTextSize(unit * 0.028f);
        text.setColor(0xFFFFFFFF);
        g.drawText("KM/H", pad, pad + unit * 0.11f, text);

        // nitro meter
        float barW = width * 0.2f, barH = unit * 0.022f, barY = pad + unit * 0.13f;
        ui.setColor(0x55FFFFFF);
        tmp.set(pad, barY, pad + barW, barY + barH);
        g.drawRoundRect(tmp, barH / 2, barH / 2, ui);
        ui.setColor(nitro >= 0.25 ? 0xFF42E2B8 : 0xFFFFC23D);
        tmp.set(pad, barY, pad + barW * (float) nitro, barY + barH);
        g.drawRoundRect(tmp, barH / 2, barH / 2, ui);
        text.setColor(0xFF9FFFE0);
        text.setTextSize(unit * 0.024f);
        g.drawText("NITRO", pad, barY + barH + unit * 0.03f, text);

        // lap + position
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(0xFFFFE34D);
        text.setTextSize(unit * 0.05f);
        int showLap = Math.min(lapsDone + 1, TOTAL_LAPS);
        g.drawText("LAP " + showLap + "/" + TOTAL_LAPS, width - pad, pad + text.getTextSize(), text);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(unit * 0.04f);
        g.drawText("P" + position() + "/" + (ais.size() + 1), width - pad, pad + unit * 0.115f, text);

        // timers + weather
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(0xFFCFC8FF);
        text.setTextSize(unit * 0.032f);
        g.drawText("LAP " + fmt(lapTime), pad, pad + unit * 0.23f, text);
        if (bestLap > 0) {
            text.setColor(0xFF8AF0FF);
            g.drawText("BEST " + fmt(bestLap), pad, pad + unit * 0.275f, text);
        }
        // weather / time icon
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(unit * 0.03f);
        String cond = (weather == 1 ? "RAIN " : "") + timeLabel();
        g.drawText(cond, width / 2f, pad + unit * 0.04f, text);
        text.clearShadowLayer();

        if (!onTrack && state == RACING) {
            text.setColor(0xFFFF4D6D);
            text.setTextSize(unit * 0.045f);
            g.drawText("OFF TRACK!", width / 2f, height * 0.18f, text);
        }
    }

    private String timeLabel() {
        double h = (dayTime * 24 + 6) % 24;
        if (h < 6 || h > 20) return "NIGHT";
        if (h < 9) return "DAWN";
        if (h > 17) return "DUSK";
        return "DAY";
    }

    private int position() {
        double mine = lapsDone * (double) N + prevIdx;
        int rank = 1;
        for (Ai a : ais) if (a.lap * (double) N + a.t > mine) rank++;
        return rank;
    }

    private static String fmt(double s) {
        int m = (int) (s / 60);
        return String.format(Locale.US, "%d:%05.2f", m, s - m * 60);
    }

    // ------------------------------------------------------------- controls

    private void renderControls(Canvas g) {
        if (state == TITLE) return;
        drawButton(g, btnLeft, "◀", keyLeft, 0xFF19E0FF);
        drawButton(g, btnRight, "▶", keyRight, 0xFF19E0FF);
        drawButton(g, btnBrake, "DRIFT", keyBrake, 0xFFFF4D6D);
        drawButton(g, btnGas, "▲", keyGas, 0xFF42E2B8);
        drawButton(g, btnBoost, "NITRO", nitro >= 0.25, 0xFFFF8A1E);
        drawButton(g, btnItem, itemLabel(heldItem), heldItem != IT_NONE, 0xFF2BD4C8);
    }

    private String itemLabel(int it) {
        switch (it) {
            case IT_TURBO: return "TURBO";
            case IT_OIL: return "OIL";
            case IT_SHIELD: return "SHIELD";
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

    // ------------------------------------------------------------- overlays

    private void renderTitle(Canvas g) {
        g.drawColor(0xBB050314);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(20, 0, 0, 0xFFFF2E88);
        text.setColor(0xFF19E0FF);
        text.setTextSize(Math.min(width, height) * 0.12f);
        g.drawText("TURBO CIRCUIT", width / 2f, height * 0.3f, text);
        text.clearShadowLayer();

        text.setColor(0xFFB9B2E6);
        text.setTextSize(Math.min(width, height) * 0.032f);
        g.drawText("pick your car   •   tap anywhere to race", width / 2f, height * 0.62f, text);

        // colour swatches
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

        float pulse = (float) (0.5 + 0.5 * Math.sin(titlePulse * 3));
        text.setColor(0xFFFFFFFF);
        text.setAlpha((int) (120 + 135 * pulse));
        text.setTextSize(Math.min(width, height) * 0.05f);
        g.drawText("TAP TO START", width / 2f, height * 0.5f, text);
        text.setAlpha(255);

        text.setColor(0xFF7F88B0);
        text.setTextSize(Math.min(width, height) * 0.026f);
        g.drawText("drift to fill nitro  •  grab ? boxes  •  hit boost pads  •  3 laps", width / 2f, height * 0.88f, text);
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
    }

    private void renderResults(Canvas g) {
        g.drawColor(0xCC050314);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(16, 0, 0, 0xFFFF2E88);
        text.setColor(0xFF19E0FF);
        text.setTextSize(Math.min(width, height) * 0.1f);
        g.drawText("FINISH", width / 2f, height * 0.2f, text);
        text.clearShadowLayer();

        // standings
        List<Standing> st = new ArrayList<>();
        st.add(new Standing("YOU", lapsDone * (double) N + prevIdx, CAR_COLORS[playerColorIdx], finishTime));
        int n = 1;
        for (Ai a : ais) st.add(new Standing("CPU " + (n++), a.lap * (double) N + a.t, a.color, 0));
        Collections.sort(st, new Comparator<Standing>() {
            public int compare(Standing a, Standing b) { return Double.compare(b.prog, a.prog); }
        });
        float unit = Math.min(width, height);
        float y = height * 0.36f;
        for (int i = 0; i < st.size(); i++) {
            Standing s = st.get(i);
            text.setTextAlign(Paint.Align.LEFT);
            ui.setColor(s.color);
            float cx = width / 2f - unit * 0.32f;
            g.drawCircle(cx, y - unit * 0.015f, unit * 0.022f, ui);
            text.setColor(i == 0 ? 0xFFFFE34D : 0xFFFFFFFF);
            text.setTextSize(unit * 0.05f);
            g.drawText((i + 1) + ".  " + s.name, cx + unit * 0.05f, y, text);
            if (s.name.equals("YOU") && finishTime > 0) {
                text.setTextAlign(Paint.Align.RIGHT);
                g.drawText(fmt(finishTime), width / 2f + unit * 0.34f, y, text);
            }
            y += unit * 0.075f;
        }

        float pulse = (float) (0.5 + 0.5 * Math.sin(titlePulse * 3));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFFFFFFF);
        text.setAlpha((int) (120 + 135 * pulse));
        text.setTextSize(unit * 0.045f);
        g.drawText("TAP TO RACE AGAIN", width / 2f, height * 0.9f, text);
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
                for (int i = 0; i < swatches.length; i++) {
                    if (swatches[i].contains(x, y)) { playerColorIdx = i; return; }
                }
                startCountdown();
            }
            return;
        }
        if (state == FINISHED) {
            if (action == MotionEvent.ACTION_DOWN) { state = TITLE; resetRace(); }
            return;
        }

        // RACING / COUNTDOWN: evaluate buttons across all active pointers
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

    private void startCountdown() {
        resetRace();
        state = COUNTDOWN;
        countdown = 3.99;
    }

    // ----------------------------------------------------------------- types

    private static final class Ai {
        double t, baseSpeed, offset, x, y, angle, bump;
        int lap, color;
    }

    private static final class ItemBox {
        int idx; float offset, x, y; boolean active = true; double respawn;
    }

    private static final class Oil {
        double x, y, life, grace; int owner;
    }

    private static final class Particle {
        double x, y, vx, vy, life, maxLife; float size;
    }

    private static final class Standing {
        String name; double prog; int color; double time;
        Standing(String n, double p, int c, double t) { name = n; prog = p; color = c; time = t; }
    }
}
