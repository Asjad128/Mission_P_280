package com.voidcrew.micromaxdisplay;

import android.util.Log;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * Displays only the newest decoded frame and scales it without changing its aspect ratio.
 */
public final class FrameRenderer extends SurfaceView implements SurfaceHolder.Callback {
    private final Object frameLock = new Object();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap latestFrame;
    private boolean stopped;

    public FrameRenderer(android.content.Context context) {
        super(context);
        getHolder().addCallback(this);
        paint.setAntiAlias(false);
        setBackgroundColor(Color.BLACK);
    }

    public void displayFrame(Bitmap frame) {
        Log.d("FrameRenderer", "Received bitmap: "
            + (frame == null ? "NULL" : frame.getWidth() + "x" + frame.getHeight()));
        if (frame == null || frame.isRecycled()) {
            return;
        }

        synchronized (frameLock) {
            if (stopped) {
                frame.recycle();
                return;
            }
            if (latestFrame != null && latestFrame != frame && !latestFrame.isRecycled()) {
                latestFrame.recycle();
            }
            latestFrame = frame;
        }
        drawLatestFrame();
    }

    public void shutdown() {
        synchronized (frameLock) {
            stopped = true;
            if (latestFrame != null && !latestFrame.isRecycled()) {
                latestFrame.recycle();
            }
            latestFrame = null;
        }
        getHolder().removeCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        drawLatestFrame();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d("FrameRenderer", "Surface created");
        drawLatestFrame();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    private void drawLatestFrame() {
        Canvas canvas = null;
        synchronized (frameLock) {
            if (stopped || !getHolder().getSurface().isValid()) {
                return;
            }
            try {
                canvas = getHolder().lockCanvas();
                if (canvas != null) {
                    Log.d("FrameRenderer", "Canvas: "
                            + canvas.getWidth() + "x" + canvas.getHeight());
                }
                if (canvas == null) {
                    return;
                }
                canvas.drawColor(Color.BLACK);
                if (latestFrame != null && !latestFrame.isRecycled()) {
                    float scale = Math.min(
                            (float) canvas.getWidth() / latestFrame.getWidth(),
                            (float) canvas.getHeight() / latestFrame.getHeight());
                    float width = latestFrame.getWidth() * scale;
                    float height = latestFrame.getHeight() * scale;
                    float left = (canvas.getWidth() - width) / 2.0f;
                    float top = (canvas.getHeight() - height) / 2.0f;
                    canvas.drawBitmap(latestFrame, null,
                            new RectF(left, top, left + width, top + height), paint);
                }
            } finally {
                if (canvas != null) {
                    getHolder().unlockCanvasAndPost(canvas);
                }
            }
        }
    }
}
