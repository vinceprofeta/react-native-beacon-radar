package com.beaconradar

import android.content.Context
import android.util.Log
import java.util.Locale
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.Identifier
import org.altbeacon.beacon.Region

object BeaconPushHandler {
    private const val TAG = "BeaconPushHandler"
    private const val DEFAULT_REGION_UUID = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825"
    private const val IBEACON_LAYOUT = "m:0-3=4c000215,i:4-19,i:20-21,i:22-23,p:24-24"
    private const val BEACON_MAX_AGE_MS = 10000L

    @JvmStatic
    fun handlePayload(
        context: Context,
        payload: Map<String, *>?,
        source: String = "unknown",
    ): Boolean {
        val payloadMap = payload ?: emptyMap<String, Any?>()
        Log.i(TAG, "handlePayload source=$source keys=${payloadMap.keys.joinToString()}")

        if (!BeaconRadarPreferences.isBackgroundModeEnabled(context)) {
            Log.i(TAG, "Ignoring push because background mode is disabled")
            return false
        }

        if (!containsBeaconScan(payloadMap)) {
            Log.i(TAG, "Ignoring push because beacon_scan flag is missing")
            return false
        }

        val beaconManager = BeaconManager.getInstanceForApplication(context)
        ensureIBeaconParser(beaconManager)

        val region = Region(
            "all-beacons",
            Identifier.parse(DEFAULT_REGION_UUID),
            null,
            null
        )

        try {
            beaconManager.startMonitoring(region)
        } catch (e: Exception) {
            Log.w(TAG, "startMonitoring failed or already active: ${e.message}")
        }

        try {
            beaconManager.requestStateForRegion(region)
        } catch (e: Exception) {
            Log.w(TAG, "requestStateForRegion failed: ${e.message}")
        }

        try {
            beaconManager.startRangingBeacons(region)
        } catch (e: Exception) {
            Log.w(TAG, "startRangingBeacons failed or already active: ${e.message}")
        }

        BeaconBluetoothManager.triggerFastConnect(context, "push:$source")
        BeaconRadarModule.instance?.retryForegroundServiceIfNeeded()

        Log.i(TAG, "Processed beacon push successfully")
        return true
    }

    @JvmStatic
    fun handleRangedBeacons(
        context: Context,
        beacons: Collection<Beacon>,
        source: String = "unknown",
    ): Boolean {
        val recentBeacons = beacons.filter { beacon ->
            val age = System.currentTimeMillis() - beacon.lastCycleDetectionTimestamp
            age < BEACON_MAX_AGE_MS
        }
        if (recentBeacons.isEmpty()) {
            Log.i(TAG, "Ignoring ranged beacons from $source because none are recent")
            return false
        }

        val nearestBeacon = recentBeacons.minByOrNull { effectiveDistance(it) } ?: return false
        val nearestDistance = effectiveDistance(nearestBeacon)
        val maxDistance = BeaconRadarPreferences.getMaxDistance(context)

        Log.i(
            TAG,
            "Ranged ${recentBeacons.size} beacon(s) from $source; nearest=${formatDistance(nearestDistance)}m max=${formatDistance(maxDistance)}m"
        )

        if (nearestDistance > maxDistance) {
            Log.i(TAG, "Nearest beacon is out of range for $source; skipping BLE connect")
            return false
        }

        val connectStarted = BeaconBluetoothManager.triggerFastConnect(
            context,
            "range:$source distance=${formatDistance(nearestDistance)}m beacon=${nearestBeacon.id1}"
        )
        BeaconRadarModule.instance?.retryForegroundServiceIfNeeded()
        return connectStarted
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

    private fun ensureIBeaconParser(beaconManager: BeaconManager) {
        if (beaconManager.beaconParsers.none { it.layout == IBEACON_LAYOUT }) {
            beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(IBEACON_LAYOUT))
        }
    }

    private fun effectiveDistance(beacon: Beacon): Double {
        return if (beacon.distance < 0 && beacon.rssi != 0) {
            calculateDistance(beacon.txPower, beacon.rssi)
        } else {
            beacon.distance
        }
    }

    private fun calculateDistance(txPower: Int, rssi: Int): Double {
        if (rssi == 0) return -1.0
        return Math.pow(10.0, (txPower - rssi) / 20.0)
    }

    private fun formatDistance(distance: Double): String {
        return String.format(Locale.US, "%.2f", distance)
    }
}
