package com.beaconradar

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
        const val TAG = "BeaconReference"
        const val NAME = "BeaconRadar"
        const val UPDATE = "updateBeacons"
        const val BEACONS = "beacons"
        private const val LAUNCH_APP_ON_BEACON_DETECTION = false
        private const val SEND_NOTIFICATION_ON_BEACON_DETECTION = false
        private const val MESSAGE_BLE_ON_BEACON_DETECTION = true
        // private const val BLUETOOTH_ERROR = "BLUETOOTH_ERROR"
        // private const val PERMISSION_REQUEST_CODE = 1
        // private const val BACKGROUND_NOTIFICATION_ID = 12345
        private const val NOTIFICATION_CHANNEL_ID = "beacon_detector_channel"
        private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "beacon_foreground_channel"
        private const val FOREGROUND_NOTIFICATION_NAME = "Throne Hands-Free Active"
        private const val FOREGROUND_NOTIFICATION_DESCRIPTION = "Throne is running in the background to detect your nearby device."
        private const val FOREGROUND_NOTIFICATION_ID = 456
        private const val PREFS_NAME = "BeaconRadarPrefs"
        private const val BACKGROUND_MODE_KEY = "backgroundModeEnabled"
        private const val USER_ID_KEY = "throneUserId"
        private const val DEFAULT_REGION_UUID = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825"
        private var MAX_DISTANCE = 0.4
        @JvmStatic
        var instance: BeaconRadarModule? = null

        // Add a timestamp to track when we last launched the app
        private var lastAppLaunchTime: Long = 0
        // Minimum time between app launches (5 seconds)
        private const val MIN_LAUNCH_INTERVAL = 5000L
        private const val FOREGROUND_SCAN_PERIOD_MS = 1100L
        private const val FOREGROUND_BETWEEN_SCAN_PERIOD_MS = 0L
        private const val BACKGROUND_SCAN_PERIOD_MS = 1100L
        private const val BACKGROUND_BETWEEN_SCAN_PERIOD_MS = 1000L
        private const val BEACON_EVENT_THROTTLE_MS = 1500L
        private const val BEACON_MAX_AGE_MS = 10000L
    }

    private val beaconManager: BeaconManager = BeaconManager.getInstanceForApplication(reactContext)
    private var region: Region = Region("all-beacons", Identifier.parse(DEFAULT_REGION_UUID), null, null)
    private var bluetoothManager: BeaconBluetoothManager? = null
    private var monitorNotifierRegistered = false
    private var rangeNotifierRegistered = false
    private var monitoringActive = false
    private var rangingActive = false
    private var lastBeaconEventAtMs = 0L

    init {
        instance = this
        setupBeaconManager()
        // Load background mode setting from SharedPreferences
        val backgroundMode = loadBackgroundModeSetting()
        Log.d(TAG, "Background mode: $backgroundMode")
        setBackgroundMode(backgroundMode)
        // Initialize Bluetooth manager
        bluetoothManager = BeaconBluetoothManager(reactContext)
    }

    // Load background mode setting from SharedPreferences
    private fun loadBackgroundModeSetting(): Boolean {
        val sharedPrefs = reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.d(TAG, "Shared prefs: $sharedPrefs")
        // Default to false if setting doesn't exist
        return sharedPrefs.getBoolean(BACKGROUND_MODE_KEY, false)
    }

    private fun setBackgroundMode(enable: Boolean) {
        debugLog("Setting background beacon scanning to: $enable")
        try {
            // Save preference first
            debugLog("Saving background mode preference to: $enable")
            val sharedPrefs = reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sharedPrefs.edit().putBoolean(BACKGROUND_MODE_KEY, enable).apply()
            debugLog("Background mode preference saved: $enable")

            if (enable) {
                // When enabling, add notifiers and restart monitoring
                // Re-enable foreground service scanning so monitoring survives cold start after process death.
                setupForegroundService()
                reactContext.runOnUiQueueThread {
                    beaconManager.setBackgroundMode(true)
                    beaconManager.backgroundScanPeriod = BACKGROUND_SCAN_PERIOD_MS
                    beaconManager.backgroundBetweenScanPeriod = BACKGROUND_BETWEEN_SCAN_PERIOD_MS
                    ensureNotifiersRegistered()
                    ensureMonitoringStarted()
                    beaconManager.requestStateForRegion(region)
                    debugLog("Background mode enabled, monitoring restarted for region: $region")
                }
            } else {
                // When disabling, stop all monitoring and ranging
                reactContext.runOnUiQueueThread {
                    try {
                        beaconManager.setBackgroundMode(false)
                        stopAllBeaconWork(removeNotifiers = true)
                        debugLog("Stopped all beacon monitoring and ranging")
                    } catch (e: Exception) {
                        debugLog("Error stopping monitoring: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting background mode: ${e.message}")
        }
    }

    private fun setupBeaconManager() {
        BeaconManager.setDebug(BuildConfig.DEBUG)
        val iBeaconParser = BeaconParser()
            .setBeaconLayout("m:0-3=4c000215,i:4-19,i:20-21,i:22-23,p:24-24") //ibeacon layout
        beaconManager.beaconParsers.add(iBeaconParser)


        beaconManager.foregroundScanPeriod = FOREGROUND_SCAN_PERIOD_MS
        beaconManager.foregroundBetweenScanPeriod = FOREGROUND_BETWEEN_SCAN_PERIOD_MS
        beaconManager.backgroundScanPeriod = BACKGROUND_SCAN_PERIOD_MS
        beaconManager.backgroundBetweenScanPeriod = BACKGROUND_BETWEEN_SCAN_PERIOD_MS

        debugLog("BeaconManager setup complete")
    }

    fun setupBeaconScanning() {
        debugLog("Setting up BeaconManager with iBeacon parser")
        reactContext.runOnUiQueueThread {
            ensureNotifiersRegistered()
        }

        setupForegroundService()

        // Only start monitoring here - ranging will be started when a region is entered
        debugLog("Starting monitoring for region: $region")
        reactContext.runOnUiQueueThread {
            debugLog("Requesting immediate state determination for region: $region")
            ensureMonitoringStarted()
            beaconManager.requestStateForRegion(region)
        }
    }

    private fun ensureNotifiersRegistered() {
        if (!monitorNotifierRegistered) {
            beaconManager.addMonitorNotifier(this)
            monitorNotifierRegistered = true
        }
        if (!rangeNotifierRegistered) {
            beaconManager.addRangeNotifier(this)
            rangeNotifierRegistered = true
        }
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
            // Guard: foreground service cannot be enabled until after location permission is granted (SDK 34+)
            if (!hasAllRequiredPermissions()) {
                Log.w(TAG, "Permissions not granted (fine/coarse/background and BLE on S+). Skipping enableForegroundServiceScanning.")
                return
            }
            val builder = NotificationCompat.Builder(reactContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(FOREGROUND_NOTIFICATION_NAME)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

            // Create a default intent that opens the app's package
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

            debugLog("Calling enableForegroundServiceScanning")
            BeaconManager.getInstanceForApplication(reactContext).enableForegroundServiceScanning(
                builder.build(),
                FOREGROUND_NOTIFICATION_ID
            )
            debugLog("Back from enableForegroundServiceScanning")
        } catch (e: IllegalStateException) {
            debugLog("Cannot enable foreground service scanning. This may be because consumers are already bound: ${e.message}")
            // Continue anyway - this is not a fatal error
        }
    }

    // Centralized permission check mirroring BLE manager
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

    // MonitorNotifier implementation
    override fun didEnterRegion(region: Region) {
        debugLog("didEnterRegion: The user entered in the region: $region")

        // Start ranging only when entering a region
        debugLog("didEnterRegion: Starting ranging beacons for region: $region")
        ensureRangingStarted(region)


        // Emit region enter event to JavaScript
        reactContext.runOnUiQueueThread {
            val params = Arguments.createMap().apply {
                putString("identifier", region.uniqueId)
                putString("uuid", region.id1?.toString())
                putString("major", region.id2?.toString())
                putString("minor", region.id3?.toString())
            }

            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("didEnterRegion", params)
            debugLog("didEnterRegion: Event emitted successfully")
        }
    }

    override fun didExitRegion(region: Region) {
        debugLog("didExitRegion: Stopping ranging beacons for region: $region")
        reactContext.runOnUiQueueThread {
            ensureRangingStopped(region)

            val params = Arguments.createMap().apply {
                putString("identifier", region.uniqueId)
                putString("uuid", region.id1?.toString())
                putString("major", region.id2?.toString())
                putString("minor", region.id3?.toString())
            }
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("didExitRegion", params)
        }
    }

    override fun didDetermineStateForRegion(state: Int, region: Region) {
        val stateStr = when(state) {
            MonitorNotifier.INSIDE -> "INSIDE"
            MonitorNotifier.OUTSIDE -> "OUTSIDE"
            else -> "UNKNOWN"
        }
        debugLog("didDetermineStateForRegion: Region ${region.uniqueId} state is $stateStr")
        if (state == MonitorNotifier.INSIDE) {
            debugLog("didDetermineStateForRegion: Already inside region, starting ranging")
            ensureRangingStarted(region)
        }
    }

    // RangeNotifier implementation
    override fun didRangeBeaconsInRegion(beacons: Collection<Beacon>, region: Region) {
        if (beacons.isEmpty()) {
            return
        }

        val recentBeacons = beacons.filter { beacon ->
            val age = System.currentTimeMillis() - beacon.lastCycleDetectionTimestamp
            age < BEACON_MAX_AGE_MS
        }
        if (recentBeacons.isEmpty()) {
            return
        }

        val nearestBeacon = recentBeacons.minByOrNull { beacon ->
            effectiveDistance(beacon)
        } ?: return
        val nearestDistance = effectiveDistance(nearestBeacon)
        if (nearestDistance > MAX_DISTANCE) {
            return
        }

        val isInForeground = reactContext.currentActivity?.hasWindowFocus() == true
        if (!isInForeground) {
            takeActionOnBeaconDetection(nearestBeacon)
        }

        val now = System.currentTimeMillis()
        if (now - lastBeaconEventAtMs < BEACON_EVENT_THROTTLE_MS) {
            return
        }
        lastBeaconEventAtMs = now

        reactContext.runOnUiQueueThread {
            val beaconArray = Arguments.createArray()
            val beaconMap = Arguments.createMap().apply {
                putString("uuid", nearestBeacon.id1?.toString() ?: "")
                putString("major", nearestBeacon.id2?.toString() ?: "")
                putString("minor", nearestBeacon.id3?.toString() ?: "")
                putDouble("distance", nearestDistance)
                putInt("rssi", nearestBeacon.rssi)
                putInt("txPower", nearestBeacon.txPower)
                putString("bluetoothName", nearestBeacon.bluetoothName ?: "")
                putString("bluetoothAddress", nearestBeacon.bluetoothAddress ?: "")
                putInt("manufacturer", nearestBeacon.manufacturer)
                putDouble("timestamp", nearestBeacon.lastCycleDetectionTimestamp.toDouble())
            }
            beaconArray.pushMap(beaconMap)
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("onBeaconsDetected", beaconArray)
        }
    }



    override fun getName(): String = NAME

    @ReactMethod
    fun startScanning(uuid: String?, config: ReadableMap?, promise: Promise) {
        val resolvedUuid = uuid ?: DEFAULT_REGION_UUID
        val previousRegion = region
        region = Region("all-beacons", Identifier.parse(resolvedUuid), null, null)
        debugLog("startScanning with region UUID: $resolvedUuid")
        reactContext.runOnUiQueueThread {
            try {
                beaconManager.stopRangingBeacons(previousRegion)
            } catch (_: Exception) {
            }
            try {
                beaconManager.stopMonitoring(previousRegion)
            } catch (_: Exception) {
            }
            rangingActive = false
            monitoringActive = false
            monitorNotifierRegistered = false
            rangeNotifierRegistered = false

            ensureNotifiersRegistered()
            ensureMonitoringStarted()
            beaconManager.requestStateForRegion(region)
        }
        setupForegroundService()
        promise.resolve(null)
    }

    @ReactMethod
    fun setMaxDistance(distance: Double, promise: Promise) {
        MAX_DISTANCE = distance
        promise.resolve(distance)
    }

    @ReactMethod
    fun getMaxDistance(promise: Promise) {
        promise.resolve(MAX_DISTANCE)
    }

    @ReactMethod
    fun requestAlwaysAuthorization(promise: Promise) {
        // Implementation here
    }

    @ReactMethod
    fun initializeBluetoothManager(promise: Promise) {
        // Implementation here
    }

    private fun getBluetoothState(): String {
        return "unknown"

    }

    @ReactMethod
    fun getAuthorizationStatus(promise: Promise) {
        // Implementation here
    }

    @ReactMethod
    fun runScan(uuid: String, promise: Promise) {
        // Implementation here
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

    fun runScanForAllBeacons(promise: Promise) {
        // Implementation here
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
            Log.e(TAG, "Error toggling background mode: ${e.message}")
            promise.reject("BACKGROUND_MODE_ERROR", "Failed to set background mode: ${e.message}")
        }
    }

    @ReactMethod
    fun getBackgroundMode(promise: Promise) {
        // Return the actual setting from SharedPreferences to ensure consistency
        promise.resolve(loadBackgroundModeSetting())
    }

    @ReactMethod
    fun setThroneUserId(userId: String, promise: Promise) {
        try {
            val sharedPrefs = reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sharedPrefs.edit().putString(USER_ID_KEY, userId).apply()
            Log.d(TAG, "Throne user ID saved: $userId")
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving throne user ID: ${e.message}")
            promise.reject("USER_ID_ERROR", "Failed to save user ID: ${e.message}")
        }
    }

    @ReactMethod
    fun getThroneUserId(promise: Promise) {
        try {
            val sharedPrefs = reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val userId = sharedPrefs.getString(USER_ID_KEY, "") ?: ""
            Log.d(TAG, "Throne user ID: $userId")
            promise.resolve(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting throne user ID: ${e.message}")
            promise.reject("USER_ID_ERROR", "Failed to get user ID: ${e.message}")
        }
    }

     @ReactMethod
    fun canDrawOverlays(promise: Promise) {
        promise.resolve(Settings.canDrawOverlays(reactContext))
    }

    private fun createNotificationChannel() {
        // Create the notification channel (required for Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Beacon Detector"
            val descriptionText = "Detects nearby Bluetooth beacons"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
                enableLights(true)
                lightColor = 0xFF0000FF.toInt()
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }

            val notificationManager = reactContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "High-priority notification channel created")
        }
    }

    private fun takeActionOnBeaconDetection(beacon: Beacon) {
        val distance = effectiveDistance(beacon)
        if (distance > MAX_DISTANCE) {
            return
        }
        if (LAUNCH_APP_ON_BEACON_DETECTION) {
            launchApp()
        }
        if (SEND_NOTIFICATION_ON_BEACON_DETECTION) {
            launchIntent(beacon)
        }
        if (MESSAGE_BLE_ON_BEACON_DETECTION) {
            messageBleOnBeaconDetection(beacon)
        }
    }

    fun messageBleOnBeaconDetection(beacon: Beacon) {
        debugLog("Messaging BLE for beacon: ${beacon.id1}")
        bluetoothManager?.fastConnectToBeacon()
    }

     // Add a method to launch the app
    fun launchIntent(beacon: Beacon) {
        Log.d(TAG, "Sending beacon notification for: ${beacon.id1}")
         createNotificationChannel()
        // Create content for the notification
        val distanceText = when {
            beacon.distance < 0 -> "Very close (${String.format("%.2f", beacon.distance)}m)"
            beacon.distance < 4.0 -> "Near (${String.format("%.2f", beacon.distance)}m)"
            else -> "Far (${String.format("%.2f", beacon.distance)}m)"
        }
        val notificationManager = reactContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val content = "Beacon detected: ${beacon.id1}\nDistance: $distanceText\nRSSI: ${beacon.rssi}"
        // Create a launch intent that will open the app
        val launchIntent = reactContext.packageManager.getLaunchIntentForPackage(reactContext.packageName)?.apply {
            // Add flags to ensure app launches from sleep/closed state
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Add "from_notification" flag to track source
            putExtra("from_notification", true)
        }
        // Create a pending intent with proper flags
        val pendingIntent = PendingIntent.getActivity(
            reactContext,
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), // Unique request code
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Create a full-screen intent for important notifications (wakes up device)
        val fullScreenIntent = PendingIntent.getActivity(
            reactContext,
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt() + 1, // Different unique request code
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Build high-priority notification that can wake device
        val builder = NotificationCompat.Builder(reactContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Beacon Detected")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM) // Important category
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(fullScreenIntent, true) // Add full screen intent
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .setSound(null) // Vibration pattern
        // Use a unique ID based on timestamp to avoid overwriting notifications
        val notificationId = System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, builder.build())
        Log.d(TAG, "Beacon notification sent with ID: $notificationId")
    }

   fun launchApp() {
        val targetPackage = reactContext.packageName;

        try {
            // Check if we have the permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(reactContext)) {
                    Log.e(TAG, "SYSTEM_ALERT_WINDOW permission not granted")
                    return
                }
            }

            val launchIntent = reactContext.packageManager.getLaunchIntentForPackage(targetPackage)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("launched_from_external", true)
            }

            if (launchIntent != null) {
                reactContext.startActivity(launchIntent)
                Log.d(TAG, "App launch intent executed successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app: ${e.message}", e)
        }
    }


    // Boot receiver class for restarting beacon scanning after device reboot
    inner class BootCompletedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                Log.d(TAG, "Device rebooted, restarting beacon scanning")
                setupBeaconScanning()
            }
        }
    }

    // Add this helper function to calculate distance from RSSI
    private fun calculateDistance(txPower: Int, rssi: Int): Double {
        if (rssi == 0) {
            return -1.0 // Can't determine distance without RSSI
        }

        // Standard formula for estimating distance from RSSI and txPower
        // This is the same formula used by the AltBeacon library internally
        return Math.pow(10.0, (txPower - rssi) / 20.0)
    }

    private fun effectiveDistance(beacon: Beacon): Double {
        return if (beacon.distance < 0 && beacon.rssi != 0) {
            calculateDistance(beacon.txPower, beacon.rssi)
        } else {
            beacon.distance
        }
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    override fun invalidate() {
        reactContext.runOnUiQueueThread {
            stopAllBeaconWork(removeNotifiers = true)
        }
        bluetoothManager?.cleanup()
        super.invalidate()
    }



}
