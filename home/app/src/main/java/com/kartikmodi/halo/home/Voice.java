package com.kartikmodi.halo.home;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The viewing-time reminders out loud, without a text-to-speech engine.
 *
 * Some TVs ship none: this MiTV3S has no engine at all, so {@link Speech} finds nothing to talk to and
 * a reminder used to arrive as a silent card. Instead of asking the owner to install an engine, the
 * sentences the launcher itself says are recorded once (16 kHz mono, Tingting) and stitched together
 * here: the fixed phrases, and the numbers in them, so "已30分钟，休息5分钟" and "已1小时30分" both work.
 *
 * Only the launcher's own wording is covered - {@link #say} answers false for anything else, and
 * {@link Speech} is still asked first, so a TV with an engine loses nothing.
 */
final class Voice {

    private static final String TAG = "Voice";
    private static final int RATE = 16000;
    private static final int CHANNELS = 1;
    private static final int BYTES_PER_SAMPLE = 2;

    /** The warning card's stock sentence; a custom one cannot be recorded in advance. */
    private static final String STOCK_WARNING = "今天看电视已满2小时，10分钟后自动关机。";

    private static final Pattern BREAK = Pattern.compile(
            "看电视已(.+?)，请看看远处，休息(\\d+)分钟。");
    private static final Pattern MINUTES_ONLY = Pattern.compile("(\\d+)分钟");
    private static final Pattern HOURS = Pattern.compile("(\\d+)小时(?:(\\d+)分)?");

    private static final Object LOCK = new Object();
    private static MediaPlayer current;
    private static AudioManager audio;
    private static AudioManager.OnAudioFocusChangeListener focus;
    private static Runnable releaseRunnable;

    private Voice() {
    }

    /** True while a recorded line is going out, so nothing talks over it. */
    static boolean speaking() {
        synchronized (LOCK) {
            return current != null;
        }
    }

    /**
     * Says text if it is one of the lines the launcher writes itself. Returns false - and says
     * nothing - for any other text, because there is no way to synthesise it here.
     */
    static boolean say(final Context context, final String text) {
        final List<Integer> plan = plan(text);
        if (plan == null || plan.isEmpty()) return false;
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> play(app, plan), "halo-voice");
        t.setDaemon(true);
        t.start();
        return true;
    }

    static void stop() {
        MediaPlayer player;
        synchronized (LOCK) {
            player = current;
            current = null;
            if (releaseRunnable != null) {
                HandlerHolder.MAIN.removeCallbacks(releaseRunnable);
                releaseRunnable = null;
            }
        }
        if (player != null) {
            try {
                player.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
        }
        abandon();
    }

    // ---- Which recordings make up a sentence.

    private static List<Integer> plan(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.isEmpty()) return null;
        if (STOCK_WARNING.equals(s)) return tokens(R.raw.vo_warn);

        Matcher b = BREAK.matcher(s);
        if (b.matches()) {
            List<Integer> out = new ArrayList<>();
            out.add(R.raw.vo_kan);
            if (!duration(out, b.group(1))) return null;
            out.add(R.raw.vo_rest);
            number(out, parseInt(b.group(2)));
            out.add(R.raw.vo_min_end);
            return out;
        }
        return null;
    }

    /** "30分钟", "1小时30分", "2小时" as the clock reads in the card. */
    private static boolean duration(List<Integer> out, String part) {
        String s = part == null ? "" : part.trim();
        Matcher h = HOURS.matcher(s);
        if (h.matches()) {
            number(out, parseInt(h.group(1)));
            out.add(R.raw.vo_hour);
            if (h.group(2) != null) {
                number(out, parseInt(h.group(2)));
                out.add(R.raw.vo_min);
            }
            return true;
        }
        Matcher m = MINUTES_ONLY.matcher(s);
        if (m.matches()) {
            number(out, parseInt(m.group(1)));
            out.add(R.raw.vo_min);
            return true;
        }
        return false;
    }

    /** A count up to 999 in spoken Mandarin: 5, 30, 105, 120. */
    private static void number(List<Integer> out, int n) {
        if (n < 0) n = 0;
        if (n > 999) n = 999;
        if (n < 10) {
            out.add(n == 2 ? R.raw.vo_liang : digit(n));
            return;
        }
        if (n < 20) {
            out.add(R.raw.vo_ten);
            if (n % 10 != 0) out.add(digit(n % 10));
            return;
        }
        if (n < 100) {
            out.add(digit(n / 10));
            out.add(R.raw.vo_ten);
            if (n % 10 != 0) out.add(digit(n % 10));
            return;
        }
        out.add(digit(n / 100));
        out.add(R.raw.vo_hundred);
        int rest = n % 100;
        if (rest == 0) return;
        if (rest < 10) {
            out.add(R.raw.vo_zero);
            out.add(digit(rest));
        } else {
            number(out, rest);
        }
    }

    private static int digit(int d) {
        switch (d) {
            case 1: return R.raw.vo_1;
            case 2: return R.raw.vo_2;
            case 3: return R.raw.vo_3;
            case 4: return R.raw.vo_4;
            case 5: return R.raw.vo_5;
            case 6: return R.raw.vo_6;
            case 7: return R.raw.vo_7;
            case 8: return R.raw.vo_8;
            case 9: return R.raw.vo_9;
            default: return R.raw.vo_zero;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException | NullPointerException e) {
            return 0;
        }
    }

    private static List<Integer> tokens(int... ids) {
        List<Integer> out = new ArrayList<>();
        for (int id : ids) out.add(id);
        return out;
    }

    // ---- Playing it.

    /** Plays the clips as one file: a shared AudioTrack refuses to start on this firmware. */
    private static void play(Context app, List<Integer> plan) {
        byte[] pcm;
        try {
            pcm = concat(app, plan);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "could not read the recorded line", e);
            return;
        }
        if (pcm.length == 0) return;

        File file = new File(app.getCacheDir(), "halo_voice.wav");
        try {
            writeWav(file, pcm);
        } catch (IOException e) {
            Log.w(TAG, "could not write the line to the cache", e);
            return;
        }

        MediaPlayer player = new MediaPlayer();
        try {
            player.setDataSource(file.getAbsolutePath());
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.prepare();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "could not open the line", e);
            player.release();
            return;
        }

        synchronized (LOCK) {
            stopPlayerLocked();
            current = player;
        }
        player.setOnCompletionListener(p -> finished(p));
        player.setOnErrorListener((p, what, extra) -> {
            Log.w(TAG, "playback stopped: " + what + "/" + extra);
            finished(p);
            return true;
        });

        hold(app);
        try {
            player.start();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not play the line", e);
            finished(player);
            return;
        }
        // A watchdog: if the completion callback never comes, the file lock is still let go.
        long ms = pcm.length * 1000L / (RATE * BYTES_PER_SAMPLE * CHANNELS);
        Runnable done = () -> finished(player);
        synchronized (LOCK) {
            releaseRunnable = done;
        }
        HandlerHolder.MAIN.postDelayed(done, ms + 1500);
    }

    private static void finished(MediaPlayer player) {
        synchronized (LOCK) {
            if (current == player) current = null;
            if (releaseRunnable != null) {
                HandlerHolder.MAIN.removeCallbacks(releaseRunnable);
                releaseRunnable = null;
            }
        }
        try {
            player.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            player.release();
        } catch (RuntimeException ignored) {
        }
        abandon();
    }

    /** A 44-byte RIFF header in front of the samples, so MediaPlayer takes it as a WAV. */
    private static void writeWav(File file, byte[] pcm) throws IOException {
        int rate = RATE, channels = CHANNELS, bits = 8 * BYTES_PER_SAMPLE;
        int byteRate = rate * channels * BYTES_PER_SAMPLE;
        try (FileOutputStream out = new FileOutputStream(file)) {
            ByteArrayOutputStream head = new ByteArrayOutputStream(44);
            head.write("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            le32(head, 36 + pcm.length);
            head.write("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            le32(head, 16);
            le16(head, 1);              // PCM
            le16(head, channels);
            le32(head, rate);
            le32(head, byteRate);
            le16(head, channels * BYTES_PER_SAMPLE);
            le16(head, bits);
            head.write("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            le32(head, pcm.length);
            out.write(head.toByteArray());
            out.write(pcm);
        }
    }

    private static void le32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }

    private static void le16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }

    /** Every clip's samples, one after another, with no header in between and no gap. */
    private static byte[] concat(Context app, List<Integer> plan) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int id : plan) {
            ByteArrayOutputStream file = new ByteArrayOutputStream();
            try (InputStream in = app.getResources().openRawResource(id)) {
                int n;
                while ((n = in.read(buffer)) > 0) file.write(buffer, 0, n);
            }
            byte[] pcm = pcmOf(file.toByteArray());
            out.write(pcm, 0, pcm.length);
        }
        return out.toByteArray();
    }

    /**
     * The samples inside a WAV, so the phrases run into each other with no gap between files. Our own
     * recordings are 16 kHz mono 16-bit; anything else is skipped rather than played as noise.
     */
    private static byte[] pcmOf(byte[] wav) {
        if (wav.length < 44 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') return wav;
        int at = 12;
        while (at + 8 <= wav.length) {
            String id = new String(wav, at, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = (wav[at + 4] & 0xff) | ((wav[at + 5] & 0xff) << 8)
                    | ((wav[at + 6] & 0xff) << 16) | ((wav[at + 7] & 0xff) << 24);
            if (size < 0) return new byte[0];
            if ("data".equals(id)) {
                int from = at + 8;
                int to = Math.min(wav.length, from + size);
                byte[] pcm = new byte[to - from];
                System.arraycopy(wav, from, pcm, 0, pcm.length);
                return pcm;
            }
            at += 8 + size + (size & 1);
        }
        return new byte[0];
    }

    private static void stopPlayerLocked() {
        if (current == null) return;
        try {
            current.stop();
            current.release();
        } catch (RuntimeException ignored) {
        }
        current = null;
    }

    // ---- Audio focus, so a film is ducked rather than drowned out.

    @SuppressWarnings("deprecation")
    private static void hold(Context app) {
        if (audio == null) audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        if (focus == null) focus = change -> { };
        try {
            audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        } catch (RuntimeException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static void abandon() {
        if (audio == null) return;
        try {
            audio.abandonAudioFocus(focus);
        } catch (RuntimeException ignored) {
        }
    }

    /** One handler for the whole process: the track's release is posted here. */
    private static final class HandlerHolder {
        static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    }
}
