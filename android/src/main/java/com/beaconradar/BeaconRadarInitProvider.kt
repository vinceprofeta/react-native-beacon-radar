package com.beaconradar

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import org.altbeacon.beacon.*

/**
 * Auto-initializes beacon monitoring at app startup via the ContentProvider lifecycle,
 * which runs before Application.onCreate and does not depend on React Native bridge init.
 * This ensures background beacon detection survives process death and cold restarts.
 */
class BeaconRadarInitProvider : ContentProvider(), MonitorNotifier, RangeNotifier {

    companion object {
        private const val TAG = "BeaconRadarInit"
        const val DEFAULT_REGION_UUID = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825"

        @Volatile
        var isMonitoringInitialized = false
            private set
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        logInfo(ctx, "InitProvider.onCreate starting", "APP_LAUNCH_START")
        val backgroundEnabled = BeaconRadarPreferences.isBackgroundModeEnabled(ctx)

        logInfo(ctx, "InitProvider.onCreate — backgroundEnabled=$backgroundEnabled")

        if (backgroundEnabled) {
            initializeMonitoring(ctx)
        }

        logInfo(ctx, "InitProvider.onCreate complete", "APP_LAUNCH_COMPLETE")
        return true
    }

    private fun initializeMonitoring(ctx: Context) {
        val beaconManager = BeaconManager.getInstanceForApplication(ctx)

        // try {
        //     // Disable AltBeacon's persisted in/out region restore path to reduce
        //     // background restore work and avoid waiting on stale state after process restarts.
        //     beaconManager.setRegionStatePeristenceEnabled(false)
        //     logInfo(ctx, "Disabled AltBeacon region state persistence")
        // } catch (e: Exception) {
        //     logWarning(ctx, "Could not disable AltBeacon region state persistence: ${e.message}")
        // }

        if (beaconManager.beaconParsers.none { it.layout == "m:0-3=4c000215,i:4-19,i:20-21,i:22-23,p:24-24" }) {
            val iBeaconParser = BeaconParser()
                .setBeaconLayout("m:0-3=4c000215,i:4-19,i:20-21,i:22-23,p:24-24")
            beaconManager.beaconParsers.add(iBeaconParser)
        }

        beaconManager.addMonitorNotifier(this)
        beaconManager.addRangeNotifier(this)

        val region = Region("all-beacons", Identifier.parse(DEFAULT_REGION_UUID), null, null)
        beaconManager.startMonitoring(region)
        beaconManager.requestStateForRegion(region)

        isMonitoringInitialized = true
        logInfo(ctx, "Background monitoring initialized from InitProvider for region: $region")
    }

    // --- MonitorNotifier ---

    override fun didEnterRegion(region: Region) {
        logInfo(context, "didEnterRegion (InitProvider): ${region.uniqueId}", "BEACON_REGION_ENTERED")
        logInfo(context, "Region entered; waiting for ranging callbacks", "BEACON_REGION_WAITING_FOR_RANGING")
        val module = BeaconRadarModule.instance
        if (module != null) {
            module.didEnterRegion(region)
        } else {
            val ctx = context
            if (ctx != null) {
                BeaconPushHandler.handleRegionPresence(ctx, region, "init-provider-enter")
                logInfo(ctx, "RN module not yet available — handled region entry natively")
            } else {
                logWarning(null, "RN module not yet available and context is null during region entry")
            }
        }
    }

    override fun didExitRegion(region: Region) {
        logInfo(context, "didExitRegion (InitProvider): ${region.uniqueId}")
        val module = BeaconRadarModule.instance
        if (module != null) {
            module.didExitRegion(region)
        } else {
            logWarning(context, "RN module not yet available — region exit noted")
        }
    }

    override fun didDetermineStateForRegion(state: Int, region: Region) {
        val stateStr = when (state) {
            MonitorNotifier.INSIDE -> "INSIDE"
            MonitorNotifier.OUTSIDE -> "OUTSIDE"
            else -> "UNKNOWN"
        }
        logInfo(context, "didDetermineState (InitProvider): ${region.uniqueId} = $stateStr", "BEACON_RANGING_STARTED")
        val module = BeaconRadarModule.instance
        if (module != null) {
            module.didDetermineStateForRegion(state, region)
        } else if (state == MonitorNotifier.INSIDE) {
            val ctx = context
            if (ctx != null) {
                BeaconPushHandler.handleRegionPresence(ctx, region, "init-provider-state-inside")
                logInfo(ctx, "RN module not yet available — handled INSIDE state natively")
            } else {
                logWarning(null, "RN module not yet available and context is null during INSIDE state")
            }
        }
    }

    // --- RangeNotifier ---

    override fun didRangeBeaconsInRegion(beacons: Collection<Beacon>, region: Region) {
        if (beacons.isEmpty()) return
        logInfo(context, "didRangeBeacons (InitProvider): ${beacons.size} beacon(s)")
        val ctx = context
        if (ctx != null) {
            BeaconPushHandler.handleRangedBeacons(ctx, beacons, "init-provider")
        } else {
            logWarning(null, "Context unavailable during ranging callback; cannot trigger native BLE")
        }
        val module = BeaconRadarModule.instance
        if (module != null) {
            module.didRangeBeaconsInRegion(beacons, region)
        } else {
            logWarning(context, "RN module not yet available — native BLE path handled without JS bridge")
        }
    }

    private fun logInfo(context: Context?, message: String, type: String = "GENERAL") {
        if (type == "GENERAL") {
            BeaconRadarLogger.i(context?.applicationContext, TAG, message, type = type)
        } else {
            BeaconRadarLogger.logKeyEvent(context?.applicationContext, TAG, message, type = type)
        }
    }

    private fun logWarning(context: Context?, message: String, type: String = "GENERAL") {
        BeaconRadarLogger.w(context?.applicationContext, TAG, message, type = type)
    }

    // --- Required ContentProvider stubs ---
    override fun query(uri: Uri, proj: Array<out String>?, sel: String?, selArgs: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, selArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, selArgs: Array<out String>?): Int = 0
}
