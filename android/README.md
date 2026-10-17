# SPOTLED Android prototype

Native Java Android app prototype. It provides text/image input using the same independent 3×5 ASCII raster font as the Python reference, solid text colors and a static rainbow gradient, system Photo Picker on API 33+, 0°/90°/180°/270° rotation, a 64×16 preview, `contain`/`cover`/`stretch` fit choices, the eight APK-verified text effects, BLE discovery for any `SpotLED_*` name with a chooser shortly after the first match, one bounded fresh-GATT retry after connection failure, and acknowledged 24-bit RGB frame transfer. Animated GIFs are sampled to two frames, transformed with the same rotation/fit controls, and sent at a shared 250–1,000 ms interval; the device's 7,280-byte receive limit prevents sending more than two full-color 64×16 frames. The About dialog credits DuvTheDove, Bug-Mag.net, clavo, and OpenAI Codex. Device selection stores the target without opening GATT; each send connects and disconnects afterward. If no scan result appears, scanning continues in 3-second intervals for up to 15 seconds. There is no None option until a safe firmware representation is verified.

## Build and test

Use JDK 17 and an Android SDK with platform 35 installed:

```sh
JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk ./gradlew testDebugUnitTest assembleDebug
```

When using ADB with multiple devices connected, select the intended Android test device explicitly. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Signed APK for internal sharing

Build the signed internal-sideload variant with:

```sh
./gradlew testReleaseUnitTest assembleRelease
```

Release asset: [SpotLED-Companion.apk](https://github.com/plughie/SpotLED-Suite/releases/download/v0.12.1/SpotLED-Companion.apk) (version `0.12.1`, package `com.spotled.app`). Signature verified with `apksigner`; SHA-256: `0ac97adc276683f0cd8f553bf0b11771b923da20368f4bb107afbe9bdf6a8af1`.

For this contributor-test build, `release` uses the local Android debug signing identity so the APK can update the currently installed prototype. The APK is signed and sideloadable, but this is **not a production release key**. No private signing key or password is in the project. To distribute future compatible updates or publish publicly, create and securely back up an owner-controlled release key, then use that same key for every update. The collaborator can install this APK fresh; an existing app signed by a different key cannot be updated in place.

## Current validation

- All 15 local JVM protocol/transform/font tests pass, covering one- and two-frame packet structure, receive-capacity fit, rotation/fit, effect IDs, color palette, bounded connection retry, and B glyph parity with Python.
- User confirmed the About SpotLED Companion dialog and project attributions on the Android phone; this is included in signed v0.12.1.
- The debug app builds and is installed/launched on the Pixel 6a.
- User has validated Nearby Devices permission, BLE discovery/connection, text, a 0° full-screen image, and a 90° image using `stretch` on the Pixel 6a. 180°/270° and other fit-mode combinations remain unverified.
- The API 33+ image chooser uses the system Photo Picker and returns the selected image URI to SPOTLED; user validation of the chooser flow remains pending.
- Animated GIF selection is now decoded into two sampled frames; preview intentionally shows the first frame only. The shared frame interval is clamped to 250–1,000 ms to avoid rapid flashing. Android GIF animation still needs user validation on a SpotLED; the separate Python GIF path is physically confirmed.
- Signed v0.12.0 is installed/launched on the Pixel 6a for validation; no LED content was sent during the build/install.
- Text effects encode the Android reference IDs 0–7 in the animation `SpecialEffectsObject`; physical effect behavior from the RGB-raster text path is not yet confirmed.
- None is currently unavailable: omitting the effect object was observed to trigger a flashing loading icon, so the implementation was removed from the UI and sender.
- Android status 201 means `ERROR_GATT_WRITE_REQUEST_BUSY`; the sender now retries that explicitly rejected write briefly, without retrying accepted/ambiguous payloads.
- The 3×5 glyph table is ported from Python, but visual confirmation of the user's B reference remains pending. A landscape image rotated 90° with `contain` is expected to have black side padding; select `stretch` to fill (distorting) or `cover` to fill (cropping).

## Safety/behavior

Static images/text send one RGB frame. Animated GIFs send two sampled frames at a deliberately bounded pace (minimum 250 ms/frame); the display's receive capacity limits full-color animation length. A transfer acknowledgment is shown as receipt only; check the physical display to verify rendering. A missing final acknowledgment is treated as ambiguous and is not retried.
