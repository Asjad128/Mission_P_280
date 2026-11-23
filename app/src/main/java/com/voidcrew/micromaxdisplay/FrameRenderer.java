
package com.voidcrew.micromaxdisplay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class FrameRenderer extends View {

    private static final String TAG = "FrameRenderer";
    private static final long STATS_INTERVAL_NS = 2000000000L;

    // Touch protocol actions. Must match the Windows receiver.
    private static final int TOUCH_DOWN = 1;
    private static final int TOUCH_MOVE = 2;
    private static final int TOUCH_UP = 3;
    private static final int TOUCH_CANCEL = 4;

    private final Paint paint;
    private final RectF destination = new RectF();

    private Bitmap latestFrame;

    private boolean firstFrameLogged = false;
    private boolean firstDrawLogged = false;
    private boolean stopped = false;

    private int scaleMode = AppSettings.SCALE_FIT;

    // Cached destination geometry.
    private int cachedViewWidth = -1;
    private int cachedViewHeight = -1;
    private int cachedFrameWidth = -1;
    private int cachedFrameHeight = -1;
    private int cachedScaleMode = -1;

    // Rendering statistics.
    private long drawStatsStart = System.nanoTime();
    private long previousDrawTime = 0;

    private int drawCount = 0;
    private int receivedCount = 0;

    private long totalDrawNanos = 0;
    private long maxDrawNanos = 0;

    private long totalDrawIntervalNanos = 0;
    private long maxDrawIntervalNanos = 0;
    private int intervalCount = 0;

    // Tracks pointers that successfully started inside the displayed image.
    private final Set<Integer> activePointerIds = new HashSet<Integer>();

    public interface FrameReleaser {
        void release(Bitmap bitmap);
    }

    public interface TouchListener {
        void onTouch(
                int action,
                int pointerId,
                float normalizedX,
                float normalizedY
        );
    }

    private final FrameReleaser frameReleaser;

    private TouchListener touchListener;

    public FrameRenderer(
            Context context,
            FrameReleaser frameReleaser) {

        super(context);

        this.frameReleaser = frameReleaser;

        paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setAntiAlias(false);

        setBackgroundColor(Color.BLACK);
        setWillNotDraw(false);

        // Required to receive touch events.
        setClickable(true);
    }

    public void setTouchListener(TouchListener listener) {
        touchListener = listener;
    }

    public void setScaleMode(int mode) {
        if (mode == scaleMode) {
            return;
        }

        scaleMode = mode;
        cachedViewWidth = -1;

        if (latestFrame != null && !latestFrame.isRecycled()) {
            updateDestination(latestFrame);
        }

        invalidate();
    }

    public void displayFrame(Bitmap frame) {

        if (frame == null || frame.isRecycled() || stopped) {
            return;
        }

        if (latestFrame != null && latestFrame != frame
                && !latestFrame.isRecycled()) {
            frameReleaser.release(latestFrame);
        }

        latestFrame = frame;
        receivedCount++;

        if (!firstFrameLogged) {
            Log.d(TAG, "First bitmap received: "
                    + frame.getWidth() + "x" + frame.getHeight()
                    + " | Config: " + frame.getConfig());

            firstFrameLogged = true;
        }

        updateDestination(frame);
        invalidate();
    }

    @Override
    protected void onSizeChanged(
            int width,
            int height,
            int oldWidth,
            int oldHeight) {

        super.onSizeChanged(width, height, oldWidth, oldHeight);

        cachedViewWidth = -1;
        cachedViewHeight = -1;

        if (latestFrame != null && !latestFrame.isRecycled()) {
            updateDestination(latestFrame);
        }
    }

    private void updateDestination(Bitmap frame) {

        int viewWidth = getWidth();
        int viewHeight = getHeight();

        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }

        int frameWidth = frame.getWidth();
        int frameHeight = frame.getHeight();

        if (viewWidth == cachedViewWidth
                && viewHeight == cachedViewHeight
                && frameWidth == cachedFrameWidth
                && frameHeight == cachedFrameHeight
                && scaleMode == cachedScaleMode) {
            return;
        }

        if (scaleMode == AppSettings.SCALE_STRETCH) {
            destination.set(0, 0, viewWidth, viewHeight);
        } else {
            float scaleX = (float) viewWidth / frameWidth;
            float scaleY = (float) viewHeight / frameHeight;

            // FIT = smaller scale (letterbox bars).
            // FILL = larger scale (image edges cropped).
            float scale = scaleMode == AppSettings.SCALE_FILL
                    ? Math.max(scaleX, scaleY)
                    : Math.min(scaleX, scaleY);

            float width = frameWidth * scale;
            float height = frameHeight * scale;

            float left = (viewWidth - width) / 2.0f;
            float top = (viewHeight - height) / 2.0f;

            destination.set(
                    left,
                    top,
                    left + width,
                    top + height
            );
        }

        cachedViewWidth = viewWidth;
        cachedViewHeight = viewHeight;
        cachedFrameWidth = frameWidth;
        cachedFrameHeight = frameHeight;
        cachedScaleMode = scaleMode;
    }

    @Override
    protected void onDraw(Canvas canvas) {

        long drawStart = System.nanoTime();

        super.onDraw(canvas);

        Bitmap frame = latestFrame;

        if (frame == null || frame.isRecycled()) {
            return;
        }

        if (!firstDrawLogged) {
            Log.d(TAG, "First draw executed: "
                    + canvas.getWidth() + "x" + canvas.getHeight());

            firstDrawLogged = true;
        }

        canvas.drawBitmap(frame, null, destination, paint);

        long drawEnd = System.nanoTime();
        long drawDuration = drawEnd - drawStart;

        drawCount++;
        totalDrawNanos += drawDuration;

        if (drawDuration > maxDrawNanos) {
            maxDrawNanos = drawDuration;
        }

        if (previousDrawTime != 0) {
            long interval = drawStart - previousDrawTime;

            totalDrawIntervalNanos += interval;
            intervalCount++;

            if (interval > maxDrawIntervalNanos) {
                maxDrawIntervalNanos = interval;
            }
        }

        previousDrawTime = drawStart;

        long now = System.nanoTime();

        if (now - drawStatsStart >= STATS_INTERVAL_NS) {

            double seconds = (now - drawStatsStart) / 1000000000.0;
            double drawFps = drawCount / seconds;

            double avgDrawMs = drawCount > 0
                    ? (totalDrawNanos / 1000000.0) / drawCount
                    : 0.0;

            double maxDrawMs = maxDrawNanos / 1000000.0;

            double avgIntervalMs = intervalCount > 0
                    ? (totalDrawIntervalNanos / 1000000.0)
                    / intervalCount
                    : 0.0;

            double maxIntervalMs = maxDrawIntervalNanos / 1000000.0;

            Log.i(TAG,
                    String.format(
                            Locale.US,
                            "Render: %.1f FPS"
                            + " | Frames received: %d"
                            + " | Avg draw: %.2f ms"
                            + " | Max draw: %.2f ms"
                            + " | Avg draw interval: %.2f ms"
                            + " | Max draw interval: %.2f ms",
                            drawFps,
                            receivedCount,
                            avgDrawMs,
                            maxDrawMs,
                            avgIntervalMs,
                            maxIntervalMs
                    )
            );

            drawStatsStart = now;

            drawCount = 0;
            receivedCount = 0;

            totalDrawNanos = 0;
            maxDrawNanos = 0;

            totalDrawIntervalNanos = 0;
            maxDrawIntervalNanos = 0;
            intervalCount = 0;
        }
    }

    /**
     * Converts screen/view coordinates into normalized coordinates
     * relative to the displayed desktop image.
     *
     * For FIT mode, a new DOWN outside the image is rejected.
     * For an already-active pointer, MOVE and terminal events are
     * clamped to the image edges.
     */
    private boolean mapTouchToFrame(
            float x,
            float y,
            boolean allowOutside,
            float[] result) {

        if (destination.width() <= 0 || destination.height() <= 0) {
            return false;
        }

        // In FIT mode, ignore a new touch in the letterbox bars.
        if (!allowOutside && scaleMode == AppSettings.SCALE_FIT) {
            if (!destination.contains(x, y)) {
                return false;
            }
        }

        float normalizedX =
                (x - destination.left) / destination.width();

        float normalizedY =
                (y - destination.top) / destination.height();

        // Clamp coordinates to the desktop image boundaries.
        normalizedX = Math.max(0.0f, Math.min(1.0f, normalizedX));
        normalizedY = Math.max(0.0f, Math.min(1.0f, normalizedY));

        result[0] = normalizedX;
        result[1] = normalizedY;

        return true;
    }

    private void dispatchTouchToListener(
            int action,
            int pointerId,
            float x,
            float y,
            boolean allowOutside) {

        if (touchListener == null) {
            return;
        }

        float[] coordinates = new float[2];

        if (!mapTouchToFrame(
                x,
                y,
                allowOutside,
                coordinates)) {
            return;
        }

        touchListener.onTouch(
                action,
                pointerId,
                coordinates[0],
                coordinates[1]
        );
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        if (stopped || touchListener == null) {
            return super.onTouchEvent(event);
        }

        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();

        switch (action) {

            case MotionEvent.ACTION_DOWN: {
                int pointerId = event.getPointerId(0);

                float x = event.getX(0);
                float y = event.getY(0);

                float[] coordinates = new float[2];

                if (mapTouchToFrame(
                        x,
                        y,
                        false,
                        coordinates)) {

                    activePointerIds.add(pointerId);

                    touchListener.onTouch(
                            TOUCH_DOWN,
                            pointerId,
                            coordinates[0],
                            coordinates[1]
                    );
                }

                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                int pointerId = event.getPointerId(actionIndex);

                float x = event.getX(actionIndex);
                float y = event.getY(actionIndex);

                float[] coordinates = new float[2];

                if (mapTouchToFrame(
                        x,
                        y,
                        false,
                        coordinates)) {

                    activePointerIds.add(pointerId);

                    touchListener.onTouch(
                            TOUCH_DOWN,
                            pointerId,
                            coordinates[0],
                            coordinates[1]
                    );
                }

                return true;
            }

            case MotionEvent.ACTION_MOVE: {

                for (int i = 0; i < event.getPointerCount(); i++) {

                    int pointerId = event.getPointerId(i);

                    if (!activePointerIds.contains(pointerId)) {
                        continue;
                    }

                    dispatchTouchToListener(
                            TOUCH_MOVE,
                            pointerId,
                            event.getX(i),
                            event.getY(i),
                            true
                    );
                }

                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int pointerId = event.getPointerId(actionIndex);

                if (activePointerIds.contains(pointerId)) {

                    dispatchTouchToListener(
                            TOUCH_UP,
                            pointerId,
                            event.getX(actionIndex),
                            event.getY(actionIndex),
                            true
                    );

                    activePointerIds.remove(pointerId);
                }

                return true;
            }

            case MotionEvent.ACTION_UP: {
                int pointerId = event.getPointerId(0);

                if (activePointerIds.contains(pointerId)) {

                    dispatchTouchToListener(
                            TOUCH_UP,
                            pointerId,
                            event.getX(0),
                            event.getY(0),
                            true
                    );

                    activePointerIds.remove(pointerId);
                }

                return true;
            }

            case MotionEvent.ACTION_CANCEL: {

                // Send CANCEL for every pointer that started successfully.
                for (int i = 0; i < event.getPointerCount(); i++) {

                    int pointerId = event.getPointerId(i);

                    if (!activePointerIds.contains(pointerId)) {
                        continue;
                    }

                    dispatchTouchToListener(
                            TOUCH_CANCEL,
                            pointerId,
                            event.getX(i),
                            event.getY(i),
                            true
                    );
                }

                activePointerIds.clear();

                return true;
            }

            default:
                return true;
        }
    }

    public void shutdown() {

        stopped = true;

        activePointerIds.clear();
        touchListener = null;

        if (latestFrame != null && !latestFrame.isRecycled()) {
            frameReleaser.release(latestFrame);
        }

        latestFrame = null;
    }
}
