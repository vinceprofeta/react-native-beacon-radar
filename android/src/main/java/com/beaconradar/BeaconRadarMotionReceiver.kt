package com.beaconradar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult

class BeaconRadarMotionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BeaconRadarMotionRx"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || !ActivityTransitionResult.hasResult(intent)) {
            return
        }

        val appCtx = context.applicationContext
        if (!BeaconRadarPreferences.isBackgroundModeEnabled(appCtx)) {
            BeaconRadarLogger.i(appCtx, TAG, "Ignoring motion transition because background mode is disabled")
            return
        }

        if (!BeaconRadarPreferences.isMotionDetectionEnabled(appCtx)) {
            BeaconRadarLogger.i(appCtx, TAG, "Ignoring motion transition because motion detection is disabled")
            return
        }

        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val relevantEvent = result.transitionEvents.firstOrNull { event ->
            event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER &&
                event.activityType != com.google.android.gms.location.DetectedActivity.STILL
        } ?: return

        val transitionLabel = "${relevantEvent.activityType}:${relevantEvent.transitionType}"
        BeaconRadarMotionController.handleTransition(
            appCtx,
            "motion:$transitionLabel"
        )
    }
}
