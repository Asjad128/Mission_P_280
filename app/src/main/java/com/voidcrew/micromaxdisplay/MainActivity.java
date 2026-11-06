
package com.voidcrew.micromaxdisplay;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

public final class MainActivity extends Activity implements UsbReceiver.Listener {

    private final Handler mainHandler = new Handler();

    private final Object frameLock = new Object();

    private FrameRenderer renderer;
    private TextView statusView;
    private UsbReceiver receiver;

    private Bitmap pendingFrame;
    private boolean frameUpdatePosted = false;
    private volatile boolean destroyed = false;

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
                if (!frame.isRecycled()) {
                    frame.recycle();
                }
                return;
            }

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

        FrameLayout root = new FrameLayout(this);

        renderer = new FrameRenderer(this);

        root.addView(renderer, new FrameLayout.LayoutParams(
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

        setContentView(root);

        receiver = new UsbReceiver(this);
        receiver.start();
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
                frame.recycle();
                return;
            }

            // Discard an older frame if it hasn't reached the renderer.
            if (pendingFrame != null && pendingFrame != frame
                    && !pendingFrame.isRecycled()) {
                pendingFrame.recycle();
            }

            pendingFrame = frame;

            // Only one UI update can be pending at a time.
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

        if (receiver != null) {
            receiver.stop();
            receiver = null;
        }

        synchronized (frameLock) {
            if (pendingFrame != null && !pendingFrame.isRecycled()) {
                pendingFrame.recycle();
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
                }
            }
        });
    }
}
