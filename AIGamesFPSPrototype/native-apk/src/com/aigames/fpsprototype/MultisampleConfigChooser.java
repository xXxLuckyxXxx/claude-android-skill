package com.aigames.fpsprototype;

import android.opengl.GLSurfaceView;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/**
 * Picks an RGB888 + depth EGL config with MSAA (anti-aliasing) when available,
 * trying {@code samples}x then 2x, and falling back to a plain config if the
 * device offers none — so it never returns null on capable hardware.
 */
class MultisampleConfigChooser implements GLSurfaceView.EGLConfigChooser {

    private static final int EGL_OPENGL_ES2_BIT = 0x0004;
    private final int samples;
    private final int[] count = new int[1];

    MultisampleConfigChooser(int samples) {
        this.samples = samples;
    }

    @Override
    public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
        // A 24-bit depth buffer first: 16-bit quantisation makes nearly-coplanar surfaces (window
        // pane vs. the dark reveal behind it) z-fight into crawling moiré stripes at a distance.
        EGLConfig c = tryChoose(egl, display, samples, 24);
        if (c == null && samples > 2) c = tryChoose(egl, display, 2, 24);
        if (c == null) c = tryChoose(egl, display, samples, 16);
        if (c == null && samples > 2) c = tryChoose(egl, display, 2, 16);
        if (c == null) c = tryChoose(egl, display, 0, 24);   // no MSAA fallback
        if (c == null) c = tryChoose(egl, display, 0, 16);
        return c;
    }

    private EGLConfig tryChoose(EGL10 egl, EGLDisplay display, int s, int depth) {
        int[] spec;
        if (s > 0) {
            spec = new int[] {
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 0, EGL10.EGL_DEPTH_SIZE, depth,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, s,
                EGL10.EGL_NONE
            };
        } else {
            spec = new int[] {
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 0, EGL10.EGL_DEPTH_SIZE, depth,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_NONE
            };
        }
        if (!egl.eglChooseConfig(display, spec, null, 0, count) || count[0] <= 0) return null;
        EGLConfig[] configs = new EGLConfig[count[0]];
        if (!egl.eglChooseConfig(display, spec, configs, count[0], count)) return null;
        return configs[0];
    }
}
