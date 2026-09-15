# Implementation Plan - Fix Real-time Signal Updates

Address the issue where the RSSI gauge does not update when moving away from the network.

## Proposed Changes

### [app]

#### [MODIFY] [NetworkScanner.kt](file:///home/ocde6223/AndroidStudioProjects/MyWifiScan/app/src/main/java/com/totof/mywifiscan/NetworkScanner.kt)
- Improve `getLatestRssi(bssid: String)`:
    - First, check if the BSSID matches the currently connected Wi-Fi. If so, use `WifiManager.connectionInfo.rssi` for immediate, high-frequency updates.
    - Otherwise, trigger a new Wi-Fi scan using `startScan()`. Note that this is throttled by Android (4 scans per 2 minutes in the foreground).
    - Safely fetch results from `scanResults`.
- Add proper permission handling (SecurityException) for these calls.

## Verification Plan

### Manual Verification
- Deploy to a physical device.
- Open the signal detail screen for the **connected** network.
- Move away from the router and verify the gauge and dBm value update instantly.
- Open the detail screen for a **non-connected** network.
- Move away and verify that the signal eventually updates (after the system allows a new scan).
