package com.ads.paragelia;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class BaseActivity extends AppCompatActivity {

    private final Handler handler = new Handler();
    private boolean isSystemUIVisible = false;

    private int tapCount = 0;
    private static final int REQUIRED_TAPS = 5;
    private static final long TAP_RESET_DELAY = 1000;

    private final Runnable hideRunnable = this::hideSystemUI;
    private final Runnable resetTapRunnable = () -> tapCount = 0;

    private LinearLayout memoryOverlay;
    private TextView tvMemUsed, tvMemAvailable, tvMemMax;
    private Handler memHandler = new Handler(Looper.getMainLooper());
    private Runnable memUpdater;

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

    protected void applyMemoryOverlaySetting() {
        SharedPreferences prefs = getSharedPreferences("debug_prefs", MODE_PRIVATE);
        boolean showOverlay = prefs.getBoolean("show_memory_overlay", false);
        if (showOverlay) {
            showMemoryOverlay();
        } else {
            hideMemoryOverlay();
        }
    }

    protected void showMemoryOverlay() {
        if (memoryOverlay != null) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        memoryOverlay = (LinearLayout) inflater.inflate(R.layout.debug_memory_overlay, null);
        addContentView(memoryOverlay, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        tvMemUsed = memoryOverlay.findViewById(R.id.tvMemoryUsed);
        tvMemAvailable = memoryOverlay.findViewById(R.id.tvMemoryAvailable);
        tvMemMax = memoryOverlay.findViewById(R.id.tvMemoryMax);

        memUpdater = new Runnable() {
            @Override
            public void run() {
                updateMemoryInfo();
                memHandler.postDelayed(this, 1000);
            }
        };
        memHandler.post(memUpdater);
    }

    protected void hideMemoryOverlay() {
        if (memoryOverlay == null) return;
        memHandler.removeCallbacks(memUpdater);
        ((ViewGroup) memoryOverlay.getParent()).removeView(memoryOverlay);
        memoryOverlay = null;
    }

    private void updateMemoryInfo() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        am.getMemoryInfo(mi);

        long usedMem = mi.totalMem - mi.availMem;
        if (tvMemUsed != null)
            tvMemUsed.setText(String.format("Used: %d MB", usedMem / (1024 * 1024)));
        if (tvMemAvailable != null)
            tvMemAvailable.setText(String.format("Avail: %d MB", mi.availMem / (1024 * 1024)));
        if (tvMemMax != null)
            tvMemMax.setText(String.format("Max: %d MB", mi.totalMem / (1024 * 1024)));
    }


    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onResume() {
        super.onResume();

        applyMemoryOverlaySetting();

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
                        handler.postDelayed(hideRunnable, 2000);
                    }
                }
                return false;
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(hideRunnable);
        handler.removeCallbacks(resetTapRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (memHandler != null && memUpdater != null) {
            memHandler.removeCallbacks(memUpdater);
        }
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