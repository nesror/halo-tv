package com.kartikmodi.halo.home;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What is playing on the TV, and pause/play for Home Assistant.
 *
 * Three ways down, in order of how much they know: the Ambient screen's own player when it is up, then
 * the media session of whichever app is playing (YouTube, Netflix, Plex...), then a bare media key press
 * for the TVs where sessions cannot be read. The first two report a title and artwork; the last only
 * presses the button, so it is the fallback.
 */
final class Playback {

    private static final String TAG = "Playback";
    private static final Map<String, String> LABELS = new HashMap<>();

    private Playback() {
    }

    /** pause, play, play_pause, stop, next or prev. Returns false when there was nothing to tell. */
    static boolean control(Context context, String action) {
        Context app = context.getApplicationContext();
        if (AmbientActivity.foreground() && AmbientActivity.playerKey(action)) return true;
        MediaController c = best(app);
        if (c != null) {
            MediaController.TransportControls t = c.getTransportControls();
            try {
                switch (action) {
                    case "play": t.play(); return true;
                    case "pause": t.pause(); return true;
                    case "stop": t.stop(); return true;
                    case "next": t.skipToNext(); return true;
                    case "prev": t.skipToPrevious(); return true;
                    default:
                        if (playing(c)) t.pause(); else t.play();
                        return true;
                }
            } catch (RuntimeException e) {
                android.util.Log.w(TAG, "the media session would not take the command", e);
            }
        }
        return key(app, action);
    }

    private static boolean playing(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    /** A media key press, which every player on Android TV answers. */
    private static boolean key(Context app, String action) {
        int code;
        switch (action) {
            case "play": code = KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case "pause": code = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "stop": code = KeyEvent.KEYCODE_MEDIA_STOP; break;
            case "next": code = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "prev": code = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            default: code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
        }
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return false;
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
        return true;
    }

    private static List<MediaController> sessions(Context app) {
        try {
            MediaSessionManager m = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (m == null) return Collections.emptyList();
            return m.getActiveSessions(HaMediaListener.component(app));
        } catch (Exception e) {
            // No notification access, or no session to read: the media key path still works.
            return Collections.emptyList();
        }
    }

    /** The playing session if there is one, else whichever session is up. */
    private static MediaController best(Context app) {
        MediaController first = null;
        for (MediaController c : sessions(app)) {
            if (playing(c)) return c;
            if (first == null) first = c;
        }
        return first;
    }

    /** What is playing, as much of it as the TV will say. Never throws, always answers. */
    static JSONObject nowPlaying(Context app) throws JSONException {
        JSONObject out = new JSONObject();
        boolean playing = false;
        String title = "", artist = "", artwork = "";

        MediaController c = best(app);
        if (c != null) {
            playing = playing(c);
            out.put("app", c.getPackageName());
            out.put("app_label", label(app, c.getPackageName()));
            MediaMetadata md = null;
            try {
                md = c.getMetadata();
            } catch (RuntimeException ignored) {
            }
            if (md != null) {
                MediaDescription d = md.getDescription();
                if (d != null) {
                    title = text(d.getTitle());
                    artist = text(d.getSubtitle());
                    Uri icon = d.getIconUri();
                    if (icon != null) artwork = icon.toString();
                }
            }
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getPosition() > 0) {
                out.put("position", s.getPosition() / 1000.0);
                if (md != null && md.getLong(MediaMetadata.METADATA_KEY_DURATION) > 0) {
                    out.put("duration", md.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000.0);
                }
            }
        }

        if (title.isEmpty()) {
            // The Ambient screen keeps its own queue; a song it is playing is the one thing on screen.
            JSONObject amb = RemoteServer.ambientState();
            JSONArray queue = amb.optJSONArray("queue");
            int i = amb.optInt("index", -1);
            JSONObject item = queue == null || i < 0 || i >= queue.length() ? null : queue.optJSONObject(i);
            if (item != null) {
                title = item.optString("title");
                artist = item.optString("artist");
                artwork = item.optString("thumb");
                playing = amb.optBoolean("playing");
                out.put("app", app.getPackageName());
                out.put("app_label", "Ambient");
            }
        }

        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (!playing && audio != null && audio.isMusicActive()) playing = true;
        out.put("title", title);
        out.put("artist", artist);
        out.put("artwork", artwork.startsWith("http") ? artwork : "");
        out.put("playing", playing);
        out.put("paused", !playing && c != null);
        return out;
    }

    private static String text(CharSequence s) {
        return s == null ? "" : s.toString();
    }

    /** An app's name for the Home Assistant entity, cached: this is polled every few seconds. */
    static String label(Context app, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        synchronized (LABELS) {
            String known = LABELS.get(pkg);
            if (known != null) return known;
        }
        String name = "";
        try {
            android.content.pm.PackageManager pm = app.getPackageManager();
            name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) {
        }
        synchronized (LABELS) {
            LABELS.put(pkg, name);
        }
        return name;
    }
}
