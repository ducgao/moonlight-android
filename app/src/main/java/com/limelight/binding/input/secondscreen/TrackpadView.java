package com.limelight.binding.input.secondscreen;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.limelight.nvstream.input.MouseButtonPacket;

/**
 * Full-surface laptop-style trackpad.
 *
 * - 1 finger move          : relative mouse move (with mild acceleration)
 * - 1 finger tap           : left click
 * - 1 finger long-press    : hold left button (drag), released on lift
 * - 2 finger tap           : right click
 * - 2 finger swipe         : vertical / horizontal scroll (natural direction)
 */
public class TrackpadView extends View {
    private static final long TAP_MAX_MS = 220;
    private static final long TWO_FINGER_TAP_MAX_MS = 300;
    private static final long LONG_PRESS_MS = 450;
    private static final long CLICK_RELEASE_DELAY_MS = 30;

    // Host pixels per finger pixel at 1.0x, before acceleration
    private static final float BASE_SENSITIVITY = 1.4f;
    // Pointer acceleration from finger velocity (dp per ms), like a laptop trackpad:
    // slow movements stay precise, fast flicks travel further
    private static final float ACCEL_PER_SPEED = 1.2f;
    private static final float MAX_ACCEL = 2.5f;
    // Exponential smoothing of the velocity so acceleration doesn't jitter between samples
    private static final float SPEED_SMOOTHING = 0.35f;

    // Wheel units (120 = 1 notch) per pixel of finger travel
    private static final float SCROLL_FACTOR = 2.5f;
    // Batch scroll output so we don't flood the host with tiny packets
    private static final int SCROLL_STEP = 20;

    private final SecondScreenInputListener listener;
    private float sensitivity = TrackpadSettings.DEFAULT_SENSITIVITY;
    private boolean hapticOnClick = true;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float touchSlop;
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Gesture state
    private long downTime;
    private int maxPointers;
    private int primaryPointerId;
    private float startX, startY, lastX, lastY;
    private boolean moved;
    private boolean dragging;

    private float startCx, startCy, lastCx, lastCy;
    private boolean scrolling;

    private float moveAccX, moveAccY;
    private long lastMoveTime;
    private float smoothedSpeed;
    private final float density;
    private float scrollAccX, scrollAccY;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (maxPointers == 1 && !moved && !dragging) {
                dragging = true;
                listener.onMouseButton(MouseButtonPacket.BUTTON_LEFT, true);
                if (hapticOnClick) {
                    vibrate(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ?
                            VibrationEffect.EFFECT_HEAVY_CLICK : -1);
                }
            }
        }
    };

    public TrackpadView(Context context, SecondScreenInputListener listener) {
        super(context);
        this.listener = listener;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.density = getResources().getDisplayMetrics().density;

        setBackgroundColor(Color.rgb(24, 24, 28));
        hintPaint.setColor(Color.argb(70, 255, 255, 255));
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        setHapticFeedbackEnabled(true);

        sensitivity = TrackpadSettings.getSensitivity(context);
        hapticOnClick = TrackpadSettings.isHapticOnClick(context);
    }

    /** Multiplier on pointer speed (see TrackpadSettings for range and default). */
    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    public void setHapticOnClick(boolean enabled) {
        this.hapticOnClick = enabled;
    }

    private boolean showHint = true;

    /** Hide the gesture hint when most of the pad is covered (keyboard mode). */
    public void setShowHint(boolean show) {
        if (showHint != show) {
            showHint = show;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!showHint) {
            return;
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float line = hintPaint.getTextSize() * 1.5f;
        canvas.drawText("Trackpad", cx, cy - line, hintPaint);
        canvas.drawText("1 ngón chạm: click trái  •  giữ: kéo", cx, cy, hintPaint);
        canvas.drawText("2 ngón chạm: click phải  •  2 ngón vuốt: cuộn", cx, cy + line, hintPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onFirstDown(event);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                maxPointers = Math.max(maxPointers, event.getPointerCount());
                handler.removeCallbacks(longPressRunnable);
                if (event.getPointerCount() == 2) {
                    startCx = lastCx = centroidX(event);
                    startCy = lastCy = centroidY(event);
                    scrolling = false;
                    scrollAccX = scrollAccY = 0;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                onMove(event);
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                // Remaining finger(s) after a multi-finger gesture are ignored until full lift
                return true;

            case MotionEvent.ACTION_UP:
                onLastUp(event);
                return true;

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                releaseDrag();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private void onFirstDown(MotionEvent event) {
        downTime = event.getEventTime();
        maxPointers = 1;
        primaryPointerId = event.getPointerId(0);
        startX = lastX = event.getX();
        startY = lastY = event.getY();
        moved = false;
        scrolling = false;
        moveAccX = moveAccY = 0;
        lastMoveTime = event.getEventTime();
        smoothedSpeed = 0;
        // Deliver every touch sample as it arrives instead of batching them per frame
        requestUnbufferedDispatch(event);
        handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
    }

    private void onMove(MotionEvent event) {
        if (maxPointers >= 2) {
            if (event.getPointerCount() < 2) {
                return;
            }
            float cx = centroidX(event);
            float cy = centroidY(event);
            if (!scrolling && Math.hypot(cx - startCx, cy - startCy) > touchSlop) {
                scrolling = true;
                lastCx = cx;
                lastCy = cy;
            }
            if (scrolling) {
                // Natural scrolling: fingers up => content up => wheel down (negative)
                scrollAccY += (cy - lastCy) * SCROLL_FACTOR;
                scrollAccX += -(cx - lastCx) * SCROLL_FACTOR;
                flushScroll();
            }
            lastCx = cx;
            lastCy = cy;
            return;
        }

        int idx = event.findPointerIndex(primaryPointerId);
        if (idx < 0) {
            return;
        }
        // Use every intermediate sample, not just the latest one, so the host cursor
        // moves in small even steps instead of one jump per batch
        int history = event.getHistorySize();
        for (int h = 0; h < history; h++) {
            onPointerSample(event.getHistoricalX(idx, h), event.getHistoricalY(idx, h),
                    event.getHistoricalEventTime(h));
        }
        onPointerSample(event.getX(idx), event.getY(idx), event.getEventTime());
    }

    private void onPointerSample(float x, float y, long time) {
        if (!moved && Math.hypot(x - startX, y - startY) > touchSlop) {
            moved = true;
            handler.removeCallbacks(longPressRunnable);
        }

        if (moved || dragging) {
            float dx = x - lastX;
            float dy = y - lastY;

            long dt = Math.max(1, time - lastMoveTime);
            float speed = (float) Math.hypot(dx, dy) / density / dt; // dp per ms
            smoothedSpeed += (speed - smoothedSpeed) * SPEED_SMOOTHING;
            float accel = Math.min(MAX_ACCEL, 1f + smoothedSpeed * ACCEL_PER_SPEED);
            float gain = BASE_SENSITIVITY * sensitivity * accel;

            moveAccX += dx * gain;
            moveAccY += dy * gain;
            short outX = (short) moveAccX;
            short outY = (short) moveAccY;
            if (outX != 0 || outY != 0) {
                moveAccX -= outX;
                moveAccY -= outY;
                listener.onMouseMove(outX, outY);
            }
        }

        lastX = x;
        lastY = y;
        lastMoveTime = time;
    }

    private void onLastUp(MotionEvent event) {
        handler.removeCallbacks(longPressRunnable);
        long duration = event.getEventTime() - downTime;

        if (dragging) {
            releaseDrag();
        }
        else if (maxPointers == 1 && !moved && duration <= TAP_MAX_MS) {
            click(MouseButtonPacket.BUTTON_LEFT);
        }
        else if (maxPointers == 2 && !scrolling && duration <= TWO_FINGER_TAP_MAX_MS) {
            click(MouseButtonPacket.BUTTON_RIGHT);
        }

        maxPointers = 0;
    }

    private void flushScroll() {
        if (Math.abs(scrollAccY) >= SCROLL_STEP) {
            short amount = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scrollAccY));
            scrollAccY -= amount;
            listener.onVerticalScroll(amount);
        }
        if (Math.abs(scrollAccX) >= SCROLL_STEP) {
            short amount = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scrollAccX));
            scrollAccX -= amount;
            listener.onHorizontalScroll(amount);
        }
    }

    private void click(final byte button) {
        if (hapticOnClick) {
            vibrate(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? VibrationEffect.EFFECT_CLICK : -1);
        }
        listener.onMouseButton(button, true);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                listener.onMouseButton(button, false);
            }
        }, CLICK_RELEASE_DELAY_MS);
    }

    /**
     * Click feedback using the vibrator directly. On Android 13+ it's tagged as
     * "physical emulation" (feedback that stands in for a real button), which the system
     * still plays when general touch/haptic vibration is switched off, as on the AYN Thor.
     * It only runs when the user enabled "Vibrate on click" in the trackpad settings.
     */
    @SuppressWarnings("deprecation")
    private void vibrate(int predefinedEffect) {
        Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(VibrationEffect.createPredefined(predefinedEffect),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION));
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(predefinedEffect));
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE));
        }
        else {
            vibrator.vibrate(20);
        }
    }

    private void releaseDrag() {
        if (dragging) {
            dragging = false;
            listener.onMouseButton(MouseButtonPacket.BUTTON_LEFT, false);
        }
    }

    private static float centroidX(MotionEvent e) {
        return (e.getX(0) + e.getX(1)) / 2f;
    }

    private static float centroidY(MotionEvent e) {
        return (e.getY(0) + e.getY(1)) / 2f;
    }

    /** Release any held button, e.g. when the view is hidden mid-gesture. */
    public void reset() {
        // Only cancel the long-press; pending click releases must still run
        handler.removeCallbacks(longPressRunnable);
        releaseDrag();
        maxPointers = 0;
    }
}
