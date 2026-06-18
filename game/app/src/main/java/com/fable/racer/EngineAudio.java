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

    // procedural synthwave music layer
    private volatile boolean musicOn;
    private volatile float musicLevel = 0.5f;
    private int musStep;
    private double musStepPos, bassPh, arpPh, kickPh, kickEnv, hatEnv, arpEnv;
    private static final int[] BASS = {0, 0, 3, 0, 5, 5, 3, 0, 0, 0, 7, 0, 5, 3, 2, 0};
    private static final int[] ARP = {12, 15, 19, 24, 19, 15, 12, 15, 12, 17, 20, 24, 20, 17, 15, 12};

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

    void setMusic(boolean on, float level) { this.musicOn = on; this.musicLevel = level; }

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

                    // procedural synthwave music
                    if (musicOn && musicLevel > 0.01f) {
                        double sps = RATE * 60.0 / 132.0 / 4.0;
                        musStepPos += 1;
                        if (musStepPos >= sps) {
                            musStepPos -= sps;
                            musStep = (musStep + 1) % 16;
                            if (musStep % 4 == 0) { kickEnv = 1.0; kickPh = 0; }
                            hatEnv = 0.7;
                            arpEnv = 1.0;
                        }
                        double bassF = 55.0 * Math.pow(2, BASS[musStep] / 12.0);
                        bassPh += 2 * Math.PI * bassF / RATE;
                        double bass = (((bassPh / (2 * Math.PI)) % 1) * 2 - 1) * 0.5;
                        double arpF = 110.0 * Math.pow(2, ARP[musStep] / 12.0);
                        arpPh += 2 * Math.PI * arpF / RATE;
                        double arp = (Math.sin(arpPh) * 0.6 + Math.sin(arpPh * 2) * 0.2) * 0.3 * arpEnv;
                        arpEnv *= 0.9997;
                        kickPh += 2 * Math.PI * (45 + 90 * kickEnv) / RATE;
                        double kick = Math.sin(kickPh) * kickEnv;
                        kickEnv *= 0.9992;
                        double hat = (Math.random() * 2 - 1) * hatEnv * 0.15;
                        hatEnv *= 0.985;
                        sample += (bass * 0.5 + arp + kick * 0.8 + hat) * 0.22 * musicLevel;
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
