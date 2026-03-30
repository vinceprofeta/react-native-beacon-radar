package com.beaconradar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.Identifier
import org.altbeacon.beacon.Region


// Future Improvements (not in scope now)
// Adaptive scan period on motion: When motion is detected (via ActivityTransition), increase BACKGROUND_BETWEEN_SCAN_PERIOD_MS to reduce battery drain while stationary and scan more aggressively when the user is moving. Currently set to 0ms (continuous), which is aggressive but effective.
// AlarmManager/WorkManager watchdog: A periodic heartbeat (~15 min) that survives force-close and restarts monitoring automatically without requiring a reboot or user app launch.



object BeaconRadarBackgroundBootstrap {
    private const val TAG = "BeaconRadarBootstrap"
    const val DEFAULT_REGION_UUID = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825"

    private const val IBEACON_LAYOUT = "m:0-3=4c000215,i:4-19,i:20-21,i:22-23,p:24-24"
    private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "beacon_foreground_channel"
    private const val FOREGROUND_NOTIFICATION_NAME = "Throne Hands-Free Active"
    private const val FOREGROUND_NOTIFICATION_DESCRIPTION =
        "Throne is running in the background to detect your nearby device."
    private const val FOREGROUND_NOTIFICATION_ID = 456
    private const val USER_PRESENT_REFRESH_DEBOUNCE_MS = 3 * 60 * 1000L

    const val FOREGROUND_SCAN_PERIOD_MS = 300L
    const val FOREGROUND_BETWEEN_SCAN_PERIOD_MS = 0L
    const val BACKGROUND_SCAN_PERIOD_MS = 300L
    const val BACKGROUND_BETWEEN_SCAN_PERIOD_MS = 0L

    @Volatile
    var isMonitoringInitialized = false
        private set

    @Volatile
    var appContext: Context? = null
        private set

    @Volatile
    private var isUserPresentReceiverRegistered = false

    @Volatile
    private var lastUserPresentRefreshAtMs = 0L

    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            val appCtx = context.applicationContext
            if (!BeaconRadarPreferences.isBackgroundModeEnabled(appCtx)) {
                logInfo(appCtx, "Ignoring $action because background mode is disabled")
                return
            }

            val now = System.currentTimeMillis()
            synchronized(this@BeaconRadarBackgroundBootstrap) {
                if (now - lastUserPresentRefreshAtMs < USER_PRESENT_REFRESH_DEBOUNCE_MS) {
                    logInfo(appCtx, "Ignoring $action because user-present refresh is debounced")
                    return
                }
                lastUserPresentRefreshAtMs = now
            }

            logInfo(appCtx, "Refreshing background monitoring after $action", "BEACON_MONITORING_SETUP")
            ensureBackgroundMonitoring(appCtx, "user-present:$action")
            retryForegroundServiceIfNeeded(appCtx)
        }
    }

    fun defaultRegion(): Region {
        return Region("all-beacons", Identifier.parse(DEFAULT_REGION_UUID), null, null)
    }

    fun configureBeaconManager(context: Context): BeaconManager {
        val appCtx = context.applicationContext
        appContext = appCtx

        BeaconManager.setDebug(BuildConfig.DEBUG)
        val beaconManager = BeaconManager.getInstanceForApplication(appCtx)

        if (beaconManager.beaconParsers.none { it.layout == IBEACON_LAYOUT }) {
            beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(IBEACON_LAYOUT))
        }

        try {
            beaconManager.setRegionStatePeristenceEnabled(true)
        } catch (e: Exception) {
            logWarning(appCtx, "Could not enable region state persistence: ${e.message}")
        }

        beaconManager.foregroundScanPeriod = FOREGROUND_SCAN_PERIOD_MS
        beaconManager.foregroundBetweenScanPeriod = FOREGROUND_BETWEEN_SCAN_PERIOD_MS
        beaconManager.backgroundScanPeriod = BACKGROUND_SCAN_PERIOD_MS
        beaconManager.backgroundBetweenScanPeriod = BACKGROUND_BETWEEN_SCAN_PERIOD_MS

        return beaconManager
    }

    /**
     * Safe to call from any entry point (InitProvider, BootReceiver, Module, push).
     * Configures the BeaconManager, registers the single notifier, starts monitoring,
     * and enables the foreground service. Calling repeatedly is harmless.
     */
    fun ensureBackgroundMonitoring(context: Context, source: String = "unknown") {
        val appCtx = context.applicationContext
        if (!BeaconRadarPreferences.isBackgroundModeEnabled(appCtx)) {
            logInfo(appCtx, "Skipping background bootstrap from $source — background mode disabled")
            return
        }

        val beaconManager = configureBeaconManager(appCtx)
        registerUserPresentReceiver(appCtx)
        BeaconRadarMotionController.applyCurrentState(appCtx)
        registerNotifiers(beaconManager)

        try {
            beaconManager.setBackgroundMode(true)
        } catch (e: Exception) {
            logWarning(appCtx, "Failed to enable background mode from $source: ${e.message}")
        }

        tryEnableForegroundServiceScanning(appCtx, beaconManager)

        val region = defaultRegion()

        try {
            beaconManager.startMonitoring(region)
        } catch (e: Exception) {
            logWarning(appCtx, "startMonitoring failed from $source: ${e.message}")
        }

        try {
            beaconManager.startRangingBeacons(region)
        } catch (e: Exception) {
            logWarning(appCtx, "startRangingBeacons failed from $source: ${e.message}")
        }

        isMonitoringInitialized = true
        logInfo(appCtx, "Background monitoring ensured from $source", "BEACON_MONITORING_SETUP")
    }

    @Synchronized
    fun unregisterBackgroundReceivers(context: Context) {
        val appCtx = context.applicationContext
        if (isUserPresentReceiverRegistered) {
            try {
                appCtx.unregisterReceiver(userPresentReceiver)
            } catch (e: IllegalArgumentException) {
                logWarning(appCtx, "User-present receiver was not registered: ${e.message}")
            } catch (e: Exception) {
                logWarning(appCtx, "Failed to unregister user-present receiver: ${e.message}")
            } finally {
                isUserPresentReceiverRegistered = false
            }
        }

        BeaconRadarMotionController.unregister(appCtx, "Background receiver cleanup")
    }

    fun tryEnableForegroundServiceScanning(context: Context, beaconManager: BeaconManager = configureBeaconManager(context)) {
        val appCtx = context.applicationContext
        if (!hasAllRequiredPermissions(appCtx)) {
            logWarning(appCtx, "Permissions not granted. Skipping enableForegroundServiceScanning.")
            return
        }

        try {
            val builder = NotificationCompat.Builder(appCtx, FOREGROUND_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(FOREGROUND_NOTIFICATION_NAME)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

            val intent = appCtx.packageManager.getLaunchIntentForPackage(appCtx.packageName)
                ?: Intent().apply {
                    setPackage(appCtx.packageName)
                }

            val pendingIntent = PendingIntent.getActivity(
                appCtx,
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

            val notificationManager =
                appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            beaconManager.enableForegroundServiceScanning(
                builder.build(),
                FOREGROUND_NOTIFICATION_ID
            )
        } catch (e: IllegalStateException) {
            logWarning(appCtx, "enableForegroundServiceScanning failed: ${e.message}")
            try {
                if (beaconManager.foregroundServiceStartFailed()) {
                    logWarning(appCtx, "Foreground service blocked by OS — using JobScheduler fallback")
                }
            } catch (inner: Exception) {
                logWarning(appCtx, "Could not check foreground service state: ${inner.message}")
            }
        }
    }

    /**
     * Registers BeaconRadarBackgroundCallbacks as the sole notifier.
     * Removes first to prevent duplicates — safe to call repeatedly.
     */
    private fun registerNotifiers(beaconManager: BeaconManager) {
        beaconManager.removeMonitorNotifier(BeaconRadarBackgroundCallbacks)
        beaconManager.removeRangeNotifier(BeaconRadarBackgroundCallbacks)
        beaconManager.addMonitorNotifier(BeaconRadarBackgroundCallbacks)
        beaconManager.addRangeNotifier(BeaconRadarBackgroundCallbacks)
    }

    @Synchronized
    private fun registerUserPresentReceiver(context: Context) {
        if (isUserPresentReceiverRegistered) {
            return
        }

        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        try {
            ContextCompat.registerReceiver(
                context.applicationContext,
                userPresentReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isUserPresentReceiverRegistered = true
            logInfo(context.applicationContext, "Registered unlock receiver for background refresh")
        } catch (e: Exception) {
            logWarning(context.applicationContext, "Failed to register unlock receiver: ${e.message}")
        }
    }

    /**
     * Retries foreground service scanning if the OS previously blocked it.
     * Lives here (not on BeaconRadarModule) so it works even when the
     * RN bridge is not loaded (dead wake, background-only).
     */
    fun retryForegroundServiceIfNeeded(context: Context) {
        try {
            val bm = BeaconManager.getInstanceForApplication(context.applicationContext)
            if (bm.foregroundServiceStartFailed()) {
                logInfo(context, "Retrying foreground service scanning")
                bm.retryForegroundServiceScanning()
            }
        } catch (e: Exception) {
            logWarning(context, "retryForegroundServiceScanning not available: ${e.message}")
        }
    }

    fun hasAllRequiredPermissions(context: Context): Boolean {
        val fineGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val requiresBackground = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val backgroundGranted =
            !requiresBackground || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        val locationGranted = fineGranted && coarseGranted && backgroundGranted

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val connectGranted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            return scanGranted && connectGranted && locationGranted
        }
        return locationGranted
    }

    private fun logInfo(context: Context, message: String, type: String = "GENERAL") {
        if (type == "GENERAL") {
            BeaconRadarLogger.i(context.applicationContext, TAG, message, type = type)
        } else {
            BeaconRadarLogger.logKeyEvent(context.applicationContext, TAG, message, type = type)
        }
    }

    private fun logWarning(context: Context, message: String, type: String = "GENERAL") {
        BeaconRadarLogger.w(context.applicationContext, TAG, message, type = type)
    }
}
