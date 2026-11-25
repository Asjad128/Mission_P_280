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
    private TextView fullscreenLabel;
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

    private boolean fullscreenMode = true;
    // Tracks the window flag so it is only changed when the mode changes.
    private boolean windowFullscreenSet = false;

    // ---- top-edge swipe that opens the menu ----
    //
    // A touch that starts in the top strip is HELD back from Windows until we
    // know what it is:
    //   - swipe down past the threshold -> open the menu, Windows never sees it
    //   - tap / sideways / upward move   -> release the held DOWN, normal touch
    // Without this, every menu swipe was also sent to the desktop as a drag.
    private static final int EDGE_NONE = 0;
    private static final int EDGE_HOLD = 1;
    private static final int EDGE_SWIPE = 2;

    private int edgeState = EDGE_NONE;
    private boolean heldDownValid = false;
    private int heldPointerId;
    private float heldX;
    private float heldY;

    private float swipeStartX;
    private float swipeStartY;

    private int topEdgePx;
    private int swipeThresholdPx;
    private int edgeSlopPx;

    private static final int TOP_EDGE_DP = 40;
    private static final int SWIPE_THRESHOLD_DP = 64;
    private static final int EDGE_SLOP_DP = 16;

    private static final long OVERLAY_TIMEOUT = 3000;
    private static final long NIGHT_TICK_MS = 15000;

    private static final float IDLE_MARGIN_FULLSCREEN = 0.05f;
    private static final float IDLE_MARGIN_WITH_BARS = 0.01f;

    private final Runnable hideOverlayRunnable = new Runnable() {
        @Override
        public void run() {
            hideExitOverlay();
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

            if (destroyed || renderer == null || !isLinkUp()) {
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

        uiPrefs = getSharedPreferences(AppSettings.PREFS, MODE_PRIVATE);
        fullscreenMode = uiPrefs.getBoolean("fullscreen", true);

        topEdgePx = dp(TOP_EDGE_DP);
        swipeThresholdPx = dp(SWIPE_THRESHOLD_DP);
        edgeSlopPx = dp(EDGE_SLOP_DP);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        applyFullscreen();
        applyKeepAwake();

        // Single guarded re-hide: only when the system actually showed the
        // bars while we are in full-screen mode.
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(
                new View.OnSystemUiVisibilityChangeListener() {
                    @Override
                    public void onSystemUiVisibilityChange(int visibility) {
                        if (!fullscreenMode || destroyed) {
                            return;
                        }
                        if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                            applyFullscreen();
                        }
                    }
                });

        root = new FrameLayout(this);

        renderer = new FrameRenderer(this, new FrameRenderer.FrameReleaser() {
            @Override
            public void release(Bitmap bitmap) {
                releaseFrame(bitmap);
            }
        });
        renderer.setScaleMode(settings.scaleMode);
        renderer.setTouchListener(new FrameRenderer.TouchListener() {
            @Override
            public void onTouch(int action, int pointerId, float x, float y) {
                handleRendererTouch(action, pointerId, x, y);
            }
        });
        root.addView(renderer, matchParent());

        idleView = new IdleView(this, settings);
        idleView.setSafeBottomFraction(
                fullscreenMode ? IDLE_MARGIN_FULLSCREEN : IDLE_MARGIN_WITH_BARS);
        idleView.setDimListener(new IdleView.DimListener() {
            @Override
            public void onDimChanged(boolean dimmed) {
                applyBacklight(dimmed);
                updateGear();
            }
        });
        root.addView(idleView, matchParent());

        statusView = new TextView(this);
        statusView.setText("Starting receiver...");
        statusView.setTextColor(0xffffffff);
        statusView.setTextSize(16.0f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(8), dp(6), dp(8), dp(6));
        statusView.setBackgroundColor(0xaa000000);
        root.addView(statusView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        nightOverlay = new View(this);
        nightOverlay.setVisibility(View.GONE);
        root.addView(nightOverlay, matchParent());

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

        FrameLayout.LayoutParams gearParams = new FrameLayout.LayoutParams(
                dp(52), dp(52), Gravity.TOP | Gravity.LEFT);
        gearParams.leftMargin = dp(12);
        gearParams.topMargin = dp(12);
        root.addView(settingsButton, gearParams);

        settingsPanel = new SettingsPanel(this, settings, new SettingsPanel.Listener() {
            @Override
            public void onSettingsChanged() {
                applySettings();
            }
        });
        root.addView(settingsPanel, matchParent());

        setContentView(root);

        applyNightFilter();
        mainHandler.postDelayed(nightTick, NIGHT_TICK_MS);

        receiver = new UsbReceiver(this);
        receiver.start();

        showIdle();
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isLinkUp() {
        int state = connState;
        return state == UsbReceiver.STATE_CONNECTED
                || state == UsbReceiver.STATE_STREAMING;
    }

    // ------------------------------------------------------------- full screen

    /**
     * Two strict modes:
     *  - Full screen: bars hidden, layout locked to the full screen size.
     *  - Exit full screen: bars always visible, app fits above them.
     * Window flags are only touched when the mode actually changes, because
     * changing them forces a re-layout (that was the idle-screen flicker).
     */
    private void applyFullscreen() {
        View decor = getWindow().getDecorView();

        if (fullscreenMode) {
            if (!windowFullscreenSet) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                windowFullscreenSet = true;
            }

            int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;

            if (Build.VERSION.SDK_INT >= 19) {
                flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            }

            decor.setSystemUiVisibility(flags);
        } else {
            if (windowFullscreenSet) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                windowFullscreenSet = false;
            }
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void setFullscreenMode(boolean value) {
        fullscreenMode = value;
        uiPrefs.edit().putBoolean("fullscreen", value).apply();
        applyFullscreen();
        updateFullscreenLabel();

        if (idleView != null) {
            idleView.setSafeBottomFraction(
                    value ? IDLE_MARGIN_FULLSCREEN : IDLE_MARGIN_WITH_BARS);
        }
    }

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
        boolean keep;
        switch (settings.keepAwake) {
            case AppSettings.AWAKE_ALWAYS:
                keep = true;
                break;
            case AppSettings.AWAKE_CONNECTED:
                keep = isLinkUp();
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

    // --------------------------------------------------------------- lifecycle

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
        // A finger may still be down when we lose the screen: release it on
        // Windows too, otherwise the desktop is left with a stuck mouse button.
        edgeState = EDGE_NONE;
        heldDownValid = false;
        if (renderer != null) {
            renderer.cancelAllTouches();
        }

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

    // ------------------------------------------------------------ exit overlay

    private TextView menuLabel(String text, int gravity) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(0xffffffff);
        label.setTextSize(16);
        label.setGravity(gravity);
        label.setPadding(dp(20), 0, dp(20), 0);
        label.setClickable(true);
        return label;
    }

    private void createExitOverlay() {
        exitOverlay = new FrameLayout(this);
        exitOverlay.setBackgroundColor(0xdd202020);
        // Consume touches on empty parts of the bar so they never reach the desktop.
        exitOverlay.setClickable(true);
        exitOverlay.setVisibility(View.GONE);

        // Left: Settings.
        TextView settingsLabel = menuLabel("Settings", Gravity.CENTER_VERTICAL);
        settingsLabel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                hideExitOverlay();
                settingsPanel.show();
            }
        });
        exitOverlay.addView(settingsLabel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL));

        // Center: Full screen toggle.
        fullscreenLabel = menuLabel("", Gravity.CENTER);
        fullscreenLabel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                hideExitOverlay();
                setFullscreenMode(!fullscreenMode);
            }
        });
        updateFullscreenLabel();
        exitOverlay.addView(fullscreenLabel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

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

        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        buttonParams.rightMargin = dp(12);
        exitOverlay.addView(exitButton, buttonParams);

        TextView exitLabel = new TextView(this);
        exitLabel.setText("Exit");
        exitLabel.setTextColor(0xffffffff);
        exitLabel.setTextSize(16);
        exitLabel.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        labelParams.rightMargin = dp(64);
        exitOverlay.addView(exitLabel, labelParams);

        root.addView(exitOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(56), Gravity.TOP));
    }

    private void showExitOverlay() {
        if (exitOverlay == null) {
            return;
        }

        updateFullscreenLabel();
        mainHandler.removeCallbacks(hideOverlayRunnable);

        exitOverlay.animate().cancel();
        exitOverlay.setAlpha(1.0f);
        exitOverlay.setVisibility(View.VISIBLE);

        mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_TIMEOUT);
    }

    private void hideExitOverlay() {
        mainHandler.removeCallbacks(hideOverlayRunnable);

        if (exitOverlay == null || exitOverlay.getVisibility() != View.VISIBLE) {
            return;
        }

        exitOverlay.animate().cancel();
        exitOverlay.animate()
                .alpha(0.0f)
                .setDuration(450)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (exitOverlay != null) {
                            exitOverlay.setVisibility(View.GONE);
                            exitOverlay.setAlpha(1.0f);
                        }
                    }
                });
    }

    private boolean overlayVisible() {
        return exitOverlay != null && exitOverlay.getVisibility() == View.VISIBLE;
    }

    // ------------------------------------------------------------------- touch

    // Y relative to the app's own content area, so the top-edge swipe keeps
    // working when a system bar is visible above it.
    private float contentY(MotionEvent event) {
        if (root == null) {
            return event.getY();
        }
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        return event.getRawY() - loc[1];
    }

    /** Called by FrameRenderer for every accepted touch; forwards it to Windows. */
    private void handleRendererTouch(int action, int pointerId, float x, float y) {
        if (receiver == null || !isLinkUp()) {
            return;
        }

        switch (edgeState) {
            case EDGE_SWIPE:
                return; // menu gesture: keep it away from the desktop

            case EDGE_HOLD:
                if (action == FrameRenderer.TOUCH_DOWN && !heldDownValid) {
                    heldPointerId = pointerId;
                    heldX = x;
                    heldY = y;
                    heldDownValid = true;
                }
                return;

            default:
                receiver.sendTouchEvent(action, pointerId, x, y);
        }
    }

    /** The touch turned out not to be a menu swipe: send the DOWN we held back. */
    private void releaseEdgeHold() {
        edgeState = EDGE_NONE;

        if (heldDownValid && receiver != null && isLinkUp()) {
            receiver.sendTouchEvent(
                    FrameRenderer.TOUCH_DOWN, heldPointerId, heldX, heldY);
        }
        heldDownValid = false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (idleView != null) {
                    idleView.wake();
                }
                swipeStartX = event.getRawX();
                swipeStartY = contentY(event);
                heldDownValid = false;
                edgeState = (swipeStartY <= topEdgePx
                        && !overlayVisible()
                        && (settingsPanel == null || !settingsPanel.isShowing()))
                        ? EDGE_HOLD : EDGE_NONE;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger means this is a real touch, not a menu swipe.
                if (edgeState == EDGE_HOLD) {
                    releaseEdgeHold();
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (edgeState == EDGE_HOLD) {
                    float dy = contentY(event) - swipeStartY;
                    float dx = Math.abs(event.getRawX() - swipeStartX);

                    if (dy >= swipeThresholdPx && dy > dx) {
                        edgeState = EDGE_SWIPE;
                        heldDownValid = false;
                        showExitOverlay();
                    } else if (dx > edgeSlopPx || dy < -edgeSlopPx
                            || (dy > edgeSlopPx && dy <= dx)) {
                        releaseEdgeHold();
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
                // Finger lifted without a swipe: it was a tap at the top edge.
                if (edgeState == EDGE_HOLD) {
                    releaseEdgeHold();
                }
                break;

            default:
                break;
        }

        boolean handled = super.dispatchTouchEvent(event);

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            edgeState = EDGE_NONE;
            heldDownValid = false;

            if (idleView != null) {
                idleView.clearSwallow();
            }
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

                // The link dropped: make sure no finger is left "down" on the desktop.
                if (state != UsbReceiver.STATE_CONNECTED
                        && state != UsbReceiver.STATE_STREAMING
                        && renderer != null) {
                    renderer.cancelAllTouches();
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

        mainHandler.removeCallbacksAndMessages(null);

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