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
import android.util.Log
import java.util.*
import androidx.core.content.ContextCompat
import android.Manifest

class BeaconBluetoothManager(private val context: Context) {
    companion object {
        private const val TAG = "BeaconBluetooth"
        private const val THRONE_SERVICE_UUID = "88FE"
        private const val THRONE_FULL_SERVICE_UUID = "20E28DFB-E639-4D07-9DFB-6C4C3164331C"
        private const val THRONE_NOTIFY_CHARACTERISTIC_UUID = "4C0D85CA-B73E-46AF-ADAF-AB8F7F150C4C"
        private const val DEVICE_ID = "19FE8314-DF24-748E-2010-A3FF4F5B919E"
        private const val SCAN_TIMEOUT_MS = 10000L
        private const val CONNECTION_TIMEOUT_MS = 30000L
        private const val PREFS_NAME = "BeaconRadarPrefs"
        private const val USER_ID_KEY = "throneUserId"

        private const val DEVICE_ID_FIELD_TAG: Byte = 0x0A
        private const val USER_FIELD_TAG: Byte = 0x32
        private const val ACTION_FIELD_TAG: Byte = 0x08
        private const val USER_ID_FIELD_TAG: Byte = 0x12
        private const val AUTH_ACTION_VALUE: Byte = 0x04
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

    fun fastConnectToBeacon() {
        if (isConnecting) {
            log("Already attempting connection, skipping")
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            log("Bluetooth is not enabled")
            cleanup()
            return
        }

        if (!hasAllRequiredPermissions()) {
            log("Missing required BLE permissions. Aborting.")
            return
        }

        isConnecting = true
        log("Starting fast connect to beacon")

        connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectionTimeoutRunnable = Runnable {
            log("Connection attempt timed out after ${CONNECTION_TIMEOUT_MS}ms")
            cleanup()
        }
        handler.postDelayed(connectionTimeoutRunnable!!, CONNECTION_TIMEOUT_MS)

        startOptimizedScan()
    }

    private fun startOptimizedScan() {
        log("Starting BLE scan for service $THRONE_SERVICE_UUID")

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString("0000${THRONE_SERVICE_UUID}-0000-1000-8000-00805F9B34FB"))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)

        scanTimeoutRunnable = Runnable {
            if (bluetoothGatt == null) {
                log("No device found after ${SCAN_TIMEOUT_MS}ms scan")
                cleanup()
            }
        }
        handler.postDelayed(scanTimeoutRunnable!!, SCAN_TIMEOUT_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            log("Found device: ${device.address}, RSSI=${result.rssi}")

            stopScanSafely("onScanResult")
            scanTimeoutRunnable?.let { handler.removeCallbacks(it) }

            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            cleanup()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Connected to device: ${gatt.device.address}")
                    bluetoothGatt = gatt
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Disconnected: status=$status")
                    cleanup()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                cleanup()
                return
            }

            log("Services discovered: ${gatt.services.size}")

            val service = gatt.getService(UUID.fromString(THRONE_FULL_SERVICE_UUID))
            if (service == null) {
                Log.e(TAG, "Throne service not found. Available: ${gatt.services.map { it.uuid }}")
                cleanup()
                return
            }

            val notifyChar = service.getCharacteristic(UUID.fromString(THRONE_NOTIFY_CHARACTERISTIC_UUID))
            if (notifyChar == null) {
                Log.e(TAG, "Notify characteristic not found. Available: ${service.characteristics.map { it.uuid }}")
                cleanup()
                return
            }

            log("Found target characteristic: ${notifyChar.uuid}")

            // Enable notifications first so we can receive the auth response
            gatt.setCharacteristicNotification(notifyChar, true)
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
                Log.e(TAG, "Descriptor write failed: $status")
                cleanup()
                return
            }
            log("Notification subscription active — sending auth message")
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
                Log.e(TAG, "Auth write failed: $status")
                cleanup()
                return
            }
            log("Auth write successful — waiting for notification response")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value
            if (value != null && value.isNotEmpty()) {
                val hex = value.joinToString("") { String.format("%02x", it) }
                log("Auth response received: ${value.size} bytes, hex=$hex")
                if (hex.contains("808")) {
                    log("Auth SUCCESS — cleaning up")
                }
            } else {
                log("Auth response: no value")
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
                log("Read response (hex): ${value.joinToString("") { String.format("%02x", it) }}")
            }
            cleanup()
        }
    }

    private fun writeAuthMessage(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val authMessage = createAuthMessage()
        log("Sending auth message: ${authMessage.joinToString("") { String.format("%02x", it) }}")
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
            Log.w(TAG, "No throneUserId found in SharedPreferences")
        }
        return userId
    }

    fun cleanup() {
        log("Cleaning up bluetooth connection")

        connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectionTimeoutRunnable = null
        scanTimeoutRunnable = null

        stopScanSafely("cleanup")
        try {
            bluetoothGatt?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "BluetoothGatt disconnect failed: ${e.message}")
        }
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "BluetoothGatt close failed: ${e.message}")
        }
        bluetoothGatt = null
        isConnecting = false
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
            Log.w(TAG, "stopScan denied in $source: ${se.message}")
        } catch (e: Exception) {
            Log.w(TAG, "stopScan failed in $source: ${e.message}")
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }
}
