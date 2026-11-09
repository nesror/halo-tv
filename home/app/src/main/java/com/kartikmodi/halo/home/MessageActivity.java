package com.kartikmodi.halo.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A message from Home Assistant, over whatever is playing: a title, a line or two of text, a picture,
 * and optionally a voice that reads it out.
 *
 * It is a translucent window on purpose - the film behind keeps playing and stays visible - and it goes
 * away by itself after its duration, or on any key on the remote, whichever comes first. A second message
 * replaces the first in place, so a doorbell and then a delivery do not stack up.
 */
public class MessageActivity extends Activity {

    private static final String TAG = "Message";
    /** The next message, handed over here rather than through the Intent: a camera snapshot is far
     *  bigger than the 1 MB an Intent extra may carry. */
    private static volatile String pending;

    static volatile MessageActivity current;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    private LinearLayout card;
    private TextView kicker, body;
    private ImageView picture;
    private View progress;

    private volatile String title = "", text = "";
    private volatile boolean hasImage;
    private volatile long since;
    private int duration = 12;
    private String gravity = "center";

    /** Shows a message; see docs/home-assistant.md for the fields. */
    static void show(Context context, JSONObject message) {
        MessageActivity open = current;
        if (open != null) {
            open.runOnUiThread(() -> open.apply(message));
            return;
        }
        pending = message.toString();
        try {
            context.getApplicationContext().startActivity(new Intent(context.getApplicationContext(), MessageActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException e) {
            // No overlay permission and no window to start from: the message is lost, and Home Assistant
            // sees the failure in the reply to its notify call.
            Log.w(TAG, "could not show a message", e);
        }
    }

    /** Takes the message away, from Home Assistant or from a key on the remote. */
    static void dismissAll() {
        MessageActivity open = current;
        if (open == null) return;
        open.runOnUiThread(open::dismiss);
    }

    static boolean showing() {
        return current != null;
    }

    static JSONObject state() {
        JSONObject out = new JSONObject();
        MessageActivity open = current;
        try {
            out.put("showing", open != null);
            if (open != null) {
                out.put("title", open.title);
                out.put("text", open.text);
                out.put("has_image", open.hasImage);
                out.put("since", open.since);
            }
        } catch (org.json.JSONException ignored) {
        }
        return out;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);   // a message is worth waking the TV for
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.dimAmount = 0.62f;
        getWindow().setAttributes(lp);
        build();
        current = this;
        String json = pending;
        pending = null;
        if (json == null) {
            finish();
            return;
        }
        try {
            apply(new JSONObject(json));
        } catch (org.json.JSONException e) {
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String json = pending;
        pending = null;
        if (json == null) return;
        try {
            apply(new JSONObject(json));
        } catch (org.json.JSONException ignored) {
        }
    }

    // ---- Layout: a glass card, the picture above the words, nothing else.

    private void build() {
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> dismiss());

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(40), dp(34), dp(40), dp(38));
        card.setElevation(dp(24));
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xF0121417);
        glass.setCornerRadius(dp(26));
        glass.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(glass);

        kicker = new TextView(this);
        kicker.setTextColor(0xFFFFC46B);
        kicker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        kicker.setLetterSpacing(0.16f);
        kicker.setAllCaps(true);
        kicker.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels * 0.60f));
        kicker.setVisibility(View.GONE);
        card.addView(kicker, rowParams(0, 0, 0, 10));

        picture = new ImageView(this);
        picture.setAdjustViewBounds(true);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        picture.setVisibility(View.GONE);
        picture.setClipToOutline(true);
        picture.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16));
            }
        });
        card.addView(picture, rowParams(0, 0, 0, 18));

        body = new TextView(this);
        body.setTextColor(0xFFF4F5F7);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        body.setLineSpacing(dp(8), 1f);
        body.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels * 0.60f));
        card.addView(body, rowParams(0, 0, 0, 0));

        progress = new View(this);
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(0xFFFFC46B);
        bar.setCornerRadius(dp(2));
        progress.setBackground(bar);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        barParams.topMargin = dp(20);
        card.addView(progress, barParams);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL;
        root.addView(card, cardParams);

        setContentView(root);
        card.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start();
    }

    private LinearLayout.LayoutParams rowParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return p;
    }

    private void apply(JSONObject m) {
        if (isFinishing() || isDestroyed()) return;
        title = first(m, "title", "heading");
        text = first(m, "message", "text", "body");
        String image = first(m, "image", "image_url", "picture", "url");
        boolean speak = m.optBoolean("tts", m.optBoolean("speak", false));
        duration = m.has("duration") ? clamp(m.optInt("duration", 12), 0, 3600) : 12;
        gravity = m.optString("position", "center").toLowerCase(java.util.Locale.US);
        since = System.currentTimeMillis();

        if (title.isEmpty() && text.isEmpty() && image.isEmpty()) {
            if (speak) {
                // A voice with nothing to show is still a useful notification.
                Speech.say(this, first(m, "message", "text", "title"), m.optString("language", ""));
            }
            finish();
            return;
        }

        kicker.setText(title);
        kicker.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);
        body.setText(text);
        body.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, text.length() > 140 ? 28 : text.length() > 70 ? 32 : 36);
        body.setMaxLines(8);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);

        hasImage = !image.isEmpty();
        picture.setVisibility(View.GONE);
        picture.setImageDrawable(null);
        if (hasImage) {
            int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.42f);
            picture.setMaxHeight(max);
            picture.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels * 0.60f));
            picture.setMinimumHeight(0);
            loadImage(image, max);
        }

        FrameLayout.LayoutParams cardParams = (FrameLayout.LayoutParams) card.getLayoutParams();
        cardParams.gravity = gravity.contains("top")
                ? Gravity.CENTER_HORIZONTAL | Gravity.TOP
                : gravity.contains("bottom")
                ? Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM
                : Gravity.CENTER;
        cardParams.topMargin = gravity.contains("top") ? dp(64) : 0;
        cardParams.bottomMargin = gravity.contains("bottom") ? dp(72) : 0;
        card.setLayoutParams(cardParams);

        progress.setVisibility(duration > 0 ? View.VISIBLE : View.GONE);
        progress.setPivotX(0f);
        progress.animate().cancel();
        progress.setScaleX(1f);
        if (duration > 0) {
            // A thin bar that empties as the message's time runs out, so nobody wonders how long it stays.
            progress.animate().scaleX(0f).setDuration(duration * 1000L)
                    .setInterpolator(new android.view.animation.LinearInterpolator()).start();
        }

        handler.removeCallbacksAndMessages(null);
        if (duration > 0) {
            handler.postDelayed(this::dismiss, duration * 1000L);
        }
        if (speak) Speech.say(this, text.isEmpty() ? title : text, m.optString("language", ""));
    }

    private void loadImage(String source, int maxHeight) {
        loader.execute(() -> {
            Bitmap bitmap = ImageFetch.load(source);
            runOnUiThread(() -> {
                if (bitmap == null || isFinishing()) return;
                picture.setImageBitmap(bitmap);
                picture.setVisibility(View.VISIBLE);
                picture.setAlpha(0f);
                picture.animate().alpha(1f).setDuration(220).start();
                picture.setOnClickListener(v -> dismiss());
            });
        });
    }

    private static String first(JSONObject m, String... keys) {
        for (String k : keys) {
            String v = m.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---- Leaving: any key, a tap, the timer, or Home Assistant.

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);   // the volume keys belong to the TV, not to us
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            dismiss();
        }
        return true;
    }

    private void dismiss() {
        handler.removeCallbacksAndMessages(null);
        Speech.stop();
        if (isFinishing()) return;
        card.animate().alpha(0f).scaleX(0.98f).scaleY(0.98f).setDuration(180)
                .withEndAction(this::finish).start();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (current == this) current = null;
        loader.shutdownNow();
        Speech.stop();
        super.onDestroy();
    }
}
