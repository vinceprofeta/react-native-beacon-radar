package com.beaconradar

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.*
import kotlinx.coroutines.*
import androidx.core.content.ContextCompat
import android.Manifest

class BeaconBluetoothManager(private val context: Context) {
    companion object {
        private const val TAG = "BeaconBluetooth"
        private const val THRONE_SERVICE_UUID = "88FE"
        private const val THRONE_FULL_SERVICE_UUID = "20E28DFB-E639-4D07-9DFB-6C4C3164331C"
        private const val DEVICE_ID = "19FE8314-DF24-748E-2010-A3FF4F5B919E"
        private const val SCAN_TIMEOUT_MS = 5000L
        private const val CONNECTION_TIMEOUT_MS = 10000L
        private const val PREFS_NAME = "BeaconRadarPrefs"
        private const val USER_ID_KEY = "throneUserId"

        // Protocol constants for auth message
        private const val DEVICE_ID_FIELD_TAG: Byte = 0x0A // (1 << 3) | 2
        private const val USER_FIELD_TAG: Byte = 0x32 // (6 << 3) | 2
        private const val ACTION_FIELD_TAG: Byte = 0x08 // (1 << 3) | 0
        private const val USER_ID_FIELD_TAG: Byte = 0x12 // (2 << 3) | 2
        private const val AUTH_ACTION_VALUE: Byte = 0x04
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnecting = false
    private var connectionTimeoutHandler = Handler(Looper.getMainLooper())
    private var connectionTimeoutRunnable: Runnable? = null
    private var scanTimeoutRunnable: Runnable? = null

    init {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    fun fastConnectToBeacon() {
        if (isConnecting) {
            Log.w(TAG, "Already attempting connection, skipping")
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth is not enabled")
            cleanup()
            return
        }

        Log.w(TAG, "Fast connect to beacon")

		// Verify required permissions before proceeding
		if (!hasAllRequiredPermissions()) {
			Log.w(TAG, "Missing required BLE permissions (scan/connect). Aborting fastConnectToBeacon.")
			return
		}

		// Mark we're connecting only after all preconditions are satisfied
		isConnecting = true

        // Cancel any existing timeout
        connectionTimeoutRunnable?.let { connectionTimeoutHandler.removeCallbacks(it) }

        // Schedule a connection timeout
        connectionTimeoutRunnable = Runnable {
            Log.w(TAG, "Connection attempt timed out after ${CONNECTION_TIMEOUT_MS}ms")
            cleanup()
        }
        connectionTimeoutHandler.postDelayed(connectionTimeoutRunnable!!, CONNECTION_TIMEOUT_MS)

        startOptimizedScan()
    }

    private fun startOptimizedScan() {
        Log.w(TAG, "Starting optimized BLE scan")

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString("0000${THRONE_SERVICE_UUID}-0000-1000-8000-00805F9B34FB"))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)

        // Stop scan after timeout
        scanTimeoutRunnable = Runnable {
            if (bluetoothGatt == null) {
                Log.w(TAG, "No device found after ${SCAN_TIMEOUT_MS}ms scan")
                cleanup()
            }
        }
        connectionTimeoutHandler.postDelayed(scanTimeoutRunnable!!, SCAN_TIMEOUT_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.w(TAG, "Found device: ${device.address}, attempting connection")

			// Stop scanning once we found a device, guarded by permissions
			if (hasAllRequiredPermissions()) {
				bluetoothLeScanner?.stopScan(this)
			} else {
				Log.w(TAG, "Skipping stopScan in onScanResult due to missing permissions")
			}

            scanTimeoutRunnable?.let { connectionTimeoutHandler.removeCallbacks(it) }

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
                    Log.w(TAG, "Connected to device: ${gatt.device.address}")
                    bluetoothGatt = gatt
                    Log.w(TAG, "Starting service discovery")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Disconnected: status=$status")
                    cleanup()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Error discovering services: $status")
                cleanup()
                return
            }

            Log.w(TAG, "Services discovered: ${gatt.services.size}")

            val service = gatt.getService(UUID.fromString(THRONE_FULL_SERVICE_UUID))
            if (service == null) {
                Log.e(TAG, "Throne service not found")
                gatt.services.forEach { svc ->
                    Log.w(TAG, "Found service: ${svc.uuid}")
                }
                cleanup()
                return
            }

            Log.w(TAG, "Found Throne service: ${service.uuid}")

            // Write auth message to all characteristics
            service.characteristics.forEach { characteristic ->
                Log.w(TAG, "Found characteristic: ${characteristic.uuid}")
                val authMessage = createAuthMessage()
                Log.w(TAG, "Sending auth message: ${authMessage.toHexString()}")
                characteristic.value = authMessage
                gatt.writeCharacteristic(characteristic)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Error writing characteristic: $status")
                cleanup()
                return
            }

            Log.w(TAG, "Write successful. Reading response...")
            gatt.readCharacteristic(characteristic)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Error reading response: $status")
                cleanup()
                return
            }

            val value = characteristic.value
            if (value != null && value.isNotEmpty()) {
                Log.w(TAG, "Serialized response (hex): ${value.toHexString()}")
            } else {
                Log.w(TAG, "No response data")
            }

            // We're done
            cleanup()
        }
    }

    private fun createAuthMessage(): ByteArray {
        Log.w(TAG, "Creating auth message")
        val message = mutableListOf<Byte>()

        // Device ID field - Field 1, wire type 2 (length-delimited)
        val deviceIdBytes = DEVICE_ID.toByteArray(Charsets.UTF_8)
        message.add(DEVICE_ID_FIELD_TAG)
        message.add(deviceIdBytes.size.toByte())
        message.addAll(deviceIdBytes.toList())

        // User sub-message
        val userMessage = mutableListOf<Byte>()

        // Action field - Field 1, wire type 0 (varint)
        userMessage.add(ACTION_FIELD_TAG)
        userMessage.add(AUTH_ACTION_VALUE)

        // UserId field - Field 2, wire type 2 (length-delimited)
        val userId = getThroneUserId()
        val userIdBytes = userId.toByteArray(Charsets.UTF_8)
        userMessage.add(USER_ID_FIELD_TAG)
        userMessage.add(userIdBytes.size.toByte())
        userMessage.addAll(userIdBytes.toList())

        // Add the User message to the main message as field 6
        message.add(USER_FIELD_TAG)
        message.add(userMessage.size.toByte())
        message.addAll(userMessage)

        val byteArray = message.toByteArray()
        Log.w(TAG, "Serialized request (hex): ${byteArray.toHexString()}")
        return byteArray
    }

    private fun getThroneUserId(): String {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userId = sharedPrefs.getString(USER_ID_KEY, "") ?: ""
        if (userId.isEmpty()) {
            Log.w(TAG, "No throneUserId found in SharedPreferences")
        } else {
            Log.w(TAG, "Found throneUserId in SharedPreferences: $userId")
        }
        return userId
    }

    fun cleanup() {
        Log.w(TAG, "Cleaning up bluetooth")

        connectionTimeoutRunnable?.let { connectionTimeoutHandler.removeCallbacks(it) }
        scanTimeoutRunnable?.let { connectionTimeoutHandler.removeCallbacks(it) }
        connectionTimeoutRunnable = null
        scanTimeoutRunnable = null

		if (hasAllRequiredPermissions()) {
			bluetoothLeScanner?.stopScan(scanCallback)
		} else {
			Log.w(TAG, "Skipping stopScan in cleanup due to missing scan permission")
		}
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnecting = false
    }

	// Permission helpers
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

    // Extension function to convert ByteArray to hex string for logging
    private fun ByteArray.toHexString(): String {
        return joinToString("") { String.format("%02x", it) }
    }
}
