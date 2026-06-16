package com.fable.racer;

/** Level / track definitions. Each level is a distinct circuit with its own
 *  layout, AI difficulty, weather, jumps and shortcuts. */
final class Tracks {

    static final class Def {
        final String name;
        final float[][] wp;        // circuit control points
        final int laps;
        final double aiBase;       // AI base speed (indices/sec)
        final int weatherBias;     // 0 clear, 1 rain, 2 random
        final double dayTime;      // 0..1 ambiance (use -1 for noon)
        final double[] jumps;      // ramp positions (fraction of lap)
        final double[] pads;       // boost pad positions
        final double[] boxes;      // item box rows
        final double[][] shortcuts;// {fracA, fracB, bulge}

        Def(String name, float[][] wp, int laps, double aiBase, int weatherBias, double dayTime,
            double[] jumps, double[] pads, double[] boxes, double[][] shortcuts) {
            this.name = name; this.wp = wp; this.laps = laps; this.aiBase = aiBase;
            this.weatherBias = weatherBias; this.dayTime = dayTime;
            this.jumps = jumps; this.pads = pads; this.boxes = boxes; this.shortcuts = shortcuts;
        }
    }

    static final Def[] LEVELS = {
            new Def("1 · SUNSET BAY",
                    new float[][]{
                            {-1000, -250}, {-700, -560}, {-150, -620}, {380, -520},
                            {760, -250}, {1040, 120}, {760, 430}, {300, 360},
                            {40, 540}, {-420, 600}, {-840, 380}, {-1080, 60}
                    },
                    3, 22.0, 0, 0.20,
                    new double[]{0.30},
                    new double[]{0.06, 0.55, 0.84},
                    new double[]{0.18, 0.62},
                    new double[][]{{0.40, 0.55, 260}}),

            new Def("2 · NEON HEIGHTS",
                    new float[][]{
                            {-900, 0}, {-820, -480}, {-360, -700}, {120, -560},
                            {220, -160}, {640, -260}, {980, -40}, {860, 420},
                            {360, 560}, {-40, 360}, {-460, 600}, {-880, 420}
                    },
                    3, 24.5, 2, 0.52,
                    new double[]{0.22, 0.70},
                    new double[]{0.10, 0.46, 0.80},
                    new double[]{0.34, 0.66},
                    new double[][]{{0.46, 0.62, -300}}),

            new Def("3 · MIDNIGHT CANYON",
                    new float[][]{
                            {-1080, -120}, {-760, -520}, {-300, -420}, {-120, -700},
                            {360, -640}, {760, -360}, {560, -40}, {1000, 200},
                            {620, 520}, {120, 420}, {-180, 640}, {-720, 520}
                    },
                    4, 27.0, 1, 0.80,
                    new double[]{0.18, 0.50, 0.78},
                    new double[]{0.30, 0.64, 0.92},
                    new double[]{0.12, 0.44, 0.72},
                    new double[][]{{0.20, 0.34, 280}, {0.58, 0.72, -260}}),
    };

    private Tracks() {}
}
