package com.limelight.binding.input.secondscreen;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;

import com.limelight.LimeLog;

/**
 * Finds a secondary display (e.g. the bottom screen of a dual-screen handheld such as
 * the AYN Thor) and shows the trackpad/keyboard presentation on it while streaming.
 */
public class SecondScreenManager implements DisplayManager.DisplayListener {
    private final Activity activity;
    private final SecondScreenInputListener listener;
    private final DisplayManager displayManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SecondScreenPresentation presentation;
    private boolean started;

    public SecondScreenManager(Activity activity, SecondScreenInputListener listener) {
        this.activity = activity;
        this.listener = listener;
        this.displayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
    }

    /** Call on the main thread once the stream is connected. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        displayManager.registerDisplayListener(this, mainHandler);
        showIfPossible();
    }

    /** Safe to call from any thread and multiple times. */
    public void stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    stop();
                }
            });
            return;
        }
        if (!started) {
            return;
        }
        started = false;
        displayManager.unregisterDisplayListener(this);
        dismiss();
    }

    private Display findSecondaryDisplay() {
        return findSecondaryDisplay(activity);
    }

    /** Returns a display other than the one the activity is on (e.g. the AYN Thor bottom screen), or null. */
    public static Display findSecondaryDisplay(Activity activity) {
        DisplayManager displayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        int ownDisplayId = activity.getWindowManager().getDefaultDisplay().getDisplayId();
        if (ownDisplayId != Display.DEFAULT_DISPLAY) {
            // We're already running on a secondary screen; presentations can't target the main one
            return null;
        }

        // Prefer displays the system flags as suitable for presentations
        for (Display d : displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (d.getDisplayId() != ownDisplayId && d.isValid()) {
                return d;
            }
        }

        // Some devices expose a built-in second panel without the presentation flag
        for (Display d : displayManager.getDisplays()) {
            if (d.getDisplayId() != ownDisplayId && d.isValid() &&
                    (d.getFlags() & Display.FLAG_PRIVATE) == 0) {
                return d;
            }
        }
        return null;
    }

    private void showIfPossible() {
        if (!started || presentation != null || activity.isFinishing()) {
            return;
        }

        Display display = findSecondaryDisplay();
        if (display == null) {
            LimeLog.info("Second screen: no secondary display found");
            return;
        }

        LimeLog.info("Second screen: showing trackpad on display " + display.getDisplayId() +
                " (" + display.getName() + ")");
        presentation = new SecondScreenPresentation(activity, display, listener);
        try {
            presentation.show();
        } catch (WindowManager.InvalidDisplayException | WindowManager.BadTokenException e) {
            LimeLog.warning("Second screen: unable to show presentation: " + e.getMessage());
            presentation = null;
        }
    }

    private void dismiss() {
        if (presentation != null) {
            try {
                presentation.dismiss();
            } catch (IllegalArgumentException ignored) {
                // Window already detached
            }
            presentation = null;
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        showIfPossible();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        if (presentation != null && presentation.getDisplay().getDisplayId() == displayId) {
            dismiss();
        }
        showIfPossible();
    }

    @Override
    public void onDisplayChanged(int displayId) {
    }
}
