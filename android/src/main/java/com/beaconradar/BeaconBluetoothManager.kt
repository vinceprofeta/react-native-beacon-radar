package com.beaconradar

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.*
import androidx.core.content.ContextCompat
import android.Manifest

class BeaconBluetoothManager(private val context: Context) {
    companion object {
        private const val TAG = "BeaconBluetooth"
        private const val THRONE_SERVICE_UUID = "88FE"
        private const val THRONE_FULL_SERVICE_UUID = "20E28DFB-E639-4D07-9DFB-6C4C3164331C"
        private const val THRONE_NOTIFY_CHARACTERISTIC_UUID = "4C0D85CA-B73E-46AF-ADAF-AB8F7F150C4C"
        private const val DEVICE_ID = "19FE8314-DF24-748E-2010-A3FF4F5B919E" // this actually doesnt matter right now. we just use it to send a device id. probably need to change this at some point.
        private const val SCAN_TIMEOUT_MS = 10000L
        private const val CONNECTION_TIMEOUT_MS = 30000L
        private const val PREFS_NAME = "BeaconRadarPrefs"
        private const val USER_ID_KEY = "throneUserId"

        private const val DEVICE_ID_FIELD_TAG: Byte = 0x0A
        private const val USER_FIELD_TAG: Byte = 0x32
        private const val ACTION_FIELD_TAG: Byte = 0x08
        private const val USER_ID_FIELD_TAG: Byte = 0x12
        private const val AUTH_ACTION_VALUE: Byte = 0x04
        private const val CONNECT_RETRY_DEBOUNCE_MS = 5000L

        @Volatile
        private var activeManager: BeaconBluetoothManager? = null

        @Volatile
        private var activeSource: String? = null

        @Volatile
        private var lastAttemptStartedAtMs: Long = 0L

        @JvmStatic
        @Synchronized
        fun triggerFastConnect(context: Context, source: String = "unknown"): Boolean {
            activeManager?.let { manager ->
                if (manager.isConnecting) {
                    BeaconRadarLogger.i(context.applicationContext, TAG, "connect already in progress from $activeSource; skipping $source", type = "CONNECT_SKIPPED_ALREADY_CONNECTED")
                    return false
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastAttemptStartedAtMs < CONNECT_RETRY_DEBOUNCE_MS) {
                BeaconRadarLogger.i(context.applicationContext, TAG, "connect debounced; last source=$activeSource, skipping $source", type = "CONNECT_SKIPPED_ALREADY_SCANNING")
                return false
            }

            val manager = BeaconBluetoothManager(context.applicationContext)
            activeManager = manager
            activeSource = source
            lastAttemptStartedAtMs = now
            manager.fastConnectToBeaconInternal(source)
            return true
        }

        @JvmStatic
        @Synchronized
        fun cancelActiveConnect() {
            activeManager?.cleanup()
        }

        @Synchronized
        private fun clearActiveManager(manager: BeaconBluetoothManager) {
            if (activeManager === manager) {
                activeManager = null
                activeSource = null
            }
        }
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnecting = false
    private val handler = Handler(Looper.getMainLooper())
    private var connectionTimeoutRunnable: Runnable? = null
    private var scanTimeoutRunnable: Runnable? = null

    init {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    fun fastConnectToBeacon(): Boolean {
        return triggerFastConnect(context, "instance")
    }

    private fun fastConnectToBeaconInternal(source: String) {
        if (isConnecting) {
            log("Already attempting connection, skipping source=$source", "CONNECT_SKIPPED_ALREADY_CONNECTED")
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            log("Bluetooth is not enabled for source=$source")
            cleanup()
            return
        }

        if (!hasAllRequiredPermissions()) {
            log("Missing required BLE permissions. Aborting source=$source")
            clearActiveManager(this)
            return
        }

        isConnecting = true
            log("Starting fast connect to beacon source=$source", "BLE_SCAN_STARTED")

        connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectionTimeoutRunnable = Runnable {
            log("Connection attempt timed out after ${CONNECTION_TIMEOUT_MS}ms", "CONNECTION_TIMEOUT")
            cleanup()
        }
        handler.postDelayed(connectionTimeoutRunnable!!, CONNECTION_TIMEOUT_MS)

        startOptimizedScan()
    }

    private fun startOptimizedScan() {
        log("Starting BLE scan for service $THRONE_SERVICE_UUID", "BLE_SERVICE_SCAN_STARTED")

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString("0000${THRONE_SERVICE_UUID}-0000-1000-8000-00805F9B34FB"))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)

        scanTimeoutRunnable = Runnable {
            if (bluetoothGatt == null) {
            log("No device found after ${SCAN_TIMEOUT_MS}ms scan", "CONNECTION_TIMEOUT")
                cleanup()
            }
        }
        handler.postDelayed(scanTimeoutRunnable!!, SCAN_TIMEOUT_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            log("Found device: ${device.address}, RSSI=${result.rssi}", "DEVICE_FOUND")

            stopScanSafely("onScanResult")
            scanTimeoutRunnable?.let { handler.removeCallbacks(it) }

            log("Starting connection to device: ${device.address}", "PERIPHERAL_CONNECTION_STARTED")
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            logError("Connection failed during scan: $errorCode", "CONNECTION_FAILED")
            logError("Scan failed with error: $errorCode")
            cleanup()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                logError("Connection failed: status=$status newState=$newState", "CONNECTION_FAILED")
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Connected to device: ${gatt.device.address}", "PERIPHERAL_CONNECTED")
                    bluetoothGatt = gatt
                    log("Starting service discovery", "SERVICE_DISCOVERY_STARTED")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Disconnected: status=$status", "DISCONNECTED")
                    cleanup()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logError("Service discovery failed: $status", "SERVICE_DISCOVERY_ERROR")
                cleanup()
                return
            }

            log("Services discovered: ${gatt.services.size}", "SERVICES_DISCOVERED")

            val service = gatt.getService(UUID.fromString(THRONE_FULL_SERVICE_UUID))
            if (service == null) {
                logError("Throne service not found. Available: ${gatt.services.map { it.uuid }}", "NO_SERVICES_FOUND")
                cleanup()
                return
            }

            log("Starting characteristic discovery for service: ${service.uuid}", "CHARACTERISTIC_DISCOVERY_STARTED")
            val notifyChar = service.getCharacteristic(UUID.fromString(THRONE_NOTIFY_CHARACTERISTIC_UUID))
            if (notifyChar == null) {
                logError("Characteristic discovery failed for service: ${service.uuid}", "CHARACTERISTIC_DISCOVERY_ERROR")
                logError("Notify characteristic not found. Available: ${service.characteristics.map { it.uuid }}", "CHARACTERISTIC_NOT_FOUND")
                cleanup()
                return
            }

            log("Characteristics found: ${service.characteristics.size}", "CHARACTERISTICS_FOUND")
            log("Found target characteristic: ${notifyChar.uuid}", "CHARACTERISTICS_FOUND")

            // Enable notifications first so we can receive the auth response
            log("Starting notification subscription for characteristic: ${notifyChar.uuid}", "NOTIFICATION_SUBSCRIPTION_STARTED")
            val notificationsEnabled = gatt.setCharacteristicNotification(notifyChar, true)
            if (!notificationsEnabled) {
                logError("Notification subscription failed to start for ${notifyChar.uuid}", "NOTIFICATION_SUBSCRIPTION_FAILED")
                cleanup()
                return
            }
            val descriptor = notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                log("Enabling notifications via descriptor write")
                // Auth write will happen in onDescriptorWrite callback
            } else {
                // No CCC descriptor — write auth directly
                log("No CCC descriptor — writing auth directly")
                writeAuthMessage(gatt, notifyChar)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logError("Notification subscription failed: $status", "NOTIFICATION_SUBSCRIPTION_FAILED")
                logError("Descriptor write failed: $status")
                cleanup()
                return
            }
            log("Notification subscription active — sending auth message", "NOTIFICATION_SUBSCRIBED")
            val service = gatt.getService(UUID.fromString(THRONE_FULL_SERVICE_UUID)) ?: return
            val notifyChar = service.getCharacteristic(UUID.fromString(THRONE_NOTIFY_CHARACTERISTIC_UUID)) ?: return
            writeAuthMessage(gatt, notifyChar)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logError("Auth write failed: $status", "AUTH_WRITE_ERROR")
                cleanup()
                return
            }
            log("Auth write successful — waiting for notification response", "AUTH_WRITE_SENT")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value
            if (value != null && value.isNotEmpty()) {
                val hex = value.joinToString("") { String.format("%02x", it) }
                log("Auth response received: ${value.size} bytes, hex=$hex", "AUTH_RESPONSE_RECEIVED")
                if (hex.contains("808")) {
                    log("Auth SUCCESS — cleaning up", "SESSION_COMPLETE")
                }
            } else {
                log("Auth response: no value", "AUTH_RESPONSE_EMPTY")
            }
            cleanup()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // Legacy fallback — prefer onCharacteristicChanged via notifications
            val value = characteristic.value
            if (value != null && value.isNotEmpty()) {
                log("Read response (hex): ${value.joinToString("") { String.format("%02x", it) }}", "AUTH_RESPONSE_RECEIVED")
            }
            cleanup()
        }
    }

    private fun writeAuthMessage(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val authMessage = createAuthMessage()
        log("Sending auth message: ${authMessage.joinToString("") { String.format("%02x", it) }}", "AUTH_MESSAGE_SENT")
        characteristic.value = authMessage
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(characteristic)
    }

    private fun createAuthMessage(): ByteArray {
        val message = mutableListOf<Byte>()

        val deviceIdBytes = DEVICE_ID.toByteArray(Charsets.UTF_8)
        message.add(DEVICE_ID_FIELD_TAG)
        message.add(deviceIdBytes.size.toByte())
        message.addAll(deviceIdBytes.toList())

        val userMessage = mutableListOf<Byte>()
        userMessage.add(ACTION_FIELD_TAG)
        userMessage.add(AUTH_ACTION_VALUE)

        val userId = getThroneUserId()
        val userIdBytes = userId.toByteArray(Charsets.UTF_8)
        userMessage.add(USER_ID_FIELD_TAG)
        userMessage.add(userIdBytes.size.toByte())
        userMessage.addAll(userIdBytes.toList())

        message.add(USER_FIELD_TAG)
        message.add(userMessage.size.toByte())
        message.addAll(userMessage)

        val byteArray = message.toByteArray()
        log("Auth message built: ${byteArray.size} bytes")
        return byteArray
    }

    private fun getThroneUserId(): String {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userId = sharedPrefs.getString(USER_ID_KEY, "") ?: ""
        if (userId.isEmpty()) {
            logWarning("No throneUserId found in SharedPreferences")
        }
        return userId
    }

    fun cleanup() {
        log("Cleaning up bluetooth connection", "BLUETOOTH_CLEANUP_STARTED")

        connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectionTimeoutRunnable = null
        scanTimeoutRunnable = null

        stopScanSafely("cleanup")
        try {
            bluetoothGatt?.disconnect()
        } catch (e: Exception) {
            logWarning("BluetoothGatt disconnect failed: ${e.message}")
        }
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            logWarning("BluetoothGatt close failed: ${e.message}")
        }
        bluetoothGatt = null
        isConnecting = false
        clearActiveManager(this)
    }

    private fun hasAllRequiredPermissions(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val requiresBackground = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val backgroundGranted = !requiresBackground || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val locationGranted = fineGranted && coarseGranted && backgroundGranted

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            return scanGranted && connectGranted && locationGranted
        }
        return locationGranted
    }

    private fun stopScanSafely(source: String) {
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (se: SecurityException) {
            logWarning("stopScan denied in $source: ${se.message}")
        } catch (e: Exception) {
            logWarning("stopScan failed in $source: ${e.message}")
        }
    }

    private fun log(message: String, type: String = "GENERAL") {
        if (type == "GENERAL") {
            BeaconRadarLogger.i(context.applicationContext, TAG, message, type = type)
        } else {
            BeaconRadarLogger.logKeyEvent(context.applicationContext, TAG, message, type = type)
        }
    }

    private fun logWarning(message: String, type: String = "GENERAL") {
        BeaconRadarLogger.w(context.applicationContext, TAG, message, type = type)
    }

    private fun logError(message: String, type: String = "GENERAL") {
        BeaconRadarLogger.e(context.applicationContext, TAG, message, type = type)
    }
}
