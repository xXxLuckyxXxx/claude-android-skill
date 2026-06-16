package com.fable.racer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pure-Java game simulation: track geometry, car physics, drift/nitro/boost,
 * items, jumps, shortcuts, AI and lap/finish logic. Contains NO Android types
 * so it can be unit-tested / playtested head-less on a normal JVM. The renderer
 * ({@link Game}) reads this state and draws it.
 */
final class Sim {

    static final float ROAD_HALF = 120f;
    static final int STEPS_PER_SEG = 26;

    // physics
    static final double ACCEL = 480;
    static final double BRAKE = 760;
    static final double DRAG = 90;
    static final double MAX_SPEED = 600;
    static final double BOOST_SPEED = 940;
    static final double GRIP_NORMAL = 6.5;
    static final double GRIP_DRIFT = 1.4;
    static final double TURN_RATE = 3.0;
    static final double TURN_REF = 150;
    static final double GRAVITY = 820;

    // items
    static final int IT_NONE = 0, IT_TURBO = 1, IT_OIL = 2, IT_SHIELD = 3;

    // ---- track ----
    float[] cx, cy, dirX, dirY;
    int N;
    float minX, minY, maxX, maxY;
    int totalLaps = 3;
    int weather;                 // 0 clear, 1 rain
    final List<float[]> shortcuts = new ArrayList<>();   // each: x0,y0,x1,y1,...
    final List<double[]> jumps = new ArrayList<>();      // each: {x,y}
    final List<int[]> pads = new ArrayList<>();          // {centerline index}
    final List<ItemBox> boxes = new ArrayList<>();
    final List<Oil> oils = new ArrayList<>();
    final List<Ai> ais = new ArrayList<>();

    // ---- player ----
    double carX, carY, carAngle, vx, vy;
    double nitro, shield, spin, boostTime;
    double jumpZ, jumpVel;
    boolean airborne, onTrack, drifting;
    int heldItem = IT_NONE;
    int lapsDone, prevIdx, startIdx = 6;
    double raceTime, lapTime, finishTime, lastLapTime;
    boolean finished;

    // ---- events (renderer consumes + clears) ----
    boolean evBoost, evPickup, evHit, evJump, evLand;

    private final Random rnd = new Random();

    void load(Tracks.Def def, long seed) {
        rnd.setSeed(seed);
        totalLaps = def.laps;
        buildTrack(def.wp);
        // weather
        weather = def.weatherBias == 2 ? (rnd.nextInt(3) == 0 ? 1 : 0) : def.weatherBias;

        shortcuts.clear();
        if (def.shortcuts != null) for (double[] s : def.shortcuts) shortcuts.add(buildShortcut(s[0], s[1], s[2]));

        jumps.clear();
        if (def.jumps != null) for (double f : def.jumps) {
            int i = ((int) Math.round(f * N)) % N;
            jumps.add(new double[]{cx[i], cy[i], Math.atan2(dirY[i], dirX[i])});
        }

        pads.clear();
        if (def.pads != null) for (double f : def.pads) pads.add(new int[]{((int) Math.round(f * N)) % N});

        boxes.clear();
        if (def.boxes != null) for (double f : def.boxes) {
            int bi = ((int) Math.round(f * N)) % N;
            for (int lane = -1; lane <= 1; lane++) {
                ItemBox b = new ItemBox();
                b.idx = bi;
                b.x = cx[bi] + dirY[bi] * lane * 55f;
                b.y = cy[bi] - dirX[bi] * lane * 55f;
                boxes.add(b);
            }
        }

        resetRace(def);
    }

    void resetRace(Tracks.Def def) {
        carX = cx[startIdx];
        carY = cy[startIdx];
        carAngle = Math.atan2(dirY[startIdx], dirX[startIdx]);
        vx = vy = 0;
        lapsDone = 0; prevIdx = startIdx;
        raceTime = lapTime = finishTime = 0;
        nitro = 0; heldItem = IT_NONE;
        spin = shield = boostTime = jumpZ = jumpVel = 0;
        airborne = false; finished = false;
        oils.clear();
        for (ItemBox b : boxes) b.active = true;
        clearEvents();

        ais.clear();
        int[] cols = {0xFFFF4D9D, 0xFFFFC23D, 0xFF8CFF45};
        for (int i = 0; i < 3; i++) {
            Ai a = new Ai();
            int idx0 = (startIdx - (i + 1) * 5 + N) % N;
            a.offset = (i - 1) * 46f;
            a.t = idx0;
            a.baseSpeed = def.aiBase + i * 1.2;
            a.color = cols[i];
            a.x = cx[idx0] + dirY[idx0] * a.offset;
            a.y = cy[idx0] - dirX[idx0] * a.offset;
            a.angle = Math.atan2(dirY[idx0], dirX[idx0]);
            ais.add(a);
        }
    }

    void clearEvents() { evBoost = evPickup = evHit = evJump = evLand = false; }

    // ----------------------------------------------------------- build track

    private void buildTrack(float[][] wp) {
        int m = wp.length;
        N = m * STEPS_PER_SEG;
        cx = new float[N]; cy = new float[N];
        dirX = new float[N]; dirY = new float[N];
        int k = 0;
        for (int i = 0; i < m; i++) {
            float[] p0 = wp[(i - 1 + m) % m], p1 = wp[i], p2 = wp[(i + 1) % m], p3 = wp[(i + 2) % m];
            for (int s = 0; s < STEPS_PER_SEG; s++) {
                float t = (float) s / STEPS_PER_SEG;
                cx[k] = catmull(p0[0], p1[0], p2[0], p3[0], t);
                cy[k] = catmull(p0[1], p1[1], p2[1], p3[1], t);
                k++;
            }
        }
        minX = minY = Float.MAX_VALUE; maxX = maxY = -Float.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            int nx = (i + 1) % N, pv = (i - 1 + N) % N;
            float dx = cx[nx] - cx[pv], dy = cy[nx] - cy[pv];
            float len = (float) Math.hypot(dx, dy);
            if (len < 1e-4f) len = 1;
            dirX[i] = dx / len; dirY[i] = dy / len;
            minX = Math.min(minX, cx[i]); maxX = Math.max(maxX, cx[i]);
            minY = Math.min(minY, cy[i]); maxY = Math.max(maxY, cy[i]);
        }
    }

    private float[] buildShortcut(double fa, double fb, double bulge) {
        int a = ((int) Math.round(fa * N)) % N, b = ((int) Math.round(fb * N)) % N;
        float ax = cx[a], ay = cy[a], bx = cx[b], by = cy[b];
        float mx = (ax + bx) / 2, my = (ay + by) / 2;
        float dx = bx - ax, dy = by - ay;
        float len = (float) Math.hypot(dx, dy); if (len < 1e-4f) len = 1;
        float nx = -dy / len, ny = dx / len;             // normal
        float cxp = (float) (mx + nx * bulge), cyp = (float) (my + ny * bulge);
        int samples = 26;
        float[] pts = new float[samples * 2];
        for (int i = 0; i < samples; i++) {
            float t = (float) i / (samples - 1);
            float u = 1 - t;
            pts[i * 2] = u * u * ax + 2 * u * t * cxp + t * t * bx;
            pts[i * 2 + 1] = u * u * ay + 2 * u * t * cyp + t * t * by;
        }
        return pts;
    }

    private static float catmull(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return 0.5f * ((2 * p1) + (-p0 + p2) * t
                + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    float distToTrack(double x, double y) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            double dx = x - cx[i], dy = y - cy[i];
            double d = dx * dx + dy * dy;
            if (d < best) best = d;
        }
        for (float[] sc : shortcuts) {
            for (int i = 0; i < sc.length; i += 2) {
                double dx = x - sc[i], dy = y - sc[i + 1];
                double d = dx * dx + dy * dy;
                if (d < best) best = d;
            }
        }
        return (float) Math.sqrt(best);
    }

    int nearestIndex(double x, double y) {
        double best = Double.MAX_VALUE; int bi = 0;
        for (int i = 0; i < N; i++) {
            double dx = x - cx[i], dy = y - cy[i];
            double d = dx * dx + dy * dy;
            if (d < best) { best = d; bi = i; }
        }
        return bi;
    }

    // --------------------------------------------------------------- update

    void update(double dt, boolean left, boolean right, boolean gas, boolean brake,
                boolean boostEdge, boolean itemEdge) {
        if (finished) return;
        raceTime += dt; lapTime += dt;

        int idx = nearestIndex(carX, carY);
        onTrack = distToTrack(carX, carY) <= ROAD_HALF;

        double ca = Math.cos(carAngle), sa = Math.sin(carAngle);
        double vForward = vx * ca + vy * sa;
        double vLat = -vx * sa + vy * ca;

        boolean spinning = spin > 0;
        if (spinning) {
            spin -= dt; carAngle += dt * 14; left = right = false;
            vForward *= (1 - 2.5 * dt);
        } else if (!airborne) {
            if (gas) vForward += ACCEL * dt;
            else if (brake) vForward -= BRAKE * dt;
            else vForward -= Math.signum(vForward) * Math.min(Math.abs(vForward), DRAG * dt);
        } else {
            if (gas) vForward += ACCEL * 0.4 * dt;   // limited air control
        }

        // boost
        if (boostEdge && boostTime <= 0 && nitro > 0.25) {
            boostTime = 1.4; nitro = Math.max(0, nitro - 0.6); evBoost = true;
        }
        double topSpeed = MAX_SPEED;
        if (boostTime > 0) { boostTime -= dt; topSpeed = BOOST_SPEED; vForward += 600 * dt; }

        if (itemEdge && heldItem != IT_NONE && !spinning) useItem();

        double steer = left ? -1 : (right ? 1 : 0);
        drifting = brake && Math.abs(vForward) > 120 && steer != 0 && !spinning && !airborne;

        if (!airborne) {
            double grip = drifting ? GRIP_DRIFT : GRIP_NORMAL;
            if (weather == 1) grip *= 0.55;
            if (!onTrack) grip *= 0.7;
            vLat *= Math.exp(-grip * dt);
        }

        double speedAbs = Math.abs(vForward);
        double steerScale = airborne ? 0.35 : 1.0;
        double turn = steer * TURN_RATE * dt * Math.min(1, speedAbs / TURN_REF) * (vForward >= 0 ? 1 : -1) * steerScale;
        if (drifting) turn *= 1.7;
        carAngle += turn;

        if (!airborne && !onTrack && speedAbs > MAX_SPEED * 0.35) {
            vForward -= 380 * dt * Math.signum(vForward);
        }
        vForward = Math.max(-0.35 * MAX_SPEED, Math.min(vForward, topSpeed));

        // jump pads
        if (!airborne) {
            for (double[] j : jumps) {
                double dx = carX - j[0], dy = carY - j[1];
                if (dx * dx + dy * dy < 58 * 58 && vForward > 200) {
                    airborne = true; jumpZ = 0; jumpVel = 230; evJump = true; break;
                }
            }
        } else {
            jumpZ += jumpVel * dt; jumpVel -= GRAVITY * dt;
            if (jumpZ <= 0) {
                jumpZ = 0; airborne = false; evLand = true;
                boostTime = Math.max(boostTime, 0.5);
            }
        }

        ca = Math.cos(carAngle); sa = Math.sin(carAngle);
        vx = ca * vForward - sa * vLat;
        vy = sa * vForward + ca * vLat;
        carX += vx * dt; carY += vy * dt;

        if (drifting) nitro = Math.min(1, nitro + dt * 0.45);

        if (!airborne) {
            for (int[] p : pads) {
                int pi = p[0];
                double dx = carX - cx[pi], dy = carY - cy[pi];
                if (dx * dx + dy * dy < 60 * 60) { boostTime = Math.max(boostTime, 0.8); nitro = Math.min(1, nitro + 0.2); }
            }
            for (ItemBox b : boxes) {
                if (!b.active) continue;
                double dx = carX - b.x, dy = carY - b.y;
                if (dx * dx + dy * dy < 45 * 45 && heldItem == IT_NONE) {
                    b.active = false; heldItem = 1 + rnd.nextInt(3); evPickup = true;
                }
            }
            if (shield <= 0 && spin <= 0) {
                for (Oil o : oils) {
                    if (o.grace > 0) continue;
                    double dx = carX - o.x, dy = carY - o.y;
                    if (dx * dx + dy * dy < 38 * 38) { spin = 1.1; evHit = true; break; }
                }
            }
        }
        if (shield > 0) shield -= dt;

        // collide with AI
        if (!airborne) for (Ai a : ais) {
            double dx = carX - a.x, dy = carY - a.y, d2 = dx * dx + dy * dy;
            if (d2 < 52 * 52 && d2 > 0.01) {
                double d = Math.sqrt(d2), push = (52 - d) / 2, nxp = dx / d, nyp = dy / d;
                double f = shield > 0 ? 2.4 : 1.0;
                carX += nxp * push; carY += nyp * push;
                a.x -= nxp * push * f; a.y -= nyp * push * f; a.bump = 0.4;
            }
        }

        updateAis(dt);
        for (int i = oils.size() - 1; i >= 0; i--) {
            Oil o = oils.get(i); o.life -= dt; o.grace -= dt;
            if (o.life <= 0) oils.remove(i);
        }

        // lap / finish
        double f = (double) idx / N, pf = (double) prevIdx / N;
        if (pf > 0.7 && f < 0.3) {
            lapsDone++;
            lastLapTime = lapTime;
            lapTime = 0;
            if (lapsDone >= totalLaps) { finishTime = raceTime; finished = true; }
        }
        prevIdx = idx;
    }

    private void useItem() {
        switch (heldItem) {
            case IT_TURBO: boostTime = Math.max(boostTime, 1.6); nitro = Math.min(1, nitro + 0.5); evBoost = true; break;
            case IT_OIL:
                Oil o = new Oil();
                o.x = carX - Math.cos(carAngle) * 50; o.y = carY - Math.sin(carAngle) * 50;
                o.life = 12; o.grace = 0.6; oils.add(o); break;
            case IT_SHIELD: shield = 6; evPickup = true; break;
        }
        heldItem = IT_NONE;
    }

    private void updateAis(double dt) {
        double playerProg = lapsDone * (double) N + prevIdx;
        for (Ai a : ais) {
            double prog = a.lap * (double) N + a.t;
            double diff = playerProg - prog;
            double rubber = 1 + Math.max(-0.18, Math.min(0.22, diff / (N * 0.5)));
            double spd = a.baseSpeed * rubber;
            if (a.bump > 0) { a.bump -= dt; spd *= 0.5; }
            a.t += spd * dt;
            if (a.t >= N) { a.t -= N; a.lap++; }
            int ai = ((int) a.t) % N;
            double tx = cx[ai] + dirY[ai] * a.offset, ty = cy[ai] - dirX[ai] * a.offset;
            a.x += (tx - a.x) * Math.min(1, dt * 5);
            a.y += (ty - a.y) * Math.min(1, dt * 5);
            a.angle = Math.atan2(dirY[ai], dirX[ai]);
            for (Oil o : oils) {
                double dx = a.x - o.x, dy = a.y - o.y;
                if (dx * dx + dy * dy < 36 * 36) a.bump = 0.8;
            }
        }
    }

    int position() {
        double mine = lapsDone * (double) N + prevIdx;
        int rank = 1;
        for (Ai a : ais) if (a.lap * (double) N + a.t > mine) rank++;
        return rank;
    }

    double speed() { return Math.hypot(vx, vy); }

    // ----------------------------------------------------------------- types

    static final class Ai {
        double t, baseSpeed, offset, x, y, angle, bump;
        int lap, color;
    }

    static final class ItemBox {
        int idx; float x, y; boolean active = true;
    }

    static final class Oil { double x, y, life, grace; }
}
