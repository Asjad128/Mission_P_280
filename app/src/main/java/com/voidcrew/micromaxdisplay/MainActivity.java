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
    private FrameRenderer renderer;
    private TextView statusView;
    private UsbReceiver receiver;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        FrameLayout root = new FrameLayout(this);
        renderer = new FrameRenderer(this);
        root.addView(renderer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setText("Starting receiver...");
        statusView.setTextColor(0xffffffff);
        statusView.setTextSize(16.0f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(12, 8, 12, 8);
        statusView.setBackgroundColor(0xaa000000);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
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
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (renderer != null) {
                    renderer.displayFrame(frame);
                } else {
                    frame.recycle();
                }
            }
        });
    }

    @Override
    public void onError(final String message, Exception exception) {
        updateStatus(message);
    }

    @Override
    protected void onDestroy() {
        if (receiver != null) {
            receiver.stop();
            receiver = null;
        }
        if (renderer != null) {
            renderer.shutdown();
            renderer = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void updateStatus(final String status) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (statusView != null) {
                    statusView.setText(status);
                }
            }
        });
    }
}
