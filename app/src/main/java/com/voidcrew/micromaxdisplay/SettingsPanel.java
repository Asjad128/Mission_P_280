package com.voidcrew.micromaxdisplay;

import android.app.TimePickerDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;

import java.util.ArrayList;
import java.util.Locale;

public final class SettingsPanel extends FrameLayout {

    public interface Listener {
        void onSettingsChanged();
    }

    private static abstract class IntBinding {
        abstract int get();
        abstract void set(int value);
    }

    private static abstract class BoolBinding {
        abstract boolean get();
        abstract void set(boolean value);
    }

    private final Context ctx;
    private final AppSettings s;
    private final Listener listener;
    private final ArrayList<Runnable> refreshers = new ArrayList<Runnable>();

    private LinearLayout column;
    private LinearLayout scheduleBox;
    private boolean refreshing = false;

    public SettingsPanel(Context context, AppSettings settings, Listener listener) {
        super(context);
        this.ctx = context;
        this.s = settings;
        this.listener = listener;

        setVisibility(GONE);
        setClickable(true);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(16), dp(20), dp(16));
        column.setClickable(true);   // absorb taps inside the card

        column.addView(text("Settings", 22, true));

        // ------------------------------------------------------------ Display
        section("Display");
        label("Video scaling");
        add(segmented(new String[]{"Fit", "Fill", "Stretch"}, null, new IntBinding() {
            int get() { return s.scaleMode; }
            void set(int v) { s.scaleMode = v; }
        }), 6);
        hint("Fit keeps the aspect ratio (black bars). Fill crops the edges to "
                + "cover the whole screen. Stretch ignores the aspect ratio.");

        // -------------------------------------------------------- Idle screen
        section("Idle screen");
        label("Clock style");
        add(segmented(new String[]{"Minimal", "Flip", "Analog"}, null, new IntBinding() {
            int get() { return s.clockStyle; }
            void set(int v) { s.clockStyle = v; }
        }), 6);

        label("Background theme");
        add(segmented(new String[]{"Blue", "Teal", "Amber", "Green"}, null, new IntBinding() {
            int get() { return s.theme; }
            void set(int v) { s.theme = v; }
        }), 6);

        label("Time format");
        add(segmented(new String[]{"System", "12-hour", "24-hour"}, null, new IntBinding() {
            int get() { return s.clockFormat; }
            void set(int v) { s.clockFormat = v; }
        }), 6);

        toggle("Auto-dim idle screen", new BoolBinding() {
            boolean get() { return s.autoDimEnabled; }
            void set(boolean v) { s.autoDimEnabled = v; }
        });
        label("Dim after");
        add(segmented(
                new String[]{"1 min", "2 min", "5 min", "10 min", "30 min"},
                new int[]{1, 2, 5, 10, 30},
                new IntBinding() {
                    int get() { return s.autoDimMinutes; }
                    void set(int v) { s.autoDimMinutes = v; }
                }), 6);
        hint("Shows a small dim clock and lowers the backlight. Touch the "
                + "screen to wake it.");

        // ---------------------------------------------------------- Night light
        section("Night light");
        add(segmented(new String[]{"Off", "Always on", "Scheduled"}, null, new IntBinding() {
            int get() { return s.nightMode; }
            void set(int v) { s.nightMode = v; }
        }), 6);

        seek("Warmth strength", "%", 100, new IntBinding() {
            int get() { return s.nightStrength; }
            void set(int v) { s.nightStrength = v; autoOn(); }
        });
        seek("Dimming", "%", 80, new IntBinding() {
            int get() { return s.nightDim; }
            void set(int v) { s.nightDim = v; autoOn(); }
        });

        label("Color");
        add(colorSwatches(), 6);

        scheduleBox = new LinearLayout(ctx);
        scheduleBox.setOrientation(LinearLayout.VERTICAL);

        LinearLayout times = new LinearLayout(ctx);
        times.setOrientation(LinearLayout.HORIZONTAL);
        addTo(times, timeButton("Start", new IntBinding() {
            int get() { return s.schedStart; }
            void set(int v) { s.schedStart = v; }
        }), 1f);
        addTo(times, timeButton("End", new IntBinding() {
            int get() { return s.schedEnd; }
            void set(int v) { s.schedEnd = v; }
        }), 1f);
        addTo(scheduleBox, times, 0f);

        TextView rampLabel = text("Gradual ramp", 15, false);
        addTo(scheduleBox, rampLabel, 0f);
        ((LinearLayout.LayoutParams) rampLabel.getLayoutParams()).topMargin = dp(12);

        View ramp = segmented(
                new String[]{"Off", "15 min", "30 min", "60 min"},
                new int[]{0, 15, 30, 60},
                new IntBinding() {
                    int get() { return s.rampMinutes; }
                    void set(int v) { s.rampMinutes = v; }
                });
        addTo(scheduleBox, ramp, 0f);
        ((LinearLayout.LayoutParams) ramp.getLayoutParams()).topMargin = dp(6);

        TextView rampHint = text("The tint fades in after the start time and fades "
                + "out before the end time. Overnight ranges work.", 12, false);
        rampHint.setTextColor(0x99FFFFFF);
        addTo(scheduleBox, rampHint, 0f);
        ((LinearLayout.LayoutParams) rampHint.getLayoutParams()).topMargin = dp(4);

        add(scheduleBox, 10);
        refreshers.add(new Runnable() {
            @Override
            public void run() {
                scheduleBox.setVisibility(
                        s.nightMode == AppSettings.NIGHT_SCHEDULE ? VISIBLE : GONE);
            }
        });

        // --------------------------------------------------------------- Power
        section("Power");
        label("Keep screen awake");
        add(segmented(
                new String[]{"Always", "While connected", "Never"}, null,
                new IntBinding() {
                    int get() { return s.keepAwake; }
                    void set(int v) { s.keepAwake = v; }
                }), 6);
        hint("\"While connected\" lets the tablet sleep when no PC is connected. "
                + "A sleeping tablet may not accept a new connection until you "
                + "wake it.");

        // ------------------------------------------------------------- Startup
        section("Startup");
        toggle("Auto-launch when USB is plugged in", new BoolBinding() {
            boolean get() { return s.autoLaunch; }
            void set(boolean v) { s.autoLaunch = v; }
        });
        hint("Opens the app when the tablet detects a USB cable (power "
                + "connected). The app must have been opened once.");

        // --------------------------------------------------------------- About
        section("About");
        String version = "1.0";
        try {
            version = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView about = text("Version " + version + "   \u2022   Port "
                + UsbReceiver.PORT, 13, false);
        about.setTextColor(0x99FFFFFF);
        add(about, 4);

        Button done = new Button(ctx);
        done.setText("Done");
        done.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });
        add(done, 20);

        // ---------------------------------------------------------------- Card
        ScrollView scroll = new ScrollView(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0202028);
        bg.setCornerRadius(dp(16));
        scroll.setBackground(bg);
        scroll.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int cardW = Math.min(screenW - dp(32), dp(520));

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                cardW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cardParams.topMargin = dp(16);
        cardParams.bottomMargin = dp(16);
        addView(scroll, cardParams);

        refreshUi();
    }

    // ------------------------------------------------------------ public API

    public void show() {
        refreshUi();
        setVisibility(VISIBLE);
    }

    public void hide() {
        setVisibility(GONE);
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    // --------------------------------------------------------------- helpers

    private void autoOn() {
        if (s.nightMode == AppSettings.NIGHT_OFF) {
            s.nightMode = AppSettings.NIGHT_ON;
        }
    }

    private void changed() {
        s.save();
        refreshUi();
        if (listener != null) {
            listener.onSettingsChanged();
        }
    }

    private void refreshUi() {
        refreshing = true;
        for (int i = 0; i < refreshers.size(); i++) {
            refreshers.get(i).run();
        }
        refreshing = false;
    }

    private GradientDrawable pill(boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(8));
        d.setColor(selected ? 0xFF3D7BFF : 0x33FFFFFF);
        return d;
    }

    private View segmented(final String[] labels, final int[] values,
                           final IntBinding binding) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final TextView[] items = new TextView[labels.length];

        for (int i = 0; i < labels.length; i++) {
            final int value = values == null ? i : values[i];

            TextView t = new TextView(ctx);
            t.setText(labels[i]);
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(14);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(6), dp(10), dp(6), dp(10));
            t.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    binding.set(value);
                    changed();
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(6);
            row.addView(t, lp);
            items[i] = t;
        }

        refreshers.add(new Runnable() {
            @Override
            public void run() {
                int current = binding.get();
                for (int i = 0; i < items.length; i++) {
                    int value = values == null ? i : values[i];
                    items[i].setBackground(pill(current == value));
                }
            }
        });

        return row;
    }

    private void seek(final String name, final String unit, int max,
                      final IntBinding binding) {
        final TextView label = text("", 15, false);
        add(label, 12);

        final SeekBar bar = new SeekBar(ctx);
        bar.setMax(max);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                binding.set(progress);
                changed();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        add(bar, 2);

        refreshers.add(new Runnable() {
            @Override
            public void run() {
                int v = binding.get();
                label.setText(name + ": " + v + unit);
                if (bar.getProgress() != v) {
                    bar.setProgress(v);
                }
            }
        });
    }

    private void toggle(String title, final BoolBinding binding) {
        final Switch sw = new Switch(ctx);
        sw.setText(title);
        sw.setTextColor(0xFFFFFFFF);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (refreshing) {
                    return;
                }
                binding.set(checked);
                changed();
            }
        });
        add(sw, 12);

        refreshers.add(new Runnable() {
            @Override
            public void run() {
                sw.setChecked(binding.get());
            }
        });
    }

    private View colorSwatches() {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final View[] swatches = new View[AppSettings.NIGHT_COLORS.length];

        for (int i = 0; i < swatches.length; i++) {
            final int index = i;
            View v = new View(ctx);
            swatches[i] = v;

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.rightMargin = dp(12);
            row.addView(v, lp);

            v.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    s.nightColor = index;
                    autoOn();
                    changed();
                }
            });
        }

        refreshers.add(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < swatches.length; i++) {
                    GradientDrawable d = new GradientDrawable();
                    d.setShape(GradientDrawable.OVAL);
                    d.setColor(AppSettings.NIGHT_COLORS[i]);
                    d.setStroke(i == s.nightColor ? dp(3) : dp(1),
                            i == s.nightColor ? 0xFFFFFFFF : 0x55FFFFFF);
                    swatches[i].setBackground(d);
                }
            }
        });

        return row;
    }

    private Button timeButton(final String prefix, final IntBinding binding) {
        final Button b = new Button(ctx);
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                int minutes = binding.get();
                new TimePickerDialog(ctx, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker view, int hour, int minute) {
                        binding.set(hour * 60 + minute);
                        if (s.nightMode == AppSettings.NIGHT_OFF) {
                            s.nightMode = AppSettings.NIGHT_SCHEDULE;
                        }
                        changed();
                    }
                }, minutes / 60, minutes % 60, true).show();
            }
        });

        refreshers.add(new Runnable() {
            @Override
            public void run() {
                int m = binding.get();
                b.setText(prefix + ": " + String.format(Locale.US, "%02d:%02d",
                        m / 60, m % 60));
            }
        });

        return b;
    }

    private void section(String title) {
        TextView t = text(title.toUpperCase(Locale.US), 13, true);
        t.setTextColor(0xFF8AB4FF);
        add(t, 22);
    }

    private void label(String s) {
        add(text(s, 15, false), 12);
    }

    private void hint(String s) {
        TextView t = text(s, 12, false);
        t.setTextColor(0x99FFFFFF);
        add(t, 4);
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(value);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(sp);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private void add(View view, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        column.addView(view, lp);
    }

    /** Adds a child to any LinearLayout. weight > 0 splits a horizontal row. */
    private void addTo(LinearLayout parent, View view, float weight) {
        LinearLayout.LayoutParams lp;
        if (weight > 0f) {
            lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, weight);
            lp.rightMargin = dp(8);
        } else {
            lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        parent.addView(view, lp);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}