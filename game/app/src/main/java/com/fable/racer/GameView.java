package com.fable.racer;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** Hosts the game loop on its own thread and forwards touch input. */
public class GameView extends SurfaceView implements SurfaceHolder.Callback {

    private GameThread thread;
    private Game game;

    public GameView(Context context) {
        super(context);
        getHolder().addCallback(this);
        setFocusable(true);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) { w = 1280; h = 720; }
        game = new Game(w, h);
        thread = new GameThread(holder, game);
        thread.setRunning(true);
        thread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (game != null && width > 0 && height > 0) game.resize(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (thread != null) {
            thread.setRunning(false);
            boolean retry = true;
            while (retry) {
                try {
                    thread.join();
                    retry = false;
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            thread = null;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (game != null) game.handleTouch(event);
        return true;
    }

    /** Fixed-step-ish loop that renders as fast as the surface allows. */
    static final class GameThread extends Thread {
        private final SurfaceHolder holder;
        private final Game game;
        private volatile boolean running;

        GameThread(SurfaceHolder holder, Game game) {
            this.holder = holder;
            this.game = game;
        }

        void setRunning(boolean r) { running = r; }

        @Override
        public void run() {
            long last = System.nanoTime();
            while (running) {
                long now = System.nanoTime();
                double dt = (now - last) / 1_000_000_000.0;
                last = now;
                if (dt > 0.05) dt = 0.05; // clamp after stalls

                game.update(dt);

                Canvas c = null;
                try {
                    c = holder.lockCanvas();
                    if (c != null) {
                        synchronized (holder) {
                            game.render(c);
                        }
                    }
                } finally {
                    if (c != null) holder.unlockCanvasAndPost(c);
                }

                // aim for ~60fps
                long frameMs = (System.nanoTime() - now) / 1_000_000;
                long sleep = 16 - frameMs;
                if (sleep > 0) {
                    try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
                }
            }
        }
    }
}
