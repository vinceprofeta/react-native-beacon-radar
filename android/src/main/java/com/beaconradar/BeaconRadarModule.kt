package com.beaconradar

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.modules.core.PermissionListener
import org.altbeacon.beacon.*
import android.provider.Settings


@ReactModule(name = BeaconRadarModule.NAME)
class BeaconRadarModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext),
    PermissionListener,
    MonitorNotifier,
    RangeNotifier {

    companion object {
        const val TAG = "BeaconRadar"
        const val NAME = "BeaconRadar"
        const val UPDATE = "updateBeacons"
        const val BEACONS = "beacons"
        private const val NOTIFICATION_CHANNEL_ID = "beacon_detector_channel"
        private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "beacon_foreground_channel"
        private const val FOREGROUND_NOTIFICATION_NAME = "Throne Hands-Free Active"
        private const val FOREGROUND_NOTIFICATION_DESCRIPTION = "Throne is running in the background to detect your nearby device."
        private const val FOREGROUND_NOTIFICATION_ID = 456
        private const val PREFS_NAME = "BeaconRadarPrefs"
        private const val USER_ID_KEY = "throneUserId"
        private const val DEFAULT_REGION_UUID = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825"
        private var MAX_DISTANCE = 0.4
        @JvmStatic
        var instance: BeaconRadarModule? = null

        private const val FOREGROUND_SCAN_PERIOD_MS = 1100L
        private const val FOREGROUND_BETWEEN_SCAN_PERIOD_MS = 0L
        private const val BACKGROUND_SCAN_PERIOD_MS = 1100L
        private const val BACKGROUND_BETWEEN_SCAN_PERIOD_MS = 0L
        private const val BEACON_MAX_AGE_MS = 10000L
    }

    private val beaconManager: BeaconManager = BeaconManager.getInstanceForApplication(reactContext)
    private var region: Region = Region("all-beacons", Identifier.parse(DEFAULT_REGION_UUID), null, null)
    private var monitorNotifierRegistered = false
    private var rangeNotifierRegistered = false
    private var monitoringActive = false
    private var rangingActive = false

    init {
        instance = this
        setupBeaconManager()
        MAX_DISTANCE = BeaconRadarPreferences.getMaxDistance(reactContext)

        val backgroundMode = loadBackgroundModeSetting()
        log(
            "Module init — backgroundMode=$backgroundMode, maxDistance=$MAX_DISTANCE, initProviderActive=${BeaconRadarInitProvider.isMonitoringInitialized}",
            "THRONE_BEACON_SETUP_STARTING"
        )

        if (backgroundMode) {
            if (BeaconRadarInitProvider.isMonitoringInitialized) {
                // InitProvider already started monitoring; just register our notifiers
                // so we can forward events to JS and trigger BLE connections.
                log("InitProvider already active — registering module notifiers alongside it", "BEACON_MONITORING_SETUP")
                registerNotifiers()
                monitoringActive = true
                beaconManager.requestStateForRegion(region)
            } else {
                // InitProvider didn't run (e.g. fresh install, pref was set after first launch).
                // Start monitoring from here.
                log("InitProvider did not start monitoring — starting from module init", "BEACON_MONITORING_SETUP")
                setBackgroundMode(true)
            }
            setupForegroundService()
        }

    }

    private fun loadBackgroundModeSetting(): Boolean {
        return BeaconRadarPreferences.isBackgroundModeEnabled(reactContext)
    }

    private fun setBackgroundMode(enable: Boolean) {
        log("setBackgroundMode: $enable")
        try {
            BeaconRadarPreferences.setBackgroundModeEnabled(reactContext, enable)

            if (enable) {
                setupForegroundService()
                reactContext.runOnUiQueueThread {
                    beaconManager.setBackgroundMode(true)
                    beaconManager.backgroundScanPeriod = BACKGROUND_SCAN_PERIOD_MS
                    beaconManager.backgroundBetweenScanPeriod = BACKGROUND_BETWEEN_SCAN_PERIOD_MS
                    registerNotifiers()
                    ensureMonitoringStarted()
                    beaconManager.requestStateForRegion(region)
                    log("Background mode enabled, monitoring active for region: $region", "BEACON_MONITORING_SETUP")
                }
            } else {
                reactContext.runOnUiQueueThread {
                    try {
                        beaconManager.setBackgroundMode(false)
                        stopAllBeaconWork(removeNotifiers = true)
                        log("Stopped all beacon monitoring and ranging", "BEACON_MONITORING_DESTROYED")
                    } catch (e: Exception) {
                        logError("Error stopping monitoring: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            logError("Error setting background mode: ${e.message}")
        }
    }

    private fun setupBeaconManager() {
        BeaconManager.setDebug(BuildConfig.DEBUG)

        if (beaconManager.beaconParsers.none { it.layout == "m:0-3=4c000215,i:4-19,i:20-21,i:22-23,p:24-24" }) {
            val iBeaconParser = BeaconParser()
                .setBeaconLayout("m:0-3=4c000215,i:4-19,i:20-21,i:22-23,p:24-24")
            beaconManager.beaconParsers.add(iBeaconParser)
        }

        try {
            beaconManager.setRegionStatePeristenceEnabled(false)
            log("Disabled AltBeacon region state persistence")
        } catch (e: Exception) {
            logWarning("Could not disable AltBeacon region state persistence: ${e.message}")
        }

        beaconManager.foregroundScanPeriod = FOREGROUND_SCAN_PERIOD_MS
        beaconManager.foregroundBetweenScanPeriod = FOREGROUND_BETWEEN_SCAN_PERIOD_MS
        beaconManager.backgroundScanPeriod = BACKGROUND_SCAN_PERIOD_MS
        beaconManager.backgroundBetweenScanPeriod = BACKGROUND_BETWEEN_SCAN_PERIOD_MS

        log("BeaconManager setup complete")
    }

    /**
     * Registers this module as a monitor/range notifier on BeaconManager.
     * Safe to call multiple times — tracks registration state and removes
     * old notifiers before re-adding to prevent duplicate callbacks.
     */
    private fun registerNotifiers() {
        if (monitorNotifierRegistered) {
            beaconManager.removeMonitorNotifier(this)
        }
        beaconManager.addMonitorNotifier(this)
        monitorNotifierRegistered = true

        if (rangeNotifierRegistered) {
            beaconManager.removeRangeNotifier(this)
        }
        beaconManager.addRangeNotifier(this)
        rangeNotifierRegistered = true
    }

    private fun ensureMonitoringStarted() {
        if (!monitoringActive) {
            beaconManager.startMonitoring(region)
            monitoringActive = true
        }
    }

    private fun ensureRangingStarted(region: Region) {
        if (!rangingActive) {
            beaconManager.startRangingBeacons(region)
            rangingActive = true
        }
    }

    private fun ensureRangingStopped(region: Region) {
        if (rangingActive) {
            beaconManager.stopRangingBeacons(region)
            rangingActive = false
        }
    }

    private fun stopAllBeaconWork(removeNotifiers: Boolean) {
        try {
            beaconManager.stopRangingBeacons(region)
        } catch (_: Exception) {
        } finally {
            rangingActive = false
        }

        try {
            beaconManager.stopMonitoring(region)
        } catch (_: Exception) {
        } finally {
            monitoringActive = false
        }

        if (removeNotifiers) {
            if (monitorNotifierRegistered) {
                beaconManager.removeMonitorNotifier(this)
                monitorNotifierRegistered = false
            }
            if (rangeNotifierRegistered) {
                beaconManager.removeRangeNotifier(this)
                rangeNotifierRegistered = false
            }
        }
    }

    fun setupForegroundService() {
        try {
            if (!hasAllRequiredPermissions()) {
                logWarning("Permissions not granted. Skipping enableForegroundServiceScanning.")
                return
            }
            val builder = NotificationCompat.Builder(reactContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(FOREGROUND_NOTIFICATION_NAME)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

            val intent = reactContext.packageManager.getLaunchIntentForPackage(reactContext.packageName)
                ?: Intent().apply {
                    setPackage(reactContext.packageName)
                }

            val pendingIntent = PendingIntent.getActivity(
                reactContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.setContentIntent(pendingIntent)

            val channel = NotificationChannel(
                FOREGROUND_NOTIFICATION_CHANNEL_ID,
                FOREGROUND_NOTIFICATION_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = FOREGROUND_NOTIFICATION_DESCRIPTION
            }

            val notificationManager = reactContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            log("Calling enableForegroundServiceScanning")
            BeaconManager.getInstanceForApplication(reactContext).enableForegroundServiceScanning(
                builder.build(),
                FOREGROUND_NOTIFICATION_ID
            )
            log("enableForegroundServiceScanning succeeded")
        } catch (e: IllegalStateException) {
            logWarning("enableForegroundServiceScanning failed (may fall back to JobScheduler): ${e.message}")
            checkAndRetryForegroundService()
        }
    }

    /**
     * On Android 12+, foreground service start can be blocked by the OS.
     * AltBeacon falls back to JobScheduler (~15min detection gaps).
     * This checks for that state and retries when possible.
     */
    private fun checkAndRetryForegroundService() {
        try {
            if (beaconManager.foregroundServiceStartFailed()) {
                logWarning("Foreground service start was blocked by OS — using JobScheduler fallback")
            }
        } catch (e: Exception) {
            // foregroundServiceStartFailed() may not exist on older library versions
            logWarning("Could not check foreground service state: ${e.message}")
        }
    }

    /**
     * Call this from the host app when returning to foreground to retry
     * foreground service if it was previously blocked.
     */
    fun retryForegroundServiceIfNeeded() {
        try {
            if (beaconManager.foregroundServiceStartFailed()) {
                log("Retrying foreground service scanning from foreground event")
                beaconManager.retryForegroundServiceScanning()
            }
        } catch (e: Exception) {
            logWarning("retryForegroundServiceScanning not available: ${e.message}")
        }
    }

    private fun hasAllRequiredPermissions(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(reactContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(reactContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val requiresBackground = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val backgroundGranted = !requiresBackground || ContextCompat.checkSelfPermission(reactContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val locationGranted = fineGranted && coarseGranted && backgroundGranted

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted = ContextCompat.checkSelfPermission(reactContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ContextCompat.checkSelfPermission(reactContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            return scanGranted && connectGranted && locationGranted
        }
        return locationGranted
    }

    // --- MonitorNotifier ---

    override fun didEnterRegion(region: Region) {
        log("didEnterRegion: ${region.uniqueId}", "BEACON_REGION_ENTERED")
        log("Region entered; waiting for ranging callbacks", "BEACON_REGION_WAITING_FOR_RANGING")
        ensureRangingStarted(region)

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
                logWarning("Could not emit didEnterRegion to JS (bridge may not be ready): ${e.message}")
            }
        }
    }

    override fun didExitRegion(region: Region) {
        log("didExitRegion: ${region.uniqueId}")
        reactContext.runOnUiQueueThread {
            ensureRangingStopped(region)
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

    override fun didDetermineStateForRegion(state: Int, region: Region) {
        val stateStr = when (state) {
            MonitorNotifier.INSIDE -> "INSIDE"
            MonitorNotifier.OUTSIDE -> "OUTSIDE"
            else -> "UNKNOWN"
        }
        log("didDetermineState: ${region.uniqueId} = $stateStr", "BEACON_RANGING_STARTED")
        if (state == MonitorNotifier.INSIDE) {
            ensureRangingStarted(region)
        }
    }

    // --- RangeNotifier ---

    override fun didRangeBeaconsInRegion(beacons: Collection<Beacon>, region: Region) {
        if (beacons.isEmpty()) return

        val recentBeacons = beacons.filter { beacon ->
            val age = System.currentTimeMillis() - beacon.lastCycleDetectionTimestamp
            age < BEACON_MAX_AGE_MS
        }
        if (recentBeacons.isEmpty()) return

        BeaconPushHandler.handleRangedBeacons(
            reactContext.applicationContext,
            recentBeacons,
            "react-native-range"
        )

        reactContext.runOnUiQueueThread {
            try {
                val beaconArray = Arguments.createArray()
                recentBeacons.forEach { beacon ->
                    val beaconMap = Arguments.createMap().apply {
                        putString("uuid", beacon.id1?.toString() ?: "")
                        putString("major", beacon.id2?.toString() ?: "")
                        putString("minor", beacon.id3?.toString() ?: "")
                        putDouble("distance", effectiveDistance(beacon))
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

    override fun getName(): String = NAME

    @ReactMethod
    fun startScanning(uuid: String?, config: ReadableMap?, promise: Promise) {
        val resolvedUuid = uuid ?: DEFAULT_REGION_UUID
        val previousRegion = region
        region = Region("all-beacons", Identifier.parse(resolvedUuid), null, null)
        log("startScanning with region UUID: $resolvedUuid", "BEACON_MONITORING_SETUP")
        reactContext.runOnUiQueueThread {
            try {
                beaconManager.stopRangingBeacons(previousRegion)
            } catch (_: Exception) {}
            try {
                beaconManager.stopMonitoring(previousRegion)
            } catch (_: Exception) {}
            rangingActive = false
            monitoringActive = false

            registerNotifiers()
            ensureMonitoringStarted()
            beaconManager.requestStateForRegion(region)
        }
        setupForegroundService()
        promise.resolve(null)
    }

    @ReactMethod
    fun setMaxDistance(distance: Double, promise: Promise) {
        BeaconRadarPreferences.setMaxDistance(reactContext, distance)
        MAX_DISTANCE = distance
        log("Updated maxDistance to $distance")
        promise.resolve(distance)
    }

    @ReactMethod
    fun getMaxDistance(promise: Promise) {
        MAX_DISTANCE = BeaconRadarPreferences.getMaxDistance(reactContext)
        promise.resolve(MAX_DISTANCE)
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
        val uuid = config?.getString("uuid") ?: DEFAULT_REGION_UUID
        startScanning(uuid, config, promise)
    }

    @ReactMethod
    fun stopScanning(promise: Promise) {
        reactContext.runOnUiQueueThread {
            try {
                stopAllBeaconWork(removeNotifiers = true)
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("STOP_SCAN_ERROR", "Failed to stop scanning: ${e.message}")
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Boolean {
        return true
    }

    @ReactMethod
    fun enableBackgroundMode(enable: Boolean, promise: Promise) {
        try {
            setBackgroundMode(enable)
            promise.resolve(true)
        } catch (e: Exception) {
            logError("Error toggling background mode: ${e.message}")
            promise.reject("BACKGROUND_MODE_ERROR", "Failed to set background mode: ${e.message}")
        }
    }

    @ReactMethod
    fun getBackgroundMode(promise: Promise) {
        promise.resolve(loadBackgroundModeSetting())
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
            putBoolean("backgroundModeEnabled", loadBackgroundModeSetting())
            putBoolean("monitoringActive", monitoringActive)
            putBoolean("rangingActive", rangingActive)
            putBoolean("initProviderActive", BeaconRadarInitProvider.isMonitoringInitialized)
            putBoolean("permissionsGranted", hasAllRequiredPermissions())
            MAX_DISTANCE = BeaconRadarPreferences.getMaxDistance(reactContext)
            putDouble("maxDistance", MAX_DISTANCE)
            try {
                putBoolean("foregroundServiceFailed", beaconManager.foregroundServiceStartFailed())
            } catch (_: Exception) {
                putBoolean("foregroundServiceFailed", false)
            }
        }
        promise.resolve(diag)
    }

    private fun calculateDistance(txPower: Int, rssi: Int): Double {
        if (rssi == 0) return -1.0
        return Math.pow(10.0, (txPower - rssi) / 20.0)
    }

    private fun effectiveDistance(beacon: Beacon): Double {
        return if (beacon.distance < 0 && beacon.rssi != 0) {
            calculateDistance(beacon.txPower, beacon.rssi)
        } else {
            beacon.distance
        }
    }

    /**
     * Always logs to logcat and mirrors the same message to PostHog when a key is configured.
     * This keeps release diagnostics visible locally while preserving the iOS-style
     * remote hands-free log stream.
     */
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
        reactContext.runOnUiQueueThread {
            stopAllBeaconWork(removeNotifiers = true)
        }
        BeaconBluetoothManager.cancelActiveConnect()
        super.invalidate()
    }
}
