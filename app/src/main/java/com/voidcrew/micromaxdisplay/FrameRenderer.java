
package com.voidcrew.micromaxdisplay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.view.View;

public final class FrameRenderer extends View {

    private final Paint paint;
    private Bitmap latestFrame;

    private boolean firstFrameLogged = false;
    private boolean firstDrawLogged = false;
    private boolean stopped = false;

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
            Log.d("FrameRenderer", "First bitmap received: "
                    + frame.getWidth() + "x" + frame.getHeight());
            firstFrameLogged = true;
        }

        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        canvas.drawColor(Color.BLACK);

        if (latestFrame == null || latestFrame.isRecycled()) {
            return;
        }

        if (!firstDrawLogged) {
            Log.d("FrameRenderer", "First draw executed: "
                    + canvas.getWidth() + "x" + canvas.getHeight());
            firstDrawLogged = true;
        }

        float scale = Math.min(
                (float) canvas.getWidth() / latestFrame.getWidth(),
                (float) canvas.getHeight() / latestFrame.getHeight()
        );

        float width = latestFrame.getWidth() * scale;
        float height = latestFrame.getHeight() * scale;

        float left = (canvas.getWidth() - width) / 2.0f;
        float top = (canvas.getHeight() - height) / 2.0f;

        RectF destination = new RectF(
                left,
                top,
                left + width,
                top + height
        );

        canvas.drawBitmap(latestFrame, null, destination, paint);
    }

    public void shutdown() {
        stopped = true;

        if (latestFrame != null && !latestFrame.isRecycled()) {
            latestFrame.recycle();
        }

        latestFrame = null;
    }
}
