# Implementation Plan - Add Time Header and Style Title

Add a top header bar with a background image and real-time clock, and update the main screen title's style.

## Proposed Changes

### [app]

#### [MODIFY] [MainActivity.kt](file:///home/ocde6223/AndroidStudioProjects/MyWifiScan/app/src/main/java/com/totof/mywifiscan/MainActivity.kt)
- Add `TimeHeader` composable to display the current time with `bandeau.png` as background.
- Integrate `TimeHeader` into the `Scaffold`'s `topBar`.
- Update the title "Scanner Réseau WIFI" in `WifiScannerScreen` to be black and use a smaller font size (`titleLarge`).

## Verification Plan

### Manual Verification
- Deploy the app to a device or emulator.
- Verify that the header appears at the very top with the background image.
- Verify that the time updates every second.
- Verify that the "Scanner Réseau WIFI" title is black and smaller than before.
