package com.kartikmodi.halo.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.Locale;

/**
 * Where the TV's own settings screen and source list live.
 *
 * <p>Android TV firmware routes both through the standard intents, but China-market firmware does not:
 * MIUI TV on the MiTV3S ships its settings as an ordinary app (com.xiaomi.mitv.settings) and answers no
 * Settings intent at all, and keeps the HDMI/AV source list inside its TV app. Resolving through the
 * apps' launcher entries rather than fixed activity names keeps this working when a firmware update
 * renames an activity.
 */
final class TvApps {

    /** Settings apps on TV firmware, most specific first: MIUI TV, then MediaTek's, then AOSP's. */
    private static final String[] SETTINGS_PACKAGES = {
            "com.xiaomi.mitv.settings",
            "com.mitv.settings",
            "com.android.tv.settings",
    };

    /** TV apps whose start screen lists the set's sources, for firmware with no TvInputManager entries. */
    private static final String[] SOURCE_PACKAGES = {
            "com.mediatek.wwtv.tvcenter",
            "com.xiaomi.mitv.tvplayer",
            "com.mitv.tvplayer",
    };

    private TvApps() {
    }

    /** The app's leanback entry point if it has one, else its plain launcher entry; null when not installed. */
    static Intent launchIntent(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) return i;
        // A set's own TV app often declares MAIN without a launcher category, which getLaunchIntent does not
        // see. Take its screens directly, preferring the one that lists the sources.
        Intent best = null;
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).setPackage(pkg), 0)) {
            if (ri.activityInfo == null) continue;
            Intent screen = new Intent(Intent.ACTION_MAIN).setComponent(
                    new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
            String name = ri.activityInfo.name.toLowerCase(Locale.ROOT);
            if (name.contains("external") || name.contains("source") || name.contains("input")) return screen;
            if (best == null) best = screen;
        }
        return best;
    }

    /** The set's own settings app, or null when this firmware only answers the standard intent. */
    static Intent settingsIntent(Context ctx) {
        for (String pkg : SETTINGS_PACKAGES) {
            Intent i = launchIntent(ctx, pkg);
            if (i != null) return i;
        }
        // Renamed vendor build: any installed package named like a settings app, AOSP's excluded because
        // a set that has it also answers the standard intent.
        for (PackageInfo info : ctx.getPackageManager().getInstalledPackages(0)) {
            String pkg = info.packageName;
            if (pkg.equals(ctx.getPackageName()) || pkg.startsWith("com.android.")) continue;
            if (!pkg.toLowerCase(Locale.ROOT).contains("settings")) continue;
            Intent i = launchIntent(ctx, pkg);
            if (i != null) return i;
        }
        return null;
    }

    /**
     * The set's own source switcher: a known vendor TV app, then the configured Live TV app, then any
     * installed package named like a TV app, so a renamed vendor build still resolves. Null when this
     * firmware has nothing of the sort and its sources have to come from TvInputManager.
     */
    static Intent sourceIntent(Context ctx, Intent liveTv) {
        for (String pkg : SOURCE_PACKAGES) {
            Intent i = launchIntent(ctx, pkg);
            if (i != null) return i;
        }
        if (liveTv != null) return liveTv;
        for (PackageInfo info : ctx.getPackageManager().getInstalledPackages(0)) {
            String pkg = info.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;
            String name = pkg.toLowerCase(Locale.ROOT);
            if (!name.contains("tvcenter") && !name.contains("tvplayer") && !name.contains("tvsource")) {
                continue;
            }
            Intent i = launchIntent(ctx, pkg);
            if (i != null) return i;
        }
        return null;
    }
}
