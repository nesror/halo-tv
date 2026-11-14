package com.kartikmodi.halo.home;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Glass Home's "video" backdrop: the motion video looping straight onto the TV's video hardware layer, with
 * no OpenGL in between. The glass above is plain (no bending), which costs far less than the lens.
 *
 * <p>On firmware where the video never starts (a decoder that refuses this file, a surface that arrives
 * after Home has painted), the backdrop would be a black screen instead of the gradient behind it, so the
 * view takes itself out of the window after a few seconds and leaves the gradient in place.
 *
 * <p>The player is rebuilt whenever the surface comes back, which is what returning from another app does.
 * API 23 firmware does not reconnect a detached render surface: the decoder stays bound to the surface that
 * died and plays on with nothing on screen, which left Home black until the launcher was restarted.
 */
final class MotionVideo extends SurfaceView implements SurfaceHolder.Callback {

    private static final long GIVE_UP_MS = 4000;

    private final File video;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable giveUp;
    private FileInputStream stream;
    private MediaPlayer player;
    private boolean prepared, wanted, gone;
    private Runnable onFailed;

    MotionVideo(Context context, File video, Runnable onFailed) {
        super(context);
        this.video = video;
        this.onFailed = onFailed;
        this.giveUp = () -> {
            if (!prepared) fail();
        };
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        getHolder().addCallback(this);
    }

    void play() {
        wanted = true;
        if (gone) return;
        if (player == null) {
            // No surface yet, or it was dropped while paused: build the player as soon as there is one.
            startIfSurfaceReady();
            return;
        }
        if (!prepared) return;
        try {
            if (!player.isPlaying()) player.start();
        } catch (IllegalStateException e) {
            // The surface went away without surfaceDestroyed; rebuild rather than play to nothing.
            stopPlayer();
            startIfSurfaceReady();
        }
    }

    void pause() {
        wanted = false;
        if (player == null || !prepared) return;
        try {
            if (player.isPlaying()) player.pause();
        } catch (IllegalStateException ignored) {
        }
    }

    void release() {
        wanted = false;
        gone = true;
        stopPlayer();
    }

    private void startIfSurfaceReady() {
        SurfaceHolder holder = getHolder();
        if (holder.getSurface() != null && holder.getSurface().isValid()) start(holder);
    }

    /** Drops the player and its stream: its surface is gone, and a new one needs a new player. */
    private void stopPlayer() {
        prepared = false;
        handler.removeCallbacks(giveUp);
        if (player != null) {
            try {
                player.setDisplay(null);
            } catch (RuntimeException ignored) {
            }
            player.release();
            player = null;
        }
        closeStream();
    }

    private void fail() {
        if (gone) return;
        Log.w("MotionVideo", "no frames from " + video.getName() + ": falling back to the gradient");
        release();
        Runnable callback = onFailed;
        onFailed = null;
        if (callback != null) callback.run();
    }

    private void closeStream() {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
        }
        stream = null;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (gone || player != null) return;
        start(holder);
    }

    /** Builds the player for {@code holder}; ends in fail() when the decoder cannot be set up. */
    private void start(SurfaceHolder holder) {
        try {
            prepared = false;
            player = new MediaPlayer();
            // Hand MediaPlayer a file descriptor opened by this process instead of a path: mediaserver
            // cannot read a file another uid owns under /sdcard on API 23 (EACCES at setDataSource), while
            // the launcher itself can.
            stream = new FileInputStream(video);
            player.setDataSource(stream.getFD());
            player.setLooping(true);
            player.setVolume(0f, 0f);
            player.setOnPreparedListener(p -> {
                handler.removeCallbacks(giveUp);
                prepared = true;
                if (wanted) p.start();
            });
            player.setOnErrorListener((p, what, extra) -> {
                fail();
                return true;
            });
            player.setDisplay(holder);
            player.prepareAsync();
            handler.postDelayed(giveUp, GIVE_UP_MS);
        } catch (Exception e) {
            Log.w("MotionVideo", "motion video failed", e);
            fail();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // This player's surface is gone; keep no half-attached player behind for the next one to inherit.
        stopPlayer();
    }
}
