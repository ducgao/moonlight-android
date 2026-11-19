package com.limelight.binding.input.secondscreen;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Content shown on the secondary (bottom) display while streaming:
 * a trackpad by default, with a corner button that swaps to an on-screen keyboard.
 */
public class SecondScreenPresentation extends Presentation {
    private final SecondScreenInputListener listener;

    private TrackpadView trackpad;
    private ScreenKeyboardView keyboard;
    private TextView modeButton;
    private boolean keyboardMode;
    private FrameLayout root;
    private View settingsPanel;

    public SecondScreenPresentation(Context outerContext, Display display, SecondScreenInputListener listener) {
        super(outerContext, display);
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (window != null) {
            // Never take key focus: gamepad / physical keys must keep going to the stream activity
            // on the main display. Touch input still reaches this window.
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        Context ctx = getContext();
        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.rgb(24, 24, 28));

        trackpad = new TrackpadView(ctx, listener);
        root.addView(trackpad, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int buttonSize = dp(44);
        int margin = dp(6);

        keyboard = new ScreenKeyboardView(ctx, listener);
        // The keyboard covers the bottom 2/3; the top third stays a working trackpad
        final FrameLayout.LayoutParams kbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM);
        keyboard.setVisibility(View.GONE);
        root.addView(keyboard, kbLp);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int height = (bottom - top) * 2 / 3;
                if (kbLp.height != height) {
                    kbLp.height = height;
                    keyboard.post(new Runnable() {
                        @Override
                        public void run() {
                            keyboard.setLayoutParams(kbLp);
                        }
                    });
                }
            }
        });

        modeButton = new TextView(ctx);
        modeButton.setGravity(Gravity.CENTER);
        modeButton.setTextColor(Color.WHITE);
        modeButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(200, 60, 60, 70));
        bg.setCornerRadius(dp(10));
        modeButton.setBackground(bg);
        modeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setKeyboardMode(!keyboardMode);
            }
        });
        FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(buttonSize, buttonSize,
                Gravity.TOP | Gravity.END);
        btnLp.setMargins(margin, margin, margin, margin);
        root.addView(modeButton, btnLp);

        // Top-left: leave the stream and go back to the app list
        ImageView backButton = new ImageView(ctx);
        backButton.setImageResource(com.limelight.R.drawable.ic_stream_back);
        int iconPad = dp(11);
        backButton.setPadding(iconPad, iconPad, iconPad, iconPad);
        GradientDrawable backBg = new GradientDrawable();
        backBg.setColor(Color.argb(200, 60, 60, 70));
        backBg.setCornerRadius(dp(10));
        backButton.setBackground(backBg);
        backButton.setContentDescription(ctx.getString(com.limelight.R.string.stream_back));
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onBackToAppList();
            }
        });
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(buttonSize, buttonSize,
                Gravity.TOP | Gravity.START);
        backLp.setMargins(margin, margin, margin, margin);
        root.addView(backButton, backLp);

        // Next to it: trackpad settings (pointer speed, vibrate on click)
        ImageView settingsButton = new ImageView(ctx);
        settingsButton.setImageResource(com.limelight.R.drawable.ic_settings);
        settingsButton.setColorFilter(Color.WHITE);
        settingsButton.setPadding(iconPad, iconPad, iconPad, iconPad);
        GradientDrawable settingsBg = new GradientDrawable();
        settingsBg.setColor(Color.argb(200, 60, 60, 70));
        settingsBg.setCornerRadius(dp(10));
        settingsButton.setBackground(settingsBg);
        settingsButton.setContentDescription(ctx.getString(com.limelight.R.string.trackpad_settings_title));
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsPanel();
            }
        });
        FrameLayout.LayoutParams settingsLp = new FrameLayout.LayoutParams(buttonSize, buttonSize,
                Gravity.TOP | Gravity.START);
        settingsLp.setMargins(margin * 2 + buttonSize, margin, margin, margin);
        root.addView(settingsButton, settingsLp);

        this.root = root;
        setContentView(root);
        setKeyboardMode(false);
        hideSystemBars(root);
    }

    private static final int SPEED_STEPS = Math.round(
            (TrackpadSettings.MAX_SENSITIVITY - TrackpadSettings.MIN_SENSITIVITY) * 10);

    private void showSettingsPanel() {
        if (settingsPanel != null) {
            return;
        }
        final Context ctx = getContext();

        // Dimmed overlay; tapping outside the card closes it
        FrameLayout overlay = new FrameLayout(ctx);
        overlay.setBackgroundColor(Color.argb(170, 0, 0, 0));
        overlay.setClickable(true);
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSettingsPanel();
            }
        });

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true); // swallow taps so they don't close the panel
        int pad = dp(20);
        card.setPadding(pad, pad, pad, dp(12));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.rgb(32, 32, 38));
        cardBg.setCornerRadius(dp(18));
        cardBg.setStroke(dp(1), Color.rgb(58, 58, 68));
        card.setBackground(cardBg);

        TextView title = new TextView(ctx);
        title.setText(com.limelight.R.string.trackpad_settings_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);

        // Pointer speed
        LinearLayout speedRow = new LinearLayout(ctx);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        speedRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(18);
        TextView speedLabel = new TextView(ctx);
        speedLabel.setText(com.limelight.R.string.trackpad_settings_speed);
        speedLabel.setTextColor(Color.rgb(229, 229, 234));
        speedLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        speedRow.addView(speedLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView speedValue = new TextView(ctx);
        speedValue.setTextColor(Color.rgb(154, 154, 165));
        speedValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        speedRow.addView(speedValue);
        card.addView(speedRow, rowLp);

        SeekBar speed = new SeekBar(ctx);
        speed.setMax(SPEED_STEPS);
        float current = TrackpadSettings.getSensitivity(ctx);
        speed.setProgress(Math.round((current - TrackpadSettings.MIN_SENSITIVITY) * 10));
        speedValue.setText(String.format(java.util.Locale.US, "%.1fx", current));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = TrackpadSettings.MIN_SENSITIVITY + progress / 10f;
                speedValue.setText(String.format(java.util.Locale.US, "%.1fx", value));
                trackpad.setSensitivity(value);
                TrackpadSettings.setSensitivity(ctx, value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        card.addView(speed, seekLp);

        // Vibrate on click
        Switch haptic = new Switch(ctx);
        haptic.setText(com.limelight.R.string.trackpad_settings_haptic);
        haptic.setTextColor(Color.rgb(229, 229, 234));
        haptic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        haptic.setChecked(TrackpadSettings.isHapticOnClick(ctx));
        haptic.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                trackpad.setHapticOnClick(isChecked);
                TrackpadSettings.setHapticOnClick(ctx, isChecked);
            }
        });
        LinearLayout.LayoutParams hapticLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        hapticLp.topMargin = dp(6);
        card.addView(haptic, hapticLp);

        TextView done = new TextView(ctx);
        done.setText(com.limelight.R.string.trackpad_settings_done);
        done.setTextColor(Color.WHITE);
        done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        done.setGravity(Gravity.CENTER);
        GradientDrawable doneBg = new GradientDrawable();
        doneBg.setColor(Color.rgb(58, 58, 68));
        doneBg.setCornerRadius(dp(12));
        done.setBackground(doneBg);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSettingsPanel();
            }
        });
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        doneLp.topMargin = dp(10);
        card.addView(done, doneLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                dp(320), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        overlay.addView(card, cardLp);

        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        settingsPanel = overlay;
    }

    private void hideSettingsPanel() {
        if (settingsPanel != null) {
            root.removeView(settingsPanel);
            settingsPanel = null;
        }
    }

    private void setKeyboardMode(boolean enabled) {
        keyboardMode = enabled;
        if (enabled) {
            // Trackpad stays active above the keyboard; just hide its gesture hint
            trackpad.setShowHint(false);
            keyboard.setVisibility(View.VISIBLE);
            modeButton.setText("🖱"); // mouse: switch back to trackpad
        }
        else {
            keyboard.reset();
            keyboard.setVisibility(View.GONE);
            trackpad.setShowHint(true);
            modeButton.setText("⌨"); // keyboard
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars(View root) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getWindow() != null) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        else {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    protected void onStop() {
        // Make sure nothing stays pressed on the host when we go away
        if (trackpad != null) {
            trackpad.reset();
        }
        if (keyboard != null) {
            keyboard.reset();
        }
        super.onStop();
    }

    private int dp(float v) {
        return Math.round(v * getContext().getResources().getDisplayMetrics().density);
    }
}
