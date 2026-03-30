package com.beaconradar

import android.content.Context

object BeaconRadarPreferences {
    private const val PREFS_NAME = "BeaconRadarPrefs"
    private const val BACKGROUND_MODE_KEY = "backgroundModeEnabled"
    private const val MOTION_DETECTION_ENABLED_KEY = "motionDetectionEnabled"
    private const val MAX_DISTANCE_KEY = "maxDistance"
    private const val POSTHOG_KEY = "posthogKey"
    private const val BEACON_DEBUG_KEY = "beaconDebug"
    private const val DEFAULT_MAX_DISTANCE = 0.4

    @JvmStatic
    fun isBackgroundModeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(BACKGROUND_MODE_KEY, false)
    }

    @JvmStatic
    fun setBackgroundModeEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(BACKGROUND_MODE_KEY, enabled).apply()
    }

    @JvmStatic
    fun isMotionDetectionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(MOTION_DETECTION_ENABLED_KEY, false)
    }

    @JvmStatic
    fun setMotionDetectionEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(MOTION_DETECTION_ENABLED_KEY, enabled).apply()
    }

    @JvmStatic
    fun getMaxDistance(context: Context): Double {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(MAX_DISTANCE_KEY, DEFAULT_MAX_DISTANCE.toFloat()).toDouble()
    }

    @JvmStatic
    fun setMaxDistance(context: Context, distance: Double) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(MAX_DISTANCE_KEY, distance.toFloat()).apply()
    }

    @JvmStatic
    fun getPosthogKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(POSTHOG_KEY, "") ?: ""
    }

    @JvmStatic
    fun setPosthogKey(context: Context, apiKey: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(POSTHOG_KEY, apiKey).apply()
    }

    @JvmStatic
    fun isBeaconDebugEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(BEACON_DEBUG_KEY, false)
    }

    @JvmStatic
    fun setBeaconDebugEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(BEACON_DEBUG_KEY, enabled).apply()
    }
}
