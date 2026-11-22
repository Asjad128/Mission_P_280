package com.voidcrew.micromaxdisplay;

import android.content.Context;
import android.content.SharedPreferences;
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

public final class NightLightPanel extends FrameLayout {

    public interface Listener {
        void onNightLightChanged();
    }

    private static final String PREFS = "night_filter";

    private static final int[] COLORS = {
            0xFFFF8C1A,   // amber
            0xFFFFC266,   // warm white
            0xFFFF2A1A,   // red
            0xFFFF7FA8    // pink
    };

    private static final String[] NAMES = {"Amber", "Warm white", "Red", "Pink"};

    // Strongest possible tint (0..1).
    private static final float MAX_TINT = 0.75f;

    private final SharedPreferences prefs;
    private final Listener listener;
    private final View[] swatches = new View[COLORS.length];

    private Switch filterSwitch;
    private TextView strengthLabel;
    private TextView dimLabel;
    private TextView colorLabel;

    private boolean enabled;
    private int strength;     // 0..100
    private int dim;          // 0..80
    private int colorIndex;

    public NightLightPanel(Context context, Listener listener) {
        super(context);
        this.listener = listener;

        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        enabled = prefs.getBoolean("enabled", false);
        strength = prefs.getInt("strength", 40);
        dim = prefs.getInt("dim", 0);
        colorIndex = prefs.getInt("color", 0) % COLORS.length;

        setVisibility(GONE);

        // Tapping outside the card closes the panel.
        setClickable(true);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(16), dp(20), dp(16));
        column.setClickable(true);   // absorb taps inside the card

        column.addView(text("Night light", 20, true));

        // On / off.
        filterSwitch = new Switch(context);
        filterSwitch.setText("Night filter (also over live video)");
        filterSwitch.setTextColor(0xFFFFFFFF);
        filterSwitch.setChecked(enabled);
        filterSwitch.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        enabled = checked;
                        changed();
                    }
                });
        addRow(column, filterSwitch, 14);

        // Strength.
        strengthLabel = text("", 15, false);
        addRow(column, strengthLabel, 14);

        SeekBar strengthBar = new SeekBar(context);
        strengthBar.setMax(100);
        strengthBar.setProgress(strength);
        strengthBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                strength = p;
                if (fromUser && !enabled) {
                    filterSwitch.setChecked(true);
                }
                changed();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        addRow(column, strengthBar, 4);

        // Dimming.
        dimLabel = text("", 15, false);
        addRow(column, dimLabel, 10);

        SeekBar dimBar = new SeekBar(context);
        dimBar.setMax(80);
        dimBar.setProgress(dim);
        dimBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                dim = p;
                if (fromUser && !enabled) {
                    filterSwitch.setChecked(true);
                }
                changed();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        addRow(column, dimBar, 4);

        // Color swatches.
        colorLabel = text("", 15, false);
        addRow(column, colorLabel, 10);

        LinearLayout swatchRow = new LinearLayout(context);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);

        for (int i = 0; i < COLORS.length; i++) {
            final int index = i;
            View swatch = new View(context);
            swatches[i] = swatch;

            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.rightMargin = dp(12);
            swatchRow.addView(swatch, lp);

            swatch.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    colorIndex = index;
                    if (!enabled) {
                        filterSwitch.setChecked(true);
                    }
                    changed();
                }
            });
        }
        addRow(column, swatchRow, 6);

        // Done.
        Button done = new Button(context);
        done.setText("Done");
        done.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });
        addRow(column, done, 16);

        refreshUi();

        ScrollView scroll = new ScrollView(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0202028);
        bg.setCornerRadius(dp(16));
        scroll.setBackground(bg);
        scroll.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int cardW = Math.min(screenW - dp(32), dp(440));

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                cardW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cardParams.topMargin = dp(16);
        cardParams.bottomMargin = dp(16);
        addView(scroll, cardParams);
    }

    // ------------------------------------------------------------ public API

    public void show() {
        setVisibility(VISIBLE);
    }

    public void hide() {
        setVisibility(GONE);
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    public boolean isFilterEnabled() {
        return enabled;
    }

    /**
     * One ARGB color that combines the warm tint and the dimming,
     * so a single translucent layer does both jobs. 0 = no filter.
     */
    public int getOverlayColor() {
        if (!enabled) {
            return 0;
        }

        float a = strength / 100f * MAX_TINT;   // tint opacity
        float d = dim / 100f;                   // black opacity

        float alpha = 1f - (1f - a) * (1f - d);
        if (alpha < 0.004f) {
            return 0;
        }

        float k = a * (1f - d) / alpha;
        int t = COLORS[colorIndex];

        int r = Math.round(((t >> 16) & 0xFF) * k);
        int g = Math.round(((t >> 8) & 0xFF) * k);
        int b = Math.round((t & 0xFF) * k);

        return (Math.round(alpha * 255f) << 24) | (r << 16) | (g << 8) | b;
    }

    // --------------------------------------------------------------- helpers

    private void changed() {
        refreshUi();

        prefs.edit()
                .putBoolean("enabled", enabled)
                .putInt("strength", strength)
                .putInt("dim", dim)
                .putInt("color", colorIndex)
                .apply();

        if (listener != null) {
            listener.onNightLightChanged();
        }
    }

    private void refreshUi() {
        strengthLabel.setText("Warmth strength: " + strength + "%");
        dimLabel.setText("Dimming: " + dim + "%");
        colorLabel.setText("Color: " + NAMES[colorIndex]);

        for (int i = 0; i < swatches.length; i++) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(COLORS[i]);
            if (i == colorIndex) {
                d.setStroke(dp(3), 0xFFFFFFFF);
            } else {
                d.setStroke(dp(1), 0x55FFFFFF);
            }
            swatches[i].setBackground(d);
        }
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(sp);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private void addRow(LinearLayout column, View view, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        column.addView(view, lp);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}   