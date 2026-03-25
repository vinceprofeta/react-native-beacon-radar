package com.beaconradar

import android.content.Context
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.MonitorNotifier
import org.altbeacon.beacon.RangeNotifier
import org.altbeacon.beacon.Region

/**
 * Single notifier registered on BeaconManager for all monitor/range events.
 * Handles BLE connection logic directly and forwards events to the RN module
 * for JS emission when the bridge is available.
 */
object BeaconRadarBackgroundCallbacks : MonitorNotifier, RangeNotifier {
    private const val TAG = "BeaconRadarBgCb"
    private const val BEACON_MAX_AGE_MS = 10000L

    private fun appContext(): Context? = BeaconRadarBackgroundBootstrap.appContext

    // --- MonitorNotifier ---

    override fun didEnterRegion(region: Region) {
        val context = appContext() ?: run {
            BeaconRadarLogger.w(null, TAG, "Missing context during didEnterRegion")
            return
        }
        BeaconRadarLogger.logKeyEvent(
            context, TAG,
            "didEnterRegion: ${region.uniqueId}",
            type = "BEACON_REGION_ENTERED"
        )

        BeaconBluetoothManager.triggerFastConnect(context, "region:enter")
        BeaconRadarBackgroundBootstrap.retryForegroundServiceIfNeeded(context)
        BeaconRadarModule.instance?.emitDidEnterRegion(region)
    }

    override fun didExitRegion(region: Region) {
        val context = appContext() ?: run {
            BeaconRadarLogger.w(null, TAG, "Missing context during didExitRegion")
            return
        }
        BeaconRadarLogger.i(context, TAG, "didExitRegion: ${region.uniqueId}")
        BeaconRadarModule.instance?.emitDidExitRegion(region)
    }

    override fun didDetermineStateForRegion(state: Int, region: Region) {
        val context = appContext() ?: run {
            BeaconRadarLogger.w(null, TAG, "Missing context during didDetermineStateForRegion")
            return
        }
        val stateStr = when (state) {
            MonitorNotifier.INSIDE -> "INSIDE"
            MonitorNotifier.OUTSIDE -> "OUTSIDE"
            else -> "UNKNOWN"
        }
        BeaconRadarLogger.logKeyEvent(
            context, TAG,
            "didDetermineState: ${region.uniqueId} = $stateStr",
            type = "BEACON_STATE_DETERMINED"
        )
        if (state == MonitorNotifier.INSIDE) {
            BeaconBluetoothManager.triggerFastConnect(context, "region:state-inside")
            BeaconRadarBackgroundBootstrap.retryForegroundServiceIfNeeded(context)
        }
    }

    // --- RangeNotifier ---

    override fun didRangeBeaconsInRegion(beacons: Collection<Beacon>, region: Region) {
        if (beacons.isEmpty()) {
            BeaconRadarLogger.w(null, TAG, "No beacons found during didRangeBeaconsInRegion")
            return
        }
        val context = appContext() ?: run {
            BeaconRadarLogger.w(null, TAG, "Missing context during didRangeBeaconsInRegion")
            return
        }

        val recentBeacons = beacons.filter { beacon ->
            val age = System.currentTimeMillis() - beacon.lastCycleDetectionTimestamp
            age < BEACON_MAX_AGE_MS
        }
        if (recentBeacons.isEmpty()) {
            BeaconRadarLogger.w(context, TAG, "No recent beacons found during didRangeBeaconsInRegion")
            return
        }

        val nearestBeacon = recentBeacons.minByOrNull { BeaconDistanceUtils.effectiveDistance(it) } ?: return
        val nearestDistance = BeaconDistanceUtils.effectiveDistance(nearestBeacon)
        val maxDistance = BeaconRadarPreferences.getMaxDistance(context)

        if (nearestDistance < 0) {
            BeaconRadarLogger.logKeyEvent(
                context, TAG,
                "Nearest beacon distance is unknown for range-callback",
                type = "BEACON_DISTANCE_UNKNOWN"
            )
            return
        }

        if (nearestDistance > maxDistance) {
            BeaconRadarLogger.logKeyEvent(
                context, TAG,
                "Nearest beacon is out of range for range-callback; skipping BLE connect",
                type = "BEACON_TOO_FAR"
            )
            return
        }

        BeaconRadarLogger.i(
            context, TAG,
            "didRangeBeacons: ${recentBeacons.size} beacon(s) for ${region.uniqueId}; nearest=${BeaconDistanceUtils.formatDistance(nearestDistance)}m max=${BeaconDistanceUtils.formatDistance(maxDistance)}m"
        )

        BeaconBluetoothManager.triggerFastConnect(
            context,
            "range-callback distance=${BeaconDistanceUtils.formatDistance(nearestDistance)}m beacon=${nearestBeacon.id1}"
        )
        BeaconRadarBackgroundBootstrap.retryForegroundServiceIfNeeded(context)
        BeaconRadarModule.instance?.emitBeaconsDetected(recentBeacons)
    }
}
