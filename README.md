# UV-Sight for Android

Native Android companion app for the [UV-Sight](https://github.com/NoDiskNoFun/uv-sight) bow sight: status, training scores, history, settings and calibration over Bluetooth LE. It offers the same functions as the web app of the main project, without a browser.

> **Disclaimer: AI-generated project.** The app was written with Claude (Anthropic) in a conversation with the project owner. The core logic was tested against the firmware running in a simulator; the app was not yet run on a phone by the AI. Use at your own risk.

## Structure

| Module | Content |
|---|---|
| `core` | Pure Kotlin: the JSON protocol of the sight, the archive on the phone, sync with the sight's log, training, CSV export/import, JSON backup. No Android dependencies, unit-tested. |
| `app` | Android app: Bluetooth LE (Nordic UART Service), Jetpack Compose screens (Status, Training, History, Settings, Console). |

The app needs firmware 5.7 or newer on the sight (protocol 15).

## Build

Requirements: JDK 17, Android SDK (platform 35, build-tools). Then:

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test                 # unit tests of the core
./gradlew :core:test -PskipAndroid   # the same without the Android SDK installed
```

Every push builds the debug APK on GitHub Actions; download it from the workflow run (artifact `uv-sight-debug-apk`).

The release build is signed with the debug key so that it can be built without secrets. For a store release put your own keystore into `app/build.gradle.kts`.

### Testing the core against the firmware

The core can be driven against the firmware running natively (the mock build of `sketch.ino` from the main project). Set `SIGHT_BIN` to that binary before running the tests; the test `SimulatorTest` then walks through a whole training session, sync, log clearing and copying back. Without `SIGHT_BIN` the test is skipped.

## Permissions

Android 12 and newer: *Nearby devices* (Bluetooth scan and connect). Android 11 and older: *Location*, which Android requires for Bluetooth scanning; the app does not use the position. Location services must be switched on for scanning on those versions.

## Using the app

1. Move the bow so the sight wakes up, then tap **Connect** on the Status tab. The app remembers the sight and reconnects on its own after a lost link.
2. **Training:** scores of the open end, keypad in target ring colours, Save / Skip / End session. Scoring works without a connection; Save reconnects.
3. **History:** sessions stored on the phone, chart, per-end details, CSV export, JSON backup and import, removal on the phone or on the sight.
4. **Settings:** shot counter, cant and aiming angle, LED, all settings with slider and description, guided calibration, setups, sync options, firmware update mode.
5. **Console:** raw messages and free command input (enable it under Settings → App and firmware).
