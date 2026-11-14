package com.kartikmodi.halo.home;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The TV's own remote service (mitv.service, port 6095 of the set itself).
 *
 * It is the channel the Xiaomi remote app uses, and it runs as the system, so a key sent through it
 * counts as a press of the real remote. That matters for power: a KEYCODE_POWER injected with
 * /system/bin/input is accepted and then ignored by this firmware, while the same key here puts the
 * set to standby. Everything else keeps using input, which needs no round trip.
 *
 * The protocol is a plain HTTP GET with the keycode by name; see docs/home-assistant.md.
 */
final class MiTv {

    private static final String TAG = "MiTv";
    static final int PORT = 6095;

    private MiTv() {
    }

    /** True when the set is answering on 6095, i.e. the remote channel is usable. */
    static boolean alive() {
        JSONObject o = get("/request?action=isalive");
        return o != null && o.optInt("status", -1) == 0;
    }

    /** Sends one key by name: power, home, back, menu, up, down, left, right, enter, volumeup... */
    static boolean press(String keycode) {
        if (keycode == null || keycode.isEmpty()) return false;
        JSONObject o = get("/controller?action=keyevent&keycode=" + keycode);
        boolean ok = o != null && o.optInt("status", -1) == 0;
        if (!ok) Log.w(TAG, "the set refused " + keycode);
        return ok;
    }

    /** Standby, through the set's own remote: the only way this firmware accepts a power press. */
    static boolean power() {
        return press("power");
    }

    private static JSONObject get(String path) {
        HttpURLConnection c = null;
        try {
            // Loopback: the request never leaves the TV, and stays answered even while the Wi-Fi is
            // going down for standby.
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + path).openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code != 200) return null;
            return new JSONObject(read(c.getInputStream()));
        } catch (Exception e) {
            Log.w(TAG, "no answer on " + PORT + path, e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int n;
            while ((n = stream.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }
}
