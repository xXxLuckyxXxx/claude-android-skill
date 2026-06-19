package javax.microedition.khronos.egl;

/**
 * Compile-only stub for the subset of EGL10 used by MultisampleConfigChooser
 * (this android.jar omits the JSR-239 EGL package). The constant VALUES are the
 * standard EGL tokens, so the code behaves correctly against the real runtime
 * class. Compiled to a separate classpath dir and deliberately NOT dexed.
 */
public interface EGL10 {
    int EGL_ALPHA_SIZE = 0x3021;
    int EGL_BLUE_SIZE = 0x3022;
    int EGL_GREEN_SIZE = 0x3023;
    int EGL_RED_SIZE = 0x3024;
    int EGL_DEPTH_SIZE = 0x3025;
    int EGL_SAMPLES = 0x3031;
    int EGL_SAMPLE_BUFFERS = 0x3032;
    int EGL_NONE = 0x3038;
    int EGL_RENDERABLE_TYPE = 0x3040;

    boolean eglChooseConfig(EGLDisplay display, int[] attrib_list,
                            EGLConfig[] configs, int config_size, int[] num_config);
}
