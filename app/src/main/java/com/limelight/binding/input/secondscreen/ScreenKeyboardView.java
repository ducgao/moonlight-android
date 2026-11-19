package com.limelight.binding.input.secondscreen;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Phone-style (iOS-like) keyboard drawn on the second screen. It sends Android key codes
 * straight to the stream, so it does not depend on an IME appearing on the secondary display.
 *
 * Pages:
 *  - ABC: number row, QWERTY letters, Shift / Backspace, "123", Space, Enter
 *  - 123: F1-F10 row, symbols, PC modifiers (Ctrl/Alt/Win), navigation keys and arrows
 *
 * Modifiers are one-shot: tap to latch, they release after the next key. Tapping a latched
 * modifier again releases it. Double-tapping Shift locks it (caps lock) until tapped again.
 */
@SuppressLint("ViewConstructor")
public class ScreenKeyboardView extends LinearLayout {
    private static final int KIND_NORMAL = 0;
    private static final int KIND_MODIFIER = 1;
    private static final int KIND_PAGE = 2;
    private static final int KIND_SPACER = 3;

    private static final int STYLE_LETTER = 0;
    private static final int STYLE_FUNCTION = 1; // darker: shift, backspace, 123...
    private static final int STYLE_SMALL = 2;    // number / F-key row
    private static final int STYLE_ACCENT = 3;   // blue: Enter

    private static final int PAGE_ALPHA = 0;
    private static final int PAGE_SYMBOLS = 1;

    private static final long REPEAT_DELAY_MS = 400;
    private static final long REPEAT_INTERVAL_MS = 50;
    private static final long CAPS_LOCK_DOUBLE_TAP_MS = 350;

    private static final int COLOR_BG_TOP = Color.rgb(30, 30, 34);
    private static final int COLOR_BG_BOTTOM = Color.rgb(18, 18, 21);
    private static final int COLOR_LETTER = Color.rgb(84, 84, 90);
    private static final int COLOR_FUNCTION = Color.rgb(52, 52, 58);
    private static final int COLOR_SMALL = Color.rgb(60, 60, 66);
    private static final int COLOR_PRESSED = Color.rgb(140, 140, 148);
    private static final int COLOR_ACCENT = Color.rgb(10, 132, 255);
    private static final int COLOR_ACCENT_PRESSED = Color.rgb(64, 160, 255);

    private static final class Key {
        final String label;
        final String shiftLabel;
        final int code;
        final float weight;
        final int kind;
        final int style;
        int iconRes;
        TextView view;

        Key(String label, String shiftLabel, int code, float weight, int kind, int style) {
            this.label = label;
            this.shiftLabel = shiftLabel;
            this.code = code;
            this.weight = weight;
            this.kind = kind;
            this.style = style;
        }
    }

    private static final class Row {
        final Key[] keys;
        final float heightWeight;

        Row(float heightWeight, Key... keys) {
            this.keys = keys;
            this.heightWeight = heightWeight;
        }
    }

    private final SecondScreenInputListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Integer> latchedModifiers = new ArrayList<>();
    private final List<Key> allKeys = new ArrayList<>();
    private int page = PAGE_ALPHA;
    private boolean capsLock;
    private long lastShiftTapTime;

    public ScreenKeyboardView(Context context, SecondScreenInputListener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {COLOR_BG_TOP, COLOR_BG_BOTTOM});
        setBackground(bg);
        int padH = dp(4);
        setPadding(padH, dp(6), padH, dp(8));
        setHapticFeedbackEnabled(true);
        // Gaps between keys must not fall through to the trackpad underneath
        setClickable(true);
        buildPage();
    }

    // ---- Layout definitions ----

    private static Key letter(char c) {
        return new Key(String.valueOf(c), String.valueOf(Character.toUpperCase(c)),
                KeyEvent.KEYCODE_A + (c - 'a'), 1f, KIND_NORMAL, STYLE_LETTER);
    }

    private static Key small(String label, String shift, int code) {
        return new Key(label, shift, code, 1f, KIND_NORMAL, STYLE_SMALL);
    }

    private static Key sym(String label, String shift, int code) {
        return new Key(label, shift, code, 1f, KIND_NORMAL, STYLE_LETTER);
    }

    private static Key fn(String label, int code, float weight) {
        return new Key(label, label, code, weight, KIND_NORMAL, STYLE_FUNCTION);
    }

    private static Key mod(String label, int code, float weight) {
        return new Key(label, label, code, weight, KIND_MODIFIER, STYLE_FUNCTION);
    }

    private static Key pageKey(String label, int targetPage, float weight) {
        return new Key(label, label, targetPage, weight, KIND_PAGE, STYLE_FUNCTION);
    }

    private static Key spacer(float weight) {
        return new Key("", "", 0, weight, KIND_SPACER, STYLE_FUNCTION);
    }

    private static Key icon(Key key, int iconRes) {
        key.iconRes = iconRes;
        return key;
    }

    private static Key enter(float weight) {
        return icon(new Key("", "", KeyEvent.KEYCODE_ENTER, weight, KIND_NORMAL, STYLE_ACCENT),
                com.limelight.R.drawable.ic_kb_enter);
    }

    private static Key shiftKey() {
        return icon(mod("", KeyEvent.KEYCODE_SHIFT_LEFT, 1.25f), com.limelight.R.drawable.ic_kb_shift);
    }

    private static Key backspaceKey() {
        return icon(fn("", KeyEvent.KEYCODE_DEL, 1.25f), com.limelight.R.drawable.ic_kb_backspace);
    }

    private static Key space(float weight) {
        return new Key("", "", KeyEvent.KEYCODE_SPACE, weight, KIND_NORMAL, STYLE_LETTER);
    }

    private Row[] alphaRows() {
        return new Row[] {
                new Row(0.72f,
                        small("1", "!", KeyEvent.KEYCODE_1), small("2", "@", KeyEvent.KEYCODE_2),
                        small("3", "#", KeyEvent.KEYCODE_3), small("4", "$", KeyEvent.KEYCODE_4),
                        small("5", "%", KeyEvent.KEYCODE_5), small("6", "^", KeyEvent.KEYCODE_6),
                        small("7", "&", KeyEvent.KEYCODE_7), small("8", "*", KeyEvent.KEYCODE_8),
                        small("9", "(", KeyEvent.KEYCODE_9), small("0", ")", KeyEvent.KEYCODE_0)),
                new Row(1f,
                        letter('q'), letter('w'), letter('e'), letter('r'), letter('t'),
                        letter('y'), letter('u'), letter('i'), letter('o'), letter('p')),
                new Row(1f,
                        spacer(0.5f),
                        letter('a'), letter('s'), letter('d'), letter('f'), letter('g'),
                        letter('h'), letter('j'), letter('k'), letter('l'),
                        spacer(0.5f)),
                new Row(1f,
                        shiftKey(),
                        spacer(0.25f),
                        letter('z'), letter('x'), letter('c'), letter('v'),
                        letter('b'), letter('n'), letter('m'),
                        spacer(0.25f),
                        backspaceKey()),
                new Row(1f,
                        pageKey("123", PAGE_SYMBOLS, 2.5f),
                        space(5f),
                        enter(2.5f)),
        };
    }

    private Row[] symbolRows() {
        return new Row[] {
                new Row(0.72f,
                        small("F1", "F1", KeyEvent.KEYCODE_F1), small("F2", "F2", KeyEvent.KEYCODE_F2),
                        small("F3", "F3", KeyEvent.KEYCODE_F3), small("F4", "F4", KeyEvent.KEYCODE_F4),
                        small("F5", "F5", KeyEvent.KEYCODE_F5), small("F6", "F6", KeyEvent.KEYCODE_F6),
                        small("F7", "F7", KeyEvent.KEYCODE_F7), small("F8", "F8", KeyEvent.KEYCODE_F8),
                        small("F9", "F9", KeyEvent.KEYCODE_F9), small("F10", "F10", KeyEvent.KEYCODE_F10)),
                new Row(1f,
                        fn("Esc", KeyEvent.KEYCODE_ESCAPE, 1f), fn("Tab", KeyEvent.KEYCODE_TAB, 1f),
                        sym("-", "_", KeyEvent.KEYCODE_MINUS), sym("=", "+", KeyEvent.KEYCODE_EQUALS),
                        sym("[", "{", KeyEvent.KEYCODE_LEFT_BRACKET), sym("]", "}", KeyEvent.KEYCODE_RIGHT_BRACKET),
                        sym("\\", "|", KeyEvent.KEYCODE_BACKSLASH), sym(";", ":", KeyEvent.KEYCODE_SEMICOLON),
                        sym("'", "\"", KeyEvent.KEYCODE_APOSTROPHE), sym("`", "~", KeyEvent.KEYCODE_GRAVE)),
                new Row(1f,
                        mod("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, 1f), mod("Alt", KeyEvent.KEYCODE_ALT_LEFT, 1f),
                        mod("Win", KeyEvent.KEYCODE_META_LEFT, 1f), fn("Del", KeyEvent.KEYCODE_FORWARD_DEL, 1f),
                        fn("Home", KeyEvent.KEYCODE_MOVE_HOME, 1f), fn("End", KeyEvent.KEYCODE_MOVE_END, 1f),
                        fn("PgUp", KeyEvent.KEYCODE_PAGE_UP, 1f), fn("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, 1f),
                        fn("F11", KeyEvent.KEYCODE_F11, 1f), fn("F12", KeyEvent.KEYCODE_F12, 1f)),
                new Row(1f,
                        shiftKey(),
                        spacer(0.25f),
                        sym(",", "<", KeyEvent.KEYCODE_COMMA), sym(".", ">", KeyEvent.KEYCODE_PERIOD),
                        sym("/", "?", KeyEvent.KEYCODE_SLASH),
                        fn("←", KeyEvent.KEYCODE_DPAD_LEFT, 1f), fn("↑", KeyEvent.KEYCODE_DPAD_UP, 1f),
                        fn("↓", KeyEvent.KEYCODE_DPAD_DOWN, 1f), fn("→", KeyEvent.KEYCODE_DPAD_RIGHT, 1f),
                        spacer(0.25f),
                        backspaceKey()),
                new Row(1f,
                        pageKey("ABC", PAGE_ALPHA, 2.5f),
                        space(5f),
                        enter(2.5f)),
        };
    }

    // ---- View construction ----

    private void buildPage() {
        removeAllViews();
        allKeys.clear();

        Row[] rows = page == PAGE_ALPHA ? alphaRows() : symbolRows();
        for (Row row : rows) {
            LinearLayout rowLayout = new LinearLayout(getContext());
            rowLayout.setOrientation(HORIZONTAL);
            addView(rowLayout, new LayoutParams(LayoutParams.MATCH_PARENT, 0, row.heightWeight));

            for (Key key : row.keys) {
                LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight);
                if (key.kind == KIND_SPACER) {
                    rowLayout.addView(new View(getContext()), lp);
                    continue;
                }
                int mh = dp(3);
                int mv = dp(key.style == STYLE_SMALL ? 3 : 4);
                lp.setMargins(mh, mv, mh, mv);
                key.view = createKeyView(key);
                rowLayout.addView(key.view, lp);
                allKeys.add(key);
            }
        }
        refreshKeys();
    }

    @SuppressLint("ClickableViewAccessibility")
    private TextView createKeyView(final Key key) {
        TextView tv = new TextView(getContext());
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Color.WHITE);
        tv.setSingleLine(true);
        tv.setIncludeFontPadding(false);

        float textSp;
        if (key.style == STYLE_SMALL) {
            textSp = key.label.length() > 2 ? 12 : 15;
        }
        else if (key.style == STYLE_LETTER && key.label.length() == 1) {
            textSp = 22;
        }
        else if (key.label.length() == 1) {
            textSp = 22; // glyph keys: shift, backspace, enter, arrows
        }
        else {
            textSp = key.label.length() > 3 ? 13 : 16;
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        if (key.style == STYLE_LETTER) {
            tv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(key.style == STYLE_SMALL ? 12 : 9));
        tv.setBackground(bg);
        tv.setElevation(dp(1));

        tv.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        onKeyPressed(key);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        onKeyReleased(key);
                        return true;
                    default:
                        return true;
                }
            }
        });
        return tv;
    }

    private int baseColor(Key key) {
        switch (key.style) {
            case STYLE_ACCENT:
                return COLOR_ACCENT;
            case STYLE_SMALL:
                return COLOR_SMALL;
            case STYLE_FUNCTION:
                return COLOR_FUNCTION;
            default:
                return COLOR_LETTER;
        }
    }

    private void setKeyColor(Key key, int color) {
        ((GradientDrawable) key.view.getBackground()).setColor(color);
    }

    // ---- Key handling ----

    private Key repeatingKey;
    private final Runnable repeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (repeatingKey != null) {
                // Re-sending key down produces OS-level auto-repeat on the host
                listener.onKeyboardKey(repeatingKey.code, true);
                handler.postDelayed(this, REPEAT_INTERVAL_MS);
            }
        }
    };

    private void onKeyPressed(Key key) {
        key.view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);

        switch (key.kind) {
            case KIND_MODIFIER:
                onModifierTapped(key.code);
                refreshKeys();
                break;

            case KIND_PAGE:
                setKeyColor(key, COLOR_PRESSED);
                break;

            default:
                setKeyColor(key, key.style == STYLE_ACCENT ? COLOR_ACCENT_PRESSED : COLOR_PRESSED);
                listener.onKeyboardKey(key.code, true);
                repeatingKey = key;
                handler.removeCallbacks(repeatRunnable);
                handler.postDelayed(repeatRunnable, REPEAT_DELAY_MS);
                break;
        }
    }

    private void onModifierTapped(int code) {
        boolean latched = latchedModifiers.contains(code);

        if (code == KeyEvent.KEYCODE_SHIFT_LEFT) {
            long now = System.currentTimeMillis();
            if (capsLock) {
                // Tap while locked: unlock
                capsLock = false;
                releaseModifier(code);
            }
            else if (latched && now - lastShiftTapTime < CAPS_LOCK_DOUBLE_TAP_MS) {
                // Second quick tap: lock it
                capsLock = true;
            }
            else if (latched) {
                releaseModifier(code);
            }
            else {
                latchModifier(code);
            }
            lastShiftTapTime = now;
            return;
        }

        if (latched) {
            releaseModifier(code);
        }
        else {
            latchModifier(code);
        }
    }

    private void latchModifier(int code) {
        latchedModifiers.add(code);
        listener.onKeyboardKey(code, true);
    }

    private void releaseModifier(int code) {
        if (latchedModifiers.remove(Integer.valueOf(code))) {
            listener.onKeyboardKey(code, false);
        }
    }

    private void onKeyReleased(Key key) {
        switch (key.kind) {
            case KIND_MODIFIER:
                // Latch state was handled on press
                break;

            case KIND_PAGE:
                // Rebuild after this touch event finishes: removing the touched key while
                // its event is still being dispatched corrupts the next draw pass
                final int target = key.code;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (page != target) {
                            page = target;
                            buildPage();
                        }
                    }
                });
                break;

            default:
                if (repeatingKey == key) {
                    repeatingKey = null;
                    handler.removeCallbacks(repeatRunnable);
                }
                listener.onKeyboardKey(key.code, false);
                releaseOneShotModifiers();
                refreshKeys();
                break;
        }
    }

    private void releaseOneShotModifiers() {
        for (Integer code : new ArrayList<>(latchedModifiers)) {
            if (capsLock && code == KeyEvent.KEYCODE_SHIFT_LEFT) {
                continue;
            }
            releaseModifier(code);
        }
    }

    private void refreshKeys() {
        boolean shift = latchedModifiers.contains(KeyEvent.KEYCODE_SHIFT_LEFT);
        for (Key key : allKeys) {
            if (key.view == null) {
                continue;
            }
            boolean latched = key.kind == KIND_MODIFIER && latchedModifiers.contains(key.code);
            if (key.iconRes != 0) {
                int icon = key.iconRes;
                if (key.code == KeyEvent.KEYCODE_SHIFT_LEFT && latched) {
                    icon = capsLock ? com.limelight.R.drawable.ic_kb_capslock : com.limelight.R.drawable.ic_kb_shift_on;
                }
                setIcon(key.view, icon);
            }
            else {
                key.view.setText(shift ? key.shiftLabel : key.label);
            }
            setKeyColor(key, latched ? COLOR_ACCENT : baseColor(key));
        }
    }

    /** Shows a vector icon centered in the key (an ImageSpan keeps it centered like text). */
    private void setIcon(TextView view, int iconRes) {
        Drawable d = getContext().getDrawable(iconRes);
        if (d == null) {
            return;
        }
        int size = dp(24);
        d.setBounds(0, 0, size, size);
        SpannableString text = new SpannableString(" ");
        int align = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ?
                DynamicDrawableSpan.ALIGN_CENTER : DynamicDrawableSpan.ALIGN_BASELINE;
        text.setSpan(new ImageSpan(d, align), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(text);
    }

    /** Release everything held on the host, e.g. when switching back to trackpad mode. */
    public void reset() {
        handler.removeCallbacks(repeatRunnable);
        if (repeatingKey != null) {
            listener.onKeyboardKey(repeatingKey.code, false);
            repeatingKey = null;
        }
        capsLock = false;
        for (Integer code : new ArrayList<>(latchedModifiers)) {
            releaseModifier(code);
        }
        refreshKeys();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
