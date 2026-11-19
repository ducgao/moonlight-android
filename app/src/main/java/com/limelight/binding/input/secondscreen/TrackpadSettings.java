package com.limelight.binding.input.secondscreen;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** Persisted settings for the second-screen trackpad. */
public final class TrackpadSettings {
    private static final String KEY_SENSITIVITY = "second_screen_trackpad_sensitivity_v2";
    private static final String KEY_HAPTIC_CLICK = "second_screen_trackpad_haptic_click";

    public static final float MIN_SENSITIVITY = 0.5f;
    public static final float MAX_SENSITIVITY = 5.0f;
    public static final float DEFAULT_SENSITIVITY = 2.0f;

    private TrackpadSettings() {}

    @SuppressWarnings("deprecation")
    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public static float getSensitivity(Context context) {
        float v = prefs(context).getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY);
        return Math.max(MIN_SENSITIVITY, Math.min(MAX_SENSITIVITY, v));
    }

    public static void setSensitivity(Context context, float value) {
        prefs(context).edit().putFloat(KEY_SENSITIVITY, value).apply();
    }

    public static boolean isHapticOnClick(Context context) {
        return prefs(context).getBoolean(KEY_HAPTIC_CLICK, true);
    }

    public static void setHapticOnClick(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_HAPTIC_CLICK, enabled).apply();
    }
}
