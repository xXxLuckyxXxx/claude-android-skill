package com.fable.racer;

import android.content.Context;
import android.content.SharedPreferences;
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

    private static final int TITLE = 0, COUNTDOWN = 1, RACING = 2, FINISHED = 3;
    private int state = TITLE;

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
    private final List<Particle> particles = new ArrayList<>();
    private final List<float[]> skidMarks = new ArrayList<>();

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
        if (prefs != null) {
            unlocked = Math.max(1, prefs.getInt("unlocked", 1));
            bestLap = prefs.getFloat("bestLap", 0f);
            playerColorIdx = prefs.getInt("color", 0);
        }
        text.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        try { audio = new EngineAudio(); } catch (Throwable t) { audio = null; }
        loadLevel(0);
        resize(w, h);
    }

    void release() { if (audio != null) audio.release(); }

    private void loadLevel(int lvl) {
        level = Math.max(0, Math.min(Tracks.LEVELS.length - 1, lvl));
        Tracks.Def def = Tracks.LEVELS[level];
        sim.load(def, System.nanoTime());
        rebuildRoadPath();
        dayTime = def.dayTime < 0 ? 0.25 : def.dayTime;
        for (int i = 0; i < rain.length; i++) {
            rain[i][0] = rnd.nextFloat(); rain[i][1] = rnd.nextFloat(); rain[i][2] = 0.5f + rnd.nextFloat();
        }
        particles.clear();
        skidMarks.clear();
        prevLaps = 0;
        camX = (sim.minX + sim.maxX) / 2;
        camY = (sim.minY + sim.maxY) / 2;
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
        float sx = width / 2f - sw * 3.3f, sy = height * 0.8f;
        for (int i = 0; i < swatches.length; i++)
            swatches[i] = new RectF(sx + i * sw * 1.1f, sy, sx + i * sw * 1.1f + sw, sy + sw);

        float bw = Math.min(width * 0.5f, 520), bh = height * 0.1f;
        float bx = width / 2f - bw / 2, byy = height * 0.34f;
        for (int i = 0; i < levelBtns.length; i++)
            levelBtns[i] = new RectF(bx, byy + i * bh * 1.2f, bx + bw, byy + i * bh * 1.2f + bh);
    }

    // --------------------------------------------------------------- update

    void update(double dt) {
        titlePulse += dt;
        dayTime += dt / 90.0; if (dayTime >= 1) dayTime -= 1;

        if (state == TITLE) { idleAudio(); return; }
        if (state == COUNTDOWN) {
            int prev = (int) Math.ceil(countdown);
            countdown -= dt;
            int now = (int) Math.ceil(countdown);
            if (now != prev && now >= 1 && now <= 3 && audio != null) audio.blip(EngineAudio.BLIP_COUNT);
            if (countdown <= 0) { state = RACING; if (audio != null) audio.blip(EngineAudio.BLIP_GO); }
            idleAudio();
            return;
        }
        if (state == FINISHED) { idleAudio(); updateParticles(dt); return; }

        // RACING
        sim.update(dt, keyLeft, keyRight, keyGas, keyBrake, tapBoost, tapItem);
        tapBoost = tapItem = false;

        // events -> audio + fx
        if (sim.evBoost && audio != null) audio.blip(EngineAudio.BLIP_BOOST);
        if (sim.evPickup && audio != null) audio.blip(EngineAudio.BLIP_PICKUP);
        if (sim.evHit) { shake = 8; if (audio != null) audio.blip(EngineAudio.BLIP_HIT); }
        if (sim.evJump && audio != null) audio.blip(EngineAudio.BLIP_BOOST);
        if (sim.evLand) shake = Math.max(shake, 4);
        sim.clearEvents();

        if (!sim.onTrack) shake = Math.min(shake + dt * 22, 4);
        if (sim.drifting) { emitSmoke(); addSkid(); }

        // best lap
        if (sim.lapsDone > prevLaps) {
            prevLaps = sim.lapsDone;
            double lt = sim.lastLapTime;
            if (lt > 0.5 && (bestLap == 0 || lt < bestLap)) {
                bestLap = lt;
                if (prefs != null) prefs.edit().putFloat("bestLap", (float) bestLap).apply();
            }
        }

        // camera
        double sp = sim.speed();
        double look = 120 * (sp / Sim.MAX_SPEED);
        double tx = sim.carX + Math.cos(sim.carAngle) * look;
        double ty = sim.carY + Math.sin(sim.carAngle) * look;
        camX += (tx - camX) * Math.min(1, dt * 6);
        camY += (ty - camY) * Math.min(1, dt * 6);
        shake = Math.max(0, shake - dt * 12);
        updateParticles(dt);

        if (audio != null) {
            float rpmF = (float) Math.min(1, sp / Sim.MAX_SPEED + 0.05);
            audio.set(rpmF, 1f, sim.boostTime > 0, sim.drifting ? 0.9f : 0f);
        }

        if (sim.finished) {
            lastFinishWon = sim.position() == 1;
            int next = level + 1;
            if (next < Tracks.LEVELS.length && next + 1 > unlocked) {
                unlocked = next + 1;
                if (prefs != null) prefs.edit().putInt("unlocked", unlocked).apply();
            }
            state = FINISHED;
        }
    }

    private void idleAudio() { if (audio != null) audio.set(0.06f, state == TITLE ? 0.5f : 0.7f, false, 0f); }

    private void emitSmoke() {
        if (particles.size() > 160) return;
        Particle p = new Particle();
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
            if (p.life <= 0) particles.remove(i);
        }
    }

    // --------------------------------------------------------------- render

    void render(Canvas g) {
        float darkness = nightAmount();
        int grass = lerp(0xFF2E7D52, 0xFF0A2238, darkness * 0.85f);
        if (sim.weather == 1) grass = lerp(grass, 0xFF20465A, 0.4f);
        g.drawColor(grass);

        g.save();
        float sx = (float) (shake * (rnd.nextDouble() - 0.5));
        float sy = (float) (shake * (rnd.nextDouble() - 0.5));
        g.translate(width / 2f + sx, height / 2f + sy);
        g.scale((float) zoom, (float) zoom);
        g.translate((float) -camX, (float) -camY);

        drawSkidMarks(g);
        drawShortcuts(g);
        drawRoad(g);
        drawPads(g);
        drawJumps(g);
        drawFinishLine(g);
        drawOils(g);
        drawBoxes(g);
        for (Sim.Ai a : sim.ais) drawCar(g, a.x, a.y, a.angle, a.color, false, 1f, 0f);
        float js = 1f + (float) sim.jumpZ * 0.012f;
        drawCar(g, sim.carX, sim.carY, sim.carAngle, CAR_COLORS[playerColorIdx], true, js, (float) sim.jumpZ * 1.4f);
        drawParticles(g);
        g.restore();

        if (darkness > 0.02f) drawNightLighting(g, darkness);
        if (sim.weather == 1) drawRain(g);
        if (sim.boostTime > 0) drawSpeedLines(g);

        drawRadar(g);
        renderHud(g);
        renderControls(g);

        if (state == TITLE) renderTitle(g);
        else if (state == COUNTDOWN) renderCountdown(g);
        else if (state == FINISHED) renderResults(g);
    }

    private float nightAmount() {
        double d = Math.cos((dayTime - 0.25) * 2 * Math.PI);
        return (float) Math.max(0, Math.min(1, (-d + 0.35) / 1.1));
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
        road.setColor(0xFF12121C);
        road.setStrokeWidth(Sim.ROAD_HALF * 2 + 22);
        g.drawPath(roadPath, road);
        int asphalt = sim.weather == 1 ? 0xFF34384E : 0xFF3C3F55;
        road.setColor(asphalt);
        road.setStrokeWidth(Sim.ROAD_HALF * 2);
        g.drawPath(roadPath, road);
        road.setColor(0x18FFFFFF);
        road.setStrokeWidth(Sim.ROAD_HALF * 2 - 14);
        g.drawPath(roadPath, road);
        road.setColor(asphalt);
        road.setStrokeWidth(Sim.ROAD_HALF * 2 - 18);
        g.drawPath(roadPath, road);
        road.setColor(0xFFFFE34D);
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
        for (int[] p : sim.pads) drawChevrons(g, sim.cx[p[0]], sim.cy[p[0]],
                Math.atan2(sim.dirY[p[0]], sim.dirX[p[0]]), 0xCCFF8A1E);
    }

    private void drawJumps(Canvas g) {
        for (double[] j : sim.jumps) {
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
        g.save();
        g.translate((float) x, (float) y);
        g.rotate((float) Math.toDegrees(angle));

        ui.setStyle(Paint.Style.FILL);
        ui.setColor(0x55000000);
        tmp.set(-L / 2 + 4 + shadowGap, -W / 2 + 6 + shadowGap, L / 2 + 6 + shadowGap, W / 2 + 8 + shadowGap);
        g.drawRoundRect(tmp, 14, 14, ui);

        if (player && sim.boostTime > 0) {
            ui.setShader(new RadialGradient(0, 0, L * 1.3f, new int[]{0x66FF8A1E, 0}, null, Shader.TileMode.CLAMP));
            g.drawCircle(0, 0, L * 1.3f, ui);
            ui.setShader(null);
        }
        if (player && sim.shield > 0) {
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
        int glow = (int) (90 * darkness);
        ui.setShader(new RadialGradient(cx, cy, r, new int[]{(glow << 24) | 0xFFF0C0, 0}, null, Shader.TileMode.CLAMP));
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

    private void renderTitle(Canvas g) {
        g.drawColor(0xBB050314);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(20, 0, 0, 0xFFFF2E88);
        text.setColor(0xFF19E0FF);
        text.setTextSize(Math.min(width, height) * 0.11f);
        g.drawText("TURBO CIRCUIT", width / 2f, height * 0.2f, text);
        text.clearShadowLayer();

        text.setColor(0xFFB9B2E6);
        text.setTextSize(Math.min(width, height) * 0.03f);
        g.drawText("SELECT A LEVEL", width / 2f, height * 0.3f, text);

        for (int i = 0; i < levelBtns.length; i++) {
            boolean locked = i >= unlocked;
            RectF r = levelBtns[i];
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(locked ? 0x33101830 : (i == level ? 0x6619E0FF : 0x44102038));
            g.drawRoundRect(r, 14, 14, ui);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(3);
            ui.setColor(locked ? 0x55FFFFFF : 0xCC19E0FF);
            g.drawRoundRect(r, 14, 14, ui);
            ui.setStyle(Paint.Style.FILL);
            text.clearShadowLayer();
            text.setColor(locked ? 0x66FFFFFF : 0xFFFFFFFF);
            text.setTextSize(r.height() * 0.36f);
            g.drawText(locked ? "LOCKED" : Tracks.LEVELS[i].name, r.centerX(), r.centerY() + r.height() * 0.13f, text);
        }

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

        if (bestLap > 0) {
            text.setColor(0xFF8AF0FF);
            text.setTextSize(Math.min(width, height) * 0.028f);
            g.drawText("BEST LAP " + fmt(bestLap), width / 2f, height * 0.74f, text);
        }
        text.setColor(0xFF7F88B0);
        text.setTextSize(Math.min(width, height) * 0.024f);
        g.drawText("drift = nitro  •  ? = item  •  blue = jump  •  yellow dashes = shortcut", width / 2f, height * 0.92f, text);
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
        g.drawText(lastFinishWon ? "YOU WIN!" : "FINISH", width / 2f, height * 0.18f, text);
        text.clearShadowLayer();

        List<Standing> st = new ArrayList<>();
        st.add(new Standing("YOU", sim.lapsDone * (double) sim.N + sim.prevIdx, CAR_COLORS[playerColorIdx], sim.finishTime));
        int n = 1;
        for (Sim.Ai a : sim.ais) st.add(new Standing("CPU " + (n++), a.lap * (double) sim.N + a.t, a.color, 0));
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

        boolean hasNext = level + 1 < Tracks.LEVELS.length;
        float pulse = (float) (0.5 + 0.5 * Math.sin(titlePulse * 3));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFFFFFFF);
        text.setAlpha((int) (120 + 135 * pulse));
        text.setTextSize(unit * 0.04f);
        g.drawText(hasNext ? "TAP: NEXT LEVEL    (long area left: menu)" : "TAP TO RETURN TO MENU",
                width / 2f, height * 0.88f, text);
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
                for (int i = 0; i < swatches.length; i++)
                    if (swatches[i].contains(x, y)) {
                        playerColorIdx = i;
                        if (prefs != null) prefs.edit().putInt("color", i).apply();
                        return;
                    }
                for (int i = 0; i < levelBtns.length; i++)
                    if (levelBtns[i].contains(x, y) && i < unlocked) { startRace(i); return; }
            }
            return;
        }
        if (state == FINISHED) {
            if (action == MotionEvent.ACTION_DOWN) {
                float x = e.getX(e.getActionIndex());
                boolean hasNext = level + 1 < Tracks.LEVELS.length;
                if (hasNext && x > width * 0.4f) startRace(level + 1);
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

    private void startRace(int lvl) {
        loadLevel(lvl);
        camX = sim.carX; camY = sim.carY;
        keyLeft = keyRight = keyGas = keyBrake = false;
        tapBoost = tapItem = false;
        state = COUNTDOWN;
        countdown = 3.99;
    }

    private static final class Particle {
        double x, y, vx, vy, life, maxLife; float size;
    }

    private static final class Standing {
        String name; double prog; int color; double time;
        Standing(String n, double p, int c, double t) { name = n; prog = p; color = c; time = t; }
    }
}
