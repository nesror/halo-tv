package com.kartikmodi.halo.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything Home Assistant asks of the TV, in one place: what the TV is doing, a message to put on it,
 * and the handful of things it can be told to do (pause, screen off, volume...).
 *
 * Reached over the launcher's own little web server (HaServer, port 8765), so there is nothing new to
 * open on the TV and nothing to keep alive: the launcher's own process answers.
 */
final class HaBridge {

    private static final String TAG = "HaBridge";

    private HaBridge() {
    }

    /** What this TV is, for the integration to show on its device page. */
    static JSONObject info(Context app) {
        JSONObject o = new JSONObject();
        try {
            o.put("app", "Halo TV");
            o.put("version", version(app));
            o.put("model", Build.MODEL);
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("android", Build.VERSION.RELEASE.isEmpty() ? "API " + Build.VERSION.SDK_INT : Build.VERSION.RELEASE);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("port", HaServer.PORT);
            o.put("features", new JSONArray()
                    .put("notify").put("tts").put("image").put("screen").put("playback")
                    .put("volume").put("viewing_time"));
            // Both are granted over ADB by install.sh; without them the TV still works, with less.
            o.put("overlay", Settings.canDrawOverlays(app));
            o.put("media_sessions", HaMediaListener.granted(app));
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** What the TV is doing right now. Polled every few seconds. */
    static JSONObject state(Context app) throws JSONException {
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        int max = audio == null ? 0 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int level = audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);

        JSONObject s = new JSONObject();
        s.put("screen", ScreenPower.isOff() || !ScreenPower.interactive(app) ? "off" : "on");
        s.put("screen_mode", ScreenPower.mode(app));
        s.put("screen_since", ScreenPower.since());
        s.put("message", MessageActivity.state());
        s.put("media", Playback.nowPlaying(app));
        s.put("volume", max == 0 ? 0 : Math.round(level * 100f / max));
        s.put("volume_level", max == 0 ? 0 : Math.round(level * 1000f / max) / 1000.0);
        s.put("volume_raw", level);
        s.put("volume_max", max);
        s.put("muted", audio != null && audio.isStreamMute(AudioManager.STREAM_MUSIC));
        s.put("overlay", Settings.canDrawOverlays(app));
        s.put("viewing", ViewingTime.brief(app));
        s.put("ts", System.currentTimeMillis());
        return s;
    }

    /** A message to show (and maybe read out). A "command" field may ride along in the same call. */
    static JSONObject notify(Context app, JSONObject request) throws JSONException {
        MessageActivity.show(app, request);
        JSONObject out = new JSONObject().put("ok", true);
        // The command runs after the message is up, so a command that takes the screen away ("熄屏")
        // has the last word rather than being covered by the message it arrived with.
        String command = request.optString("command", "");
        if (!command.isEmpty()) {
            out.put("command", command(app, new JSONObject().put("action", command)));
        }
        // False means the message may not appear over another app on this TV; see install.sh.
        out.put("overlay", Settings.canDrawOverlays(app));
        return out;
    }

    /** pause, play, play_pause, stop, next, prev, screen_off, screen_on, screen_toggle, home,
     *  dismiss, say, volume_set, volume_mute, mute, unmute, volume_up, volume_down, notify. */
    static JSONObject command(Context app, JSONObject request) throws JSONException {
        String action = request.optString("action", request.optString("command", request.optString("do", "")))
                .trim().toLowerCase(Locale.US).replace('-', '_').replace(' ', '_');
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        boolean ok = true;

        switch (action) {
            case "pause": case "play": case "play_pause": case "stop": case "next": case "prev":
                ok = Playback.control(app, action);
                break;
            case "screen_off": case "off": case "sleep": case "standby":
                ScreenPower.off(app);
                break;
            case "screen_on": case "on": case "wake":
                ScreenPower.on(app);
                break;
            case "screen_toggle": case "power": case "toggle":
                ScreenPower.toggle(app);
                break;
            case "home": case "launcher":
                openHome(app);
                break;
            case "dismiss": case "clear":
                MessageActivity.dismissAll();
                break;
            case "say": case "speak":
                Speech.say(app, request.optString("text", request.optString("message", "")),
                        request.optString("language", ""));
                break;
            case "stop_speech":
                Speech.stop();
                break;
            // Viewing time is configured over HTTP (/api/tvtime/config); these two are the shortcuts
            // an automation is most likely to want.
            case "viewing_reset":
                ViewingTime.reset(app);
                break;
            case "viewing_off": case "viewing_disable":
                ViewingTime.configure(app, new JSONObject().put("enabled", false));
                break;
            case "viewing_on": case "viewing_enable":
                ViewingTime.configure(app, new JSONObject().put("enabled", true));
                break;
            case "volume_set": case "set_volume":
                setVolume(audio, request);
                break;
            case "volume_up": case "louder":
                adjust(audio, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                break;
            case "volume_down": case "quieter":
                adjust(audio, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                break;
            case "mute": case "volume_mute":
                adjust(audio, AudioManager.ADJUST_MUTE, 0);
                break;
            case "unmute":
                adjust(audio, AudioManager.ADJUST_UNMUTE, 0);
                break;
            case "notify": case "message":
                notify(app, request);
                break;
            default:
                ok = false;
                break;
        }
        return new JSONObject().put("ok", ok).put("action", action).put("state", state(app));
    }

    // ---- The phone remote's buttons.

    /** The key each button maps to, and what to try when the TV refuses to let a key be injected. */
    private static int keycode(String name) {
        switch (name) {
            case "up": return Keys.UP;
            case "down": return Keys.DOWN;
            case "left": return Keys.LEFT;
            case "right": return Keys.RIGHT;
            case "ok": case "center": case "enter": case "select": return Keys.CENTER;
            case "back": return Keys.BACK;
            case "menu": return Keys.MENU;
            case "info": return 165;
            case "recents": case "app_switch": return 187;
            case "power": return Keys.POWER;
            case "volume_up": return Keys.VOLUME_UP;
            case "volume_down": return Keys.VOLUME_DOWN;
            case "play_pause": return Keys.MEDIA_PLAY_PAUSE;
            default: return -1;
        }
    }

    /** The same key as the set's own remote channel names it, or null when it has no such button. */
    private static String miTvName(String name) {
        switch (name) {
            case "up": case "down": case "left": case "right": return name;
            case "ok": case "center": case "enter": case "select": return "enter";
            case "back": return "back";
            case "menu": return "menu";
            case "home": return "home";
            case "volume_up": return "volumeup";
            case "volume_down": return "volumedown";
            default: return null;
        }
    }

    /** The system-wide equivalent, for firmware that refuses injected keys. -1 where there is none. */
    private static int globalAction(String name) {
        switch (name) {
            case "back": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK;
            case "home": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME;
            case "recents": case "app_switch":
                return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS;
            case "notifications":
                return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
            default: return -1;
        }
    }

    /**
     * One button of the remote. Keys first: they behave exactly like the remote, and only the shell
     * may inject them, so a set that refuses falls back to the accessibility service for the actions
     * it can perform. "via" says which path answered, so the page can explain a refusal.
     */
    static JSONObject key(Context app, JSONObject request) throws JSONException {
        String name = request.optString("key", "").trim().toLowerCase(Locale.ROOT);
        JSONObject o = new JSONObject().put("key", name).put("ok", false).put("via", "");

        if ("home".equals(name)) {
            openHome(app);
            return o.put("ok", true).put("via", "launcher").put("state", state(app));
        }
        if ("power".equals(name)) {
            // Injected power keys are accepted and ignored on this firmware; the set's own remote
            // channel is the only one that puts it to standby.
            if (MiTv.power()) {
                return o.put("ok", true).put("via", "mitv")
                        .put("note", "电视已进入待机，需要用实体遥控器或叫小爱才能再打开");
            }
            return o.put("state", state(app)).put("reason", "电视的遥控服务没有应答，关不了机");
        }

        int code = keycode(name);
        if (code > 0 && Boolean.TRUE.equals(Keys.press(code))) {
            return o.put("ok", true).put("via", "input").put("state", state(app));
        }
        String miKey = miTvName(name);
        if (miKey != null && MiTv.press(miKey)) {
            return o.put("ok", true).put("via", "mitv").put("state", state(app));
        }
        int action = globalAction(name);
        if (action > 0 && Keys.global(action)) {
            return o.put("ok", true).put("via", "accessibility").put("state", state(app));
        }
        return o.put("state", state(app))
                .put("reason", "这个电视不允许模拟按键（需要系统权限），也没能由无障碍服务代做");
    }

    // ---- The phone remote: what can be opened, and opening it.

    /** Every app the remote may open, as the launcher sees them: no duplicates, no hidden apps, no Home. */
    static JSONObject apps(Context app) throws JSONException {
        Config cfg = Config.load(app);
        PackageManager pm = app.getPackageManager();
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        // MIUI TV and other firmware ship phone-style APKs with no leanback entry at all.
        found.addAll(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));

        Map<String, String> byPackage = new LinkedHashMap<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(app.getPackageName()) || cfg.hiddenApps.contains(pkg) || byPackage.containsKey(pkg)) continue;
            if (TvApps.launchIntent(app, pkg) == null) continue;   // listed but nothing opens it
            String name = cfg.appLabels.get(pkg);
            if (name == null) name = String.valueOf(ri.loadLabel(pm)).trim();
            byPackage.put(pkg, name.isEmpty() ? pkg : name);
        }

        List<Map.Entry<String, String>> entries = new ArrayList<>(byPackage.entrySet());
        Collections.sort(entries, (a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
        JSONArray list = new JSONArray();
        for (Map.Entry<String, String> e : entries) {
            list.put(new JSONObject().put("package", e.getKey()).put("label", e.getValue()));
        }
        return new JSONObject().put("apps", list);
    }

    /** Opens one app on the TV; the phone remote's tiles are all this. */
    static JSONObject launch(Context app, JSONObject request) throws JSONException {
        String pkg = request.optString("package", "").trim();
        Intent i = TvApps.launchIntent(app, pkg);
        boolean ok = false;
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                app.startActivity(i);
                ok = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "could not open " + pkg, e);
            }
        }
        return new JSONObject().put("ok", ok).put("package", pkg).put("state", state(app));
    }

    private static void setVolume(AudioManager audio, JSONObject request) {
        if (audio == null) return;
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target;
        if (request.has("level")) {
            target = Math.round((float) request.optDouble("level", 0.5) * max);   // 0..1, as Home Assistant has it
        } else {
            target = Math.round(request.optInt("volume", 50) * max / 100f);       // 0..100
        }
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, target)), 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "the TV refused a volume change", e);
        }
    }

    private static void adjust(AudioManager audio, int direction, int flags) {
        if (audio == null) return;
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, flags);
        } catch (RuntimeException e) {
            Log.w(TAG, "the TV refused a volume change", e);
        }
    }

    private static void openHome(Context app) {
        try {
            app.startActivity(new Intent(app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (RuntimeException ignored) {
        }
    }

    private static String version(Context app) {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
