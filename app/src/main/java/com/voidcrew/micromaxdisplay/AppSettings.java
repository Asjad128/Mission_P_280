package com.voidcrew.micromaxdisplay;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

public final class AppSettings {

    public static final String PREFS = "app_settings";

    public static final int NIGHT_OFF = 0;
    public static final int NIGHT_ON = 1;
    public static final int NIGHT_SCHEDULE = 2;

    public static final int SCALE_FIT = 0;
    public static final int SCALE_FILL = 1;
    public static final int SCALE_STRETCH = 2;

    public static final int AWAKE_ALWAYS = 0;
    public static final int AWAKE_CONNECTED = 1;
    public static final int AWAKE_NEVER = 2;

    public static final int CLOCK_SYSTEM = 0;
    public static final int CLOCK_12 = 1;
    public static final int CLOCK_24 = 2;

    public static final int CLOCK_STYLE_COUNT = 3;
    public static final int THEME_COUNT = 4;

    public static final int[] NIGHT_COLORS = {
            0xFFFF8C1A,   // amber
            0xFFFFC266,   // warm white
            0xFFFF2A1A,   // red
            0xFFFF7FA8    // pink
    };

    public static final String[] NIGHT_COLOR_NAMES = {
            "Amber", "Warm white", "Red", "Pink"
    };

    private static final float MAX_TINT = 0.75f;

    private static AppSettings instance;

    public static synchronized AppSettings get(Context context) {
        if (instance == null) {
            instance = new AppSettings(context.getApplicationContext());
        }
        return instance;
    }

    /** Read directly by AutoLaunchReceiver. */
    public static boolean isAutoLaunchEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("autoLaunch", true);
    }

    private final SharedPreferences prefs;

    // Idle screen
    public int clockStyle;
    public int theme;
    public int clockFormat;
    public boolean autoDimEnabled;
    public int autoDimMinutes;

    // Display
    public int scaleMode;

    // Power
    public int keepAwake;

    // Night light
    public int nightMode;
    public int nightStrength;   // 0..100
    public int nightDim;        // 0..80
    public int nightColor;
    public int schedStart;      // minutes after midnight
    public int schedEnd;
    public int rampMinutes;

    // Startup
    public boolean autoLaunch;

    private AppSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        clockStyle = prefs.getInt("clockStyle", 0) % CLOCK_STYLE_COUNT;
        theme = prefs.getInt("theme", 0) % THEME_COUNT;
        clockFormat = prefs.getInt("clockFormat", CLOCK_SYSTEM) % 3;
        autoDimEnabled = prefs.getBoolean("autoDimEnabled", true);
        autoDimMinutes = prefs.getInt("autoDimMinutes", 5);

        scaleMode = prefs.getInt("scaleMode", SCALE_FIT) % 3;
        keepAwake = prefs.getInt("keepAwake", AWAKE_ALWAYS) % 3;

        nightMode = prefs.getInt("nightMode", NIGHT_OFF) % 3;
        nightStrength = prefs.getInt("nightStrength", 40);
        nightDim = prefs.getInt("nightDim", 0);
        nightColor = prefs.getInt("nightColor", 0) % NIGHT_COLORS.length;
        schedStart = prefs.getInt("schedStart", 22 * 60);
        schedEnd = prefs.getInt("schedEnd", 6 * 60);
        rampMinutes = prefs.getInt("rampMinutes", 30);

        autoLaunch = prefs.getBoolean("autoLaunch", true);
    }

    public void save() {
        prefs.edit()
                .putInt("clockStyle", clockStyle)
                .putInt("theme", theme)
                .putInt("clockFormat", clockFormat)
                .putBoolean("autoDimEnabled", autoDimEnabled)
                .putInt("autoDimMinutes", autoDimMinutes)
                .putInt("scaleMode", scaleMode)
                .putInt("keepAwake", keepAwake)
                .putInt("nightMode", nightMode)
                .putInt("nightStrength", nightStrength)
                .putInt("nightDim", nightDim)
                .putInt("nightColor", nightColor)
                .putInt("schedStart", schedStart)
                .putInt("schedEnd", schedEnd)
                .putInt("rampMinutes", rampMinutes)
                .putBoolean("autoLaunch", autoLaunch)
                .apply();
    }

    /**
     * One ARGB color that combines warm tint and dimming.
     * 0 means "no filter right now".
     */
    public int nightOverlayColor(long nowMillis) {
        float factor;

        if (nightMode == NIGHT_ON) {
            factor = 1f;
        } else if (nightMode == NIGHT_SCHEDULE) {
            factor = scheduleFactor(nowMillis);
        } else {
            return 0;
        }

        if (factor <= 0f) {
            return 0;
        }

        float a = nightStrength / 100f * MAX_TINT * factor;
        float d = nightDim / 100f * factor;

        float alpha = 1f - (1f - a) * (1f - d);
        if (alpha < 0.004f) {
            return 0;
        }

        float k = a * (1f - d) / alpha;
        int t = NIGHT_COLORS[nightColor];

        int r = Math.round(((t >> 16) & 0xFF) * k);
        int g = Math.round(((t >> 8) & 0xFF) * k);
        int b = Math.round((t & 0xFF) * k);

        return (Math.round(alpha * 255f) << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * 0..1. Fades in after the start time and fades out before the end time.
     * Works for overnight ranges such as 22:00 to 06:00.
     */
    private float scheduleFactor(long nowMillis) {
        if (schedStart == schedEnd) {
            return 0f;
        }

        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMillis);

        float minuteOfDay = c.get(Calendar.HOUR_OF_DAY) * 60f
                + c.get(Calendar.MINUTE)
                + c.get(Calendar.SECOND) / 60f;

        float length = (schedEnd - schedStart + 1440) % 1440;
        float since = (minuteOfDay - schedStart + 1440f) % 1440f;

        if (since >= length) {
            return 0f;
        }

        if (rampMinutes <= 0) {
            return 1f;
        }

        float ramp = Math.min(rampMinutes, Math.max(1f, length / 2f));
        float until = length - since;

        float f = Math.min(since / ramp, until / ramp);
        if (f > 1f) {
            f = 1f;
        }
        return f;
    }
}