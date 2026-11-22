package com.voidcrew.micromaxdisplay;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.text.format.DateFormat;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public final class IdleView extends View {

    private static final long FRAME_DELAY_MS = 33;
    private static final long SHIFT_INTERVAL_MS = 60000L;
    private static final int SHIFT_RANGE_PX = 24;

    // Tune this if the bottom is still hidden by the bezel (0.05 = 5% of height).
    private static final float SAFE_BOTTOM_FRACTION = 0.05f;

    private static final int STYLE_MINIMAL = 0;
    private static final int STYLE_FLIP = 1;
    private static final int STYLE_ANALOG = 2;
    private static final int STYLE_COUNT = 3;

    private static final String PREFS = "idle_prefs";

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

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flipDigitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint batteryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint batteryTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final Path boltPath = new Path();

    private final Random random = new Random();
    private final Date date = new Date();
    private final float[] hsv = new float[3];
    private final SharedPreferences prefs;

    private Calendar cal = Calendar.getInstance();
    private SimpleDateFormat timeFormat;
    private SimpleDateFormat dateFormat;
    private String timeText = "";
    private String dateText = "";
    private long lastMinute = -1;

    private String statusText = "";
    private int state = UsbReceiver.STATE_STARTING;
    private int theme = 0;
    private int clockStyle = STYLE_MINIMAL;

    private long lastShiftTime = 0;
    private float shiftX = 0;
    private float shiftY = 0;

    private long lastGradientTime = 0;
    private boolean running = false;

    private long lastBatteryTime = 0;
    private int batteryPercent = -1;
    private boolean charging = false;

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

        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        clockStyle = prefs.getInt("style", STYLE_MINIMAL) % STYLE_COUNT;
        theme = prefs.getInt("theme", 0) % THEMES.length;

        timePaint.setColor(0xFFFFFFFF);
        timePaint.setTextAlign(Paint.Align.CENTER);
        timePaint.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));

        datePaint.setColor(0xCCFFFFFF);
        datePaint.setTextAlign(Paint.Align.CENTER);
        datePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));

        statusPaint.setColor(0xFFFFFFFF);
        statusPaint.setTextAlign(Paint.Align.CENTER);

        dotPaint.setColor(0xFF4FC3F7);

        cardPaint.setStyle(Paint.Style.FILL);
        cardPaint.setColor(0xDD101018);

        flipDigitPaint.setColor(0xFFEEEEEE);
        flipDigitPaint.setTextAlign(Paint.Align.CENTER);
        flipDigitPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(0xCCFFFFFF);

        handPaint.setStrokeCap(Paint.Cap.ROUND);

        batteryPaint.setStyle(Paint.Style.STROKE);
        batteryTextPaint.setTextAlign(Paint.Align.RIGHT);
        batteryTextPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));

        setBackgroundColor(Color.BLACK);
        setClickable(true);

        // Tap = next clock style.
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                clockStyle = (clockStyle + 1) % STYLE_COUNT;
                prefs.edit().putInt("style", clockStyle).apply();
                invalidate();
            }
        });

        // Long press = next color theme.
        setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                theme = (theme + 1) % THEMES.length;
                prefs.edit().putInt("theme", theme).apply();
                lastGradientTime = 0;
                invalidate();
                return true;
            }
        });

        refreshFormats();
    }

    private void refreshFormats() {
        boolean is24 = DateFormat.is24HourFormat(getContext());
        timeFormat = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault());
        dateFormat = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault());
        cal = Calendar.getInstance();
        lastMinute = -1;
    }

    public void setStatus(String status) {
        statusText = status == null ? "" : status;
    }

    public void setState(int newState) {
        state = newState;
    }

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

    private int colorForState() {
        switch (state) {
            case UsbReceiver.STATE_CONNECTED:
            case UsbReceiver.STATE_STREAMING:
                return 0xFF66BB6A;
            case UsbReceiver.STATE_LOST:
                return 0xFFFFA726;
            case UsbReceiver.STATE_ERROR:
                return 0xFFEF5350;
            default:
                return 0xFF4FC3F7;
        }
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        lastBatteryTime = 0;
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
        updateBattery(now);

        float base = Math.min(w, h);

        datePaint.setTextSize(base * 0.065f);
        statusPaint.setTextSize(base * 0.04f);

        canvas.save();
        canvas.translate(shiftX, shiftY);

        switch (clockStyle) {
            case STYLE_FLIP:
                drawFlipClock(canvas, w, h, base);
                break;
            case STYLE_ANALOG:
                drawAnalogClock(canvas, w, h, base, now);
                break;
            default:
                drawMinimalClock(canvas, w, h, base);
                break;
        }

        drawBattery(canvas, w, base);
        drawStatus(canvas, w, h, base, now);

        canvas.restore();
    }

    // ---------------------------------------------------------------- clocks

    private void drawMinimalClock(Canvas canvas, int w, int h, float base) {
        timePaint.setTextSize(base * 0.38f);

        float cx = w / 2f;
        float cy = h * 0.46f;

        canvas.drawText(timeText, cx, cy, timePaint);
        canvas.drawText(dateText, cx, cy + base * 0.12f, datePaint);
    }

    private void drawFlipClock(Canvas canvas, int w, int h, float base) {
        int len = timeText.length();
        if (len == 0) {
            return;
        }

        int colons = 0;
        for (int i = 0; i < len; i++) {
            if (timeText.charAt(i) == ':') {
                colons++;
            }
        }
        int digits = len - colons;

        float gap = 0.06f;
        float colonUnit = 0.35f;
        float units = digits + colons * colonUnit + (len - 1) * gap;

        float cardW = Math.min(base * 0.32f, (w * 0.88f) / units);
        float cardH = cardW * 1.35f;
        float radius = cardW * 0.08f;

        float totalW = units * cardW;
        float x = (w - totalW) / 2f;
        float cardTop = h * 0.42f - cardH / 2f;

        flipDigitPaint.setTextSize(cardH * 0.72f);
        Paint.FontMetrics fm = flipDigitPaint.getFontMetrics();
        float textY = cardTop + cardH / 2f - (fm.ascent + fm.descent) / 2f;

        for (int i = 0; i < len; i++) {
            char ch = timeText.charAt(i);

            if (ch == ':') {
                float colonW = cardW * colonUnit;
                float dotR = cardW * 0.045f;
                float dx = x + colonW / 2f;
                canvas.drawCircle(dx, cardTop + cardH * 0.36f, dotR, flipDigitPaint);
                canvas.drawCircle(dx, cardTop + cardH * 0.64f, dotR, flipDigitPaint);
                x += colonW + cardW * gap;
            } else {
                rect.set(x, cardTop, x + cardW, cardTop + cardH);
                canvas.drawRoundRect(rect, radius, radius, cardPaint);

                canvas.drawText(String.valueOf(ch), x + cardW / 2f, textY, flipDigitPaint);

                // Split line across the middle of the card.
                float mid = cardTop + cardH / 2f;
                ringPaint.setColor(0xFF000000);
                ringPaint.setStrokeWidth(Math.max(2f, cardH * 0.012f));
                canvas.drawLine(x, mid, x + cardW, mid, ringPaint);

                x += cardW + cardW * gap;
            }
        }

        canvas.drawText(dateText, w / 2f, cardTop + cardH + base * 0.10f, datePaint);
    }

    private void drawAnalogClock(Canvas canvas, int w, int h, float base, long now) {
        cal.setTimeInMillis(now);

        float cx = w / 2f;
        float cy = h * 0.43f;
        float r = base * 0.27f;

        // Dial ring.
        ringPaint.setColor(0xCCFFFFFF);
        ringPaint.setStrokeWidth(r * 0.02f);
        canvas.drawCircle(cx, cy, r, ringPaint);

        // Tick marks.
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30);
            float sin = (float) Math.sin(a);
            float cos = (float) Math.cos(a);
            boolean major = (i % 3 == 0);
            float inner = r * (major ? 0.80f : 0.88f);
            float outer = r * 0.95f;

            handPaint.setColor(0xFFFFFFFF);
            handPaint.setStrokeWidth(r * (major ? 0.035f : 0.018f));
            canvas.drawLine(
                    cx + inner * sin, cy - inner * cos,
                    cx + outer * sin, cy - outer * cos,
                    handPaint);
        }

        float ms = cal.get(Calendar.MILLISECOND);
        float sec = cal.get(Calendar.SECOND) + ms / 1000f;
        float min = cal.get(Calendar.MINUTE) + sec / 60f;
        float hour = (cal.get(Calendar.HOUR) % 12) + min / 60f;

        drawHand(canvas, cx, cy, hour * 30f, r * 0.50f, r * 0.055f, 0xFFFFFFFF);
        drawHand(canvas, cx, cy, min * 6f, r * 0.76f, r * 0.035f, 0xFFFFFFFF);
        drawHand(canvas, cx, cy, sec * 6f, r * 0.86f, r * 0.015f, 0xFFFF7043);

        dotPaint.setColor(0xFFFF7043);
        dotPaint.setAlpha(255);
        canvas.drawCircle(cx, cy, r * 0.045f, dotPaint);

        canvas.drawText(dateText, cx, cy + r + base * 0.09f, datePaint);
    }

    private void drawHand(Canvas canvas, float cx, float cy, float degrees,
                          float length, float width, int color) {
        double a = Math.toRadians(degrees);
        handPaint.setColor(color);
        handPaint.setStrokeWidth(width);
        canvas.drawLine(
                cx, cy,
                cx + length * (float) Math.sin(a),
                cy - length * (float) Math.cos(a),
                handPaint);
    }

    // --------------------------------------------------------------- battery

    private void updateBattery(long now) {
        if (now - lastBatteryTime < 10000) {
            return;
        }
        lastBatteryTime = now;

        try {
            Intent i = getContext().registerReceiver(
                    null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) {
                return;
            }

            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

            batteryPercent = (level >= 0 && scale > 0) ? (level * 100 / scale) : -1;
            charging = plugged != 0
                    || status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Exception ignored) {
        }
    }

    private void drawBattery(Canvas canvas, int w, float base) {
        if (batteryPercent < 0) {
            return;
        }

        float margin = base * 0.05f;
        float bh = base * 0.04f;
        float bw = bh * 1.9f;
        float nubW = bh * 0.14f;

        float top = margin;
        float bodyRight = w - margin - nubW;
        float bodyLeft = bodyRight - bw;

        int color;
        if (charging) {
            color = 0xFF66BB6A;
        } else if (batteryPercent <= 15) {
            color = 0xFFEF5350;
        } else {
            color = 0xFFFFFFFF;
        }

        // Outline.
        batteryPaint.setStyle(Paint.Style.STROKE);
        batteryPaint.setStrokeWidth(Math.max(2f, bh * 0.09f));
        batteryPaint.setColor(color);
        rect.set(bodyLeft, top, bodyRight, top + bh);
        canvas.drawRoundRect(rect, bh * 0.15f, bh * 0.15f, batteryPaint);

        // Nub.
        batteryPaint.setStyle(Paint.Style.FILL);
        rect.set(bodyRight, top + bh * 0.3f, bodyRight + nubW, top + bh * 0.7f);
        canvas.drawRect(rect, batteryPaint);

        // Fill level.
        float pad = bh * 0.16f;
        float fillW = (bw - pad * 2f) * batteryPercent / 100f;
        rect.set(bodyLeft + pad, top + pad, bodyLeft + pad + fillW, top + bh - pad);
        canvas.drawRect(rect, batteryPaint);

        // Percentage text.
        batteryTextPaint.setColor(color);
        batteryTextPaint.setTextSize(bh * 0.95f);
        Paint.FontMetrics fm = batteryTextPaint.getFontMetrics();
        String text = batteryPercent + "%";
        float textRight = bodyLeft - bh * 0.4f;
        float textY = top + bh / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, textRight, textY, batteryTextPaint);

        // Lightning bolt while charging.
        if (charging) {
            float textW = batteryTextPaint.measureText(text);
            float bx = textRight - textW - bh * 0.55f;
            float by = top + bh / 2f;
            float s = bh * 0.55f;

            boltPath.reset();
            boltPath.moveTo(bx + 0.10f * s, by - s);
            boltPath.lineTo(bx - 0.45f * s, by + 0.10f * s);
            boltPath.lineTo(bx - 0.05f * s, by + 0.10f * s);
            boltPath.lineTo(bx - 0.10f * s, by + s);
            boltPath.lineTo(bx + 0.45f * s, by - 0.15f * s);
            boltPath.lineTo(bx + 0.05f * s, by - 0.15f * s);
            boltPath.close();
            canvas.drawPath(boltPath, batteryPaint);
        }
    }

    // ---------------------------------------------------------------- status

    private void drawStatus(Canvas canvas, int w, int h, float base, long now) {
        float cx = w / 2f;

        // Keep status clear of the bezel at the bottom.
        float statusY = h - h * SAFE_BOTTOM_FRACTION - base * 0.075f;

        // Pulse: 0..1 over ~2.4 s.
        float pulse = (float) (0.5 + 0.5 * Math.sin(now / 380.0));

        String label = labelForState();
        dotPaint.setColor(colorForState());

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
            canvas.drawText(statusText, cx, statusY + base * 0.045f, statusPaint);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void updateGradient(long now, int w, int h) {
        if (now - lastGradientTime < 1000) {
            return;
        }
        lastGradientTime = now;

        float drift = (now / 1000f * 3f) % 360f;
        float[] t = THEMES[theme];

        hsv[0] = (t[0] + 30f * (float) Math.sin(Math.toRadians(drift)) + 360f) % 360f;
        hsv[1] = 0.65f;
        hsv[2] = 0.22f;
        int c1 = Color.HSVToColor(hsv);

        hsv[0] = (t[1] + 30f * (float) Math.cos(Math.toRadians(drift)) + 360f) % 360f;
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