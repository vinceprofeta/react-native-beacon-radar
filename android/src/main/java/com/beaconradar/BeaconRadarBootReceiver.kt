package com.beaconradar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BeaconRadarBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!BeaconRadarPreferences.isBackgroundModeEnabled(context)) {
                    BeaconRadarLogger.i(
                        context.applicationContext,
                        "BeaconRadarBootReceiver",
                        "Ignoring $action because background mode is disabled"
                    )
                    return
                }

                BeaconRadarLogger.logKeyEvent(
                    context.applicationContext,
                    "BeaconRadarBootReceiver",
                    "Restoring background monitoring from broadcast: $action",
                    type = "BEACON_MONITORING_SETUP"
                )
                BeaconRadarBackgroundBootstrap.ensureBackgroundMonitoring(
                    context.applicationContext,
                    "broadcast:$action"
                )
            }
        }
    }
}
