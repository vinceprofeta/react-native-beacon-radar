package com.beaconradar

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.util.Log
import org.altbeacon.beacon.*

/**
 * Auto-initializes beacon monitoring at app startup via the ContentProvider lifecycle,
 * which runs before Application.onCreate and does not depend on React Native bridge init.
 * This ensures background beacon detection survives process death and cold restarts.
 */
class BeaconRadarInitProvider : ContentProvider(), MonitorNotifier, RangeNotifier {

    companion object {
        private const val TAG = "BeaconRadarInit"
        private const val PREFS_NAME = "BeaconRadarPrefs"
        private const val BACKGROUND_MODE_KEY = "backgroundModeEnabled"
        const val DEFAULT_REGION_UUID = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825"

        @Volatile
        var isMonitoringInitialized = false
            private set
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val backgroundEnabled = prefs.getBoolean(BACKGROUND_MODE_KEY, false)

        Log.i(TAG, "InitProvider.onCreate — backgroundEnabled=$backgroundEnabled")

        if (backgroundEnabled) {
            initializeMonitoring(ctx)
        }

        return true
    }

    private fun initializeMonitoring(ctx: Context) {
        val beaconManager = BeaconManager.getInstanceForApplication(ctx)

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
        Log.i(TAG, "Background monitoring initialized from InitProvider for region: $region")
    }

    // --- MonitorNotifier ---

    override fun didEnterRegion(region: Region) {
        Log.i(TAG, "didEnterRegion (InitProvider): ${region.uniqueId}")
        val module = BeaconRadarModule.instance
        if (module != null) {
            module.didEnterRegion(region)
        } else {
            Log.w(TAG, "RN module not yet available — region entry will be handled when module initializes")
        }
    }

    override fun didExitRegion(region: Region) {
        Log.i(TAG, "didExitRegion (InitProvider): ${region.uniqueId}")
        val module = BeaconRadarModule.instance
        if (module != null) {
            module.didExitRegion(region)
        } else {
            Log.w(TAG, "RN module not yet available — region exit noted")
        }
    }

    override fun didDetermineStateForRegion(state: Int, region: Region) {
        val stateStr = when (state) {
            MonitorNotifier.INSIDE -> "INSIDE"
            MonitorNotifier.OUTSIDE -> "OUTSIDE"
            else -> "UNKNOWN"
        }
        Log.i(TAG, "didDetermineState (InitProvider): ${region.uniqueId} = $stateStr")
        val module = BeaconRadarModule.instance
        if (module != null) {
            module.didDetermineStateForRegion(state, region)
        } else if (state == MonitorNotifier.INSIDE) {
            val beaconManager = context?.let { BeaconManager.getInstanceForApplication(it) } ?: return
            beaconManager.startRangingBeacons(region)
            Log.i(TAG, "Started ranging from InitProvider (module not yet available)")
        }
    }

    // --- RangeNotifier ---

    override fun didRangeBeaconsInRegion(beacons: Collection<Beacon>, region: Region) {
        if (beacons.isEmpty()) return
        Log.i(TAG, "didRangeBeacons (InitProvider): ${beacons.size} beacon(s)")
        val module = BeaconRadarModule.instance
        if (module != null) {
            module.didRangeBeaconsInRegion(beacons, region)
        } else {
            Log.w(TAG, "RN module not yet available — ${beacons.size} beacon(s) detected but cannot forward to JS")
        }
    }

    // --- Required ContentProvider stubs ---
    override fun query(uri: Uri, proj: Array<out String>?, sel: String?, selArgs: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, selArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, selArgs: Array<out String>?): Int = 0
}
