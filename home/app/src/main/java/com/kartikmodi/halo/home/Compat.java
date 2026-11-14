package com.kartikmodi.halo.home;

import android.content.Context;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.tv.TvInputInfo;
import android.os.Build;
import android.view.View;

import androidx.core.content.res.ResourcesCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Stand-ins for the APIs this launcher used to get for free at minSdk 26.
 *
 * <p>Halo TV targets Android TV 8.0 and newer, but it also runs on a Xiaomi MiTV3S (pulpfiction) whose
 * last firmware is Android 6.0.1 / API 23, so minSdk is 23. Every call below is either wrapped in an
 * SDK_INT check or replaced with a lower-API equivalent.
 */
final class Compat {

    private Compat() {
    }

    /** {@code View#forceHasOverlappingRendering} is API 24. Below that the offscreen buffer stays. */
    static void noOverlap(View view) {
        if (Build.VERSION.SDK_INT >= 24) view.forceHasOverlappingRendering(false);
    }

    /** {@code Resources#getFont} is API 26; ResourcesCompat resolves the same font resource on API 21+. */
    static Typeface font(Context context, int id) {
        try {
            return ResourcesCompat.getFont(context, id);
        } catch (RuntimeException e) {
            return null;   // a missing font must not stop the app list from drawing
        }
    }

    /**
     * {@code AudioAttributes.USAGE_ASSISTANT} is API 26, and older platforms reject unknown usages in
     * {@code AudioAttributes.Builder#setUsage} with an IllegalArgumentException rather than ignoring them.
     */
    static int speechUsage() {
        return Build.VERSION.SDK_INT >= 26 ? AudioAttributes.USAGE_ASSISTANT : AudioAttributes.USAGE_MEDIA;
    }

    /** {@code TvInputInfo#isHidden} is API 24. */
    static boolean isHidden(Context context, TvInputInfo info) {
        return Build.VERSION.SDK_INT >= 24 && info.isHidden(context);
    }

    /** {@code TvInputInfo#loadCustomLabel} is API 24; below that there is no custom label to show. */
    static CharSequence customLabel(Context context, TvInputInfo info) {
        return Build.VERSION.SDK_INT >= 24 ? info.loadCustomLabel(context) : null;
    }

    /** {@code Files#readAllBytes} is API 26. */
    static byte[] readAllBytes(File file) throws IOException {
        long length = file.length();
        int initial = (int) Math.max(64, Math.min(length, 1 << 20));
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(initial);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
