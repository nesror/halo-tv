package com.kartikmodi.halo.home;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * The screen, turned off and on again, for Home Assistant.
 *
 * A truly dark screen needs PowerManager.goToSleep(), which only a system app may call. When that is
 * refused - which is every normal install - the screen is covered by a black window at zero brightness
 * instead. That window, unlike the Ambient screen, does not ask the TV to stay awake, so the TV's own
 * sleep timer carries on from there and the panel really does go dark. Either way any key on the remote
 * brings the picture back, so a message from Home Assistant can never leave the TV black.
 */
final class ScreenPower {

    private static final String TAG = "ScreenPower";

    /** Home Assistant asked for the screen to be off and nothing has brought it back yet. */
    private static volatile boolean off;
    /** The TV really went to sleep, rather than being covered by the black window. */
    private static volatile boolean slept;
    private static volatile long since;

    private ScreenPower() {
    }

    static boolean isOff() {
        return off;
    }

    /** awake, dim (black window) or sleep (the TV itself is asleep). */
    static String mode(Context context) {
        if (!off) return interactive(context) ? "awake" : "sleep";
        return slept ? "sleep" : "dim";
    }

    static long since() {
        return since;
    }

    static void off(Context context) {
        Context app = context.getApplicationContext();
        if (off) return;
        off = true;
        since = System.currentTimeMillis();
        slept = sleep(app);
        if (!slept) showBlack(app);
    }

    static void on(Context context) {
        Context app = context.getApplicationContext();
        if (!off && interactive(app)) return;
        off = false;
        since = 0;
        ScreenOffActivity a = ScreenOffActivity.current;
        if (a != null) a.dismiss();
        wake(app);
    }

    static void toggle(Context context) {
        if (isOff()) on(context); else off(context);
    }

    /** The black window went away on its own (a key on the remote): the TV is awake and in charge again. */
    static void escaped() {
        off = false;
        slept = false;
        since = 0;
    }

    static boolean interactive(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getApplicationContext()
                    .getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ---- Both calls are hidden from the SDK and need DEVICE_POWER, so they are made by reflection and
    // are allowed to fail: a sideloaded launcher simply does not have that permission.

    private static boolean sleep(Context app) {
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return false;
            Method m = PowerManager.class.getMethod("goToSleep", long.class);
            m.invoke(pm, SystemClock.uptimeMillis());
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "the TV will not sleep for us; covering the screen instead");
            return false;
        }
    }

    private static void wake(Context app) {
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                Method m = PowerManager.class.getMethod("wakeUp", long.class);
                m.invoke(pm, SystemClock.uptimeMillis());
            }
        } catch (Throwable ignored) {
        }
        // Whichever way it went, an activity that asks to turn the screen on is the one way a normal app
        // can light a sleeping panel.
        try {
            app.startActivity(new Intent(app, WakeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not wake the screen", e);
        }
    }

    private static void showBlack(Context app) {
        if (ScreenOffActivity.current != null) return;
        try {
            app.startActivity(new Intent(app, ScreenOffActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not cover the screen", e);
            off = false;
        }
    }
}
