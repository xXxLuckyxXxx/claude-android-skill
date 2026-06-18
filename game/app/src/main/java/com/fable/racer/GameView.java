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
        setFocusableInTouchMode(true);
        requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (game != null && event.getRepeatCount() == 0 && game.onKey(keyCode, true)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (game != null && game.onKey(keyCode, false)) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (game != null && game.onMotion(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) { w = 1280; h = 720; }
        game = new Game(getContext(), w, h);
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
        if (game != null) game.release();
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
            final long frameNs = 16_666_667L;           // 60 fps cadence
            long last = System.nanoTime();
            long nextFrame = last;
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

                // pace to an absolute schedule so frame spacing stays even (no drift)
                nextFrame += frameNs;
                long sleepNs = nextFrame - System.nanoTime();
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L)); }
                    catch (InterruptedException ignored) {}
                } else if (sleepNs < -frameNs * 3) {
                    nextFrame = System.nanoTime();        // fell far behind: resync
                }
            }
        }
    }
}
