package com.kartikmodi.halo.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * Keeps the Home Assistant bridge up without an entry point to depend on.
 *
 * A reboot is not the only way the port goes away. This set is put into standby rather than shut
 * down, and standby is enough for the system to reclaim the launcher: the process dies, no boot
 * broadcast is sent on the way back, and 8765 stays closed until somebody presses Home. Installing
 * over adb makes it worse, because an install leaves the package flagged stopped, and a stopped
 * package receives no broadcast at all - not even BOOT_COMPLETED.
 *
 * So the bridge does not wait to be asked. Every call to {@link #start} opens the port and arms a
 * one-minute heartbeat; the heartbeat comes back as a broadcast to {@link BootReceiver}, which
 * starts this process again if it had died. Cost is a few milliseconds of CPU a minute on a set
 * that is plugged into the wall.
 */
public final class Watchdog {

    /** Broadcast the alarm sends. Delivered to {@link BootReceiver} by explicit component. */
    static final String ACTION_HEARTBEAT = "com.kartikmodi.halo.home.HEARTBEAT";

    private static final long EVERY_MS = 60_000L;

    private Watchdog() {
    }

    /** Open the bridge, start the viewing clock, and make sure the heartbeat is armed. */
    static void start(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        HaServer.ensureStarted(app);
        ViewingTime.init(app);
        arm(app);
    }

    private static void arm(Context app) {
        AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        Intent beat = new Intent(ACTION_HEARTBEAT).setClassName(app.getPackageName(),
                BootReceiver.class.getName());
        PendingIntent pending = PendingIntent.getBroadcast(app, 0, beat,
                PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            // Callers are frequent (every Home press, every service bind), and each one pushes the
            // next beat a minute out. That is the intent: while something is alive there is nothing
            // to recover, and a beat only has to arrive once everything else has stopped.
            alarms.setRepeating(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + EVERY_MS, EVERY_MS, pending);
        } catch (RuntimeException e) {
            android.util.Log.w("Watchdog", "could not arm heartbeat", e);
        }
    }
}
