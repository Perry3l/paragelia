package com.ads.paragelia;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;

public class BaseActivity extends AppCompatActivity {

    private final Handler handler = new Handler();
    private boolean isSystemUIVisible = false;

    private int tapCount = 0;
    private static final int REQUIRED_TAPS = 5;
    private static final long TAP_RESET_DELAY = 1000;  // 1 δευτερόλεπτο

    private final Runnable hideRunnable = this::hideSystemUI;
    private final Runnable resetTapRunnable = () -> tapCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hideSystemUI();

        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0 && !isSystemUIVisible) {
                handler.postDelayed(hideRunnable, 100);
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onResume() {
        super.onResume();

        View root = findViewById(android.R.id.content);
        if (root != null) {
            root.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    tapCount++;
                    handler.removeCallbacks(resetTapRunnable);
                    handler.postDelayed(resetTapRunnable, TAP_RESET_DELAY);

                    if (tapCount >= REQUIRED_TAPS) {
                        tapCount = 0;
                        if (!isSystemUIVisible) {
                            showSystemUI();
                        }
                        handler.removeCallbacks(hideRunnable);
                        handler.postDelayed(hideRunnable, 2000); // κρύψε ξανά μετά από 2 δευτερόλεπτα
                    }
                }
                return false; // μην καταναλώνεις το event, άφησε το να περάσει στα child views
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(hideRunnable);
        handler.removeCallbacks(resetTapRunnable);
    }

    private void hideSystemUI() {
        View decor = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        decor.setSystemUiVisibility(flags);
        isSystemUIVisible = false;
    }

    private void showSystemUI() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        isSystemUIVisible = true;
    }
}