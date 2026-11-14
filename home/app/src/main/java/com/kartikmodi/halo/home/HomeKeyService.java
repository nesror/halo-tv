package com.kartikmodi.halo.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Collections;
import java.util.Map;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * It also serves the phone remote: back, home, recents and notifications are performGlobalAction here,
 * for firmware that refuses injected keys (see Keys).
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.kartikmodi.halo.home/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** The bound service, so the phone remote can ask the system for back / recents / notifications. */
    private static HomeKeyService instance;

    static HomeKeyService running() {
        return instance;
    }

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** app_keys, the same map InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    /** Firmware launcher that cannot be disabled (config.xml home_forward_package); empty on most sets. */
    private String forwardPackage = "";
    private long lastForward;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        // Bound by the system for as long as it is enabled, so Home Assistant stays reachable.
        HaServer.ensureStarted(this);
        ViewingTime.init(this);
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // Keycodes and the panel choice can also come from a theme; a theme push restarts this service.
        Config config = Config.load(this);
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        forwardPackage = getString(R.string.home_forward_package);
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open;
                if (settingsPanel) {
                    open = new Intent(this, QuickSettingsActivity.class);
                } else {
                    // The set's own settings app where there is one: MIUI TV answers no Settings intent.
                    open = TvApps.settingsIntent(this);
                    if (open == null) open = new Intent(Settings.ACTION_SETTINGS);
                }
                startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }
        String app = appKeys.get(event.getKeyCode());
        if (app != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RemoteKeys.launch(this, app);
            return true;
        }
        // A real Home button: on firmware that cannot hand Home over, this is the only interception.
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_UP) openHome();
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            openHome();
        }
        return true;
    }

    /** Fire OS keeps Home and its Settings screens in this package, so it can never be disabled. */
    static final String FIRE_OS_LAUNCHER = "com.amazon.tv.launcher";
    /** Fire OS's Settings grid, also in Amazon's launcher; it can be started directly. */
    static final String FIRE_OS_SETTINGS = "com.amazon.tv.launcher.ui.MainSettingsActivity";
    /** How long Amazon's home may sit in front on its way to Settings before it counts as Home. */
    private static final long SETTINGS_HOP_MS = 1200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable openHome = this::openHome;
    private CharSequence lastPackage;
    /** A redirect is waiting to see whether Settings follows Amazon's home. */
    private boolean hopPending;

    /**
     * Fire OS never hands Home to another app, and its remote's Home button never reaches an
     * accessibility service, so bring this launcher up whenever Amazon's home screen comes to the
     * front: the Home button, start-up, Back out of Settings. Amazon's Settings screens live in the
     * same app, and a Settings request coming from outside it passes through Amazon's home first; in
     * that case wait briefly and stand down when Settings follows. Other TVs never match.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null) return;
        // Which app owns the window: the viewing clock excludes our own screens and other launchers.
        if (pkg.length() > 0) ViewingTime.window(pkg.toString());
        boolean amazonHome = FIRE_OS_LAUNCHER.contentEquals(pkg) && cls.toString().contains("HomeActivity");
        // Firmware whose launcher cannot be disabled over adb (config.xml home_forward_package): its window
        // arriving means the Home button was pressed, so treat it exactly like the Amazon home below.
        // Rate limited, so a slow start cannot bounce between the two home screens.
        if (!amazonHome && !forwardPackage.isEmpty() && forwardPackage.contentEquals(pkg)) {
            long now = SystemClock.uptimeMillis();
            if (now - lastForward >= 1500) {
                lastForward = now;
                amazonHome = true;
            }
        }
        if (!amazonHome) {
            // Anything else in front, Settings included, cancels a pending redirect. Bare FrameLayout
            // events are transient window frames, not a screen the person landed on.
            if (!"android.widget.FrameLayout".contentEquals(cls)) {
                handler.removeCallbacks(openHome);
                hopPending = false;
            }
            lastPackage = pkg;
            return;
        }
        if (hopPending) return;   // Amazon's home often reports itself twice; keep waiting
        // A Settings request from another app (this launcher's ACTION_SETTINGS, the remote's quick
        // settings panel) shows the settings app's frame, then Amazon's home, then Settings.
        boolean maybeSettingsHop = lastPackage != null && (lastPackage.toString().startsWith("com.amazon.tv.settings")
                || lastPackage.toString().startsWith("com.amazon.tv.quicksettings"));
        lastPackage = pkg;
        if (maybeSettingsHop) {
            hopPending = true;
            handler.postDelayed(openHome, SETTINGS_HOP_MS);
        } else {
            openHome();
        }
    }

    private void openHome() {
        hopPending = false;
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }
}
