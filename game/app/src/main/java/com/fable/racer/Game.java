package com.fable.racer;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * A pretty top-down (bird's-eye) racing game drawn entirely with the Canvas.
 * The player drives a car around a closed circuit; the camera follows the car
 * while the world stays fixed. No external image assets are used.
 */
final class Game {

    // ---- circuit control points (world space, pixels) ----
    private static final float[][] WAYPOINTS = {
            {-1000, -250}, {-700, -560}, {-150, -620}, {380, -520},
            {760, -250}, {1040, 120}, {760, 430}, {300, 360},
            {40, 540}, {-420, 600}, {-840, 380}, {-1080, 60}
    };
    private static final int STEPS_PER_SEG = 26;
    private static final float ROAD_HALF = 120f;

    // ---- car physics ----
    private static final double MAX_SPEED = 560;
    private static final double ACCEL = 430;
    private static final double BRAKE = 720;
    private static final double DRAG = 300;          // coasting deceleration
    private static final double OFF_MAX = 200;       // top speed on grass
    private static final double OFF_DRAG = 640;      // grass slowdown
    private static final double BASE_TURN = 2.9;     // rad/s
    private static final double TURN_REF = 170;      // speed for full steering

    private int width, height;
    private double zoom = 1.2;

    // centerline (dense, closed)
    private float[] cxArr, cyArr, dirX, dirY;
    private int N;
    private float minX, minY, maxX, maxY;

    // player
    private double carX, carY, carAngle, carSpeed;
    private double camX, camY;
    private int lap = 0, prevIdx = 0;
    private boolean onTrack = true;
    private double shake = 0;

    // timing & score
    private double raceTime = 0, lapTime = 0, bestLap = 0;
    private static double allTimeBest = 0;

    // rivals
    private final List<Ai> ais = new ArrayList<>();
    private final List<float[]> trees = new ArrayList<>();

    // input
    boolean keyLeft, keyRight, keyGas, keyBrake;
    private boolean started = false;
    private double titlePulse = 0;
    private double dash = 0;

    // controls
    private final RectF btnLeft = new RectF();
    private final RectF btnRight = new RectF();
    private final RectF btnGas = new RectF();
    private final RectF btnBrake = new RectF();

    // reusable paints / objects
    private final Paint road = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path roadPath = new Path();
    private final RectF tmp = new RectF();
    private final Random rnd = new Random(11);
    private LinearGradient grassShader;

    Game(int w, int h) {
        buildTrack();
        buildTrees();
        buildAis();
        // place the player just after the finish line
        int start = 4;
        carX = cxArr[start];
        carY = cyArr[start];
        carAngle = Math.atan2(dirY[start], dirX[start]);
        camX = carX;
        camY = carY;
        prevIdx = start;
        text.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        resize(w, h);
    }

    void resize(int w, int h) {
        this.width = w;
        this.height = h;
        zoom = Math.min(w, h) / 640.0;
        grassShader = new LinearGradient(0, 0, 0, h,
                new int[]{0xFF2E7D52, 0xFF1F6B45, 0xFF18583A}, null, Shader.TileMode.CLAMP);
        layoutControls();
    }

    private void layoutControls() {
        float r = Math.min(width, height) * 0.115f;
        float pad = Math.min(width, height) * 0.045f;
        float by = height - pad - r * 2;
        btnLeft.set(pad, by, pad + r * 2, by + r * 2);
        btnRight.set(pad + r * 2.3f, by, pad + r * 4.3f, by + r * 2);
        btnGas.set(width - pad - r * 2, by, width - pad, by + r * 2);
        btnBrake.set(width - pad - r * 4.3f, by, width - pad - r * 2.3f, by + r * 2);
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
            float dx = cxArr[nx] - cxArr[pv];
            float dy = cyArr[nx] - cyArr[pv];
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

    private void buildTrees() {
        int want = 16;
        int guard = 0;
        while (trees.size() < want && guard++ < 2000) {
            float x = minX - 200 + rnd.nextFloat() * (maxX - minX + 400);
            float y = minY - 200 + rnd.nextFloat() * (maxY - minY + 400);
            if (distToTrack(x, y) > ROAD_HALF + 70) {
                trees.add(new float[]{x, y, 26 + rnd.nextFloat() * 22});
            }
        }
    }

    private void buildAis() {
        int[] colors = {0xFFFF5D73, 0xFFFFC857, 0xFF8AF0FF};
        for (int i = 0; i < colors.length; i++) {
            Ai a = new Ai();
            a.t = (N - (i + 1) * 12) % N;
            a.speed = 25 + i * 1.5;   // indices per second
            a.offset = (i - 1) * 42f;
            a.color = colors[i];
            ais.add(a);
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

    // --------------------------------------------------------------- update

    void update(double dt) {
        titlePulse += dt;
        if (!started) return;

        raceTime += dt;
        lapTime += dt;
        dash -= carSpeed * dt * 0.5;

        double steer = keyLeft ? -1 : (keyRight ? 1 : 0);

        if (keyGas) carSpeed += ACCEL * dt;
        else if (keyBrake) carSpeed -= BRAKE * dt;
        else carSpeed -= Math.signum(carSpeed) * Math.min(Math.abs(carSpeed), DRAG * dt);

        int idx = nearestIndex(carX, carY);
        onTrack = distToTrack((float) carX, (float) carY) <= ROAD_HALF;
        if (!onTrack) {
            if (carSpeed > OFF_MAX) carSpeed -= OFF_DRAG * dt;
            shake = Math.min(shake + dt * 26, 5);
        }

        carSpeed = Util.limit(carSpeed, -0.35 * MAX_SPEED, MAX_SPEED);

        double turnFactor = Util.limit(Math.abs(carSpeed) / TURN_REF, 0, 1);
        carAngle += steer * BASE_TURN * dt * turnFactor * (carSpeed >= 0 ? 1 : -1);

        carX += Math.cos(carAngle) * carSpeed * dt;
        carY += Math.sin(carAngle) * carSpeed * dt;

        // lap detection by progress fraction around the loop
        double f = (double) idx / N;
        double pf = (double) prevIdx / N;
        if (pf > 0.75 && f < 0.25) {
            lap++;
            if (lap > 1) {            // ignore the very first crossing
                bestLap = (bestLap == 0) ? lapTime : Math.min(bestLap, lapTime);
                if (allTimeBest == 0 || bestLap < allTimeBest) allTimeBest = bestLap;
            }
            lapTime = 0;
        }
        prevIdx = idx;

        // rivals follow the racing line
        for (Ai a : ais) {
            double prev = a.t;
            a.t += a.speed * dt;
            if (a.t >= N) { a.t -= N; a.lap++; }
            int ai = ((int) a.t) % N;
            a.x = cxArr[ai] + dirYn(ai) * a.offset;
            a.y = cyArr[ai] - dirXn(ai) * a.offset;
            a.angle = Math.atan2(dirY[ai], dirX[ai]);
        }

        // smooth camera with a little look-ahead
        double look = 130 * (carSpeed / MAX_SPEED);
        double tx = carX + Math.cos(carAngle) * look;
        double ty = carY + Math.sin(carAngle) * look;
        camX += (tx - camX) * Math.min(1, dt * 6);
        camY += (ty - camY) * Math.min(1, dt * 6);
        shake = Math.max(0, shake - dt * 12);
    }

    private float dirXn(int i) { return dirX[i]; }
    private float dirYn(int i) { return dirY[i]; }

    // --------------------------------------------------------------- render

    void render(Canvas g) {
        // grass background (screen space)
        fill.setShader(grassShader);
        g.drawRect(0, 0, width, height, fill);
        fill.setShader(null);

        g.save();
        float sx = (float) (shake * (rnd.nextDouble() - 0.5));
        float sy = (float) (shake * (rnd.nextDouble() - 0.5));
        g.translate(width / 2f + sx, height / 2f + sy);
        g.scale((float) zoom, (float) zoom);
        g.translate((float) -camX, (float) -camY);

        drawTrees(g);
        drawRoad(g);
        drawFinishLine(g);
        for (Ai a : ais) {
            drawCarSprite(g, a.x, a.y, a.angle, a.color, false);
        }
        drawCarSprite(g, carX, carY, carAngle, 0xFF19E0FF, true);

        g.restore();

        renderHud(g);
        renderControls(g);
        if (!started) renderTitle(g);
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

        // dark outer border / kerb shadow
        road.setPathEffect(null);
        road.setColor(0xFF12121C);
        road.setStrokeWidth(ROAD_HALF * 2 + 22);
        g.drawPath(roadPath, road);

        // asphalt
        road.setColor(0xFF3C3F55);
        road.setStrokeWidth(ROAD_HALF * 2);
        g.drawPath(roadPath, road);

        // subtle inner edge highlight
        road.setColor(0x22FFFFFF);
        road.setStrokeWidth(ROAD_HALF * 2 - 12);
        g.drawPath(roadPath, road);
        road.setColor(0xFF3C3F55);
        road.setStrokeWidth(ROAD_HALF * 2 - 16);
        g.drawPath(roadPath, road);

        // dashed center line (animated)
        road.setColor(0xFFFFE34D);
        road.setStrokeWidth(6);
        road.setPathEffect(new DashPathEffect(new float[]{34, 30}, (float) dash));
        g.drawPath(roadPath, road);
        road.setPathEffect(null);
    }

    private void drawFinishLine(Canvas g) {
        int i = 0;
        double ang = Math.atan2(dirY[i], dirX[i]);
        g.save();
        g.translate(cxArr[i], cyArr[i]);
        g.rotate((float) Math.toDegrees(ang));
        int cols = 8;
        float cell = (ROAD_HALF * 2) / cols;
        float depth = cell;
        for (int c = 0; c < cols; c++) {
            for (int rrow = 0; rrow < 2; rrow++) {
                fill.setColor(((c + rrow) % 2 == 0) ? 0xFFFFFFFF : 0xFF111111);
                float yy = -ROAD_HALF + c * cell;
                float xx = -depth + rrow * depth;
                g.drawRect(xx, yy, xx + depth, yy + cell, fill);
            }
        }
        g.restore();
    }

    /** Draws a top-down car centered at (x,y) heading along angle (radians). */
    private void drawCarSprite(Canvas g, double x, double y, double angle, int color, boolean player) {
        float L = 72, W = 36;
        g.save();
        g.translate((float) x, (float) y);
        g.rotate((float) Math.toDegrees(angle));

        // shadow
        ui.setColor(0x55000000);
        tmp.set(-L / 2 + 4, -W / 2 + 6, L / 2 + 6, W / 2 + 8);
        g.drawRoundRect(tmp, 14, 14, ui);

        if (player) {
            ui.setShader(new RadialGradient(0, 0, L,
                    new int[]{0x5519E0FF, 0x00000000}, null, Shader.TileMode.CLAMP));
            g.drawCircle(0, 0, L, ui);
            ui.setShader(null);
        }

        // body with a length-wise gradient for a glossy look
        ui.setShader(new LinearGradient(0, -W / 2, 0, W / 2,
                new int[]{lighten(color, 1.35f), color, darken(color, 0.7f)}, null, Shader.TileMode.CLAMP));
        tmp.set(-L / 2, -W / 2, L / 2, W / 2);
        g.drawRoundRect(tmp, 16, 16, ui);
        ui.setShader(null);

        // cockpit / windshield (toward the front, +x)
        ui.setColor(0xCC0E1420);
        tmp.set(-L * 0.05f, -W * 0.34f, L * 0.34f, W * 0.34f);
        g.drawRoundRect(tmp, 8, 8, ui);
        ui.setColor(0x886FD2FF);
        tmp.set(L * 0.02f, -W * 0.28f, L * 0.30f, W * 0.28f);
        g.drawRoundRect(tmp, 6, 6, ui);

        // headlights (front)
        ui.setColor(0xFFFFF3B0);
        g.drawCircle(L * 0.44f, -W * 0.28f, 4.5f, ui);
        g.drawCircle(L * 0.44f, W * 0.28f, 4.5f, ui);
        // tail lights (back)
        ui.setColor(0xFFFF3355);
        tmp.set(-L * 0.5f, -W * 0.34f, -L * 0.42f, -W * 0.06f);
        g.drawRect(tmp, ui);
        tmp.set(-L * 0.5f, W * 0.06f, -L * 0.42f, W * 0.34f);
        g.drawRect(tmp, ui);

        // wheels
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

    // ------------------------------------------------------------------ HUD

    private void renderHud(Canvas g) {
        float pad = Math.min(width, height) * 0.04f;
        float unit = Math.min(width, height);
        int kmh = (int) Math.abs(carSpeed / MAX_SPEED * 240);

        text.setShadowLayer(8, 0, 2, 0xCC000000);
        text.setTextAlign(Paint.Align.LEFT);

        // speed
        text.setColor(0xFFFFFFFF);
        text.setTextSize(unit * 0.085f);
        g.drawText(String.format(Locale.US, "%03d", kmh), pad, pad + text.getTextSize(), text);
        text.setTextSize(unit * 0.03f);
        g.drawText("KM/H", pad, pad + unit * 0.115f, text);

        // speed bar
        float barW = width * 0.2f, barH = unit * 0.016f;
        float barY = pad + unit * 0.135f;
        ui.setColor(0x55FFFFFF);
        tmp.set(pad, barY, pad + barW, barY + barH);
        g.drawRoundRect(tmp, barH / 2, barH / 2, ui);
        ui.setColor(!onTrack ? 0xFFFF4D6D : (carSpeed > MAX_SPEED * 0.8 ? 0xFFFFC857 : 0xFF19E0FF));
        tmp.set(pad, barY, pad + barW * (float) Math.min(1, Math.abs(carSpeed) / MAX_SPEED), barY + barH);
        g.drawRoundRect(tmp, barH / 2, barH / 2, ui);

        // lap + position (top right)
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(0xFFFFE34D);
        text.setTextSize(unit * 0.05f);
        g.drawText("LAP " + Math.max(1, lap), width - pad, pad + text.getTextSize(), text);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(unit * 0.04f);
        g.drawText("P" + position() + "/" + (ais.size() + 1), width - pad, pad + unit * 0.115f, text);

        // lap timers (top center)
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFCFC8FF);
        text.setTextSize(unit * 0.038f);
        g.drawText("TIME " + fmt(lapTime), width / 2f, pad + text.getTextSize(), text);
        if (bestLap > 0) {
            text.setColor(0xFF8AF0FF);
            g.drawText("BEST " + fmt(bestLap), width / 2f, pad + unit * 0.09f, text);
        }
        text.clearShadowLayer();

        if (!onTrack && started) {
            text.setColor(0xFFFF4D6D);
            text.setTextSize(unit * 0.05f);
            g.drawText("SLOW DOWN — OFF TRACK!", width / 2f, height * 0.2f, text);
        }
    }

    private int position() {
        double mine = lap * (double) N + prevIdx;
        int rank = 1;
        for (Ai a : ais) {
            double theirs = a.lap * (double) N + a.t;
            if (theirs > mine) rank++;
        }
        return rank;
    }

    private static String fmt(double s) {
        int m = (int) (s / 60);
        double sec = s - m * 60;
        return String.format(Locale.US, "%d:%05.2f", m, sec);
    }

    private void renderControls(Canvas g) {
        drawButton(g, btnLeft, "◀", keyLeft, 0xFF19E0FF);
        drawButton(g, btnRight, "▶", keyRight, 0xFF19E0FF);
        drawButton(g, btnBrake, "⏸", keyBrake, 0xFFFF4D6D);
        drawButton(g, btnGas, "▲", keyGas, 0xFF42E2B8);
    }

    private void drawButton(Canvas g, RectF r, String label, boolean pressed, int accent) {
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(pressed ? 0x66102038 : 0x33101830);
        g.drawOval(r, ui);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(r.width() * 0.04f);
        ui.setColor(pressed ? accent : 0x88FFFFFF);
        g.drawOval(r, ui);
        ui.setStyle(Paint.Style.FILL);
        text.clearShadowLayer();
        text.setColor(pressed ? accent : 0xCCFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(r.height() * 0.4f);
        g.drawText(label, r.centerX(), r.centerY() + r.height() * 0.14f, text);
    }

    private void renderTitle(Canvas g) {
        g.drawColor(0xB3050314);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(18, 0, 0, 0xFFFF2E88);
        text.setColor(0xFF19E0FF);
        text.setTextSize(Math.min(width, height) * 0.13f);
        g.drawText("TURBO CIRCUIT", width / 2f, height * 0.42f, text);
        text.clearShadowLayer();

        float pulse = (float) (0.5 + 0.5 * Math.sin(titlePulse * 3));
        text.setColor(0xFFFFFFFF);
        text.setAlpha((int) (120 + 135 * pulse));
        text.setTextSize(Math.min(width, height) * 0.05f);
        g.drawText("TAP TO START", width / 2f, height * 0.58f, text);
        text.setAlpha(255);
        text.setColor(0xFFB9B2E6);
        text.setTextSize(Math.min(width, height) * 0.032f);
        g.drawText("◀ ▶ steer   •   hold ▲ to accelerate   •   ⏸ brake", width / 2f, height * 0.7f, text);
    }

    // --------------------------------------------------------------- helpers

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

    // ---------------------------------------------------------------- input

    void handleTouch(MotionEvent e) {
        boolean l = false, r = false, gas = false, brake = false, anyDown = false;
        int action = e.getActionMasked();
        for (int i = 0; i < e.getPointerCount(); i++) {
            boolean thisUp = (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP)
                    && i == e.getActionIndex();
            if (thisUp) continue;
            float x = e.getX(i), y = e.getY(i);
            anyDown = true;
            if (btnLeft.contains(x, y)) l = true;
            if (btnRight.contains(x, y)) r = true;
            if (btnGas.contains(x, y)) gas = true;
            if (btnBrake.contains(x, y)) brake = true;
        }
        if (!started && anyDown
                && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)) {
            started = true;
        }
        keyLeft = l; keyRight = r; keyGas = gas; keyBrake = brake;
        if (action == MotionEvent.ACTION_UP) keyLeft = keyRight = keyGas = keyBrake = false;
    }

    // ----------------------------------------------------------------- types

    private static final class Ai {
        double t, speed, offset, x, y, angle;
        int lap, color;
    }
}
