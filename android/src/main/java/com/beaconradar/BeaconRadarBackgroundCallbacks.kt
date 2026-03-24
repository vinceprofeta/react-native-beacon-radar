package com.beaconradar

import android.content.Context
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.MonitorNotifier
import org.altbeacon.beacon.RangeNotifier
import org.altbeacon.beacon.Region

object BeaconRadarBackgroundCallbacks : MonitorNotifier, RangeNotifier {
    private const val TAG = "BeaconRadarBgCb"

    private fun appContext(): Context? = BeaconRadarBackgroundBootstrap.appContext

    override fun didEnterRegion(region: Region) {
        val context = appContext()
        if (context == null) {
            BeaconRadarLogger.w(null, TAG, "Missing context during didEnterRegion")
            return
        }
        BeaconRadarLogger.logKeyEvent(
            context,
            TAG,
            "didEnterRegion (background callbacks): ${region.uniqueId}",
            type = "BEACON_REGION_ENTERED"
        )
        BeaconPushHandler.handleRegionPresence(
            context,
            region,
            "background-enter"
        )
    }

    override fun didExitRegion(region: Region) {
        appContext()?.let {
            BeaconRadarLogger.i(it, TAG, "didExitRegion (background callbacks): ${region.uniqueId}")
        } ?: BeaconRadarLogger.w(null, TAG, "Missing context during didExitRegion")
    }

    override fun didDetermineStateForRegion(state: Int, region: Region) {
        val context = appContext()
        if (context == null) {
            BeaconRadarLogger.w(null, TAG, "Missing context during didDetermineStateForRegion")
            return
        }
        val stateStr = when (state) {
            MonitorNotifier.INSIDE -> "INSIDE"
            MonitorNotifier.OUTSIDE -> "OUTSIDE"
            else -> "UNKNOWN"
        }
        BeaconRadarLogger.logKeyEvent(
            context,
            TAG,
            "didDetermineState (background callbacks): ${region.uniqueId} = $stateStr",
            type = "BEACON_RANGING_STARTED"
        )
        if (state == MonitorNotifier.INSIDE) {
            BeaconPushHandler.handleRegionPresence(
                context,
                region,
                "background-state-inside"
            )
        }
    }

    override fun didRangeBeaconsInRegion(beacons: Collection<Beacon>, region: Region) {
        if (beacons.isEmpty()) return
        val context = appContext()
        if (context == null) {
            BeaconRadarLogger.w(null, TAG, "Missing context during didRangeBeaconsInRegion")
            return
        }
        BeaconRadarLogger.i(
            context,
            TAG,
            "didRangeBeacons (background callbacks): ${beacons.size} beacon(s) for ${region.uniqueId}"
        )
        BeaconPushHandler.handleRangedBeacons(context, beacons, "background-callbacks")
    }
}
