package com.kartikmodi.halo.home;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;

/**
 * Says a Home Assistant message out loud, with the TV's own text-to-speech engine.
 *
 * One engine for the whole process: a TextToSpeech takes a second or two to start on a TV, so it is
 * built once, the first time a message arrives, and kept for the next one. While it speaks it holds a
 * transient audio focus that only ducks the music, so an announcement can be heard over a film and the
 * film carries on afterwards.
 */
final class Speech {

    private static final String TAG = "Speech";

    private static TextToSpeech tts;
    private static boolean ready;
    private static AudioManager audio;
    private static AudioFocusRequest focus;
    private static boolean focusHeld;
    /** Said as soon as the engine finishes starting, for a message that arrived while it did. */
    private static String pendingText, pendingLang;

    private Speech() {
    }

    /** Says text now. The language tag ("zh-CN", "en-GB") is optional; empty means the TV's own. */
    static void say(Context context, String text, String language) {
        if (text == null || text.trim().isEmpty()) return;
        Context app = context.getApplicationContext();
        synchronized (Speech.class) {
            if (tts == null) {
                pendingText = text.trim();
                pendingLang = language;
                audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                tts = new TextToSpeech(app, status -> {
                    synchronized (Speech.class) {
                        if (status != TextToSpeech.SUCCESS) {
                            Log.w(TAG, "no voice engine on this TV");
                            tts = null;
                            pendingText = null;
                            return;
                        }
                        ready = true;
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String id) { }
                            @Override public void onDone(String id) { release(); }
                            @Override public void onError(String id) { release(); }
                        });
                        String t = pendingText, l = pendingLang;
                        pendingText = null;
                        if (t != null) utter(t, l);
                    }
                });
            } else if (ready) {
                utter(text.trim(), language);
            } else {
                // Still starting: this replaces anything queued, because a newer message wins.
                pendingText = text.trim();
                pendingLang = language;
            }
        }
    }

    /** Stops whatever is being said and lets the music back up. */
    static void stop() {
        synchronized (Speech.class) {
            pendingText = null;
            if (tts != null && ready) tts.stop();
            release();
        }
    }

    private static void utter(String text, String lang) {
        Locale locale = locale(lang);
        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
            tts.setLanguage(Locale.getDefault());
        }
        tts.setSpeechRate(1.0f);
        tts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        hold(true);
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "halo-ha") != TextToSpeech.SUCCESS) release();
    }

    /** A "zh-CN" or "en" tag as a Locale; anything unusable falls back to the TV's own language. */
    static Locale locale(String tag) {
        if (tag == null || tag.trim().isEmpty()) return Locale.getDefault();
        try {
            Locale l = Locale.forLanguageTag(tag.trim().replace('_', '-'));
            if (l.getLanguage() == null || l.getLanguage().isEmpty()) return Locale.getDefault();
            return l;
        } catch (RuntimeException e) {
            return Locale.getDefault();
        }
    }

    private static void hold(boolean on) {
        if (audio == null) return;
        if (on) {
            if (focus == null) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setOnAudioFocusChangeListener(c -> { })
                        .build();
            }
            if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusHeld = true;
        } else {
            release();
        }
    }

    private static void release() {
        if (audio == null || !focusHeld) return;
        try {
            audio.abandonAudioFocusRequest(focus);
        } catch (RuntimeException ignored) {
        }
        focusHeld = false;
    }
}
