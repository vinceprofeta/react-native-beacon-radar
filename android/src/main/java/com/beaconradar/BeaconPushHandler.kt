package com.beaconradar

import android.content.Context

/**
 * Handles push-notification-triggered beacon work.
 */
object BeaconPushHandler {
    private const val TAG = "BeaconPushHandler"

    /**
     * Called from push notifications with beacon_scan flag.
     * Ensures monitoring is running and triggers BLE connect.
     */
    @JvmStatic
    fun handlePayload(
        context: Context,
        payload: Map<String, *>?,
        source: String = "unknown",
    ): Boolean {
        val payloadMap = payload ?: emptyMap<String, Any?>()
        logInfo(context, "Push payload received from $source", "REMOTE_NOTIFICATION_RECEIVED")
        logInfo(context, "handlePayload source=$source keys=${payloadMap.keys.joinToString()}")

        if (!BeaconRadarPreferences.isBackgroundModeEnabled(context)) {
            logInfo(context, "Ignoring push because background mode is disabled")
            return false
        }

        if (!containsBeaconScan(payloadMap)) {
            logInfo(context, "Ignoring push because beacon_scan flag is missing", "PUSH_BEACON_SCAN_MISSING")
            return false
        }

        logInfo(context, "beacon_scan found, waking up beacon system", "PUSH_BEACON_SCAN_FOUND")

        BeaconRadarBackgroundBootstrap.ensureBackgroundMonitoring(
            context.applicationContext,
            "push:$source"
        )

        BeaconBluetoothManager.triggerFastConnect(context, "push:$source")
        BeaconRadarBackgroundBootstrap.retryForegroundServiceIfNeeded(context)

        logInfo(context, "Push handler accepted payload and started work", "PUSH_HANDLER_CALLED_SUCCESS")
        return true
    }

    private fun containsBeaconScan(payload: Map<String, *>): Boolean {
        val directValue = payload["beacon_scan"]
        if (directValue != null) {
            return when (directValue) {
                is Boolean -> directValue
                else -> directValue.toString().equals("true", ignoreCase = true) ||
                    directValue.toString() == "1" ||
                    directValue.toString().equals("beacon_scan", ignoreCase = true)
            }
        }

        val body = payload["body"]?.toString()
        return body?.contains("beacon_scan", ignoreCase = true) == true
    }

    private fun logInfo(context: Context, message: String, type: String = "GENERAL") {
        if (type == "GENERAL") {
            BeaconRadarLogger.i(context.applicationContext, TAG, message, type = type)
        } else {
            BeaconRadarLogger.logKeyEvent(context.applicationContext, TAG, message, type = type)
        }
    }

    @Suppress("unused")
    private fun logWarning(context: Context, message: String, type: String = "GENERAL") {
        BeaconRadarLogger.w(context.applicationContext, TAG, message, type = type)
    }
}
