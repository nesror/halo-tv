package com.kartikmodi.halo.home;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * How long somebody has been watching something other than the home screen today, and what to do about it.
 *
 * The clock counts only while all of these hold: the feature is enabled, the screen is on and unlocked,
 * no Halo window (Home, Ambient, a message card) is in front, and we are not inside a rest break. Time
 * spent on the home screen is deliberately excluded - that is the launcher, not a programme. There is no
 * new permission and no new service: Home's own lifecycle is the main signal, and the accessibility
 * service, when the installer enabled it, sharpens it with the package that owns the current window.
 *
 * Every threshold is changeable over HTTP on the web server the phone remote already uses:
 *   GET  /api/tvtime             status and current configuration
 *   POST /api/tvtime/config      change any of the configuration keys below
 *   POST /api/tvtime/reset       clear today's count (a parent extending the day)
 *   POST /api/tvtime/adjust      {"minutes": 30} add or subtract time, for testing and one-off allowances
 *
 * A normal launcher cannot power a television off: PowerManager.goToSleep() is signature-protected. At
 * the daily limit ScreenPower falls back to its black window at zero brightness, which lets the set's own
 * sleep timer finish the job. docs/viewing-time.zh-CN.md explains this and the alternatives.
 */
final class ViewingTime {

    private static final String TAG = "ViewingTime";
    private static final String PREFS = "viewing_time";
    private static final long TICK_MS = 5000;
    /** A gap longer than this means the process was frozen or the set was asleep; never back-charge it. */
    private static final long MAX_DELTA_MS = 30_000;
    /** A window event old enough that its package can no longer be trusted. */
    private static final long WINDOW_FRESH_MS = 30_000;

    /** Launchers and system UI are not programmes either. */
    private static final Set<String> HOME_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.systemui", "com.android.launcher3", "com.miui.home",
            "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher", "com.mitv.tvhome",
            "com.amazon.tv.launcher", "com.android.settings"));

    private ViewingTime() {
    }

    // ---- Configuration, persisted so a reboot or an HTTP change survives.

    private static final String K_ENABLED = "enabled";
    private static final String K_BREAK_INTERVAL = "break_interval_min";
    private static final String K_BREAK_MINUTES = "break_minutes";
    private static final String K_DAILY_LIMIT = "daily_limit_min";
    private static final String K_WARN_BEFORE = "warn_before_min";
    private static final String K_PAUSE_ON_BREAK = "pause_on_break";
    private static final String K_PAUSE_DURING_BREAK = "pause_during_break";
    private static final String K_TTS = "tts";
    private static final String K_AUTO_SHUTDOWN = "auto_shutdown";
    private static final String K_MESSAGE_SECONDS = "message_seconds";
    private static final String K_SHUTDOWN_DELAY = "shutdown_delay_s";
    private static final String K_LANGUAGE = "language";

    private static boolean enabled = true;
    private static int breakIntervalMin = 30;
    private static int breakMinutes = 5;
    private static int dailyLimitMin = 150;
    private static int warnBeforeMin = 10;
    private static boolean pauseOnBreak = true;
    private static boolean pauseDuringBreak = true;
    private static boolean tts = true;
    private static boolean autoShutdown = true;
    private static int messageSeconds = 45;
    private static int shutdownDelayS = 12;
    private static String language = "zh-CN";

    // ---- Today's state.

    @SuppressLint("StaticFieldLeak")   // init() always stores context.getApplicationContext()
    private static Context app;
    private static SharedPreferences prefs;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable ticker = ViewingTime::tick;

    private static String day = "";
    private static long watchedMs;
    private static long nextBreakMs;
    private static int breaks;
    private static boolean warned;
    private static boolean shutdownDone;
    private static long breakUntil;       // elapsedRealtime
    private static long notifyUntil;      // elapsedRealtime, while a card we raised is up
    private static long lastTick;         // elapsedRealtime

    // ---- What is on the screen right now.

    private static boolean mainResumed;
    private static boolean windowHome;
    private static boolean haveWindow;
    private static long windowAt;
    private static String foreground = "";

    /** Idempotent: Home and the accessibility service both call it, whichever starts first. */
    static synchronized void init(Context context) {
        if (app != null) return;
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
        lastTick = SystemClock.elapsedRealtime();
        handler.postDelayed(ticker, TICK_MS);
    }

    private static void load() {
        enabled = prefs.getBoolean(K_ENABLED, enabled);
        breakIntervalMin = prefs.getInt(K_BREAK_INTERVAL, breakIntervalMin);
        breakMinutes = prefs.getInt(K_BREAK_MINUTES, breakMinutes);
        dailyLimitMin = prefs.getInt(K_DAILY_LIMIT, dailyLimitMin);
        warnBeforeMin = prefs.getInt(K_WARN_BEFORE, warnBeforeMin);
        pauseOnBreak = prefs.getBoolean(K_PAUSE_ON_BREAK, pauseOnBreak);
        pauseDuringBreak = prefs.getBoolean(K_PAUSE_DURING_BREAK, pauseDuringBreak);
        tts = prefs.getBoolean(K_TTS, tts);
        autoShutdown = prefs.getBoolean(K_AUTO_SHUTDOWN, autoShutdown);
        messageSeconds = prefs.getInt(K_MESSAGE_SECONDS, messageSeconds);
        shutdownDelayS = prefs.getInt(K_SHUTDOWN_DELAY, shutdownDelayS);
        language = prefs.getString(K_LANGUAGE, language);

        day = prefs.getString("day", "");
        if (!today().equals(day)) {
            clearDay();
            return;
        }
        watchedMs = prefs.getLong("watched_ms", 0);
        nextBreakMs = prefs.getLong("next_break_ms", breakIntervalMin * 60_000L);
        breaks = prefs.getInt("breaks", 0);
        warned = prefs.getBoolean("warned", false);
        shutdownDone = prefs.getBoolean("shutdown_done", false);
    }

    private static String today() {
        return LocalDate.now().toString();
    }

    private static void clearDay() {
        day = today();
        watchedMs = 0;
        nextBreakMs = Math.max(1, breakIntervalMin) * 60_000L;
        breaks = 0;
        warned = false;
        shutdownDone = false;
        breakUntil = 0;
        notifyUntil = 0;
        save();
    }

    private static void save() {
        if (prefs == null) return;
        SharedPreferences.Editor e = prefs.edit()
                .putString("day", day)
                .putLong("watched_ms", watchedMs)
                .putLong("next_break_ms", nextBreakMs)
                .putInt("breaks", breaks)
                .putBoolean("warned", warned)
                .putBoolean("shutdown_done", shutdownDone);
        e.putBoolean(K_ENABLED, enabled)
                .putInt(K_BREAK_INTERVAL, breakIntervalMin)
                .putInt(K_BREAK_MINUTES, breakMinutes)
                .putInt(K_DAILY_LIMIT, dailyLimitMin)
                .putInt(K_WARN_BEFORE, warnBeforeMin)
                .putBoolean(K_PAUSE_ON_BREAK, pauseOnBreak)
                .putBoolean(K_PAUSE_DURING_BREAK, pauseDuringBreak)
                .putBoolean(K_TTS, tts)
                .putBoolean(K_AUTO_SHUTDOWN, autoShutdown)
                .putInt(K_MESSAGE_SECONDS, messageSeconds)
                .putInt(K_SHUTDOWN_DELAY, shutdownDelayS)
                .putString(K_LANGUAGE, language);
        e.apply();
    }

    // ---- Signals from the rest of the launcher.

    /** MainActivity's resume/pause: the one signal that works with no permission at all. */
    static synchronized void home(boolean resumed) {
        mainResumed = resumed;
    }

    /** HomeKeyService's window events: tells a Halo card from somebody else's video app. */
    static synchronized void window(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        foreground = pkg;
        haveWindow = true;
        windowAt = SystemClock.elapsedRealtime();
        windowHome = (app != null && pkg.equals(app.getPackageName())) || HOME_PACKAGES.contains(pkg);
    }

    // ---- The clock.

    private static void tick() {
        synchronized (ViewingTime.class) {
            long now = SystemClock.elapsedRealtime();
            if (app != null) {
                long delta = now - lastTick;
                lastTick = now;
                if (!today().equals(day)) clearDay();
                if (delta < 0 || delta > MAX_DELTA_MS) delta = 0;
                if (delta > 0 && counting(now)) {
                    watchedMs += delta;
                    save();
                    evaluate(now);
                }
            }
        }
        handler.postDelayed(ticker, TICK_MS);
    }

    private static boolean counting(long now) {
        return enabled
                && !shutdownDone
                && now >= breakUntil
                && now >= notifyUntil
                && ScreenPower.interactive(app)
                && !ScreenPower.isOff()
                && !keyguardLocked()
                && !onHome(now);
    }

    private static boolean onHome(long now) {
        if (mainResumed) return true;
        return haveWindow && windowHome && now - windowAt < WINDOW_FRESH_MS;
    }

    private static boolean keyguardLocked() {
        try {
            KeyguardManager km = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String reason(long now) {
        if (!enabled) return "disabled";
        if (shutdownDone) return "limit_reached";
        if (now < breakUntil) return "rest_break";
        if (now < notifyUntil) return "showing_notice";
        if (!ScreenPower.interactive(app) || ScreenPower.isOff()) return "screen_off";
        if (keyguardLocked()) return "locked";
        if (onHome(now)) return "home_screen";
        return "watching";
    }

    // ---- Thresholds.

    private static void evaluate(long now) {
        long limit = Math.max(1, dailyLimitMin) * 60_000L;
        long warnAt = Math.max(0, limit - warnBeforeMin * 60_000L);

        if (!shutdownDone && watchedMs >= limit) {
            shutdownDone = true;
            save();
            fireShutdown();
            return;
        }
        if (!warned && warnAt > 0 && watchedMs >= warnAt) {
            warned = true;
            // A rest card right on top of the shutdown warning would be noise, not help.
            nextBreakMs = Math.max(nextBreakMs, watchedMs + breakIntervalMin * 60_000L);
            save();
            fireWarning(warnAt);
            return;
        }
        if (warnAt > 0 && watchedMs >= warnAt) {
            // Past the warning: no more eye-rest interruptions before the set goes dark.
            nextBreakMs = Math.max(nextBreakMs, watchedMs + breakIntervalMin * 60_000L);
            return;
        }
        if (nextBreakMs > 0 && watchedMs >= nextBreakMs) {
            breaks++;
            nextBreakMs = watchedMs + Math.max(1, breakIntervalMin) * 60_000L;
            if (pauseDuringBreak) breakUntil = now + Math.max(0, breakMinutes) * 60_000L;
            save();
            fireBreak();
        }
    }

    private static void fireBreak() {
        if (pauseOnBreak) {
            try {
                Playback.control(app, "pause");
            } catch (RuntimeException e) {
                Log.w(TAG, "could not pause for the eye-rest reminder", e);
            }
        }
        String text = "看电视已" + durationText(breakIntervalMin)
                + "，请看看远处，休息" + breakMinutes + "分钟。";
        show("该休息了", text, messageSeconds);
    }

    private static void fireWarning(long warnAt) {
        String text = "今天看电视已满" + durationText((int) (warnAt / 60_000L))
                + "，" + warnBeforeMin + "分钟后自动关机。";
        show("今日观看时间", text, messageSeconds);
    }

    private static void fireShutdown() {
        String text = "今天看电视已满" + durationText(dailyLimitMin) + "，电视即将关闭。";
        show("今日观看时间已到", text, Math.min(messageSeconds, 20));
        if (!autoShutdown) return;
        handler.postDelayed(() -> {
            try {
                ScreenPower.off(app);
            } catch (RuntimeException e) {
                Log.w(TAG, "could not turn the screen off at the daily limit", e);
            }
        }, Math.max(0, shutdownDelayS) * 1000L);
    }

    private static void show(String title, String text, int seconds) {
        try {
            JSONObject m = new JSONObject()
                    .put("title", title)
                    .put("message", text)
                    .put("tts", tts)
                    .put("language", language)
                    .put("duration", Math.max(5, seconds))
                    .put("position", "center");
            notifyUntil = SystemClock.elapsedRealtime() + Math.max(5, seconds) * 1000L;
            MessageActivity.show(app, m);
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "could not put the reminder on the screen", e);
        }
    }

    private static String durationText(int minutes) {
        if (minutes < 60) return minutes + "分钟";
        int hours = minutes / 60;
        int rest = minutes % 60;
        return hours + "小时" + (rest > 0 ? rest + "分" : "");
    }

    // ---- HTTP: status, configuration, reset, adjust.

    static synchronized JSONObject status(Context context) throws JSONException {
        init(context);
        long now = SystemClock.elapsedRealtime();
        long limit = Math.max(1, dailyLimitMin) * 60_000L;
        long warnAt = Math.max(0, limit - warnBeforeMin * 60_000L);

        JSONObject config = new JSONObject()
                .put(K_ENABLED, enabled)
                .put(K_BREAK_INTERVAL, breakIntervalMin)
                .put(K_BREAK_MINUTES, breakMinutes)
                .put(K_DAILY_LIMIT, dailyLimitMin)
                .put(K_WARN_BEFORE, warnBeforeMin)
                .put(K_PAUSE_ON_BREAK, pauseOnBreak)
                .put(K_PAUSE_DURING_BREAK, pauseDuringBreak)
                .put(K_TTS, tts)
                .put(K_AUTO_SHUTDOWN, autoShutdown)
                .put(K_MESSAGE_SECONDS, messageSeconds)
                .put(K_SHUTDOWN_DELAY, shutdownDelayS)
                .put(K_LANGUAGE, language);

        JSONObject todayJson = new JSONObject()
                .put("date", day)
                .put("watched_ms", watchedMs)
                .put("watched_min", round1(watchedMs / 60_000.0))
                .put("watched_text", durationText((int) (watchedMs / 60_000L)))
                .put("remaining_min", round1(Math.max(0, limit - watchedMs) / 60_000.0))
                .put("shutdown_at_min", dailyLimitMin)
                .put("warn_at_min", warnAt / 60_000L)
                .put("next_break_in_min", round1(Math.max(0, nextBreakMs - watchedMs) / 60_000.0))
                .put("rest_left_min", round1(Math.max(0, breakUntil - now) / 60_000.0))
                .put("breaks", breaks)
                .put("warned", warned)
                .put("shutdown", shutdownDone)
                .put("counting", counting(now))
                .put("reason", reason(now))
                .put("home", onHome(now))
                .put("screen_on", ScreenPower.interactive(app) && !ScreenPower.isOff())
                .put("foreground", foreground);

        return new JSONObject().put("ok", true).put("config", config).put("today", todayJson);
    }

    /** A short form for Home Assistant's /api/ha/state, which is polled every few seconds. */
    static synchronized JSONObject brief(Context context) {
        try {
            init(context);
            long limit = Math.max(1, dailyLimitMin) * 60_000L;
            return new JSONObject()
                    .put("enabled", enabled)
                    .put("watched_min", round1(watchedMs / 60_000.0))
                    .put("remaining_min", round1(Math.max(0, limit - watchedMs) / 60_000.0))
                    .put("daily_limit_min", dailyLimitMin)
                    .put("break_interval_min", breakIntervalMin)
                    .put("counting", counting(SystemClock.elapsedRealtime()))
                    .put("limit_reached", shutdownDone);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** Merge any known keys into the configuration. Unknown keys are reported back, not silently kept. */
    static synchronized JSONObject configure(Context context, JSONObject request) throws JSONException {
        init(context);
        JSONObject applied = new JSONObject();
        org.json.JSONArray unknown = new org.json.JSONArray();
        boolean reschedule = false;
        java.util.Iterator<String> keys = request.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = request.get(key);
            switch (key) {
                case K_ENABLED:
                    if (value instanceof Boolean) { enabled = (Boolean) value; applied.put(key, enabled); }
                    else unknown.put(key);
                    break;
                case K_BREAK_INTERVAL:
                    if (value instanceof Number) {
                        breakIntervalMin = clamp((int) ((Number) value).longValue(), 1, 240);
                        applied.put(key, breakIntervalMin);
                        reschedule = true;
                    } else unknown.put(key);
                    break;
                case K_BREAK_MINUTES:
                    if (value instanceof Number) {
                        breakMinutes = clamp((int) ((Number) value).longValue(), 0, 120);
                        applied.put(key, breakMinutes);
                    } else unknown.put(key);
                    break;
                case K_DAILY_LIMIT:
                    if (value instanceof Number) {
                        dailyLimitMin = clamp((int) ((Number) value).longValue(), 1, 1440);
                        applied.put(key, dailyLimitMin);
                    } else unknown.put(key);
                    break;
                case K_WARN_BEFORE:
                    if (value instanceof Number) {
                        warnBeforeMin = clamp((int) ((Number) value).longValue(), 0, 120);
                        applied.put(key, warnBeforeMin);
                    } else unknown.put(key);
                    break;
                case K_PAUSE_ON_BREAK:
                    if (value instanceof Boolean) { pauseOnBreak = (Boolean) value; applied.put(key, pauseOnBreak); }
                    else unknown.put(key);
                    break;
                case K_PAUSE_DURING_BREAK:
                    if (value instanceof Boolean) { pauseDuringBreak = (Boolean) value; applied.put(key, pauseDuringBreak); }
                    else unknown.put(key);
                    break;
                case K_TTS:
                    if (value instanceof Boolean) { tts = (Boolean) value; applied.put(key, tts); }
                    else unknown.put(key);
                    break;
                case K_AUTO_SHUTDOWN:
                    if (value instanceof Boolean) { autoShutdown = (Boolean) value; applied.put(key, autoShutdown); }
                    else unknown.put(key);
                    break;
                case K_MESSAGE_SECONDS:
                    if (value instanceof Number) {
                        messageSeconds = clamp((int) ((Number) value).longValue(), 5, 600);
                        applied.put(key, messageSeconds);
                    } else unknown.put(key);
                    break;
                case K_SHUTDOWN_DELAY:
                    if (value instanceof Number) {
                        shutdownDelayS = clamp((int) ((Number) value).longValue(), 0, 300);
                        applied.put(key, shutdownDelayS);
                    } else unknown.put(key);
                    break;
                case K_LANGUAGE:
                    language = String.valueOf(value);
                    applied.put(key, language);
                    break;
                default:
                    unknown.put(key);
                    break;
            }
        }
        // A new interval counts from now, so shortening it never fires a backlog of old reminders.
        if (reschedule) nextBreakMs = watchedMs + Math.max(1, breakIntervalMin) * 60_000L;
        save();
        return new JSONObject().put("ok", true).put("applied", applied)
                .put("unknown", unknown).put("status", status(context));
    }

    /** Reset today's count and all of today's fired flags. */
    static synchronized JSONObject reset(Context context) throws JSONException {
        init(context);
        clearDay();
        return new JSONObject().put("ok", true).put("status", status(context));
    }

    /** Add or subtract minutes from today's count; used for allowances and for testing thresholds. */
    static synchronized JSONObject adjust(Context context, JSONObject request) throws JSONException {
        init(context);
        long minutes = (long) request.optDouble("minutes", request.optDouble("add_minutes", 0));
        watchedMs = Math.max(0, watchedMs + minutes * 60_000L);
        // Leave nextBreakMs alone: adding time past a threshold should fire it on the next tick, which
        // also makes this the quickest way to exercise a reminder without waiting half an hour.
        save();
        return new JSONObject().put("ok", true).put("status", status(context));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
