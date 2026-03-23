# react-native-beacon-radar

## CURRENTLY IN PROGRESS.... RIGHT NOW ONLY SCANS FOR CERTAIN IBEACON

Package to scan for iBeacons on both Android and IOS. This module is fully compatible with Expo (Will not work with Expo Go, but will work with development build.)

## Installation

```sh
npm install react-native-beacon-radar
```
OR
```sh
yarn add react-native-beacon-radar
```

## Basic usage

```js
import { DeviceEventEmitter } from 'react-native';
import { startScanning } from 'react-native-beacon-radar';

// ...

startScanning('YOUR UUID', {
  useForegroundService: true,
  useBackgroundScanning: true,
});

DeviceEventEmitter.addListener('onBeaconsDetected', (beacons) => {
  console.log('onBeaconsDetected', beacons);
});
```

## Current API:
| Method                            | Description                                                                                                                                                                                                           |
|:----------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **requestWhenInUseAuthorization** | This method should be called before anything else is called. It handles to request the use of beacons while the application is open. If the application is in the background, you will not get a signal from beacons. |
| **requestAlwaysAuthorization**    | This method should be called before anything else is called. It handles to request the use of beacons while the application is open or in the background.                                                             |
| **getAuthorizationStatus**        | This methods gets the current authorization status.                                                                                                                                                                   |
| **startScanning**                 | This method starts scanning for a certain beacon based on its UUID.                                                                                                                                                   |
| **startRadar (Android only)**     | This method starts scanning for all beacons in range. This is only available on Android.                                                                                                                              |
| **handlePushNotification (Android only)** | This method lets your existing push pipeline delegate `beacon_scan` payloads into the native beacon handler without registering another `FirebaseMessagingService`. |
| **setPosthogKey (Android only)** | Sets the PostHog project API key used for native `handsFreeLog` events. |
| **getPosthogKey (Android only)** | Returns the currently stored PostHog project API key. |
| **setBeaconDebug (Android only)** | Enables additional native debug logging while keeping PostHog hands-free logging active. |
| **getBeaconDebug (Android only)** | Returns whether native beacon debug logging is enabled. |


| Event                 | Description                                                               |
|:----------------------|:--------------------------------------------------------------------------|
| **onBeaconsDetected** | This event gets called when the beacon you are searching for is in range. |


## Expo
This module will work with the Expo managed workflow. It will not however work with Expo Go, since it needs native features. You can use the development build of Expo to test this module. More about this can be found [here](https://docs.expo.dev/develop/development-builds/create-a-build/). To use this module in expo managed add the following to your app.json:
```json
"expo": {
  "ios": {
    "infoPlist": {
      "NSLocationWhenInUseUsageDescription": "We need your location to detect nearby beacons.",
      "NSLocationAlwaysUsageDescription": "We need your location to detect nearby beacons even when the app is in the background.",
      "NSLocationAlwaysAndWhenInUseUsageDescription": "We need your location to detect nearby beacons even when the app is in the background."
    }
  },
  "plugins": [
    "react-native-beacon-radar"
  ]
}
```

## Android silent push integration

Android apps should keep a single push owner and delegate beacon-trigger payloads into this package. This package does **not** register its own `FirebaseMessagingService`, so it will not compete with `expo-notifications` or another messaging SDK.

### JS delegation

If your existing push pipeline reaches JavaScript, call `handlePushNotification` with the notification `data` payload:

```ts
import { handlePushNotification } from "react-native-beacon-radar"

await handlePushNotification({
  beacon_scan: "true",
})
```

### Native Android delegation

If your app already owns a native `FirebaseMessagingService`, call the shared helper instead of duplicating beacon logic:

```kotlin
BeaconPushHandler.handlePayload(
    applicationContext,
    remoteMessage.data,
    "fcm"
)
```

The payload must contain `beacon_scan` for the handler to run. When accepted, the native handler will:

- verify background mode is enabled
- ensure beacon monitoring and ranging are active
- trigger BLE fast connect in the background
- avoid launching the visible app UI

## Hands-free logging

Android can mirror native hands-free logs to PostHog using the `handsFreeLog` event name, matching iOS.

### Setup

```ts
import {
  setBeaconDebug,
  setPosthogKey,
  setThroneUserId,
} from "react-native-beacon-radar"

await setThroneUserId("user-123")
await setPosthogKey("phc_your_project_key")
await setBeaconDebug(true)
```

### PostHog payload

When configured, Android sends a `handsFreeLog` event with properties including:

- `distinct_id`
- `message`
- `level`
- `type`
- `tag`
- `description` for key event logs
- `elapsed` for key event logs

### Android event `type` values

These Android event types intentionally use the same names as the equivalent iOS event types where Android supports the same step in the flow.

| Event type | Description |
|:--|:--|
| `APP_LAUNCH_START` | Native Android startup logging began. |
| `APP_LAUNCH_COMPLETE` | Native Android startup logging completed. |
| `THRONE_BEACON_SETUP_STARTING` | Hands-free native setup is starting. |
| `BEACON_MONITORING_SETUP` | Beacon monitoring/ranging setup was enabled or resumed. |
| `BEACON_MONITORING_DESTROYED` | Beacon monitoring/ranging was fully stopped. |
| `BEACON_REGION_ENTERED` | The monitored beacon region was entered. |
| `BEACON_REGION_WAITING_FOR_RANGING` | A region entry was observed and Android is waiting for ranging callbacks. |
| `BEACON_RANGING_STARTED` | Android requested or resumed beacon ranging after region state indicated the device is inside. |
| `REMOTE_NOTIFICATION_RECEIVED` | A push payload reached the native Android handler. |
| `PUSH_HANDLER_CALLED_SUCCESS` | The native Android push handler accepted the payload and started work. |
| `PUSH_BEACON_SCAN_FOUND` | A push payload contained `beacon_scan` and native ranging/connection work started. |
| `PUSH_BEACON_SCAN_MISSING` | A push payload was received but did not include `beacon_scan`. |
| `BEACON_DISTANCE_UNKNOWN` | A ranged beacon had an unknown/invalid distance estimate. |
| `BEACON_RANGED_WITHIN_RANGE` | A recent beacon was ranged within the configured maximum distance. |
| `BEACON_TOO_FAR` | A recent beacon was ranged, but was outside the configured maximum distance. |
| `CONNECT_SKIPPED_ALREADY_CONNECTED` | A fast-connect request was skipped because a connection was already active. |
| `CONNECT_SKIPPED_ALREADY_SCANNING` | A fast-connect request was skipped because another recent attempt was already in progress. |
| `BLE_SCAN_STARTED` | A new fast-connect attempt began. |
| `BLE_SERVICE_SCAN_STARTED` | BLE scanning started for the Throne service UUID. |
| `DEVICE_FOUND` | A matching BLE device was discovered during scan. |
| `PERIPHERAL_CONNECTION_STARTED` | Android started connecting to the discovered peripheral. |
| `PERIPHERAL_CONNECTED` | Android connected to the Throne peripheral. |
| `DISCONNECTED` | The BLE peripheral disconnected. |
| `CONNECTION_FAILED` | The BLE connection attempt failed before a usable session was established. |
| `CONNECTION_TIMEOUT` | The connection attempt timed out before success. |
| `SERVICE_DISCOVERY_STARTED` | Android started BLE service discovery after connecting. |
| `SERVICE_DISCOVERY_ERROR` | BLE service discovery failed. |
| `SERVICES_DISCOVERED` | BLE services were discovered successfully. |
| `NO_SERVICES_FOUND` | Android connected but could not find the expected Throne service. |
| `CHARACTERISTIC_DISCOVERY_STARTED` | Android started looking up the target characteristic on the discovered service. |
| `CHARACTERISTIC_DISCOVERY_ERROR` | Android could not complete characteristic discovery for the target service. |
| `CHARACTERISTICS_FOUND` | The service exposed characteristics and Android found the target list to inspect. |
| `CHARACTERISTIC_NOT_FOUND` | The expected notify characteristic was not present on the service. |
| `NOTIFICATION_SUBSCRIPTION_STARTED` | Android started enabling notifications on the target characteristic. |
| `NOTIFICATION_SUBSCRIPTION_FAILED` | Android failed to enable notifications on the target characteristic. |
| `NOTIFICATION_SUBSCRIBED` | Notification subscription succeeded and Android is ready for the auth response. |
| `AUTH_MESSAGE_SENT` | The auth payload was written to the device. |
| `AUTH_WRITE_ERROR` | Writing the auth payload failed. |
| `AUTH_WRITE_SENT` | The auth write completed and Android is waiting for the notification response. |
| `AUTH_RESPONSE_RECEIVED` | A notification payload was received back from the device. |
| `AUTH_RESPONSE_EMPTY` | A notification callback arrived without a payload. |
| `BLUETOOTH_CLEANUP_STARTED` | Native BLE cleanup/disconnect flow started. |
| `SESSION_COMPLETE` | The BLE auth session completed successfully and cleanup ran. |

## Contributing

See the [contributing guide](CONTRIBUTING.md) to learn how to contribute to the repository and the development workflow.

## License

MIT

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
