package com.kartikmodi.halo.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Brings the launcher up far enough for the Home Assistant bridge to answer.
 *
 * Two ways in, and it needs both. {@link Intent#ACTION_BOOT_COMPLETED} covers a real reboot. The
 * heartbeat from {@link Watchdog} covers everything else - standby, a process the system reclaimed,
 * an install that left the package flagged stopped - which on this set is the ordinary case. Doing
 * the work twice is harmless: the second call finds the port already open.
 *
 * Nothing is shown here: the receiver only starts the bridge and the viewing clock, and leaves the
 * screen to whatever the TV puts on it.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        switch (action) {            case Watchdog.ACTION_HEARTBEAT:
            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.QUICKBOOT_POWERON":
            case "com.android.quickboot.poweron":
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                android.util.Log.i("Watchdog", "starting the bridge on " + action);
                Watchdog.start(context);
                break;
            default:
                break;
        }
    }
}
