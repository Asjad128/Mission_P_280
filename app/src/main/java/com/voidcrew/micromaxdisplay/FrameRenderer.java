
package com.voidcrew.micromaxdisplay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.view.View;

import java.util.Locale;

public final class FrameRenderer extends View {

    private static final String TAG = "FrameRenderer";
    private static final long STATS_INTERVAL_NS =
            2_000_000_000L;

    private final Paint paint;

    private Bitmap latestFrame;

    private boolean firstFrameLogged = false;
    private boolean firstDrawLogged = false;
    private boolean stopped = false;

    // Rendering performance counters.
    private long drawStatsStart = System.nanoTime();
    private int drawCount = 0;
    private long totalDrawNanos = 0;
    private long maxDrawNanos = 0;

    public FrameRenderer(Context context) {
        super(context);

        paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setAntiAlias(false);

        setBackgroundColor(Color.BLACK);
        setWillNotDraw(false);
    }

    public void displayFrame(Bitmap frame) {

        if (frame == null || frame.isRecycled() || stopped) {
            return;
        }

        if (latestFrame != null && latestFrame != frame
                && !latestFrame.isRecycled()) {
            latestFrame.recycle();
        }

        latestFrame = frame;

        if (!firstFrameLogged) {
            Log.d(TAG, "First bitmap received: "
                    + frame.getWidth() + "x" + frame.getHeight()
                    + " | Config: " + frame.getConfig());

            firstFrameLogged = true;
        }

        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {

        long drawStart = System.nanoTime();

        super.onDraw(canvas);

        canvas.drawColor(Color.BLACK);

        Bitmap frame = latestFrame;

        if (frame == null || frame.isRecycled()) {
            return;
        }

        if (!firstDrawLogged) {
            Log.d(TAG, "First draw executed: "
                    + canvas.getWidth() + "x" + canvas.getHeight());

            firstDrawLogged = true;
        }

        float scale = Math.min(
                (float) canvas.getWidth() / frame.getWidth(),
                (float) canvas.getHeight() / frame.getHeight()
        );

        float width = frame.getWidth() * scale;
        float height = frame.getHeight() * scale;

        float left = (canvas.getWidth() - width) / 2.0f;
        float top = (canvas.getHeight() - height) / 2.0f;

        RectF destination = new RectF(
                left,
                top,
                left + width,
                top + height
        );

        canvas.drawBitmap(frame, null, destination, paint);

        // Measure time spent executing the drawing operations.
        long drawDuration = System.nanoTime() - drawStart;

        drawCount++;
        totalDrawNanos += drawDuration;

        if (drawDuration > maxDrawNanos) {
            maxDrawNanos = drawDuration;
        }

        long now = System.nanoTime();

        if (now - drawStatsStart >= STATS_INTERVAL_NS) {

            double seconds =
                    (now - drawStatsStart) / 1_000_000_000.0;

            double drawFps = drawCount / seconds;

            double avgDrawMs = drawCount > 0
                    ? (totalDrawNanos / 1_000_000.0) / drawCount
                    : 0.0;

            double maxDrawMs =
                    maxDrawNanos / 1_000_000.0;

            Log.i(TAG,
                    String.format(
                            Locale.US,
                            "Render: %.1f FPS"
                            + " | Avg draw: %.2f ms"
                            + " | Max draw: %.2f ms",
                            drawFps,
                            avgDrawMs,
                            maxDrawMs
                    )
            );

            drawStatsStart = now;
            drawCount = 0;
            totalDrawNanos = 0;
            maxDrawNanos = 0;
        }
    }

    public void shutdown() {
        stopped = true;

        if (latestFrame != null && !latestFrame.isRecycled()) {
            latestFrame.recycle();
        }

        latestFrame = null;
    }
}
