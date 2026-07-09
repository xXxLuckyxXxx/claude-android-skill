package com.fable.racer;

import java.util.Random;

/** Level / track definitions. Eight distinct circuits, each a longer, smooth
 *  star-convex loop (so it never self-intersects) with its own difficulty,
 *  weather, time of day, jumps and shortcuts. */
final class Tracks {

    static final class Def {
        final String name;
        final float[][] wp;
        final int laps;
        final double aiBase;
        final int weatherBias;     // 0 clear, 1 rain, 2 random
        final double dayTime;      // 0..1 ambiance
        final double[] jumps;
        final double[] pads;
        final double[] boxes;
        final double[][] shortcuts;

        Def(String name, float[][] wp, int laps, double aiBase, int weatherBias, double dayTime,
            double[] jumps, double[] pads, double[] boxes, double[][] shortcuts) {
            this.name = name; this.wp = wp; this.laps = laps; this.aiBase = aiBase;
            this.weatherBias = weatherBias; this.dayTime = dayTime;
            this.jumps = jumps; this.pads = pads; this.boxes = boxes; this.shortcuts = shortcuts;
        }
    }

    /** Builds a smooth, non-self-intersecting closed loop from a jittered ellipse. */
    private static float[][] loop(double rx, double ry, int n, long seed, double jitter) {
        Random r = new Random(seed);
        double[] rad = new double[n];
        for (int i = 0; i < n; i++) rad[i] = 1 + (r.nextDouble() * 2 - 1) * jitter;
        // circular smoothing so there are no sharp radial spikes
        double[] sm = new double[n];
        for (int i = 0; i < n; i++) {
            double a = rad[(i - 1 + n) % n], b = rad[i], c = rad[(i + 1) % n];
            sm[i] = (a + 2 * b + c) / 4;
        }
        float[][] wp = new float[n][2];
        for (int i = 0; i < n; i++) {
            double ang = 2 * Math.PI * i / n;
            wp[i][0] = (float) (Math.cos(ang) * rx * sm[i]);
            wp[i][1] = (float) (Math.sin(ang) * ry * sm[i]);
        }
        return wp;
    }

    static final Def[] LEVELS = {
            new Def("1 · SUNSET BAY",
                    loop(1950, 1380, 18, 101, 0.26), 3, 27.0, 0, 0.20,
                    new double[]{0.30, 0.66}, new double[]{0.06, 0.40, 0.70, 0.88}, new double[]{0.18, 0.5, 0.78},
                    new double[][]{{0.40, 0.55, 380}}),

            new Def("2 · NEON HEIGHTS",
                    loop(1850, 1650, 20, 207, 0.31), 3, 29.0, 2, 0.52,
                    new double[]{0.22, 0.55, 0.80}, new double[]{0.10, 0.46, 0.80}, new double[]{0.30, 0.62},
                    new double[][]{{0.46, 0.62, -420}, {0.12, 0.24, 320}}),

            new Def("3 · MIDNIGHT CANYON",
                    loop(2150, 1550, 21, 313, 0.35), 3, 31.0, 1, 0.80,
                    new double[]{0.18, 0.50, 0.78}, new double[]{0.30, 0.55, 0.78, 0.92}, new double[]{0.12, 0.44, 0.72},
                    new double[][]{{0.20, 0.34, 380}, {0.58, 0.72, -360}}),

            new Def("4 · COASTAL LOOP",
                    loop(2400, 1600, 19, 419, 0.25), 3, 30.0, 0, 0.30,
                    new double[]{0.40, 0.75}, new double[]{0.12, 0.42, 0.7, 0.9}, new double[]{0.25, 0.6},
                    new double[][]{{0.60, 0.74, 380}}),

            new Def("5 · FOREST SPRINT",
                    loop(1780, 1780, 24, 521, 0.37), 3, 31.0, 2, 0.18,
                    new double[]{0.15, 0.45, 0.75}, new double[]{0.20, 0.45, 0.7, 0.9}, new double[]{0.3, 0.65},
                    new double[][]{{0.42, 0.56, -380}, {0.10, 0.22, 320}}),

            new Def("6 · DESERT MIRAGE",
                    loop(2550, 1500, 18, 631, 0.27), 4, 32.0, 0, 0.45,
                    new double[]{0.25, 0.55, 0.85}, new double[]{0.08, 0.40, 0.66, 0.9}, new double[]{0.18, 0.5, 0.78},
                    new double[][]{{0.50, 0.66, 440}}),

            new Def("7 · HARBOR LIGHTS",
                    loop(2000, 2000, 24, 743, 0.34), 4, 33.0, 1, 0.85,
                    new double[]{0.20, 0.45, 0.70, 0.90}, new double[]{0.14, 0.42, 0.66, 0.86}, new double[]{0.32, 0.68},
                    new double[][]{{0.24, 0.38, 380}, {0.60, 0.74, -360}}),

            new Def("8 · GRAND FINALE",
                    loop(2550, 2050, 26, 857, 0.37), 4, 35.0, 2, 0.78,
                    new double[]{0.16, 0.40, 0.62, 0.84}, new double[]{0.10, 0.36, 0.6, 0.8, 0.92}, new double[]{0.12, 0.44, 0.72},
                    new double[][]{{0.20, 0.33, 420}, {0.56, 0.68, -400}, {0.40, 0.50, 320}}),
    };

    /** Builds a fully procedural circuit from a 5-digit share code (0..99999). */
    static Def random(int code) {
        long seed = 0x9E3779B97F4A7C15L ^ (code * 2654435761L);
        Random r = new Random(seed);
        double rx = 1850 + r.nextInt(800);
        double ry = 1450 + r.nextInt(650);
        int n = 18 + r.nextInt(9);
        double jit = 0.24 + r.nextDouble() * 0.16;
        int laps = 3 + r.nextInt(2);
        double ai = 28 + r.nextDouble() * 7;
        int weather = r.nextInt(3);
        double day = r.nextDouble();
        double[] jumps = fracs(r, 2 + r.nextInt(3), 0.1, 0.9);
        double[] pads = fracs(r, 3 + r.nextInt(3), 0.05, 0.95);
        double[] boxes = fracs(r, 2 + r.nextInt(2), 0.1, 0.9);
        double[][] sc = {{0.20 + r.nextDouble() * 0.08, 0.34 + r.nextDouble() * 0.08,
                (r.nextBoolean() ? 1 : -1) * (270 + r.nextInt(130))}};
        return new Def(String.format("RANDOM %05d", code), loop(rx, ry, n, seed, jit),
                laps, ai, weather, day, jumps, pads, boxes, sc);
    }

    private static double[] fracs(Random r, int count, double lo, double hi) {
        double[] f = new double[count];
        for (int i = 0; i < count; i++) f[i] = lo + (hi - lo) * (i + r.nextDouble() * 0.6) / count;
        return f;
    }

    private Tracks() {}
}
