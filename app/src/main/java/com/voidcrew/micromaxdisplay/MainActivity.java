package com.voidcrew.micromaxdisplay;

import android.os.Build;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
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

    private FrameLayout root;
    private FrameRenderer renderer;
    private IdleView idleView;                 // NEW
    private TextView statusView;
    private FrameLayout exitOverlay;
    private UsbReceiver receiver;

    private Bitmap pendingFrame;
    private boolean frameUpdatePosted = false;
    private volatile boolean destroyed = false;

    private boolean idleShowing = false;       // NEW
    private volatile int connState = UsbReceiver.STATE_STARTING;

    private float swipeStartY;
    private float swipeStartX;

    private static final int SWIPE_THRESHOLD = 80;
    private static final int TOP_EDGE_LIMIT = 60;
    private static final long OVERLAY_TIMEOUT = 3000;

    private final Runnable hideOverlayRunnable = new Runnable() {
        @Override
        public void run() {
            hideExitOverlay();
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
                showIdle();   // every state except streaming shows the idle screen
            }
        });
    }

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
            // Drop late frames that arrive after a disconnect.
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

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        setRequestedOrientation(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        );

        applyFullscreen();

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

        root.addView(renderer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // NEW: idle screen sits on top of the renderer
        idleView = new IdleView(this);
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

        createExitOverlay();

        setContentView(root);

        receiver = new UsbReceiver(this);
        receiver.start();

        // NEW
        showIdle();
    }

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

    // NEW
    private void showIdle() {
        if (idleView == null) {
            return;
        }
        idleShowing = true;
        idleView.animate().cancel();
        idleView.setAlpha(1f);
        idleView.setVisibility(View.VISIBLE);
        idleView.start();
        mainHandler.removeCallbacks(fadeStatusRunnable);
        statusView.animate().cancel();
        statusView.setVisibility(View.GONE);
    }

    // NEW
    private void hideIdle() {
        if (idleView == null || !idleShowing) {
            return;
        }
        idleShowing = false;
        showStatusBriefly();
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

   private void applyFullscreen() {
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;

        if (Build.VERSION.SDK_INT >= 19) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }

        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            applyFullscreen();
        }
    }

    // NEW
    @Override
    protected void onPause() {
        if (idleView != null) {
            idleView.stop();
        }
        super.onPause();
    }

    // NEW
    @Override
    protected void onResume() {
        super.onResume();
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

        mainHandler.removeCallbacks(hideOverlayRunnable);
        exitOverlay.setVisibility(View.VISIBLE);
        mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_TIMEOUT);
    }

    private void hideExitOverlay() {
        if (exitOverlay != null) {
            exitOverlay.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            swipeStartY = event.getY();
            swipeStartX = event.getX();
        }

        else if (event.getAction() == MotionEvent.ACTION_UP) {

            float deltaY = event.getY() - swipeStartY;
            float deltaX = Math.abs(event.getX() - swipeStartX);

            if (swipeStartY <= TOP_EDGE_LIMIT
                    && deltaY >= SWIPE_THRESHOLD
                    && deltaY > deltaX) {

                showExitOverlay();
            }
        }

        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onStatus(final String status) {
        updateStatus(status);
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
                releaseFrame(pendingFrame);           // CHANGED (was recycle())
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

        if (idleView != null) {                       // NEW
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
                    if (idleView != null) {          // NEW
                        idleView.setStatus(status);
                    }
                }
            }
        });
    }
}