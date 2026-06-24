package com.fable.racer;

/** Small math helpers for the pseudo-3D projection and track building. */
final class Util {
    private Util() {}

    static double limit(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    static double interpolate(double a, double b, double percent) {
        return a + (b - a) * percent;
    }

    static double accelerate(double v, double accel, double dt) {
        return v + accel * dt;
    }

    /** Remaining fraction of n within a segment of length total. */
    static double percentRemaining(double n, double total) {
        return (n % total) / total;
    }

    static double easeIn(double a, double b, double p) {
        return a + (b - a) * Math.pow(p, 2);
    }

    static double easeOut(double a, double b, double p) {
        return a + (b - a) * (1 - Math.pow(1 - p, 2));
    }

    static double easeInOut(double a, double b, double p) {
        return a + (b - a) * (-Math.cos(p * Math.PI) / 2 + 0.5);
    }

    static double exponentialFog(double distance, double density) {
        return 1 / Math.pow(Math.E, distance * distance * density);
    }

    /** Loop a position value within [0, max). */
    static double increase(double start, double increment, double max) {
        double result = start + increment;
        while (result >= max) result -= max;
        while (result < 0) result += max;
        return result;
    }
}
