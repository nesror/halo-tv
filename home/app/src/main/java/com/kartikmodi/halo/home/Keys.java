package com.kartikmodi.halo.home;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * The phone remote's buttons, sent to the TV.
 *
 * Android gives an ordinary app no way to press a key in another app: KEYCODE injection needs
 * INJECT_EVENTS, which only the system and the shell hold. So there are two paths, tried in order:
 *
 *  1. /system/bin/input keyevent N. It runs as this app, so it only works on firmware that does not
 *     enforce the permission check; when it does work every button behaves exactly like the remote.
 *  2. performGlobalAction on the accessibility service, for the handful of system-wide actions
 *     (back, home, recents, notifications). Those need HomeKeyService enabled, which this branch uses
 *     for the Home button anyway.
 *
 * Direction keys have no fallback: if (1) is refused, up/down/left/right report that honestly.
 */
final class Keys {

    private static final String TAG = "Keys";

    static final int UP = 19, DOWN = 20, LEFT = 21, RIGHT = 22, CENTER = 23;
    static final int BACK = 4, HOME = 3, MENU = 82, POWER = 26;
    static final int VOLUME_UP = 24, VOLUME_DOWN = 25;
    static final int MEDIA_PLAY_PAUSE = 85;

    private Keys() {
    }

    /** Presses one key. Returns null when the TV refused it, so the caller can report why. */
    static Boolean press(int keycode) {
        try {
            Process p = new ProcessBuilder("input", "keyevent", String.valueOf(keycode))
                    .redirectErrorStream(true)
                    .start();
            // Reading to the end is the wait: the command closes its output when it is done. (Process
            // .waitFor(timeout) is API 26, which this branch cannot use.)
            String output = read(p.getInputStream());
            boolean ok = p.waitFor() == 0 && !output.contains("Exception") && !output.contains("denied");
            if (!ok) Log.w(TAG, "input keyevent " + keycode + " refused: " + output.trim());
            return ok ? Boolean.TRUE : Boolean.FALSE;
        } catch (Exception e) {
            Log.w(TAG, "no input command on this TV", e);
            return Boolean.FALSE;
        }
    }

    /** A long press, for the power menu: down, wait, up. */
    static Boolean pressAndHold(int keycode, long ms) {
        try {
            Process p = new ProcessBuilder("input", "--longpress", String.valueOf(ms), "keyevent",
                    String.valueOf(keycode)).redirectErrorStream(true).start();
            String output = read(p.getInputStream());
            return p.waitFor() == 0 && !output.contains("Exception") ? Boolean.TRUE : Boolean.FALSE;
        } catch (Exception e) {
            Log.w(TAG, "no input command on this TV", e);
            return Boolean.FALSE;
        }
    }

    private static String read(InputStream in) {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int n;
            while ((n = stream.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** The system-wide actions, for buttons the input command could not deliver. */
    static boolean global(int action) {
        AccessibilityService service = HomeKeyService.running();
        if (service == null) return false;
        try {
            return service.performGlobalAction(action);
        } catch (RuntimeException e) {
            Log.w(TAG, "global action refused", e);
            return false;
        }
    }

}
