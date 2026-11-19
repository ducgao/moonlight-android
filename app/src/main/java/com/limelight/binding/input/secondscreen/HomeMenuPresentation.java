package com.limelight.binding.input.secondscreen;

import android.annotation.SuppressLint;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu shown on the secondary (bottom) display while browsing PCs, holding the
 * actions that used to sit as small buttons on the main screen (Settings, Help, Add PC).
 */
public class HomeMenuPresentation extends Presentation {
    public static final class Action {
        final int iconRes;
        final String label;
        final Runnable onClick;

        public Action(int iconRes, String label, Runnable onClick) {
            this.iconRes = iconRes;
            this.label = label;
            this.onClick = onClick;
        }
    }

    private final List<Action> actions = new ArrayList<>();

    public HomeMenuPresentation(Context outerContext, Display display, List<Action> actions) {
        super(outerContext, display);
        this.actions.addAll(actions);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (window != null) {
            // Keep gamepad focus on the PC carousel on the main screen
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }

        Context ctx = getContext();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);

        for (Action action : actions) {
            // Square-ish tiles side by side, vertically centered
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(120), 1f);
            int m = dp(7);
            lp.setMargins(m, m, m, m);
            root.addView(createTile(ctx, action), lp);
        }

        setContentView(root);
        hideSystemBars(root);
    }

    @SuppressLint("ClickableViewAccessibility")
    private View createTile(Context ctx, final Action action) {
        LinearLayout tile = new LinearLayout(ctx);
        tile.setGravity(Gravity.CENTER);
        tile.setContentDescription(action.label);
        tile.setBackground(tileBackground());
        tile.setClickable(true);
        tile.setHapticFeedbackEnabled(true);
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                action.onClick.run();
            }
        });

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(action.iconRes);
        icon.setColorFilter(Color.WHITE);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        return tile;
    }

    private StateListDrawable tileBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_pressed}, roundRect(Color.rgb(52, 52, 62), Color.argb(230, 255, 255, 255)));
        states.addState(new int[] {}, roundRect(Color.rgb(24, 24, 30), Color.rgb(58, 58, 68)));
        return states;
    }

    private GradientDrawable roundRect(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(20));
        d.setStroke(dp(1.5f), stroke);
        return d;
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

    private int dp(float v) {
        return Math.round(v * getContext().getResources().getDisplayMetrics().density);
    }
}
