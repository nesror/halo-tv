package com.kartikmodi.halo.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Brings the launcher up far enough for the Home Assistant bridge to answer right after a reboot.
 *
 * Without this the port only opens when Home is first shown or when the system binds HomeKeyService,
 * which can be a while after the TV wakes - Home Assistant then reports the TV as unreachable even
 * though the set is on. Nothing is shown here: the receiver only starts the bridge and the viewing
 * clock, and leaves the screen to whatever the TV puts on it.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.QUICKBOOT_POWERON":
            case "com.android.quickboot.poweron":
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                HaServer.ensureStarted(context);
                ViewingTime.init(context);
                break;
            default:
                break;
        }
    }
}
