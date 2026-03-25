package com.beaconradar

import android.content.Context
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.modules.core.PermissionListener
import org.altbeacon.beacon.*
import android.provider.Settings


@ReactModule(name = BeaconRadarModule.NAME)
class BeaconRadarModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext),
    PermissionListener {

    companion object {
        const val TAG = "BeaconRadar"
        const val NAME = "BeaconRadar"
        private const val PREFS_NAME = "BeaconRadarPrefs"
        private const val USER_ID_KEY = "throneUserId"

        @JvmStatic
        var instance: BeaconRadarModule? = null
    }

    private val beaconManager: BeaconManager = BeaconManager.getInstanceForApplication(reactContext)
    private var region: Region = BeaconRadarBackgroundBootstrap.defaultRegion()

    init {
        instance = this
        BeaconRadarBackgroundBootstrap.configureBeaconManager(reactContext)

        val backgroundMode = BeaconRadarPreferences.isBackgroundModeEnabled(reactContext)
        val maxDistance = BeaconRadarPreferences.getMaxDistance(reactContext)
        log(
            "Module init — backgroundMode=$backgroundMode, maxDistance=$maxDistance, monitoringInitialized=${BeaconRadarBackgroundBootstrap.isMonitoringInitialized}",
            "THRONE_BEACON_SETUP_STARTING"
        )

        if (backgroundMode) {
            BeaconRadarBackgroundBootstrap.ensureBackgroundMonitoring(reactContext, "module-init")
        }
    }

    // --- JS event emitters (called by BeaconRadarBackgroundCallbacks) ---

    fun emitDidEnterRegion(region: Region) {
        reactContext.runOnUiQueueThread {
            try {
                val params = Arguments.createMap().apply {
                    putString("identifier", region.uniqueId)
                    putString("uuid", region.id1?.toString())
                    putString("major", region.id2?.toString())
                    putString("minor", region.id3?.toString())
                }
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("didEnterRegion", params)
            } catch (e: Exception) {
                logWarning("Could not emit didEnterRegion to JS: ${e.message}")
            }
        }
    }

    fun emitDidExitRegion(region: Region) {
        reactContext.runOnUiQueueThread {
            try {
                val params = Arguments.createMap().apply {
                    putString("identifier", region.uniqueId)
                    putString("uuid", region.id1?.toString())
                    putString("major", region.id2?.toString())
                    putString("minor", region.id3?.toString())
                }
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("didExitRegion", params)
            } catch (e: Exception) {
                logWarning("Could not emit didExitRegion to JS: ${e.message}")
            }
        }
    }

    fun emitBeaconsDetected(beacons: Collection<Beacon>) {
        reactContext.runOnUiQueueThread {
            try {
                val beaconArray = Arguments.createArray()
                beacons.forEach { beacon ->
                    val beaconMap = Arguments.createMap().apply {
                        putString("uuid", beacon.id1?.toString() ?: "")
                        putString("major", beacon.id2?.toString() ?: "")
                        putString("minor", beacon.id3?.toString() ?: "")
                        putDouble("distance", BeaconDistanceUtils.effectiveDistance(beacon))
                        putInt("rssi", beacon.rssi)
                        putInt("txPower", beacon.txPower)
                        putString("bluetoothName", beacon.bluetoothName ?: "")
                        putString("bluetoothAddress", beacon.bluetoothAddress ?: "")
                        putInt("manufacturer", beacon.manufacturer)
                        putDouble("timestamp", beacon.lastCycleDetectionTimestamp.toDouble())
                    }
                    beaconArray.pushMap(beaconMap)
                }
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("onBeaconsDetected", beaconArray)
            } catch (e: Exception) {
                logWarning("Could not emit onBeaconsDetected to JS: ${e.message}")
            }
        }
    }

    // --- React methods ---

    override fun getName(): String = NAME

    @ReactMethod
    fun startScanning(uuid: String?, config: ReadableMap?, promise: Promise) {
        val resolvedUuid = uuid ?: BeaconRadarBackgroundBootstrap.DEFAULT_REGION_UUID
        region = Region("all-beacons", Identifier.parse(resolvedUuid), null, null)
        log("startScanning with region UUID: $resolvedUuid", "BEACON_MONITORING_SETUP")

        BeaconRadarBackgroundBootstrap.ensureBackgroundMonitoring(reactContext, "startScanning")
        promise.resolve(null)
    }

    @ReactMethod
    fun setMaxDistance(distance: Double, promise: Promise) {
        BeaconRadarPreferences.setMaxDistance(reactContext, distance)
        log("Updated maxDistance to $distance")
        promise.resolve(distance)
    }

    @ReactMethod
    fun getMaxDistance(promise: Promise) {
        promise.resolve(BeaconRadarPreferences.getMaxDistance(reactContext))
    }

    @ReactMethod
    fun requestAlwaysAuthorization(promise: Promise) {
        val status = if (hasAllRequiredPermissions()) "granted" else "denied"
        promise.resolve(Arguments.createMap().apply { putString("status", status) })
    }

    @ReactMethod
    fun requestWhenInUseAuthorization(promise: Promise) {
        val status = if (hasAllRequiredPermissions()) "granted" else "denied"
        promise.resolve(Arguments.createMap().apply { putString("status", status) })
    }

    @ReactMethod
    fun getAuthorizationStatus(promise: Promise) {
        val status = if (hasAllRequiredPermissions()) "granted" else "denied"
        promise.resolve(Arguments.createMap().apply { putString("status", status) })
    }

    @ReactMethod
    fun isBluetoothEnabled(promise: Promise) {
        val adapter = (reactContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        promise.resolve(adapter?.isEnabled == true)
    }

    @ReactMethod
    fun getBluetoothState(promise: Promise) {
        val adapter = (reactContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        val state = when {
            adapter == null -> "unsupported"
            !adapter.isEnabled -> "off"
            else -> "on"
        }
        promise.resolve(state)
    }

    @ReactMethod
    fun startRadar(config: ReadableMap?, promise: Promise) {
        log("startRadar called")
        val uuid = config?.getString("uuid") ?: BeaconRadarBackgroundBootstrap.DEFAULT_REGION_UUID
        startScanning(uuid, config, promise)
    }

    @ReactMethod
    fun stopScanning(promise: Promise) {
        try {
            beaconManager.stopRangingBeacons(region)
        } catch (_: Exception) {}
        try {
            beaconManager.stopMonitoring(region)
        } catch (_: Exception) {}
        log("Stopped scanning", "BEACON_MONITORING_DESTROYED")
        promise.resolve(null)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Boolean = true

    @ReactMethod
    fun enableBackgroundMode(enable: Boolean, promise: Promise) {
        try {
            log("enableBackgroundMode: $enable")
            BeaconRadarPreferences.setBackgroundModeEnabled(reactContext, enable)

            if (enable) {
                BeaconRadarBackgroundBootstrap.ensureBackgroundMonitoring(reactContext, "enableBackgroundMode")
            } else {
                try {
                    beaconManager.stopRangingBeacons(region)
                } catch (_: Exception) {}
                try {
                    beaconManager.stopMonitoring(region)
                } catch (_: Exception) {}
                log("Background mode disabled, monitoring stopped", "BEACON_MONITORING_DESTROYED")
            }
            promise.resolve(true)
        } catch (e: Exception) {
            logError("Error toggling background mode: ${e.message}")
            promise.reject("BACKGROUND_MODE_ERROR", "Failed to set background mode: ${e.message}")
        }
    }

    @ReactMethod
    fun getBackgroundMode(promise: Promise) {
        promise.resolve(BeaconRadarPreferences.isBackgroundModeEnabled(reactContext))
    }

    @ReactMethod
    fun handlePushNotification(data: ReadableMap?, promise: Promise) {
        val handled = BeaconPushHandler.handlePayload(
            reactContext.applicationContext,
            data?.toHashMap(),
            "react-native"
        )
        promise.resolve(handled)
    }

    @ReactMethod
    fun setThroneUserId(userId: String, promise: Promise) {
        try {
            val sharedPrefs = reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sharedPrefs.edit().putString(USER_ID_KEY, userId).apply()
            log("Throne user ID saved: $userId")
            promise.resolve(true)
        } catch (e: Exception) {
            logError("Error saving throne user ID: ${e.message}")
            promise.reject("USER_ID_ERROR", "Failed to save user ID: ${e.message}")
        }
    }

    @ReactMethod
    fun getThroneUserId(promise: Promise) {
        try {
            val sharedPrefs = reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val userId = sharedPrefs.getString(USER_ID_KEY, "") ?: ""
            promise.resolve(userId)
        } catch (e: Exception) {
            logError("Error getting throne user ID: ${e.message}")
            promise.reject("USER_ID_ERROR", "Failed to get user ID: ${e.message}")
        }
    }

    @ReactMethod
    fun setPosthogKey(apiKey: String, promise: Promise) {
        try {
            BeaconRadarPreferences.setPosthogKey(reactContext, apiKey)
            log("PostHog key updated")
            promise.resolve(true)
        } catch (e: Exception) {
            logError("Error saving PostHog key: ${e.message}")
            promise.reject("POSTHOG_KEY_ERROR", "Failed to save PostHog key: ${e.message}")
        }
    }

    @ReactMethod
    fun getPosthogKey(promise: Promise) {
        try {
            promise.resolve(BeaconRadarPreferences.getPosthogKey(reactContext))
        } catch (e: Exception) {
            logError("Error getting PostHog key: ${e.message}")
            promise.reject("POSTHOG_KEY_ERROR", "Failed to get PostHog key: ${e.message}")
        }
    }

    @ReactMethod
    fun setBeaconDebug(enabled: Boolean, promise: Promise) {
        try {
            BeaconRadarPreferences.setBeaconDebugEnabled(reactContext, enabled)
            log("Beacon debug logging set to $enabled")
            promise.resolve(true)
        } catch (e: Exception) {
            logError("Error saving beacon debug flag: ${e.message}")
            promise.reject("BEACON_DEBUG_ERROR", "Failed to save beacon debug flag: ${e.message}")
        }
    }

    @ReactMethod
    fun getBeaconDebug(promise: Promise) {
        try {
            promise.resolve(BeaconRadarPreferences.isBeaconDebugEnabled(reactContext))
        } catch (e: Exception) {
            logError("Error getting beacon debug flag: ${e.message}")
            promise.reject("BEACON_DEBUG_ERROR", "Failed to get beacon debug flag: ${e.message}")
        }
    }

    @ReactMethod
    fun canDrawOverlays(promise: Promise) {
        promise.resolve(Settings.canDrawOverlays(reactContext))
    }

    @ReactMethod
    fun getBeaconDiagnostics(promise: Promise) {
        val diag = Arguments.createMap().apply {
            putBoolean("backgroundModeEnabled", BeaconRadarPreferences.isBackgroundModeEnabled(reactContext))
            putBoolean("monitoringInitialized", BeaconRadarBackgroundBootstrap.isMonitoringInitialized)
            putBoolean("permissionsGranted", hasAllRequiredPermissions())
            putDouble("maxDistance", BeaconRadarPreferences.getMaxDistance(reactContext))
            try {
                putBoolean("foregroundServiceFailed", beaconManager.foregroundServiceStartFailed())
            } catch (_: Exception) {
                putBoolean("foregroundServiceFailed", false)
            }
        }
        promise.resolve(diag)
    }

    // --- Helpers ---

    private fun hasAllRequiredPermissions(): Boolean {
        return BeaconRadarBackgroundBootstrap.hasAllRequiredPermissions(reactContext)
    }

    private fun log(message: String, type: String = "GENERAL") {
        if (type == "GENERAL") {
            BeaconRadarLogger.i(reactContext.applicationContext, TAG, message, type = type)
        } else {
            BeaconRadarLogger.logKeyEvent(reactContext.applicationContext, TAG, message, type = type)
        }
    }

    private fun logWarning(message: String, type: String = "GENERAL") {
        BeaconRadarLogger.w(reactContext.applicationContext, TAG, message, type = type)
    }

    private fun logError(message: String, type: String = "GENERAL") {
        BeaconRadarLogger.e(reactContext.applicationContext, TAG, message, type = type)
    }

    override fun invalidate() {
        BeaconBluetoothManager.cancelActiveConnect()
        super.invalidate()
    }
}
