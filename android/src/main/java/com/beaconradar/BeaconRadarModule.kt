package com.beaconradar

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import org.altbeacon.beacon.*
import java.util.*
import android.content.ActivityNotFoundException
import android.net.Uri
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
        // private const val BLUETOOTH_ERROR = "BLUETOOTH_ERROR"
        // private const val PERMISSION_REQUEST_CODE = 1
        // private const val BACKGROUND_NOTIFICATION_ID = 12345
        private const val NOTIFICATION_CHANNEL_ID = "beacon_detector_channel"
        private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "beacon_foreground_channel"
        private const val FOREGROUND_NOTIFICATION_NAME = "Beacon Scanner Service"
        private const val FOREGROUND_NOTIFICATION_DESCRIPTION = "Required for background beacon scanning"
        private const val FOREGROUND_NOTIFICATION_ID = 456
        private const val PREFS_NAME = "BeaconRadarPrefs"
        private const val BACKGROUND_MODE_KEY = "backgroundModeEnabled"
        private var MAX_DISTANCE = 4.0
        @JvmStatic
        var instance: BeaconRadarModule? = null

        // Add a timestamp to track when we last launched the app
        private var lastAppLaunchTime: Long = 0
        // Minimum time between app launches (5 seconds)
        private const val MIN_LAUNCH_INTERVAL = 5000L
    }

    private val beaconManager: BeaconManager = BeaconManager.getInstanceForApplication(reactContext)
    private var region: Region = Region("all-beacons", Identifier.parse("FDA50693-A4E2-4FB1-AFCF-C6EB07647825"), null, null)

    init {
        instance = this
        setupBeaconManager()
        // Load background mode setting from SharedPreferences
        val backgroundMode = loadBackgroundModeSetting()
        setBackgroundMode(backgroundMode)
    }

    // Load background mode setting from SharedPreferences
    private fun loadBackgroundModeSetting(): Boolean {
        val sharedPrefs = reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Default to true if setting doesn't exist
        return sharedPrefs.getBoolean(BACKGROUND_MODE_KEY, false)
    }



    private fun setBackgroundMode(enable: Boolean) {
        Log.d(TAG, "Setting background beacon scanning to: $enable")
        try {
            val sharedPrefs = reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sharedPrefs.edit().putBoolean(BACKGROUND_MODE_KEY, enable).apply()
            // Setup background scanning parameters
            beaconManager.setEnableScheduledScanJobs(enable)
            beaconManager.setBackgroundMode(enable)
            beaconManager.backgroundScanPeriod = 1100L
            beaconManager.backgroundBetweenScanPeriod = 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error setting background mode: ${e.message}")
        }
    }

    private fun setupBeaconManager() {
        BeaconManager.setDebug(true)
        val iBeaconParser = BeaconParser()
            .setBeaconLayout("m:0-3=4c000215,i:4-19,i:20-21,i:22-23,p:24-24") //ibeacon layout
        beaconManager.beaconParsers.add(iBeaconParser)


        beaconManager.foregroundScanPeriod = 1100L
        beaconManager.foregroundBetweenScanPeriod = 0L
        beaconManager.backgroundScanPeriod = 1100L
        beaconManager.backgroundBetweenScanPeriod = 0L

        Log.d(TAG, "BeaconManager setup complete")
    }

    fun setupBeaconScanning() {
        Log.d(TAG, "Setting up BeaconManager with iBeacon parser")
        val moduleInstance = this
        reactContext.runOnUiQueueThread {
            beaconManager.addMonitorNotifier(moduleInstance)
            beaconManager.addRangeNotifier(moduleInstance)
        }

        setupForegroundService()

        // Only start monitoring here - ranging will be started when a region is entered
        Log.d(TAG, "Starting monitoring for region: $region")
        reactContext.runOnUiQueueThread {
            Log.d(TAG, "Requesting immediate state determination for region: $region")
            beaconManager.startMonitoring(region)
            beaconManager.requestStateForRegion(region)
        }
    }

    fun setupForegroundService() {
        try {
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

            Log.d(TAG, "Calling enableForegroundServiceScanning")
            BeaconManager.getInstanceForApplication(reactContext).enableForegroundServiceScanning(
                builder.build(),
                FOREGROUND_NOTIFICATION_ID
            )
            Log.d(TAG, "Back from enableForegroundServiceScanning")
        } catch (e: IllegalStateException) {
            Log.d(TAG, "Cannot enable foreground service scanning. This may be because consumers are already bound: ${e.message}")
            // Continue anyway - this is not a fatal error
        }
    }

    // MonitorNotifier implementation
    override fun didEnterRegion(region: Region) {
        Log.d(TAG, "didEnterRegion: The user entered in the region: $region")

        // Start ranging only when entering a region
        Log.d(TAG, "didEnterRegion: Starting ranging beacons for region: $region")
        beaconManager.startRangingBeacons(region)


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
            Log.d(TAG, "didEnterRegion: Event emitted successfully")
        }
    }

    override fun didExitRegion(region: Region) {
        Log.d(TAG, "didExitRegion: Stopping ranging beacons for region: $region")
        reactContext.runOnUiQueueThread {
            beaconManager.stopRangingBeacons(region)

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
        Log.d(TAG, "didDetermineStateForRegion: Region ${region.uniqueId} state is $stateStr")
        if (state == MonitorNotifier.INSIDE) {
            Log.d(TAG, "didDetermineStateForRegion: Already inside region, starting ranging")
            beaconManager.startRangingBeacons(region)
        }
    }

    // RangeNotifier implementation
    override fun didRangeBeaconsInRegion(beacons: Collection<Beacon>, region: Region) {
        Log.d(TAG, "didRangeBeaconsInRegion: Found ${beacons.size} beacons in region ${region.uniqueId}")

        if (beacons.isNotEmpty()) {
            // Filter out beacons older than 10 seconds
            val recentBeacons = beacons.filter { beacon ->
                val age = System.currentTimeMillis() - beacon.lastCycleDetectionTimestamp
                age < 10000
            }

            if (recentBeacons.isNotEmpty()) {
                // Find the nearest beacon
                val nearestBeacon = recentBeacons.minByOrNull { it.distance }

                val nearestBeaconCalculatedDistance = if (nearestBeacon != null && nearestBeacon.distance < 0 && nearestBeacon.rssi != 0) {
                    calculateDistance(nearestBeacon.txPower, nearestBeacon.rssi)
                } else {
                    nearestBeacon?.distance ?: Double.MAX_VALUE
                }

                if(nearestBeaconCalculatedDistance > MAX_DISTANCE) {
                    Log.d(TAG, "Nearest beacon is too far away, max distance is: $MAX_DISTANCE, beacon distance is: ${nearestBeaconCalculatedDistance}")
                    return
                }

                // Send notification for the nearest beacon if app is in background
                val isInForeground = reactContext.currentActivity?.hasWindowFocus() == true
                if (!isInForeground && nearestBeacon != null) {
                    Log.d(TAG, "App in background, sending notification for nearest beacon")
                    sendBeaconNotification(nearestBeacon)
                }

                // Emit event to JavaScript
                reactContext.runOnUiQueueThread {
                    val beaconArray = Arguments.createArray()
                    recentBeacons.forEach { beacon ->
                        // Calculate distance if it's negative
                        val calculatedDistance = if (beacon.distance < 0 && beacon.rssi != 0) {
                            calculateDistance(beacon.txPower, beacon.rssi)
                        } else {
                            beacon.distance
                        }

                        Log.d(TAG, "Beacon ${beacon.id1}: Raw distance=${beacon.distance}, " +
                              "Calculated=${calculatedDistance}, RSSI=${beacon.rssi}, TxPower=${beacon.txPower}")

                        val beaconMap = Arguments.createMap().apply {
                            putString("uuid", beacon.id1?.toString() ?: "")
                            putString("major", beacon.id2?.toString() ?: "")
                            putString("minor", beacon.id3?.toString() ?: "")
                            putDouble("distance", calculatedDistance)
                            putInt("rssi", beacon.rssi)
                            putInt("txPower", beacon.txPower)
                            putString("bluetoothName", beacon.bluetoothName ?: "")
                            putString("bluetoothAddress", beacon.bluetoothAddress ?: "")
                            putInt("manufacturer", beacon.manufacturer)
                            putDouble("timestamp", beacon.lastCycleDetectionTimestamp.toDouble())
                        }
                        beaconArray.pushMap(beaconMap)
                        Log.d(TAG, "didRangeBeaconsInRegion: Beacon detected: ${beacon.id1}")
                    }
                    reactContext
                        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                        .emit("onBeaconsDetected", beaconArray)
                }
            }
        }
    }



    override fun getName(): String = NAME

    @ReactMethod
    fun startScanning(uuid: String?, config: ReadableMap?, promise: Promise) {
        region = Region("all-beacons", Identifier.parse(uuid), null, null)
        setupBeaconScanning()
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
            beaconManager.stopRangingBeacons(region)
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

    private fun sendBeaconNotification(beacon: Beacon) {
        Log.d(TAG, "Sending beacon notification for: ${beacon.id1}")

        // Ensure notification channel exists first
        createNotificationChannel()

        val notificationManager = reactContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager


        // Create content for the notification
        val distanceText = when {
            beacon.distance < 1.0 -> "Very close (${String.format("%.2f", beacon.distance)}m)"
            beacon.distance < 4.0 -> "Near (${String.format("%.2f", beacon.distance)}m)"
            else -> "Far (${String.format("%.2f", beacon.distance)}m)"
        }

        val tooFar = beacon.distance > MAX_DISTANCE
        Log.d(TAG, "Beacon ${beacon.id1} distance: ${beacon.distance}, max distance: $MAX_DISTANCE, tooFar: $tooFar")
        if(tooFar) {
            Log.d(TAG, "Beacon ${beacon.id1} is too far away, max distance is: $MAX_DISTANCE, beacon distance is: ${beacon.distance}")
            return
        }


        // Only try to launch the app if enough time has passed since the last attempt
        // val currentTime = System.currentTimeMillis()
        // if (currentTime - lastAppLaunchTime > MIN_LAUNCH_INTERVAL) {
        //     Log.d(TAG, "Attempting to launch app (last launch was ${(currentTime - lastAppLaunchTime)/1000} seconds ago)")
        //     launchApp()
        //     lastAppLaunchTime = currentTime
        // } else {
        //     Log.d(TAG, "Skipping app launch - last launch was only ${(currentTime - lastAppLaunchTime)/1000} seconds ago")
        // }
        launchApp()

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

     // Add a method to launch the app

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



}
