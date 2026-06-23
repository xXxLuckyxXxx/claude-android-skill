package com.aigames.fpsprototype;

import android.content.Context;
import android.media.AudioManager;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Tiny procedural sound effects — no asset files. Synthesizes short PCM clips,
 * writes them as WAVs into the app cache, and plays them through SoundPool.
 * Everything is guarded so any audio failure leaves the game silent, never
 * broken.
 */
class Sfx {
    private static final int SR = 22050;

    private SoundPool pool;
    private int sShoot, sHit, sHead, sOver, sReload;
    private boolean ok = false;

    Sfx(Context ctx) {
        try {
            pool = new SoundPool(6, AudioManager.STREAM_MUSIC, 0);
            sShoot  = load(ctx, "sfx_shoot",  genShoot());
            sHit    = load(ctx, "sfx_hit",    genBlip(680f, 0.07f, 0f));
            sHead   = load(ctx, "sfx_head",   genBlip(900f, 0.13f, 700f));
            sOver   = load(ctx, "sfx_over",   genSweep(540f, 130f, 0.55f));
            sReload = load(ctx, "sfx_reload", genBlip(320f, 0.10f, 480f));
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
    }

    void shoot()  { play(sShoot, 0.6f); }
    void hit()    { play(sHit, 0.9f); }
    void head()   { play(sHead, 1.0f); }
    void over()   { play(sOver, 0.9f); }
    void reload() { play(sReload, 0.7f); }

    private void play(int id, float vol) {
        if (ok && id != 0 && pool != null) {
            try { pool.play(id, vol, vol, 1, 0, 1f); } catch (Throwable t) { }
        }
    }

    private int load(Context ctx, String name, short[] pcm) throws Exception {
        File f = new File(ctx.getCacheDir(), name + ".wav");
        writeWav(f, pcm);
        return pool.load(f.getAbsolutePath(), 1);
    }

    // --- synthesis ---

    private static short[] genShoot() {
        int n = (int) (SR * 0.09f);
        short[] d = new short[n];
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < n; i++) {
            float t = (float) i / n;
            float env = (float) Math.exp(-6.0 * t);
            float noise = r.nextFloat() * 2f - 1f;
            float chirp = (float) Math.sin(2 * Math.PI * (900 - 700 * t) * i / SR);
            d[i] = (short) (clamp((noise * 0.5f + chirp * 0.6f) * env) * 32000);
        }
        return d;
    }

    private static short[] genBlip(float freq, float dur, float sweepTo) {
        int n = (int) (SR * dur);
        short[] d = new short[n];
        for (int i = 0; i < n; i++) {
            float t = (float) i / n;
            float env = (float) Math.sin(Math.PI * t);          // attack/decay
            float f = sweepTo > 0f ? freq + (sweepTo - freq) * t : freq;
            d[i] = (short) (clamp((float) Math.sin(2 * Math.PI * f * i / SR) * env) * 30000);
        }
        return d;
    }

    private static short[] genSweep(float f0, float f1, float dur) {
        int n = (int) (SR * dur);
        short[] d = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / n;
            float f = f0 + (f1 - f0) * t;
            phase += 2 * Math.PI * f / SR;
            float env = (float) Math.exp(-2.5 * t);
            d[i] = (short) (clamp((float) Math.sin(phase) * env) * 30000);
        }
        return d;
    }

    private static float clamp(float v) { return v < -1f ? -1f : (v > 1f ? 1f : v); }

    private static void writeWav(File f, short[] pcm) throws Exception {
        int dataLen = pcm.length * 2;
        FileOutputStream o = new FileOutputStream(f);
        try {
            byte[] h = new byte[44];
            putStr(h, 0, "RIFF"); putInt(h, 4, 36 + dataLen); putStr(h, 8, "WAVE");
            putStr(h, 12, "fmt "); putInt(h, 16, 16); putShort(h, 20, (short) 1);
            putShort(h, 22, (short) 1); putInt(h, 24, SR); putInt(h, 28, SR * 2);
            putShort(h, 32, (short) 2); putShort(h, 34, (short) 16);
            putStr(h, 36, "data"); putInt(h, 40, dataLen);
            o.write(h);
            byte[] b = new byte[dataLen];
            for (int i = 0; i < pcm.length; i++) {
                b[i * 2] = (byte) (pcm[i] & 0xff);
                b[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xff);
            }
            o.write(b);
        } finally {
            o.close();
        }
    }

    private static void putStr(byte[] b, int o, String s) { for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i); }
    private static void putInt(byte[] b, int o, int v) { b[o] = (byte) (v & 0xff); b[o + 1] = (byte) ((v >> 8) & 0xff); b[o + 2] = (byte) ((v >> 16) & 0xff); b[o + 3] = (byte) ((v >> 24) & 0xff); }
    private static void putShort(byte[] b, int o, short v) { b[o] = (byte) (v & 0xff); b[o + 1] = (byte) ((v >> 8) & 0xff); }
}
