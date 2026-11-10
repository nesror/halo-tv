package com.kartikmodi.halo.home;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Everything Home Assistant asks of the TV, in one place: what the TV is doing, a message to put on it,
 * and the handful of things it can be told to do (pause, screen off, volume...).
 *
 * Reached over the little web server the phone remote already uses (RemoteServer, port 8765), so there is
 * nothing new to open on the TV and nothing to keep alive: the launcher's own process answers.
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
            o.put("port", RemoteServer.PORT);
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
        s.put("ambient", AmbientActivity.alive());
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

    /** pause, play, play_pause, stop, next, prev, screen_off, screen_on, screen_toggle, home, ambient,
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
            case "ambient":
                RemoteServer.startAmbient(app);
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
