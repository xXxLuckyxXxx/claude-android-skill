package com.fable.racer;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * Tiny procedural audio engine. Streams a synthesized engine tone whose pitch
 * tracks the car's RPM, plus short one-shot blips for pickups, boost and skids.
 * Everything is wrapped in try/catch so a device without audio simply stays
 * silent instead of crashing the game.
 */
final class EngineAudio {

    static final int BLIP_BOOST = 1;
    static final int BLIP_PICKUP = 2;
    static final int BLIP_HIT = 3;
    static final int BLIP_COUNT = 4;
    static final int BLIP_GO = 5;

    private static final int RATE = 22050;

    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;
    boolean enabled = true;

    private volatile float rpm = 0.05f;   // 0..1 engine load
    private volatile float vol = 0f;      // master 0..1
    private volatile boolean boosting;
    private volatile float skid = 0f;     // 0..1 tyre screech amount

    private double ph1, ph2, ph3, phNoise;
    private volatile int blipReq = 0;

    // one-shot effect oscillator
    private int fxLeft = 0;
    private double fxPhase, fxFreq, fxFreq2;
    private float fxAmp, fxDecay;

    EngineAudio() {
        try {
            int min = AudioTrack.getMinBufferSize(RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buf = Math.max(min, RATE / 5 * 2);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    buf, AudioTrack.MODE_STREAM);
            track.play();
            running = true;
            thread = new Thread(new Runnable() {
                public void run() { loop(); }
            }, "engine-audio");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            enabled = false;
        }
    }

    void set(float rpm, float vol, boolean boosting, float skid) {
        this.rpm = rpm;
        this.vol = vol;
        this.boosting = boosting;
        this.skid = skid;
    }

    void blip(int type) { blipReq = type; }

    void release() {
        running = false;
        try { if (thread != null) thread.join(200); } catch (InterruptedException ignored) {}
        try { if (track != null) { track.stop(); track.release(); } } catch (Throwable ignored) {}
    }

    private void startFx(int type) {
        switch (type) {
            case BLIP_BOOST: fxFreq = 180; fxFreq2 = 90; fxAmp = 0.5f; fxDecay = 0.99985f; fxLeft = 9000; break;
            case BLIP_PICKUP: fxFreq = 880; fxFreq2 = 1320; fxAmp = 0.4f; fxDecay = 0.9994f; fxLeft = 4000; break;
            case BLIP_HIT: fxFreq = 120; fxFreq2 = 70; fxAmp = 0.6f; fxDecay = 0.9990f; fxLeft = 3000; break;
            case BLIP_COUNT: fxFreq = 600; fxFreq2 = 600; fxAmp = 0.45f; fxDecay = 0.9992f; fxLeft = 3500; break;
            case BLIP_GO: fxFreq = 1000; fxFreq2 = 1500; fxAmp = 0.5f; fxDecay = 0.99975f; fxLeft = 8000; break;
            default: fxLeft = 0;
        }
        fxPhase = 0;
    }

    private void loop() {
        short[] buf = new short[1024];
        try {
            while (running) {
                for (int i = 0; i < buf.length; i++) {
                    double f = 42 + rpm * 235 + (boosting ? 70 : 0);
                    ph1 += 2 * Math.PI * f / RATE;
                    ph2 += 2 * Math.PI * (f * 2.01) / RATE;
                    ph3 += 2 * Math.PI * (f * 0.5) / RATE;
                    double saw = ((ph1 / (2 * Math.PI)) % 1) * 2 - 1;
                    double s = saw * 0.45 + Math.sin(ph2) * 0.12 + Math.sin(ph3) * 0.30;
                    if (boosting) s += (Math.random() * 2 - 1) * 0.18;
                    double amp = (0.10 + rpm * 0.55) * vol;
                    double sample = s * amp;

                    // tyre screech: filtered noise
                    if (skid > 0.01f) {
                        phNoise = phNoise * 0.6 + (Math.random() * 2 - 1) * 0.4;
                        sample += phNoise * 0.25 * skid;
                    }

                    if (fxLeft <= 0 && blipReq != 0) { startFx(blipReq); blipReq = 0; }
                    if (fxLeft > 0) {
                        fxPhase += 2 * Math.PI * fxFreq / RATE;
                        double e = fxAmp;
                        sample += (Math.sin(fxPhase) * 0.6 + Math.sin(fxPhase * (fxFreq2 / fxFreq)) * 0.4) * e;
                        fxAmp *= fxDecay;
                        fxLeft--;
                    }

                    if (sample > 1) sample = 1;
                    if (sample < -1) sample = -1;
                    buf[i] = (short) (sample * 32767 * 0.85);
                }
                track.write(buf, 0, buf.length);
            }
        } catch (Throwable t) {
            // stop silently
        }
    }
}
