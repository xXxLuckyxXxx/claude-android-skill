package com.fable.racer;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A pseudo-3D (2.5D) "OutRun" style racing game rendered entirely with the
 * Android Canvas. No external image assets: the road, scenery, traffic, car
 * and UI are drawn procedurally so the whole thing stays tiny and crisp.
 */
final class Game {

    // ---- Track / projection constants ----
    private static final double SEGMENT_LENGTH = 200;
    private static final int    RUMBLE_LENGTH  = 3;
    private static final double ROAD_WIDTH      = 2000;
    private static final int    LANES           = 3;
    private static final int    DRAW_DISTANCE   = 280;
    private static final double CAMERA_HEIGHT    = 1000;
    private static final double FIELD_OF_VIEW    = 100;
    private static final double FOG_DENSITY      = 5;
    private static final double CENTRIFUGAL      = 0.3;

    // road section lengths (in segments)
    private static final int LEN_SHORT = 25, LEN_MEDIUM = 50, LEN_LONG = 100;
    private static final double CURVE_EASY = 2, CURVE_MEDIUM = 4, CURVE_HARD = 6;
    private static final double HILL_LOW = 20, HILL_MEDIUM = 40, HILL_HIGH = 60;

    // ---- Palette (neon sunset) ----
    private static final int FOG_COLOR = 0xFF1A1036;

    private int width, height;
    private double cameraDepth;

    private final List<Segment> segments = new ArrayList<>();
    private final List<Car> cars = new ArrayList<>();
    private double trackLength;

    // player / camera state
    private double position = 0;
    private double speed = 0;
    private double playerX = 0;            // -1..1 = on the road
    private double maxSpeed;
    private double accel, breaking, decel, offRoadDecel, offRoadLimit;
    private final double playerZ;

    // presentation
    private double skyOffset = 0;
    private double hillOffset = 0;
    private double bounce = 0;
    private double shake = 0;
    private final Random rnd = new Random(7);

    // score
    private long score = 0;
    private static long best = 0;

    // input
    boolean keyLeft, keyRight, keyGas, keyBrake;
    private boolean started = false;
    private double titlePulse = 0;

    // control button rects
    private final RectF btnLeft = new RectF();
    private final RectF btnRight = new RectF();
    private final RectF btnGas = new RectF();
    private final RectF btnBrake = new RectF();

    // reusable drawing objects (avoid per-frame allocation)
    private final Path path = new Path();
    private final Paint fill = new Paint();
    private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();
    private final List<Sprite> spriteQueue = new ArrayList<>();
    private LinearGradient skyShader;

    Game(int w, int h) {
        playerZ = CAMERA_HEIGHT * (1 / Math.tan((FIELD_OF_VIEW / 2) * Math.PI / 180));
        maxSpeed     = SEGMENT_LENGTH * 60;
        accel        = maxSpeed / 5;
        breaking     = -maxSpeed;
        decel        = -maxSpeed / 5;
        offRoadDecel = -maxSpeed / 2.5;
        offRoadLimit = maxSpeed / 4;
        buildRoad();
        buildTraffic();
        resize(w, h);
        fill.setAntiAlias(false);
        fill.setStyle(Paint.Style.FILL);
        text.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD));
    }

    void resize(int w, int h) {
        this.width = w;
        this.height = h;
        cameraDepth = 1 / Math.tan((FIELD_OF_VIEW / 2) * Math.PI / 180);
        skyShader = new LinearGradient(0, 0, 0, h,
                new int[]{0xFF241552, 0xFF6A2A6E, 0xFFB94A78, 0xFFF98C54},
                new float[]{0f, 0.42f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        layoutControls();
    }

    private void layoutControls() {
        float pad = Math.min(width, height) * 0.04f;
        float r = Math.min(width, height) * 0.115f;
        float by = height - pad - r * 2;
        // steering on the left
        btnLeft.set(pad, by, pad + r * 2, by + r * 2);
        btnRight.set(pad + r * 2.3f, by, pad + r * 4.3f, by + r * 2);
        // pedals on the right
        btnGas.set(width - pad - r * 2, by, width - pad, by + r * 2);
        btnBrake.set(width - pad - r * 4.3f, by, width - pad - r * 2.3f, by + r * 2);
    }

    // ---------------------------------------------------------------- build

    private double lastY() {
        return segments.isEmpty() ? 0 : segments.get(segments.size() - 1).p2y;
    }

    private void addSegment(double curve, double y) {
        int n = segments.size();
        Segment s = new Segment();
        s.index = n;
        s.p1z = n * SEGMENT_LENGTH;
        s.p2z = (n + 1) * SEGMENT_LENGTH;
        s.p1y = lastY();
        s.p2y = y;
        s.curve = curve;
        s.dark = (n / RUMBLE_LENGTH) % 2 == 0;
        // world-space points used by the projection
        s.p1.wy = s.p1y; s.p1.wz = s.p1z;
        s.p2.wy = s.p2y; s.p2.wz = s.p2z;
        segments.add(s);
    }

    private void addRoad(int enter, int hold, int leave, double curve, double y) {
        double startY = lastY();
        double endY = startY + y * SEGMENT_LENGTH;
        int total = enter + hold + leave;
        for (int i = 0; i < enter; i++)
            addSegment(Util.easeIn(0, curve, (double) i / enter),
                    Util.easeInOut(startY, endY, (double) i / total));
        for (int i = 0; i < hold; i++)
            addSegment(curve, Util.easeInOut(startY, endY, (double) (enter + i) / total));
        for (int i = 0; i < leave; i++)
            addSegment(Util.easeInOut(curve, 0, (double) i / leave),
                    Util.easeInOut(startY, endY, (double) (enter + hold + i) / total));
    }

    private void addStraight(int n) { addRoad(n, n, n, 0, 0); }
    private void addCurve(int n, double curve, double height) { addRoad(n, n, n, curve, height); }
    private void addHill(int n, double height) { addRoad(n, n, n, 0, height); }

    private void addSCurves() {
        addRoad(LEN_MEDIUM, LEN_MEDIUM, LEN_MEDIUM, -CURVE_EASY, HILL_NONE());
        addRoad(LEN_MEDIUM, LEN_MEDIUM, LEN_MEDIUM, CURVE_MEDIUM, HILL_MEDIUM);
        addRoad(LEN_MEDIUM, LEN_MEDIUM, LEN_MEDIUM, CURVE_EASY, -HILL_LOW);
        addRoad(LEN_MEDIUM, LEN_MEDIUM, LEN_MEDIUM, -CURVE_EASY, HILL_MEDIUM);
        addRoad(LEN_MEDIUM, LEN_MEDIUM, LEN_MEDIUM, -CURVE_MEDIUM, -HILL_MEDIUM);
    }

    private double HILL_NONE() { return 0; }

    private void buildRoad() {
        segments.clear();
        addStraight(LEN_SHORT);
        addHill(LEN_SHORT, HILL_LOW);
        addLowRollingHills();
        addSCurves();
        addCurve(LEN_MEDIUM, CURVE_MEDIUM, HILL_LOW);
        addBumps();
        addStraight(LEN_SHORT);
        addCurve(LEN_LONG, CURVE_MEDIUM, HILL_MEDIUM);
        addCurve(LEN_LONG, -CURVE_MEDIUM, HILL_MEDIUM);
        addHill(LEN_LONG, HILL_HIGH);
        addSCurves();
        addCurve(LEN_LONG, -CURVE_MEDIUM, HILL_NONE());
        addHill(LEN_LONG, -HILL_HIGH);
        addStraight(LEN_MEDIUM);
        addSCurves();
        addCurve(LEN_SHORT, CURVE_HARD, -HILL_LOW);
        addBumps();
        addStraight(LEN_SHORT);
        addLowRollingHills();
        trackLength = segments.size() * SEGMENT_LENGTH;
    }

    private void addLowRollingHills() {
        int n = LEN_SHORT;
        addRoad(n, n, n, 0, HILL_LOW);
        addRoad(n, n, n, 0, -HILL_LOW);
        addRoad(n, n, n, CURVE_EASY, HILL_LOW);
        addRoad(n, n, n, 0, 0);
        addRoad(n, n, n, -CURVE_EASY, -HILL_LOW);
    }

    private void addBumps() {
        addRoad(10, 10, 10, 0, 5);
        addRoad(10, 10, 10, 0, -7);
        addRoad(10, 10, 10, 0, 8);
        addRoad(10, 10, 10, 0, 5);
        addRoad(10, 10, 10, 0, -9);
        addRoad(10, 10, 10, 0, 6);
    }

    private void buildTraffic() {
        cars.clear();
        int total = 40;
        for (int i = 0; i < total; i++) {
            Car c = new Car();
            c.offset = (rnd.nextDouble() * 1.6) - 0.8;
            c.z = Math.floor(rnd.nextDouble() * segments.size()) * SEGMENT_LENGTH;
            c.speed = maxSpeed / (3 + rnd.nextDouble() * 2);
            c.color = TRAFFIC_COLORS[rnd.nextInt(TRAFFIC_COLORS.length)];
            cars.add(c);
        }
    }

    private static final int[] TRAFFIC_COLORS = {
            0xFFFF5D73, 0xFF42E2B8, 0xFFFFC857, 0xFF7D8CFF, 0xFFFF8FB1, 0xFF8AF0FF
    };

    private Segment findSegment(double z) {
        return segments.get((int) Math.floor(z / SEGMENT_LENGTH) % segments.size());
    }

    // ---------------------------------------------------------------- update

    void update(double dt) {
        titlePulse += dt;
        if (!started) return;

        Segment playerSeg = findSegment(position + playerZ);
        double speedPercent = speed / maxSpeed;
        double dx = dt * 2 * speedPercent;

        position = Util.increase(position, dt * speed, trackLength);

        if (keyLeft) playerX -= dx;
        else if (keyRight) playerX += dx;
        playerX -= dx * speedPercent * playerSeg.curve * CENTRIFUGAL;

        if (keyGas) speed = Util.accelerate(speed, accel, dt);
        else if (keyBrake) speed = Util.accelerate(speed, breaking, dt);
        else speed = Util.accelerate(speed, decel, dt);

        if ((playerX < -1 || playerX > 1) && speed > offRoadLimit) {
            speed = Util.accelerate(speed, offRoadDecel, dt);
            shake = Math.min(shake + dt * 30, 6);
        }

        // collision with traffic
        for (Car c : cars) {
            Segment cs = findSegment(c.z);
            if (cs.index == playerSeg.index && speed > c.speed) {
                if (overlap(playerX, 0.45, c.offset, 0.45)) {
                    speed = c.speed / 2;
                    position = Util.increase(position, -position % SEGMENT_LENGTH, trackLength);
                    shake = 8;
                    break;
                }
            }
        }

        // move traffic and reassign to segments
        for (Segment s : segments) s.cars.clear();
        for (Car c : cars) {
            c.z = Util.increase(c.z, dt * c.speed, trackLength);
            findSegment(c.z).cars.add(c);
        }

        playerX = Util.limit(playerX, -2, 2);
        speed = Util.limit(speed, 0, maxSpeed);

        skyOffset = Util.increase(skyOffset, dt * playerSeg.curve * speedPercent * 0.04, 1);
        hillOffset = Util.increase(hillOffset, dt * playerSeg.curve * speedPercent * 0.02, 1);
        bounce += dt * (speedPercent * 0.6 + 0.05) * 14;
        shake = Math.max(0, shake - dt * 14);

        score += (long) (speedPercent * 100 * dt) + 1;
        if (score > best) best = score;
    }

    private static boolean overlap(double x1, double w1, double x2, double w2) {
        double half = (w1 + w2) / 2;
        return Math.abs(x1 - x2) < half;
    }

    // ---------------------------------------------------------------- render

    void render(Canvas g) {
        renderBackground(g);
        renderRoad(g);
        renderPlayer(g);
        renderHud(g);
        renderControls(g);
        if (!started) renderTitle(g);
    }

    private void renderBackground(Canvas g) {
        fill.setShader(skyShader);
        g.drawRect(0, 0, width, height, fill);
        fill.setShader(null);

        float horizon = height * 0.52f;

        // retro sun with radial glow + scan bands
        float sunR = Math.min(width, height) * 0.16f;
        float sunX = width * 0.5f - (float) (skyOffset - 0.5) * width * 0.8f;
        float sunY = horizon - sunR * 0.55f;
        ui.setShader(new RadialGradient(sunX, sunY, sunR * 2.4f,
                new int[]{0x88FFD36B, 0x33FF7AA8, 0x00000000},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        g.drawCircle(sunX, sunY, sunR * 2.4f, ui);
        ui.setShader(new LinearGradient(0, sunY - sunR, 0, sunY + sunR,
                new int[]{0xFFFFE9A0, 0xFFFF8E5A}, null, Shader.TileMode.CLAMP));
        g.drawCircle(sunX, sunY, sunR, ui);
        ui.setShader(null);
        // dark scan bands across the sun (retro look)
        fill.setColor(0xFF241552);
        for (int i = 0; i < 6; i++) {
            float yy = sunY + sunR * 0.15f + i * sunR * 0.22f;
            if (yy < sunY + sunR) g.drawRect(sunX - sunR, yy, sunX + sunR, yy + sunR * 0.07f, fill);
        }

        // two parallax hill silhouettes
        drawHills(g, horizon, hillOffset, 0.0f, 0xFF3A1F5C, height * 0.10f);
        drawHills(g, horizon, hillOffset * 1.8f, 0.5f, 0xFF2A1547, height * 0.06f);
    }

    private void drawHills(Canvas g, float horizon, double off, float phase, int color, float amp) {
        path.reset();
        path.moveTo(0, horizon);
        int steps = 16;
        for (int i = 0; i <= steps; i++) {
            float x = (float) i / steps * width;
            double t = (double) i / steps * Math.PI * 3 + phase + off * Math.PI * 2;
            float y = horizon - (float) (Math.abs(Math.sin(t)) * amp) - amp * 0.2f;
            path.lineTo(x, y);
        }
        path.lineTo(width, horizon);
        path.close();
        fill.setColor(color);
        g.drawPath(path, fill);
    }

    private void renderRoad(Canvas g) {
        Segment baseSeg = findSegment(position);
        double basePercent = Util.percentRemaining(position, SEGMENT_LENGTH);
        Segment playerSeg = findSegment(position + playerZ);
        double playerPercent = Util.percentRemaining(position + playerZ, SEGMENT_LENGTH);
        double playerY = Util.interpolate(playerSeg.p1y, playerSeg.p2y, playerPercent);
        double maxy = height;

        double x = 0;
        double dx = -(baseSeg.curve * basePercent);
        double cameraY = CAMERA_HEIGHT + playerY;
        spriteQueue.clear();

        for (int n = 0; n < DRAW_DISTANCE; n++) {
            Segment s = segments.get((baseSeg.index + n) % segments.size());
            boolean looped = s.index < baseSeg.index;
            double camZ = position - (looped ? trackLength : 0);
            double fog = Util.exponentialFog((double) n / DRAW_DISTANCE, FOG_DENSITY);

            project(s.s1, s.p1, playerX * ROAD_WIDTH - x, cameraY, camZ);
            project(s.s2, s.p2, playerX * ROAD_WIDTH - x - dx, cameraY, camZ);
            x += dx;
            dx += s.curve;

            if (s.s1.cz <= cameraDepth || s.s2.sy >= s.s1.sy || s.s2.sy >= maxy) {
                queueSprites(s, fog);
                continue;
            }
            renderSegment(g, s, fog);
            queueSprites(s, fog);
            maxy = s.s2.sy;
        }

        // draw sprites far -> near
        for (int i = spriteQueue.size() - 1; i >= 0; i--) drawCar(g, spriteQueue.get(i));
    }

    private void queueSprites(Segment s, double fog) {
        if (s.cars.isEmpty()) return;
        double scale = s.s1.scale;
        if (scale <= 0) return;
        for (Car c : s.cars) {
            Sprite sp = new Sprite();
            sp.scale = scale;
            sp.x = s.s1.sx + (float) (scale * c.offset * ROAD_WIDTH * width / 2);
            sp.y = s.s1.sy;
            sp.color = c.color;
            sp.fog = fog;
            spriteQueue.add(sp);
        }
    }

    private void project(Screen sc, Point p, double camX, double camY, double camZ) {
        double cx = p.wx - camX;
        double cy = p.wy - camY;
        double cz = p.wz - camZ;
        sc.cz = cz;
        sc.scale = cameraDepth / (cz == 0 ? 0.0001 : cz);
        sc.sx = (float) Math.round(width / 2 + sc.scale * cx * width / 2);
        sc.sy = (float) Math.round(height / 2 - sc.scale * cy * height / 2);
        sc.sw = (float) Math.round(sc.scale * ROAD_WIDTH * width / 2);
    }

    private void renderSegment(Canvas g, Segment s, double fog) {
        float x1 = s.s1.sx, y1 = s.s1.sy, w1 = s.s1.sw;
        float x2 = s.s2.sx, y2 = s.s2.sy, w2 = s.s2.sw;

        int grass = s.dark ? 0xFF14965F : 0xFF17A268;
        int road  = s.dark ? 0xFF3D3F5A : 0xFF474A66;
        int rumble = s.dark ? 0xFFFF4D6D : 0xFFF4F4FA;
        boolean drawLane = !s.dark;

        grass = lerpColor(grass, FOG_COLOR, 1 - fog);
        road = lerpColor(road, FOG_COLOR, 1 - fog);
        rumble = lerpColor(rumble, FOG_COLOR, 1 - fog);

        // grass band
        fill.setColor(grass);
        g.drawRect(0, y2, width, y1, fill);

        float r1 = w1 / Math.max(6, 2 * LANES);
        float r2 = w2 / Math.max(6, 2 * LANES);

        // rumble strips
        fill.setColor(rumble);
        poly(g, x1 - w1 - r1, y1, x1 - w1, y1, x2 - w2, y2, x2 - w2 - r2, y2);
        poly(g, x1 + w1 + r1, y1, x1 + w1, y1, x2 + w2, y2, x2 + w2 + r2, y2);

        // road
        fill.setColor(road);
        poly(g, x1 - w1, y1, x1 + w1, y1, x2 + w2, y2, x2 - w2, y2);

        // lane markers
        if (drawLane) {
            float l1 = w1 / Math.max(32, 8 * LANES);
            float l2 = w2 / Math.max(32, 8 * LANES);
            float lw1 = w1 * 2 / LANES;
            float lw2 = w2 * 2 / LANES;
            float lx1 = x1 - w1 + lw1;
            float lx2 = x2 - w2 + lw2;
            fill.setColor(lerpColor(0xFFFFE34D, FOG_COLOR, 1 - fog));
            for (int lane = 1; lane < LANES; lane++) {
                poly(g, lx1 - l1 / 2, y1, lx1 + l1 / 2, y1, lx2 + l2 / 2, y2, lx2 - l2 / 2, y2);
                lx1 += lw1;
                lx2 += lw2;
            }
        }
    }

    private void poly(Canvas g, float x1, float y1, float x2, float y2,
                      float x3, float y3, float x4, float y4) {
        path.reset();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.lineTo(x4, y4);
        path.close();
        g.drawPath(path, fill);
    }

    // ---------------------------------------------------------- car drawing

    private void drawCar(Canvas g, Sprite sp) {
        float destW = (float) (sp.scale * ROAD_WIDTH * width / 2) * 1.1f;
        float destH = destW * 0.7f;
        if (destW < 6) return;
        float cx = sp.x;
        float top = sp.y - destH;
        drawCarShape(g, cx, top, destW, destH, sp.color, true, (float) sp.fog);
    }

    /** Draws a glossy car. faded uses fog amount to blend distant traffic. */
    private void drawCarShape(Canvas g, float cx, float top, float w, float h,
                              int color, boolean isTraffic, float fog) {
        float left = cx - w / 2;
        int body = isTraffic ? lerpColor(color, FOG_COLOR, 1 - fog) : color;

        // shadow
        ui.setColor(0x55000000);
        tmpRect.set(left + w * 0.05f, top + h * 0.82f, left + w * 0.95f, top + h * 1.02f);
        g.drawOval(tmpRect, ui);

        // body
        ui.setColor(body);
        tmpRect.set(left + w * 0.08f, top + h * 0.30f, left + w * 0.92f, top + h * 0.9f);
        g.drawRoundRect(tmpRect, w * 0.12f, w * 0.12f, ui);
        // cabin / roof
        ui.setColor(darken(body, 0.6f));
        tmpRect.set(left + w * 0.24f, top + h * 0.05f, left + w * 0.76f, top + h * 0.5f);
        g.drawRoundRect(tmpRect, w * 0.1f, w * 0.1f, ui);
        // windshield glow
        ui.setColor(lerpColor(0xFF9AD6FF, body, 0.3f));
        tmpRect.set(left + w * 0.30f, top + h * 0.10f, left + w * 0.70f, top + h * 0.36f);
        g.drawRoundRect(tmpRect, w * 0.06f, w * 0.06f, ui);

        // tail lights
        ui.setColor(0xFFFF3355);
        tmpRect.set(left + w * 0.12f, top + h * 0.62f, left + w * 0.30f, top + h * 0.78f);
        g.drawRoundRect(tmpRect, w * 0.04f, w * 0.04f, ui);
        tmpRect.set(left + w * 0.70f, top + h * 0.62f, left + w * 0.88f, top + h * 0.78f);
        g.drawRoundRect(tmpRect, w * 0.04f, w * 0.04f, ui);

        // wheels
        ui.setColor(0xFF111122);
        tmpRect.set(left + w * 0.02f, top + h * 0.55f, left + w * 0.16f, top + h * 0.92f);
        g.drawRoundRect(tmpRect, w * 0.04f, w * 0.04f, ui);
        tmpRect.set(left + w * 0.84f, top + h * 0.55f, left + w * 0.98f, top + h * 0.92f);
        g.drawRoundRect(tmpRect, w * 0.04f, w * 0.04f, ui);
    }

    private void renderPlayer(Canvas g) {
        float w = width * 0.20f;
        float h = w * 0.7f;
        float steer = 0;
        if (keyLeft) steer = -1;
        else if (keyRight) steer = 1;
        float cx = width / 2f + (float) (Math.sin(bounce * 0.5) * 2) + (float) (shake * (rnd.nextDouble() - 0.5));
        cx += steer * w * 0.06f;
        float top = height - h * 1.35f + (float) (Math.abs(Math.sin(bounce)) * 3) + (float) shake;

        // neon underglow
        ui.setShader(new RadialGradient(cx, top + h * 0.8f, w * 0.8f,
                new int[]{0x6619E0FF, 0x00000000}, null, Shader.TileMode.CLAMP));
        g.drawCircle(cx, top + h * 0.8f, w * 0.8f, ui);
        ui.setShader(null);

        drawCarShape(g, cx, top, w, h, 0xFF19E0FF, false, 1f);
        // a bright cyan rim on the player to stand out
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(w * 0.02f);
        ui.setColor(0xAAFFFFFF);
        tmpRect.set(cx - w / 2 + w * 0.08f, top + h * 0.30f, cx + w / 2 - w * 0.08f, top + h * 0.9f);
        g.drawRoundRect(tmpRect, w * 0.12f, w * 0.12f, ui);
        ui.setStyle(Paint.Style.FILL);
    }

    // ---------------------------------------------------------------- HUD

    private void renderHud(Canvas g) {
        float pad = Math.min(width, height) * 0.04f;
        float kmh = (float) (speed / maxSpeed * 320);

        // speed (bottom-ish left of top)
        text.setColor(0xFFFFFFFF);
        text.setShadowLayer(8, 0, 2, 0xCC000000);
        text.setTextSize(Math.min(width, height) * 0.085f);
        text.setTextAlign(Paint.Align.LEFT);
        g.drawText(String.format(java.util.Locale.US, "%03d", (int) kmh), pad, pad + text.getTextSize(), text);
        text.setTextSize(Math.min(width, height) * 0.032f);
        g.drawText("KM/H", pad, pad + text.getTextSize() + Math.min(width, height) * 0.095f, text);

        // speed bar
        float barW = width * 0.22f;
        float barH = Math.min(width, height) * 0.018f;
        float barX = pad;
        float barY = pad + Math.min(width, height) * 0.16f;
        ui.setColor(0x55FFFFFF);
        tmpRect.set(barX, barY, barX + barW, barY + barH);
        g.drawRoundRect(tmpRect, barH / 2, barH / 2, ui);
        ui.setColor(speed > maxSpeed * 0.8 ? 0xFFFF4D6D : 0xFF19E0FF);
        tmpRect.set(barX, barY, barX + barW * (float) (speed / maxSpeed), barY + barH);
        g.drawRoundRect(tmpRect, barH / 2, barH / 2, ui);

        // score + best (top right)
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTextSize(Math.min(width, height) * 0.05f);
        text.setColor(0xFFFFE34D);
        g.drawText(String.format(java.util.Locale.US, "%06d", score), width - pad, pad + text.getTextSize(), text);
        text.setTextSize(Math.min(width, height) * 0.03f);
        text.setColor(0xFFCFC8FF);
        g.drawText("BEST " + String.format(java.util.Locale.US, "%06d", best),
                width - pad, pad + text.getTextSize() + Math.min(width, height) * 0.06f, text);
        text.clearShadowLayer();
    }

    private void renderControls(Canvas g) {
        drawButton(g, btnLeft, "◀", keyLeft, 0xFF19E0FF);
        drawButton(g, btnRight, "▶", keyRight, 0xFF19E0FF);
        drawButton(g, btnBrake, "⏸", keyBrake, 0xFFFF4D6D);
        drawButton(g, btnGas, "▲", keyGas, 0xFF42E2B8);
    }

    private void drawButton(Canvas g, RectF r, String label, boolean pressed, int accent) {
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(pressed ? (accent & 0x66FFFFFF) | 0x66000000 : 0x33101830);
        g.drawOval(r, ui);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(r.width() * 0.04f);
        ui.setColor(pressed ? accent : (accent & 0x00FFFFFF) | 0x88000000);
        ui.setColor(pressed ? accent : 0x88FFFFFF);
        g.drawOval(r, ui);
        ui.setStyle(Paint.Style.FILL);
        text.setColor(pressed ? accent : 0xCCFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(r.height() * 0.4f);
        text.clearShadowLayer();
        g.drawText(label, r.centerX(), r.centerY() + r.height() * 0.14f, text);
    }

    private void renderTitle(Canvas g) {
        g.drawColor(0xB3050314);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(18, 0, 0, 0xFFFF2E88);
        text.setColor(0xFF19E0FF);
        text.setTextSize(Math.min(width, height) * 0.13f);
        g.drawText("NEON RACER", width / 2f, height * 0.42f, text);
        text.clearShadowLayer();

        float pulse = (float) (0.5 + 0.5 * Math.sin(titlePulse * 3));
        text.setColor(0xFFFFFFFF);
        text.setAlpha((int) (120 + 135 * pulse));
        text.setTextSize(Math.min(width, height) * 0.05f);
        g.drawText("TAP TO START", width / 2f, height * 0.58f, text);
        text.setAlpha(255);
        text.setColor(0xFFB9B2E6);
        text.setTextSize(Math.min(width, height) * 0.032f);
        g.drawText("LEFT / RIGHT to steer  •  hold ▲ to accelerate", width / 2f, height * 0.7f, text);
    }

    private static int lerpColor(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        int rr = (int) (ar + (br - ar) * t);
        int rg = (int) (ag + (bg - ag) * t);
        int rb = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (rr << 16) | (rg << 8) | rb;
    }

    private static int darken(int c, float f) {
        int r = (int) (((c >> 16) & 0xff) * f);
        int gg = (int) (((c >> 8) & 0xff) * f);
        int b = (int) ((c & 0xff) * f);
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }

    // ---------------------------------------------------------------- input

    void handleTouch(MotionEvent e) {
        boolean l = false, r = false, gas = false, brake = false;
        int action = e.getActionMasked();
        boolean anyDown = false;
        for (int i = 0; i < e.getPointerCount(); i++) {
            boolean thisUp = (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP)
                    && i == e.getActionIndex();
            if (thisUp) continue;
            float x = e.getX(i);
            float y = e.getY(i);
            anyDown = true;
            if (btnLeft.contains(x, y)) l = true;
            if (btnRight.contains(x, y)) r = true;
            if (btnGas.contains(x, y)) gas = true;
            if (btnBrake.contains(x, y)) brake = true;
        }
        if (!started && anyDown && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)) {
            started = true;
        }
        keyLeft = l;
        keyRight = r;
        keyGas = gas;
        keyBrake = brake;
        if (action == MotionEvent.ACTION_UP) {
            keyLeft = keyRight = keyGas = keyBrake = false;
        }
    }

    // ---------------------------------------------------------------- types

    private static final class Point {
        double wx, wy, wz;
    }

    private static final class Screen {
        double scale, cz;
        float sx, sy, sw;
    }

    private static final class Segment {
        int index;
        double p1y, p2y, p1z, p2z, curve;
        boolean dark;
        final Point p1 = new Point();
        final Point p2 = new Point();
        final Screen s1 = new Screen();
        final Screen s2 = new Screen();
        final List<Car> cars = new ArrayList<>();

        Segment() {
            p1.wx = 0; p2.wx = 0;
        }
    }

    private static final class Car {
        double offset, z, speed;
        int color;
    }

    private static final class Sprite {
        float x, y;
        double scale, fog;
        int color;
    }

}
