package com.limelight.debugpreview;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.FrameLayout;

import com.limelight.binding.input.secondscreen.ScreenKeyboardView;
import com.limelight.binding.input.secondscreen.SecondScreenInputListener;

/** Debug-only: shows the second-screen keyboard without starting a stream. */
public class KeyboardPreviewActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(24, 24, 28));
        ScreenKeyboardView keyboard = new ScreenKeyboardView(this, new SecondScreenInputListener() {
            @Override public void onMouseMove(short deltaX, short deltaY) {}
            @Override public void onMouseButton(byte button, boolean down) {}
            @Override public void onVerticalScroll(short amount) {}
            @Override public void onHorizontalScroll(short amount) {}
            @Override public void onKeyboardKey(int androidKeyCode, boolean down) {}
            @Override public void onBackToAppList() { finish(); }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        lp.topMargin = Math.round(56 * getResources().getDisplayMetrics().density);
        root.addView(keyboard, lp);
        setContentView(root);
    }
}
