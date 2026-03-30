package com.beaconradar

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

object BeaconRadarMotionController {
    private const val TAG = "BeaconRadarMotion"
    private const val MOTION_REFRESH_DEBOUNCE_MS = 2 * 60 * 1000L
    private const val MOTION_PENDING_INTENT_REQUEST_CODE = 457

    @Volatile
    private var isTransitionUpdatesRegistered = false

    @Volatile
    private var lastMotionRefreshAtMs = 0L

    @Synchronized
    fun applyCurrentState(context: Context) {
        val appCtx = context.applicationContext
        if (!BeaconRadarPreferences.isMotionDetectionEnabled(appCtx)) {
            unregister(appCtx, "Motion detection disabled")
            return
        }

        if (!BeaconRadarPreferences.isBackgroundModeEnabled(appCtx)) {
            logInfo(appCtx, "Skipping motion detection setup because background mode is disabled")
            return
        }

        if (!hasPermission(appCtx)) {
            logWarning(appCtx, "Skipping motion detection setup because ACTIVITY_RECOGNITION is not granted")
            return
        }

        if (isTransitionUpdatesRegistered) {
            return
        }

        try {
            ActivityRecognition.getClient(appCtx)
                .requestActivityTransitionUpdates(
                    buildTransitionRequest(),
                    motionPendingIntent(appCtx)
                )
                .addOnSuccessListener {
                    isTransitionUpdatesRegistered = true
                    logInfo(appCtx, "Registered motion transition updates")
                }
                .addOnFailureListener { error ->
                    logWarning(appCtx, "Failed to register motion transition updates: ${error.message}")
                }
        } catch (e: Exception) {
            logWarning(appCtx, "Motion detection setup failed: ${e.message}")
        }
    }

    fun handleTransition(context: Context, source: String) {
        val appCtx = context.applicationContext
        if (!BeaconRadarPreferences.isMotionDetectionEnabled(appCtx)) {
            logInfo(appCtx, "Ignoring $source because motion detection is disabled")
            return
        }
        if (!BeaconRadarPreferences.isBackgroundModeEnabled(appCtx)) {
            logInfo(appCtx, "Ignoring $source because background mode is disabled")
            return
        }

        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastMotionRefreshAtMs < MOTION_REFRESH_DEBOUNCE_MS) {
                logInfo(appCtx, "Ignoring $source because motion refresh is debounced")
                return
            }
            lastMotionRefreshAtMs = now
        }

        logInfo(appCtx, "Refreshing background monitoring after $source", "BEACON_MONITORING_SETUP")
        BeaconRadarBackgroundBootstrap.ensureBackgroundMonitoring(appCtx, source)
        BeaconRadarBackgroundBootstrap.retryForegroundServiceIfNeeded(appCtx)
    }

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Synchronized
    fun unregister(context: Context, reason: String = "Motion detection cleanup") {
        val appCtx = context.applicationContext
        if (!isTransitionUpdatesRegistered) {
            return
        }

        try {
            ActivityRecognition.getClient(appCtx)
                .removeActivityTransitionUpdates(motionPendingIntent(appCtx))
            logInfo(appCtx, "$reason; removed transition updates")
        } catch (e: Exception) {
            logWarning(appCtx, "Failed removing motion transition updates: ${e.message}")
        } finally {
            isTransitionUpdatesRegistered = false
        }
    }

    private fun buildTransitionRequest(): ActivityTransitionRequest {
        val transitions = listOf(
            transition(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
            transition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            transition(DetectedActivity.ON_FOOT, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            transition(DetectedActivity.RUNNING, ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        )
        return ActivityTransitionRequest(transitions)
    }

    private fun transition(activityType: Int, transitionType: Int): ActivityTransition {
        return ActivityTransition.Builder()
            .setActivityType(activityType)
            .setActivityTransition(transitionType)
            .build()
    }

    private fun motionPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, BeaconRadarMotionReceiver::class.java)
        return PendingIntent.getBroadcast(
            context.applicationContext,
            MOTION_PENDING_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun logInfo(context: Context, message: String, type: String = "GENERAL") {
        if (type == "GENERAL") {
            BeaconRadarLogger.i(context.applicationContext, TAG, message, type = type)
        } else {
            BeaconRadarLogger.logKeyEvent(context.applicationContext, TAG, message, type = type)
        }
    }

    private fun logWarning(context: Context, message: String, type: String = "GENERAL") {
        BeaconRadarLogger.w(context.applicationContext, TAG, message, type = type)
    }
}
