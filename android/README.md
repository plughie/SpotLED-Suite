# SPOTLED Android prototype

Native Java Android app. It provides text/image input, solid and static-rainbow text colors, system Photo Picker on API 33+, 0°/90°/180°/270° rotation, a 64×16 preview, `contain`/`cover`/`stretch` fit choices, eight text effects, BLE discovery for any `SpotLED_*` name with a chooser shortly after the first match, one bounded fresh-GATT retry after connection failure, and acknowledged transfers. All unrotated text uses native WordStock/CharacterString transfers and captured 16×16 glyphs shared with Python; rotated text retains RGB rasterization. Settings include System/Light/Dark appearance, a native/raster text switch, and text intervals of 300/250/200/150/100 ms for both native CharacterString and raster FrameAnimation. The launcher icon uses the supplied artwork, and the gear menu contains Settings and About. Animated GIFs are sampled to two frames, transformed with rotation/fit, previewed at the transfer pace, and sent at 250–1,000 ms/frame; the 7,280-byte receive limit allows at most two full-color 64×16 frames. The last selected display name/address is remembered locally; no GATT connection stays open while idle, and sending with no remembered target prompts discovery and continues the send after selection. The About dialog credits DuvTheDove, Bug-Mag.net, clavo, and OpenAI Codex. Scanning continues in 3-second intervals for up to 15 seconds. None remains unavailable because omitting the effect object triggered the firmware loading icon.

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

Latest release: [SpotLED-Companion.apk v0.16.1](https://github.com/plughie/SpotLED-Suite/releases/download/v0.16.1/SpotLED-Companion.apk), package `com.spotled.app`.

Version 0.16.0 (versionCode 17) remembers the last selected display and prompts to discover one when a send has no remembered target; the user validated both flows across app restarts using text and images. Version 0.16.1 (versionCode 18) persists image rotation and fit across the system Photo Picker. Unreleased v0.16.2 (versionCode 19) adds 150 ms and 250 ms choices alongside 100/200/300 ms in both native and raster timing. The user physically validated Android image rotations at 0°/90°/180°/270° and compared Contain/Cover/Stretch using the asymmetric test pattern. Version 0.15.0 added the animated GIF preview, which animates the same two transformed frames sent to the display and pauses in the background.

The v0.16.1 signed APK is available from GitHub. Native Fast (100 ms) was confirmed about 3× faster than native Slow (300 ms). Raster Slow (300 ms) matches native Slow; Fast raster is faster than Normal raster but slower than Fast native. SpotLED-MQTT was stopped during these tests.

For this contributor-test build, `release` uses the local Android debug signing identity so the APK can update the currently installed prototype. The APK is signed and sideloadable, but this is **not a production release key**. No private signing key or password is in the project. To distribute future compatible updates or publish publicly, create and securely back up an owner-controlled release key, then use that same key for every update. The collaborator can install this APK fresh; an existing app signed by a different key cannot be updated in place.

## Current validation

- All 23 release JVM tests pass, including five text-speed presets, native/raster timing encoding, WordStock/CharacterString framing, captured W/N glyphs, effect routing, settings text-mode selection, and setup commands.
- SpotLED-MQTT was stopped before speed tests to avoid BLE contention. User confirmed native Slow (300 ms) was slow and Fast (100 ms) about 3× faster; raster Slow matches native Slow and Fast raster is faster than Normal raster, but slower than Fast native.
- User confirmed the v0.14.0 launcher icon, “SPOTLED Companion” header, gear-based Settings/About, appearance options, and native/raster text switch on the Pixel 6a.
- User confirmed the v0.13.0 native Scroll-left text output and improved glyph rendering on the SpotLED; other native effect choices have not yet been individually checked.
- User confirmed the About SpotLED Companion dialog and project attributions on the Android phone; this is included in signed v0.12.1.
- The debug app builds and is installed/launched on the Pixel 6a.
- User has validated Nearby Devices permission, BLE discovery/connection, text, a 0° full-screen image, and a 90° image using `stretch` on the Pixel 6a. 180°/270° and other fit-mode combinations remain unverified.
- The API 33+ image chooser uses the system Photo Picker and returns the selected image URI to SPOTLED; user validation of the chooser flow remains pending.
- Animated GIF selection is decoded into two sampled frames; the preview animates those same transformed frames at the shared transfer interval, clamped to 250–1,000 ms. Preview playback pauses while the app is backgrounded and resumes when it returns. User confirmed the preview works on the Android phone and that SpotLED GIF behavior matches the CLI.
- Signed v0.12.0 is installed/launched on the Pixel 6a for validation; no LED content was sent during the build/install.
- All unrotated text encodes its selected effect ID in CharacterString `SpecialEffectsObject`; Scroll-left is physically confirmed. Rotated text uses raster frame effect objects.
- None is currently unavailable: omitting the effect object was observed to trigger a flashing loading icon, so the implementation was removed from the UI and sender.
- Android status 201 means `ERROR_GATT_WRITE_REQUEST_BUSY`; the sender now retries that explicitly rejected write briefly, without retrying accepted/ambiguous payloads.
- The 3×5 glyph table is ported from Python, but visual confirmation of the user's B reference remains pending. A landscape image rotated 90° with `contain` is expected to have black side padding; select `stretch` to fill (distorting) or `cover` to fill (cropping).

## Safety/behavior

Static images/text send one RGB frame. Animated GIF previews animate the same two sampled, rotated/fitted frames sent to the display, at the transfer's deliberately bounded pace (minimum 250 ms/frame). The display's receive capacity limits full-color animation length. A transfer acknowledgment is shown as receipt only; check the physical display to verify rendering. A missing final acknowledgment is treated as ambiguous and is not retried.
