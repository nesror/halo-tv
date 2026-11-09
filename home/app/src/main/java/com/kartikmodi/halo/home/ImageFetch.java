package com.kartikmodi.halo.home;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * A picture for a Home Assistant message: an http(s) URL, a data: URI (what Home Assistant sends for a
 * camera snapshot) or a path on the TV. Blocking, so it is called off the UI thread; the biggest edge is
 * capped, because a 4K phone photo is 30 MB of bitmap and this TV has a gigabyte of memory to share.
 */
final class ImageFetch {

    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_EDGE = 1600;

    private ImageFetch() {
    }

    static Bitmap load(String source) {
        if (source == null || source.trim().isEmpty()) return null;
        try {
            byte[] data = bytes(source.trim());
            if (data == null || data.length == 0) return null;
            BitmapFactory.Options size = new BitmapFactory.Options();
            size.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, size);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample(size.outWidth, size.outHeight);
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private static int sample(int w, int h) {
        int longest = Math.max(w, h), n = 1;
        while (longest / n > MAX_EDGE) n *= 2;
        return n;
    }

    private static byte[] bytes(String source) throws Exception {
        if (source.startsWith("data:")) {
            int comma = source.indexOf(',');
            if (comma < 0) return null;
            String meta = source.substring(5, comma);
            String payload = source.substring(comma + 1);
            return meta.contains("base64")
                    ? Base64.decode(payload, Base64.DEFAULT)
                    : payload.getBytes("UTF-8");
        }
        if (source.startsWith("http://") || source.startsWith("https://")) {
            HttpURLConnection c = (HttpURLConnection) new URL(source).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "HaloTV");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > MAX_BYTES) return null;
                }
                return buf.toByteArray();
            } finally {
                c.disconnect();
            }
        }
        java.io.File file = new java.io.File(source);
        if (!file.isFile()) return null;
        return java.nio.file.Files.readAllBytes(file.toPath());
    }
}
