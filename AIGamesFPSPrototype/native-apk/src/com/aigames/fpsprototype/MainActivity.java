package com.aigames.fpsprototype;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * Entry point. Hosts the GL view fullscreen in landscape, keeps the screen on,
 * and enables immersive mode.
 *
 * Note: this is compiled against an API-15 android.jar, so newer immersive
 * flags are referenced by their literal integer values (resolved by the OS on
 * the modern target device).
 */
public class MainActivity extends Activity {
    private FpsGLSurfaceView glView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        glView = new FpsGLSurfaceView(this);
        setContentView(glView);
        hideSystemUI();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    private void hideSystemUI() {
        int flags =
              0x00000100   // SYSTEM_UI_FLAG_LAYOUT_STABLE
            | 0x00000200   // SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | 0x00000400   // SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | 0x00000002   // SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | 0x00000004   // SYSTEM_UI_FLAG_FULLSCREEN
            | 0x00001000;  // SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        glView.setSystemUiVisibility(flags);
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        glView.onPause();
    }
}
