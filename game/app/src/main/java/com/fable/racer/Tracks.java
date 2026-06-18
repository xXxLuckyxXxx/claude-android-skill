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
                    loop(1500, 1050, 16, 101, 0.22), 3, 22.0, 0, 0.20,
                    new double[]{0.30}, new double[]{0.06, 0.55, 0.84}, new double[]{0.18, 0.62},
                    new double[][]{{0.40, 0.55, 300}}),

            new Def("2 · NEON HEIGHTS",
                    loop(1400, 1250, 18, 207, 0.26), 3, 24.0, 2, 0.52,
                    new double[]{0.22, 0.70}, new double[]{0.10, 0.46, 0.80}, new double[]{0.34, 0.66},
                    new double[][]{{0.46, 0.62, -340}}),

            new Def("3 · MIDNIGHT CANYON",
                    loop(1650, 1150, 18, 313, 0.30), 3, 26.0, 1, 0.80,
                    new double[]{0.18, 0.50, 0.78}, new double[]{0.30, 0.64, 0.92}, new double[]{0.12, 0.44, 0.72},
                    new double[][]{{0.20, 0.34, 320}, {0.58, 0.72, -300}}),

            new Def("4 · COASTAL LOOP",
                    loop(1800, 1200, 16, 419, 0.18), 3, 25.0, 0, 0.30,
                    new double[]{0.40}, new double[]{0.12, 0.50, 0.85}, new double[]{0.25, 0.70},
                    new double[][]{{0.60, 0.74, 320}}),

            new Def("5 · FOREST SPRINT",
                    loop(1380, 1380, 20, 521, 0.30), 3, 27.0, 2, 0.18,
                    new double[]{0.15, 0.60}, new double[]{0.20, 0.55, 0.90}, new double[]{0.30, 0.65},
                    new double[][]{{0.42, 0.56, -320}}),

            new Def("6 · DESERT MIRAGE",
                    loop(1950, 1150, 16, 631, 0.20), 3, 28.0, 0, 0.45,
                    new double[]{0.25, 0.72}, new double[]{0.08, 0.48, 0.82}, new double[]{0.18, 0.60},
                    new double[][]{{0.50, 0.66, 360}}),

            new Def("7 · HARBOR LIGHTS",
                    loop(1520, 1520, 20, 743, 0.28), 4, 29.0, 1, 0.85,
                    new double[]{0.20, 0.55, 0.85}, new double[]{0.14, 0.50, 0.78}, new double[]{0.32, 0.68},
                    new double[][]{{0.24, 0.38, 320}, {0.60, 0.74, -300}}),

            new Def("8 · GRAND FINALE",
                    loop(1950, 1550, 22, 857, 0.30), 4, 30.0, 2, 0.78,
                    new double[]{0.16, 0.46, 0.74}, new double[]{0.10, 0.40, 0.70, 0.92}, new double[]{0.12, 0.44, 0.72},
                    new double[][]{{0.20, 0.33, 340}, {0.56, 0.70, -320}}),
    };

    /** Builds a fully procedural circuit from a 5-digit share code (0..99999). */
    static Def random(int code) {
        long seed = 0x9E3779B97F4A7C15L ^ (code * 2654435761L);
        Random r = new Random(seed);
        double rx = 1300 + r.nextInt(750);
        double ry = 1050 + r.nextInt(650);
        int n = 14 + r.nextInt(9);
        double jit = 0.18 + r.nextDouble() * 0.16;
        int laps = 3 + r.nextInt(2);
        double ai = 23 + r.nextDouble() * 7;
        int weather = r.nextInt(3);
        double day = r.nextDouble();
        double[] jumps = fracs(r, 1 + r.nextInt(3), 0.1, 0.9);
        double[] pads = fracs(r, 2 + r.nextInt(3), 0.05, 0.95);
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
