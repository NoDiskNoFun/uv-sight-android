# UV-Sight for Android

Native Android companion app for the [UV-Sight](https://github.com/NoDiskNoFun/uv-sight) bow sight: status, training scores, history, settings and calibration over Bluetooth LE. It offers the same functions as the web app of the main project, without a browser.

> **Disclaimer: AI-generated project.** The app was written with Claude (Anthropic) in a conversation with the project owner. The core logic was tested against the firmware running in a simulator; the app was not yet run on a phone by the AI. Use at your own risk.

## Structure

| Module | Content |
|---|---|
| `core` | Pure Kotlin: the JSON protocol of the sight, the archive on the phone, sync with the sight's log, training, CSV export/import, JSON backup. No Android dependencies, unit-tested. |
| `app` | Android app: Bluetooth LE (Nordic UART Service), Jetpack Compose screens (Status, Training, History, Settings, Console). |

The app needs firmware 5.8 or newer on the sight (protocol 16).

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

Android 12 and newer: *Nearby devices* (Bluetooth scan and connect). Android 11 and older: *Location*, which Android requires for Bluetooth scanning; the app does not use the position. Location services must be switched on for scanning on those versions. Android 13 and newer also ask for *Notifications*; without it the app still works, but shows no notifications.

## Photo scoring

On the Training tab, *Score from photo* opens the camera. Then:

1. **Mark the face:** choose the face (40, 60, 80, 122 cm or a 40 cm spot) and the environment (outdoor/indoor), tap the centre, then five or more points on the outer edge of the blue ring. The app fits the ellipse and removes the perspective; the rings appear on the photo. *Skip face* works without this, then you pick each arrow's ring by hand.
2. **Mark the arrows:** tap each arrow where it enters the face. The ring appears next to the mark (line-cutter rule with the arrow diameter from the settings). Drag a mark to move it; tap it to change the ring or delete it. Pinch or use the + / − buttons to zoom. With a finger, a magnifier shows the spot under the fingertip while dragging; with a stylus the tip is used as is.
3. **Use scores** fills the keypad entries in tap order. Save the end as usual; the sight's shot count is compared as always. The arrow positions are kept with the end and shown on a face in the session details, together with the group centre.

### Training data

Under *Settings → Photo scoring*, *Collect training data* (off by default) keeps every scored photo with its marks on the phone: the full photo, the tapped centre and edge points, the fitted ellipse, every arrow in pixels and in millimetres from the centre, the ring the app computed and the ring you confirmed, whether the mark was moved and whether a stylus or a finger placed it, the sight's shot count, distance, session and end, environment, camera exposure data, device and app version. *Export* writes a ZIP with the photos, one JSON per photo and a COCO keypoint file over everything; *Delete* removes the data from the phone. Photos never leave the phone unless you export them. The `training/` folder holds the scripts that turn an export into a model.

### Detection model

Once a model is imported under *Settings → Photo scoring → Detection model* (a `.tflite` file trained with the local web page in `training/`, see `training/README.md`), *Score from photo* uses it: the arrows are looked for while you mark the face, and when you reach the arrow step the found arrows are already marked in blue. Check them, drag, change the ring or delete as with your own marks, add the ones it missed. The confidence slider sets how sure the model has to be before an arrow is proposed. Without a model the button works by hand as described above. The app checks a model by loading it once and refuses files of the wrong shape.

Importing a model switches *Collect training data* off and keeps it off while the model is installed, so model proposals never end up in the training set. Remove the model to collect hand-marked photos again; data already collected stays and can still be exported.

## Notifications and vibration

All of these are switched on by default and can be changed under *Settings → Notifications and vibration*.

- **Session notification:** while the app is connected, a foreground service keeps the Bluetooth link alive with the phone in your pocket and shows the running session: "End 4, arrow 3", below it the ends, average and X count. Tapping it opens the scoring keypad. Without a session it shows the connection and the sight's battery. Switching it off limits the connection to the time the app is open.
- **Vibrate while setting up a sight:** only with the aiming angle set to *On*. The phone mirrors the LED: 2 pulses = aiming too low (arrow short), 3 pulses = too high, one long soft pulse = the aim fits. Repeats every 1.5 s while the state lasts. Strength: light, medium, strong. During training (aiming angle *Auto*) the phone never vibrates while you aim.
- **Session ended by the sight:** when the sight closes a session after `session_end` minutes without a shot, a notification shows the summary.
- **Sight battery low:** below 15 % and when the sight locks the light because the battery is empty.
- **Charging finished:** when the sight reports a full battery over USB.

Some phones (Xiaomi, Huawei, Samsung with aggressive power saving) stop foreground services anyway. If the session notification vanishes when the screen is off, exclude UV-Sight from the battery optimisation in the Android settings.

## Using the app

1. Move the bow so the sight wakes up, then tap **Connect** on the Status tab. The app remembers the sight and reconnects on its own after a lost link.
2. **Training:** scores of the open end, keypad in target ring colours or scoring from a photo of the face, Save / Skip / End session. Scoring works without a connection; Save reconnects.
3. **History:** sessions stored on the phone, chart, per-end details, CSV export, JSON backup and import, removal on the phone or on the sight.
4. **Settings:** shot counter, cant and aiming angle, LED, all settings with slider and description, guided calibration, setups, sync options, notifications and vibration, firmware update mode.
5. **Console:** raw messages and free command input (enable it under Settings → App and firmware).
