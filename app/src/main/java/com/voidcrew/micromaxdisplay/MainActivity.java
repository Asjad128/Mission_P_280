package com.voidcrew.micromaxdisplay;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

public final class MainActivity extends Activity implements UsbReceiver.Listener {

    private final Handler mainHandler = new Handler();
    private final Object frameLock = new Object();

    private AppSettings settings;
    private SharedPreferences uiPrefs;

    private FrameLayout root;
    private FrameRenderer renderer;
    private IdleView idleView;
    private TextView statusView;
    private FrameLayout exitOverlay;
    private TextView fullscreenLabel;          // NEW: toggle in the top bar
    private UsbReceiver receiver;

    private View nightOverlay;
    private SettingsPanel settingsPanel;
    private ImageButton settingsButton;
    private int lastOverlayColor = 0;

    private Bitmap pendingFrame;
    private boolean frameUpdatePosted = false;
    private volatile boolean destroyed = false;

    private boolean idleShowing = false;
    private volatile int connState = UsbReceiver.STATE_STARTING;

    private boolean fullscreenMode = true;     // NEW

    private float swipeStartY;
    private float swipeStartX;

    private static final int SWIPE_THRESHOLD = 80;
    private static final int TOP_EDGE_LIMIT = 60;
    private static final long OVERLAY_TIMEOUT = 3000;
    private static final long NIGHT_TICK_MS = 15000;

    private final Runnable hideOverlayRunnable = new Runnable() {
        @Override
        public void run() {
            hideExitOverlay();
        }
    };

    // NEW: pushes the system bars back out of sight after a touch.
    private final Runnable rehideBarsRunnable = new Runnable() {
        @Override
        public void run() {
            if (!destroyed && fullscreenMode) {
                applyFullscreen();
            }
        }
    };

    private final Runnable barsWatchdog = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            if (fullscreenMode) {
                applyFullscreen();
            }
            mainHandler.postDelayed(this, 1000);
        }
    };

    private final Runnable nightTick = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            applyNightFilter();
            mainHandler.postDelayed(this, NIGHT_TICK_MS);
        }
    };

    private final Runnable fadeStatusRunnable = new Runnable() {
        @Override
        public void run() {
            if (statusView == null) {
                return;
            }
            statusView.animate()
                    .alpha(0f)
                    .setDuration(500)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (statusView != null) {
                                statusView.setVisibility(View.GONE);
                            }
                        }
                    });
        }
    };

    private final Runnable frameUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            Bitmap frame;

            synchronized (frameLock) {
                frame = pendingFrame;
                pendingFrame = null;
                frameUpdatePosted = false;
            }

            if (frame == null) {
                return;
            }

            if (destroyed || renderer == null) {
                releaseFrame(frame);
                return;
            }

            if (connState != UsbReceiver.STATE_CONNECTED
                    && connState != UsbReceiver.STATE_STREAMING) {
                releaseFrame(frame);
                return;
            }

            if (connState != UsbReceiver.STATE_STREAMING) {
                connState = UsbReceiver.STATE_STREAMING;
                idleView.setState(UsbReceiver.STATE_STREAMING);
            }

            hideIdle();
            renderer.displayFrame(frame);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        settings = AppSettings.get(this);

        // NEW: remembered full-screen choice (same prefs file, separate key).
        uiPrefs = getSharedPreferences(AppSettings.PREFS, MODE_PRIVATE);
        fullscreenMode = uiPrefs.getBoolean("fullscreen", true);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        setRequestedOrientation(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        );

        applyFullscreen();
        applyKeepAwake();

        // NEW: on Android 4.x any touch brings the bars back.
        // When that happens, hide them again right away.
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(
            new View.OnSystemUiVisibilityChangeListener() {
                @Override
                public void onSystemUiVisibilityChange(int visibility) {
                    if (!fullscreenMode || destroyed) {
                        return;
                    }
                    if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0
                            || (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                        applyFullscreen();   // immediately, no delay
                    }
                }
            });

        root = new FrameLayout(this);

        renderer = new FrameRenderer(
                this,
                new FrameRenderer.FrameReleaser() {
                    @Override
                    public void release(Bitmap bitmap) {
                        releaseFrame(bitmap);
                    }
                }
        );
        renderer.setScaleMode(settings.scaleMode);

        root.addView(renderer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        idleView = new IdleView(this, settings);
        idleView.setDimListener(new IdleView.DimListener() {
            @Override
            public void onDimChanged(boolean dimmed) {
                applyBacklight(dimmed);
                updateGear();
            }
        });
        root.addView(idleView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        statusView = new TextView(this);
        statusView.setText("Starting receiver...");
        statusView.setTextColor(0xffffffff);
        statusView.setTextSize(16.0f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(12, 8, 12, 8);
        statusView.setBackgroundColor(0xaa000000);

        FrameLayout.LayoutParams statusParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL
                );

        root.addView(statusView, statusParams);

        nightOverlay = new View(this);
        nightOverlay.setVisibility(View.GONE);
        root.addView(nightOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        createExitOverlay();

        settingsButton = new ImageButton(this);
        settingsButton.setImageResource(android.R.drawable.ic_menu_preferences);
        settingsButton.setBackgroundColor(0x00000000);
        settingsButton.setAlpha(0.7f);
        settingsButton.setContentDescription("Settings");
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                settingsPanel.show();
            }
        });

        FrameLayout.LayoutParams gearParams =
                new FrameLayout.LayoutParams(dp(52), dp(52),
                        Gravity.TOP | Gravity.LEFT);
        gearParams.leftMargin = dp(12);
        gearParams.topMargin = dp(12);
        root.addView(settingsButton, gearParams);

        settingsPanel = new SettingsPanel(this, settings, new SettingsPanel.Listener() {
            @Override
            public void onSettingsChanged() {
                applySettings();
            }
        });
        root.addView(settingsPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);

        applyNightFilter();
        mainHandler.postDelayed(nightTick, NIGHT_TICK_MS);
        mainHandler.postDelayed(barsWatchdog, 1000);

        receiver = new UsbReceiver(this);
        receiver.start();

        showIdle();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------- full screen

    // CHANGED: respects the full-screen toggle.
    private void applyFullscreen() {
        View decor = getWindow().getDecorView();

        if (fullscreenMode) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

            int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;

            if (Build.VERSION.SDK_INT >= 19) {
                flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            }

            decor.setSystemUiVisibility(flags);
        } else {
            // Bars visible, and the app is laid out BETWEEN them,
            // so the whole picture stays visible.
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    // NEW
    private void setFullscreenMode(boolean value) {
        fullscreenMode = value;
        uiPrefs.edit().putBoolean("fullscreen", value).apply();
        mainHandler.removeCallbacks(rehideBarsRunnable);
        applyFullscreen();
        updateFullscreenLabel();
        if (root != null) {
            root.requestLayout();
        }
    }

    // NEW
    private void updateFullscreenLabel() {
        if (fullscreenLabel != null) {
            fullscreenLabel.setText(fullscreenMode ? "Exit full screen" : "Full screen");
        }
    }

    // ---------------------------------------------------------------- settings

    private void applySettings() {
        if (renderer != null) {
            renderer.setScaleMode(settings.scaleMode);
        }
        applyNightFilter();
        applyKeepAwake();
        if (idleView != null) {
            idleView.invalidate();
        }
    }

    private void applyNightFilter() {
        if (nightOverlay == null) {
            return;
        }

        int color = settings.nightOverlayColor(System.currentTimeMillis());

        if (color == lastOverlayColor) {
            return;
        }
        lastOverlayColor = color;

        if (color == 0) {
            nightOverlay.setVisibility(View.GONE);
        } else {
            nightOverlay.setBackgroundColor(color);
            nightOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void applyKeepAwake() {
        boolean connected = connState == UsbReceiver.STATE_CONNECTED
                || connState == UsbReceiver.STATE_STREAMING;

        boolean keep;
        switch (settings.keepAwake) {
            case AppSettings.AWAKE_ALWAYS:
                keep = true;
                break;
            case AppSettings.AWAKE_CONNECTED:
                keep = connected;
                break;
            default:
                keep = false;
                break;
        }

        if (keep) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void applyBacklight(boolean dimmed) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = dimmed
                ? 0.03f
                : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    private void updateGear() {
        if (settingsButton == null) {
            return;
        }
        boolean show = idleShowing && (idleView == null || !idleView.isDimmed());
        settingsButton.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // -------------------------------------------------------------- idle / status

    private void showStatusBriefly() {
        if (statusView == null) {
            return;
        }
        mainHandler.removeCallbacks(fadeStatusRunnable);
        statusView.animate().cancel();
        statusView.setAlpha(1f);
        statusView.setVisibility(View.VISIBLE);
        mainHandler.postDelayed(fadeStatusRunnable, 2000);
    }

    private void showIdle() {
        if (idleView == null) {
            return;
        }
        idleShowing = true;
        idleView.animate().cancel();
        idleView.setAlpha(1f);
        idleView.setVisibility(View.VISIBLE);
        idleView.resetActivity();
        idleView.start();

        mainHandler.removeCallbacks(fadeStatusRunnable);
        statusView.animate().cancel();
        statusView.setVisibility(View.GONE);

        updateGear();
    }

    private void hideIdle() {
        if (idleView == null || !idleShowing) {
            return;
        }
        idleShowing = false;
        showStatusBriefly();

        idleView.resetActivity();
        updateGear();

        idleView.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!idleShowing && idleView != null) {
                            idleView.stop();
                            idleView.setVisibility(View.GONE);
                        }
                    }
                });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            applyFullscreen();
        }
    }

    @Override
    public void onBackPressed() {
        if (settingsPanel != null && settingsPanel.isShowing()) {
            settingsPanel.hide();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        if (idleView != null) {
            idleView.stop();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyFullscreen();
        applyNightFilter();
        if (idleView != null && idleShowing) {
            idleView.start();
        }
    }

    private void releaseFrame(Bitmap frame) {
        if (frame == null || frame.isRecycled()) {
            return;
        }

        if (receiver != null) {
            receiver.releaseBitmap(frame);
        } else {
            frame.recycle();
        }
    }

    private void createExitOverlay() {
        exitOverlay = new FrameLayout(this);
        exitOverlay.setBackgroundColor(0xdd202020);
        exitOverlay.setVisibility(View.GONE);

        // Left: Settings.
        TextView settingsLabel = new TextView(this);
        settingsLabel.setText("Settings");
        settingsLabel.setTextColor(0xffffffff);
        settingsLabel.setTextSize(16);
        settingsLabel.setGravity(Gravity.CENTER_VERTICAL);
        settingsLabel.setPadding(dp(20), 0, dp(20), 0);
        settingsLabel.setClickable(true);
        settingsLabel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mainHandler.removeCallbacks(hideOverlayRunnable);
                hideExitOverlay();
                settingsPanel.show();
            }
        });

        exitOverlay.addView(settingsLabel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL
        ));

        // NEW. Center: Exit full screen / Full screen.
        fullscreenLabel = new TextView(this);
        fullscreenLabel.setTextColor(0xffffffff);
        fullscreenLabel.setTextSize(16);
        fullscreenLabel.setGravity(Gravity.CENTER);
        fullscreenLabel.setPadding(dp(20), 0, dp(20), 0);
        fullscreenLabel.setClickable(true);
        fullscreenLabel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mainHandler.removeCallbacks(hideOverlayRunnable);
                hideExitOverlay();
                setFullscreenMode(!fullscreenMode);
            }
        });
        updateFullscreenLabel();

        exitOverlay.addView(fullscreenLabel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));

        // Right: Exit.
        ImageButton exitButton = new ImageButton(this);
        exitButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        exitButton.setBackgroundColor(0x00000000);
        exitButton.setContentDescription("Exit application");

        exitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });

        FrameLayout.LayoutParams buttonParams =
                new FrameLayout.LayoutParams(
                        56,
                        56,
                        Gravity.RIGHT | Gravity.CENTER_VERTICAL
                );

        buttonParams.rightMargin = 12;
        exitOverlay.addView(exitButton, buttonParams);

        TextView exitLabel = new TextView(this);
        exitLabel.setText("Exit");
        exitLabel.setTextColor(0xffffffff);
        exitLabel.setTextSize(16);
        exitLabel.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout.LayoutParams labelParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.RIGHT | Gravity.CENTER_VERTICAL
                );

        labelParams.rightMargin = 76;
        exitOverlay.addView(exitLabel, labelParams);

        FrameLayout.LayoutParams overlayParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        72,
                        Gravity.TOP
                );

        root.addView(exitOverlay, overlayParams);
    }

    private void showExitOverlay() {
        if (exitOverlay == null) {
            return;
        }

        updateFullscreenLabel();
        mainHandler.removeCallbacks(hideOverlayRunnable);
        exitOverlay.setVisibility(View.VISIBLE);
        mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_TIMEOUT);
    }

    private void hideExitOverlay() {
        if (exitOverlay != null) {
            exitOverlay.setVisibility(View.GONE);
        }
    }

    // NEW: Y position relative to the app's own content area, so the
    // top-edge swipe still works when a status bar is visible above it.
    private float contentY(MotionEvent event) {
        if (root == null) {
            return event.getY();
        }
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        return event.getRawY() - loc[1];
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {

        int action = event.getAction();

        if (action == MotionEvent.ACTION_DOWN) {
            if (idleView != null) {
                idleView.wake();
            }
            swipeStartY = contentY(event);
            swipeStartX = event.getX();
        }

        else if (action == MotionEvent.ACTION_UP) {

            float deltaY = contentY(event) - swipeStartY;
            float deltaX = Math.abs(event.getX() - swipeStartX);

            if (swipeStartY <= TOP_EDGE_LIMIT
                    && deltaY >= SWIPE_THRESHOLD
                    && deltaY > deltaX) {

                showExitOverlay();
            }
        }

        boolean handled = super.dispatchTouchEvent(event);

        // Android 4.2 clears the hide flags on touch. Put them back right away.
        if (fullscreenMode) {
            applyFullscreen();
            mainHandler.post(rehideBarsRunnable);
        }

        if (idleView != null
                && (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL)) {
            idleView.clearSwallow();
        }

        return handled;
    }

    // ------------------------------------------------------- UsbReceiver.Listener

    @Override
    public void onStatus(final String status) {
        updateStatus(status);
    }

    @Override
    public void onConnectionState(final int state) {
        connState = state;

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || idleView == null) {
                    return;
                }
                idleView.setState(state);
                applyKeepAwake();
                showIdle();
            }
        });
    }

    @Override
    public void onFrame(final Bitmap frame) {

        if (frame == null || frame.isRecycled()) {
            return;
        }

        synchronized (frameLock) {

            if (destroyed) {
                releaseFrame(frame);
                return;
            }

            if (pendingFrame != null && pendingFrame != frame
                    && !pendingFrame.isRecycled()) {
                releaseFrame(pendingFrame);
            }

            pendingFrame = frame;

            if (!frameUpdatePosted) {
                frameUpdatePosted = true;
                mainHandler.post(frameUpdateRunnable);
            }
        }
    }

    @Override
    public void onError(final String message, Exception exception) {
        updateStatus(message);
    }

    @Override
    protected void onDestroy() {

        destroyed = true;

        mainHandler.removeCallbacks(hideOverlayRunnable);
        mainHandler.removeCallbacks(rehideBarsRunnable);

        if (idleView != null) {
            idleView.stop();
        }

        if (receiver != null) {
            receiver.stop();
            receiver = null;
        }

        synchronized (frameLock) {
            if (pendingFrame != null && !pendingFrame.isRecycled()) {
                releaseFrame(pendingFrame);
            }

            pendingFrame = null;
        }

        mainHandler.removeCallbacksAndMessages(null);

        if (renderer != null) {
            renderer.shutdown();
            renderer = null;
        }

        super.onDestroy();
    }

    private void updateStatus(final String status) {

        if (destroyed) {
            return;
        }

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!destroyed && statusView != null) {
                    statusView.setText(status);
                    if (!idleShowing) {
                        showStatusBriefly();
                    }
                    if (idleView != null) {
                        idleView.setStatus(status);
                    }
                }
            }
        });
    }
}