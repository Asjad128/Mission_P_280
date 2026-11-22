package com.voidcrew.micromaxdisplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public final class IdleView extends View {

    private static final long FRAME_DELAY_MS = 33;
    private static final long SHIFT_INTERVAL_MS = 60000L;
    private static final int SHIFT_RANGE_PX = 24;

    // Base hue pairs (degrees) for tap-to-cycle themes.
    private static final float[][] THEMES = {
            {220f, 270f},   // blue / purple
            {160f, 200f},   // teal
            {20f, 340f},    // amber / rose
            {120f, 170f}    // green
    };

    private final Paint bgPaint = new Paint();
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Random random = new Random();
    private final Date date = new Date();
    private final float[] hsv = new float[3];

    private SimpleDateFormat timeFormat;
    private SimpleDateFormat dateFormat;
    private String timeText = "";
    private String dateText = "";
    private long lastMinute = -1;

    private String statusText = "";
    private int state = UsbReceiver.STATE_STARTING;   // NEW
    private int theme = 0;

    private long lastShiftTime = 0;
    private float shiftX = 0;
    private float shiftY = 0;

    private long lastGradientTime = 0;
    private boolean running = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            invalidate();
            postDelayed(this, FRAME_DELAY_MS);
        }
    };

    public IdleView(Context context) {
        super(context);

        timePaint.setColor(0xFFFFFFFF);
        timePaint.setTextAlign(Paint.Align.CENTER);
        timePaint.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));

        datePaint.setColor(0xCCFFFFFF);
        datePaint.setTextAlign(Paint.Align.CENTER);
        datePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));

        statusPaint.setColor(0xFFFFFFFF);
        statusPaint.setTextAlign(Paint.Align.CENTER);

        dotPaint.setColor(0xFF4FC3F7);

        setBackgroundColor(Color.BLACK);
        setClickable(true);

        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                theme = (theme + 1) % THEMES.length;
                lastGradientTime = 0; // force rebuild
                invalidate();
            }
        });

        refreshFormats();
    }

    private void refreshFormats() {
        boolean is24 = DateFormat.is24HourFormat(getContext());
        timeFormat = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault());
        dateFormat = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault());
        lastMinute = -1;
    }

    public void setStatus(String status) {
        statusText = status == null ? "" : status;
    }

    // NEW
    public void setState(int newState) {
        state = newState;
    }

    // NEW
    private String labelForState() {
        switch (state) {
            case UsbReceiver.STATE_WAITING:
                return "Waiting for Windows\u2026";
            case UsbReceiver.STATE_CONNECTED:
                return "Connected \u2013 starting stream\u2026";
            case UsbReceiver.STATE_STREAMING:
                return "Streaming";
            case UsbReceiver.STATE_LOST:
                return "Connection lost";
            case UsbReceiver.STATE_ERROR:
                return "Connection error";
            default:
                return "Starting receiver\u2026";
        }
    }

    // NEW
    private int colorForState() {
        switch (state) {
            case UsbReceiver.STATE_CONNECTED:
            case UsbReceiver.STATE_STREAMING:
                return 0xFF66BB6A;   // green
            case UsbReceiver.STATE_LOST:
                return 0xFFFFA726;   // orange
            case UsbReceiver.STATE_ERROR:
                return 0xFFEF5350;   // red
            default:
                return 0xFF4FC3F7;   // blue
        }
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        refreshFormats();
        removeCallbacks(ticker);
        post(ticker);
    }

    public void stop() {
        running = false;
        removeCallbacks(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        lastGradientTime = 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        long now = System.currentTimeMillis();

        updateGradient(now, w, h);
        canvas.drawRect(0, 0, w, h, bgPaint);

        updateTexts(now);
        updateShift(now);

        float base = Math.min(w, h);

        timePaint.setTextSize(base * 0.38f);
        datePaint.setTextSize(base * 0.065f);
        statusPaint.setTextSize(base * 0.04f);

        canvas.save();
        canvas.translate(shiftX, shiftY);

        float cx = w / 2f;
        float cy = h * 0.46f;

        canvas.drawText(timeText, cx, cy, timePaint);
        canvas.drawText(dateText, cx, cy + base * 0.12f, datePaint);

        // Pulse: 0..1 over ~2.4 s.
        float pulse = (float) (0.5 + 0.5 * Math.sin(now / 380.0));

        float statusY = h * 0.90f;
        String label = labelForState();          // CHANGED
        dotPaint.setColor(colorForState());      // NEW

        float labelWidth = statusPaint.measureText(label);
        float dotRadius = base * 0.012f * (0.8f + 0.4f * pulse);
        float dotX = cx - labelWidth / 2f - base * 0.035f;
        float dotY = statusY - statusPaint.getTextSize() * 0.32f;

        dotPaint.setAlpha((int) (90 + 165 * pulse));
        canvas.drawCircle(dotX, dotY, dotRadius, dotPaint);

        statusPaint.setAlpha((int) (140 + 90 * pulse));
        canvas.drawText(label, cx, statusY, statusPaint);

        if (statusText.length() > 0) {
            statusPaint.setAlpha(120);
            statusPaint.setTextSize(base * 0.03f);
            canvas.drawText(statusText, cx, statusY + base * 0.05f, statusPaint);
        }

        canvas.restore();
    }

    private void updateGradient(long now, int w, int h) {
        if (now - lastGradientTime < 1000) {
            return;
        }
        lastGradientTime = now;

        // Hue drifts ~3 degrees per second around the theme's base hues.
        float drift = (now / 1000f * 3f) % 360f;
        float[] t = THEMES[theme];

        hsv[0] = (t[0] + 30f * (float) Math.sin(Math.toRadians(drift))) % 360f;
        hsv[1] = 0.65f;
        hsv[2] = 0.22f;
        int c1 = Color.HSVToColor(hsv);

        hsv[0] = (t[1] + 30f * (float) Math.cos(Math.toRadians(drift))) % 360f;
        hsv[2] = 0.10f;
        int c2 = Color.HSVToColor(hsv);

        bgPaint.setShader(new LinearGradient(
                0, 0, w, h, c1, c2, Shader.TileMode.CLAMP));
    }

    private void updateTexts(long now) {
        long minute = now / 60000L;
        if (minute == lastMinute) {
            return;
        }
        lastMinute = minute;
        date.setTime(now);
        timeText = timeFormat.format(date);
        dateText = dateFormat.format(date);
    }

    // Burn-in protection: nudge content a little every minute.
    private void updateShift(long now) {
        if (now - lastShiftTime < SHIFT_INTERVAL_MS) {
            return;
        }
        lastShiftTime = now;
        shiftX = random.nextInt(SHIFT_RANGE_PX * 2 + 1) - SHIFT_RANGE_PX;
        shiftY = random.nextInt(SHIFT_RANGE_PX * 2 + 1) - SHIFT_RANGE_PX;
    }
}