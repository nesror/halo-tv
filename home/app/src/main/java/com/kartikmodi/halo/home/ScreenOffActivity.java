package com.kartikmodi.halo.home;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * The black window that stands in for a really dark screen (see ScreenPower).
 *
 * It asks for zero brightness and, unlike every other screen in this app, does not ask the TV to stay
 * awake, so the panel can go to sleep on the TV's own timer. Any key or tap closes it at once: that is
 * the way out if Home Assistant is not around to send "screen_on".
 */
public class ScreenOffActivity extends Activity {

    static volatile ScreenOffActivity current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0f;
        getWindow().setAttributes(lp);
        // The one flag every other screen here sets, and the reason the TV can finally sleep.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View black = new FrameLayout(this);
        black.setBackgroundColor(Color.BLACK);
        black.setOnClickListener(v -> dismiss());
        black.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(black);
        current = this;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) {
            dismiss();
            return true;
        }
        return true;   // swallow the press that wakes the screen, so it does not also hit the app behind
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            dismiss();
            return true;
        }
        return true;
    }

    void dismiss() {
        ScreenPower.escaped();
        if (!isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        if (ScreenPower.isOff()) ScreenPower.escaped();
        super.onDestroy();
    }
}
