# BLE Implementation for React Native Beacon Radar

This document describes the BLE (Bluetooth Low Energy) functionality that has been implemented in the Kotlin version of the React Native Beacon Radar module, similar to the Swift AppDelegate implementation.

## Overview

The BLE implementation allows the app to:

1. Connect to BLE devices when beacons are detected
2. Authenticate with the device using a protobuf-style message
3. Send and receive data over BLE
4. Handle connection timeouts and cleanup

## Files Added/Modified

### New Files

- `BeaconBluetoothManager.kt` - Handles all BLE operations

### Modified Files

- `BeaconRadarModule.kt` - Integrated BLE functionality
- `src/index.tsx` - Added TypeScript interfaces for new methods

## BLE Connection Flow

1. **Beacon Detection**: When a beacon is detected within range, the `messageBleOnBeaconDetection()` method is called
2. **Fast Connect**: Initiates a BLE connection attempt with timeout handling
3. **Device Discovery**: Scans for devices with the specific service UUID (88FE)
4. **Connection**: Establishes GATT connection to the discovered device
5. **Service Discovery**: Discovers the Throne service (20E28DFB-E639-4D07-9DFB-6C4C3164331C)
6. **Authentication**: Sends an auth message to all characteristics
7. **Response Handling**: Reads the response from the device
8. **Cleanup**: Disconnects and cleans up resources

## Configuration

### Service UUIDs

- **Scan Filter**: `88FE` (short UUID for device discovery)
- **Full Service**: `20E28DFB-E639-4D07-9DFB-6C4C3164331C` (for communication)

### Timeouts

- **Scan Timeout**: 5 seconds
- **Connection Timeout**: 10 seconds

## Authentication Message Format

The auth message follows a protobuf-style format:

```
Message {
  deviceId: "19FE8314-DF24-748E-2010-A3FF4F5B919E" (Field 1, wire type 2)
  user: {
    action: 4 (AUTH) (Field 1, wire type 0)
    userId: <stored_user_id> (Field 2, wire type 2)
  } (Field 6, wire type 2)
}
```

## New React Native Methods

### `setThroneUserId(userId: string): Promise<boolean>`

Sets the user ID that will be included in BLE authentication messages.

### `getThroneUserId(): Promise<string>`

Retrieves the currently stored user ID.

## Usage Example

```typescript
import { setThroneUserId, startScanning } from "react-native-beacon-radar"

// Set the user ID for BLE authentication
await setThroneUserId("user123")

// Start beacon scanning (BLE connection will happen automatically when beacons are detected)
await startScanning("FDA50693-A4E2-4FB1-AFCF-C6EB07647825")
```

## Permissions

The following permissions are required in `AndroidManifest.xml`:

- `BLUETOOTH`
- `BLUETOOTH_ADMIN`
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`

## Error Handling

The implementation includes comprehensive error handling:

- Connection timeouts
- Scan failures
- Service discovery failures
- Characteristic read/write errors
- Automatic cleanup on errors

## Integration with Existing Code

The BLE functionality is integrated seamlessly with the existing beacon detection:

- No changes to existing beacon scanning behavior
- BLE connection is triggered automatically when `MESSAGE_BLE_ON_BEACON_DETECTION` is true
- Uses the same SharedPreferences for storing user ID
- Maintains the same logging patterns

## Testing

To test the BLE functionality:

1. Ensure Bluetooth is enabled on the device
2. Set a valid user ID using `setThroneUserId()`
3. Start beacon scanning
4. Bring a compatible BLE device within range of a beacon
5. Monitor logs for BLE connection and authentication messages

## Notes

- The BLE connection is automatically cleaned up after each authentication
- Multiple connection attempts are prevented while one is in progress
- The implementation follows Android BLE best practices
- All BLE operations are performed on the main thread as required by Android
