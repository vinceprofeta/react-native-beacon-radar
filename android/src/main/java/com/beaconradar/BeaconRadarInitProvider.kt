package com.beaconradar

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Auto-initializes beacon monitoring at app startup via the ContentProvider lifecycle,
 * which runs before Application.onCreate and does not depend on React Native bridge init.
 * This ensures background beacon detection survives process death and cold restarts.
 */
class BeaconRadarInitProvider : ContentProvider() {

    companion object {
        private const val TAG = "BeaconRadarInit"
        val isMonitoringInitialized: Boolean
            get() = BeaconRadarBackgroundBootstrap.isMonitoringInitialized
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        logInfo(ctx, "InitProvider.onCreate starting", "APP_LAUNCH_START")
        val backgroundEnabled = BeaconRadarPreferences.isBackgroundModeEnabled(ctx)

        logInfo(ctx, "InitProvider.onCreate — backgroundEnabled=$backgroundEnabled")

        if (backgroundEnabled) {
            BeaconRadarBackgroundBootstrap.ensureBackgroundMonitoring(ctx, "init-provider")
        }

        logInfo(ctx, "InitProvider.onCreate complete", "APP_LAUNCH_COMPLETE")
        return true
    }

    private fun logInfo(context: android.content.Context?, message: String, type: String = "GENERAL") {
        if (type == "GENERAL") {
            BeaconRadarLogger.i(context?.applicationContext, TAG, message, type = type)
        } else {
            BeaconRadarLogger.logKeyEvent(context?.applicationContext, TAG, message, type = type)
        }
    }

    // --- Required ContentProvider stubs ---
    override fun query(uri: Uri, proj: Array<out String>?, sel: String?, selArgs: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, selArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, selArgs: Array<out String>?): Int = 0
}
