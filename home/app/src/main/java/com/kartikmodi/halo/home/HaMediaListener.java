package com.kartikmodi.halo.home;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

/**
 * A notification listener that exists to be empty.
 *
 * MediaSessionManager.getActiveSessions() only answers an app that is an enabled notification listener,
 * and that is the one honest way for Home Assistant to learn what is playing - title, artist, artwork -
 * and to pause it without guessing. Nothing here reads or touches notifications; the install script
 * switches the listener on over ADB, and without it Halo TV falls back to sending media keys.
 */
public class HaMediaListener extends NotificationListenerService {

    /** Settings.Secure key holding every enabled listener, as the system writes it. */
    private static final String ENABLED_KEY = "enabled_notification_listeners";

    static ComponentName component(Context context) {
        return new ComponentName(context.getApplicationContext(), HaMediaListener.class);
    }

    static boolean granted(Context context) {
        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(), ENABLED_KEY);
            return enabled != null && enabled.contains(context.getPackageName());
        } catch (RuntimeException e) {
            return false;
        }
    }
}
