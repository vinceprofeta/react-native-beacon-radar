package com.beaconradar

import java.util.Locale
import org.altbeacon.beacon.Beacon

object BeaconDistanceUtils {
    internal fun effectiveDistance(beacon: Beacon): Double {
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

    internal fun formatDistance(distance: Double): String {
        return String.format(Locale.US, "%.2f", distance)
    }
}
