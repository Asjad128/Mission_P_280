
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
    private static final boolean LOG_STATS = false;
    private static final long STATS_INTERVAL_NS = 2000000000L;

    public static final int TOUCH_DOWN = 1;
    public static final int TOUCH_MOVE = 2;
    public static final int TOUCH_UP = 3;
    public static final int TOUCH_CANCEL = 4;

    public interface FrameReleaser {
        void release(Bitmap bitmap);
    }

    public interface TouchListener {
        void onTouch(int action, int pointerId,
                     float normalizedX, float normalizedY);
    }

    private final Paint paint;
    private final RectF destination = new RectF();
    private final FrameReleaser frameReleaser;

    private final Set<Integer> activePointerIds =
            new HashSet<Integer>();

    private final float[] scratch = new float[2];

    private TouchListener touchListener;
    private Bitmap latestFrame;

    private boolean stopped = false;
    private boolean firstFrameLogged = false;

    private int scaleMode = AppSettings.SCALE_FIT;

    /*
     * Source dimensions are used for H.264 SurfaceView rendering.
     * JPEG mode can continue to obtain dimensions from its Bitmap.
     */
    private int sourceWidth = 800;
    private int sourceHeight = 480;

    private int cachedViewWidth = -1;
    private int cachedViewHeight = -1;
    private int cachedFrameWidth = -1;
    private int cachedFrameHeight = -1;
    private int cachedScaleMode = -1;

    private long statsStart = System.nanoTime();
    private int drawCount = 0;
    private long drawNanos = 0;

    public FrameRenderer(
            Context context,
            FrameReleaser frameReleaser) {

        super(context);

        this.frameReleaser = frameReleaser;

        paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setAntiAlias(false);

        /*
         * Critical: this view must not paint an opaque black background.
         * The SurfaceView underneath it displays the decoded H.264 video.
         */
        setBackgroundColor(Color.TRANSPARENT);

        setWillNotDraw(false);
        setClickable(true);
    }

    public void setTouchListener(TouchListener listener) {
        touchListener = listener;
    }

    /**
     * Sets the video dimensions used by the H.264 SurfaceView.
     */
    public void setVideoSize(int width, int height) {

        if (width <= 0 || height <= 0) {
            return;
        }

        if (sourceWidth == width && sourceHeight == height) {
            return;
        }

        sourceWidth = width;
        sourceHeight = height;

        cachedViewWidth = -1;

        updateDestination();
        invalidate();
    }

    public void setScaleMode(int mode) {

        if (mode == scaleMode) {
            return;
        }

        scaleMode = mode;
        cachedViewWidth = -1;

        updateDestination();
        invalidate();
    }

    /**
     * Existing JPEG rendering path.
     */
    public void displayFrame(Bitmap frame) {

        if (frame == null || frame.isRecycled() || stopped) {
            return;
        }

        if (latestFrame != null
                && latestFrame != frame
                && !latestFrame.isRecycled()) {

            frameReleaser.release(latestFrame);
        }

        latestFrame = frame;

        sourceWidth = frame.getWidth();
        sourceHeight = frame.getHeight();

        if (!firstFrameLogged) {

            firstFrameLogged = true;

            Log.d(TAG,
                    "First bitmap: "
                            + frame.getWidth()
                            + "x"
                            + frame.getHeight()
                            + " "
                            + frame.getConfig());
        }

        cachedViewWidth = -1;
        updateDestination();

        invalidate();
    }

    @Override
    protected void onSizeChanged(
            int width,
            int height,
            int oldWidth,
            int oldHeight) {

        super.onSizeChanged(
                width,
                height,
                oldWidth,
                oldHeight
        );

        cachedViewWidth = -1;
        cachedViewHeight = -1;

        updateDestination();
    }

    private void updateDestination() {

        int viewWidth = getWidth();
        int viewHeight = getHeight();

        if (viewWidth <= 0 || viewHeight <= 0
                || sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }

        if (viewWidth == cachedViewWidth
                && viewHeight == cachedViewHeight
                && sourceWidth == cachedFrameWidth
                && sourceHeight == cachedFrameHeight
                && scaleMode == cachedScaleMode) {
            return;
        }

        if (scaleMode == AppSettings.SCALE_STRETCH) {

            destination.set(
                    0,
                    0,
                    viewWidth,
                    viewHeight
            );

        } else {

            float scaleX =
                    (float) viewWidth / sourceWidth;

            float scaleY =
                    (float) viewHeight / sourceHeight;

            float scale =
                    scaleMode == AppSettings.SCALE_FILL
                            ? Math.max(scaleX, scaleY)
                            : Math.min(scaleX, scaleY);

            float width = sourceWidth * scale;
            float height = sourceHeight * scale;

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
        cachedFrameWidth = sourceWidth;
        cachedFrameHeight = sourceHeight;
        cachedScaleMode = scaleMode;
    }

    @Override
    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);

        /*
         * H.264 mode:
         * MediaCodec draws directly to the SurfaceView.
         * This overlay intentionally draws nothing.
         *
         * JPEG mode:
         * Continue drawing the Bitmap as before.
         */
        Bitmap frame = latestFrame;

        if (frame == null || frame.isRecycled()) {
            return;
        }

        long start = LOG_STATS ? System.nanoTime() : 0;

        canvas.drawBitmap(
                frame,
                null,
                destination,
                paint
        );

        if (LOG_STATS) {

            long now = System.nanoTime();

            drawCount++;
            drawNanos += now - start;

            if (now - statsStart >= STATS_INTERVAL_NS) {

                double seconds =
                        (now - statsStart) / 1000000000.0;

                Log.i(TAG, String.format(
                        Locale.US,
                        "Render: %.1f FPS | Avg draw: %.2f ms",
                        drawCount / seconds,
                        drawCount > 0
                                ? (drawNanos / 1000000.0) / drawCount
                                : 0.0
                ));

                statsStart = now;
                drawCount = 0;
                drawNanos = 0;
            }
        }
    }

    // --------------------------------------------------------------- touch

    private boolean mapTouchToFrame(
            float x,
            float y,
            boolean isNewTouch,
            float[] out) {

        updateDestination();

        if (destination.width() <= 0
                || destination.height() <= 0) {
            return false;
        }

        if (isNewTouch
                && scaleMode == AppSettings.SCALE_FIT
                && !destination.contains(x, y)) {

            return false;
        }

        float nx =
                (x - destination.left) / destination.width();

        float ny =
                (y - destination.top) / destination.height();

        out[0] = Math.max(0.0f, Math.min(1.0f, nx));
        out[1] = Math.max(0.0f, Math.min(1.0f, ny));

        return true;
    }

    private void dispatchTouch(
            int action,
            int pointerId,
            float x,
            float y,
            boolean isNewTouch) {

        if (touchListener != null
                && mapTouchToFrame(
                        x,
                        y,
                        isNewTouch,
                        scratch)) {

            touchListener.onTouch(
                    action,
                    pointerId,
                    scratch[0],
                    scratch[1]
            );
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        if (stopped || touchListener == null) {
            return super.onTouchEvent(event);
        }

        switch (event.getActionMasked()) {

            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {

                int index = event.getActionIndex();
                int pointerId = event.getPointerId(index);

                if (mapTouchToFrame(
                        event.getX(index),
                        event.getY(index),
                        true,
                        scratch)) {

                    activePointerIds.add(pointerId);

                    touchListener.onTouch(
                            TOUCH_DOWN,
                            pointerId,
                            scratch[0],
                            scratch[1]
                    );
                }

                return true;
            }

            case MotionEvent.ACTION_MOVE: {

                for (int i = 0;
                     i < event.getPointerCount();
                     i++) {

                    int pointerId = event.getPointerId(i);

                    if (activePointerIds.contains(pointerId)) {

                        dispatchTouch(
                                TOUCH_MOVE,
                                pointerId,
                                event.getX(i),
                                event.getY(i),
                                false
                        );
                    }
                }

                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {

                int index = event.getActionIndex();
                int pointerId = event.getPointerId(index);

                if (activePointerIds.remove(pointerId)) {

                    dispatchTouch(
                            TOUCH_UP,
                            pointerId,
                            event.getX(index),
                            event.getY(index),
                            false
                    );
                }

                return true;
            }

            case MotionEvent.ACTION_CANCEL:

                cancelAllTouches();
                return true;

            default:
                return true;
        }
    }

    public void cancelAllTouches() {

        if (activePointerIds.isEmpty()) {
            return;
        }

        int firstPointer =
                activePointerIds.iterator().next();

        activePointerIds.clear();

        if (touchListener != null) {

            touchListener.onTouch(
                    TOUCH_CANCEL,
                    firstPointer,
                    0.0f,
                    0.0f
            );
        }
    }

    public void shutdown() {

        stopped = true;

        activePointerIds.clear();
        touchListener = null;

        if (latestFrame != null
                && !latestFrame.isRecycled()) {

            frameReleaser.release(latestFrame);
        }

        latestFrame = null;
    }
}
