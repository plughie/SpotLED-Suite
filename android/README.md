# SPOTLED Android prototype

Native Java Android app prototype. It provides text/image input, solid and static-rainbow text colors, system Photo Picker on API 33+, 0°/90°/180°/270° rotation, a 64×16 preview, `contain`/`cover`/`stretch` fit choices, eight text effects, BLE discovery for any `SpotLED_*` name with a chooser shortly after the first match, one bounded fresh-GATT retry after connection failure, and acknowledged transfers. All unrotated text uses native WordStock/CharacterString transfers and captured 16×16 glyphs shared with Python; rotated text retains RGB rasterization. Settings include System/Light/Dark appearance and a native/raster text switch; the launcher icon uses the supplied artwork, and the gear menu contains Settings and About. The user confirmed the icon, settings, raster/native switch, and branded title on the Pixel 6a. Animated GIFs are sampled to two frames, transformed with rotation/fit, and sent at 250–1,000 ms/frame; the 7,280-byte receive limit allows at most two full-color 64×16 frames. The About dialog credits DuvTheDove, Bug-Mag.net, clavo, and OpenAI Codex. Device selection stores the target without opening GATT; each send connects and disconnects afterward. Scanning continues in 3-second intervals for up to 15 seconds. None remains unavailable because omitting the effect object triggered the firmware loading icon.

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

Latest release: [SpotLED-Companion.apk v0.14.0](https://github.com/plughie/SpotLED-Suite/releases/download/v0.14.0/SpotLED-Companion.apk), package `com.spotled.app`. The signed APK SHA-256 is `07167b19ca02640494ab46cbd178beb58b18d6f78e018b57a02a803fa3b52286`.

For this contributor-test build, `release` uses the local Android debug signing identity so the APK can update the currently installed prototype. The APK is signed and sideloadable, but this is **not a production release key**. No private signing key or password is in the project. To distribute future compatible updates or publish publicly, create and securely back up an owner-controlled release key, then use that same key for every update. The collaborator can install this APK fresh; an existing app signed by a different key cannot be updated in place.

## Current validation

- All 20 release JVM protocol/transform/font tests pass, including native WordStock/CharacterString framing, captured W/N glyphs, effect routing, settings text-mode selection, and setup commands.
- User confirmed the v0.14.0 launcher icon, “SPOTLED Companion” header, gear-based Settings/About, appearance options, and native/raster text switch on the Pixel 6a.
- User confirmed the v0.13.0 native Scroll-left text output and improved glyph rendering on the SpotLED; other native effect choices have not yet been individually checked.
- User confirmed the About SpotLED Companion dialog and project attributions on the Android phone; this is included in signed v0.12.1.
- The debug app builds and is installed/launched on the Pixel 6a.
- User has validated Nearby Devices permission, BLE discovery/connection, text, a 0° full-screen image, and a 90° image using `stretch` on the Pixel 6a. 180°/270° and other fit-mode combinations remain unverified.
- The API 33+ image chooser uses the system Photo Picker and returns the selected image URI to SPOTLED; user validation of the chooser flow remains pending.
- Animated GIF selection is decoded into two sampled frames; preview intentionally shows the first frame only. The shared frame interval is clamped to 250–1,000 ms to avoid rapid flashing. User has physically confirmed Android GIF animation on a SpotLED; rendering is simplified by two-frame sampling and the panel's 64×16 resolution.
- Signed v0.12.0 is installed/launched on the Pixel 6a for validation; no LED content was sent during the build/install.
- All unrotated text encodes its selected effect ID in CharacterString `SpecialEffectsObject`; Scroll-left is physically confirmed. Rotated text uses raster frame effect objects.
- None is currently unavailable: omitting the effect object was observed to trigger a flashing loading icon, so the implementation was removed from the UI and sender.
- Android status 201 means `ERROR_GATT_WRITE_REQUEST_BUSY`; the sender now retries that explicitly rejected write briefly, without retrying accepted/ambiguous payloads.
- The 3×5 glyph table is ported from Python, but visual confirmation of the user's B reference remains pending. A landscape image rotated 90° with `contain` is expected to have black side padding; select `stretch` to fill (distorting) or `cover` to fill (cropping).

## Safety/behavior

Static images/text send one RGB frame. Animated GIFs send two sampled frames at a deliberately bounded pace (minimum 250 ms/frame); the display's receive capacity limits full-color animation length. A transfer acknowledgment is shown as receipt only; check the physical display to verify rendering. A missing final acknowledgment is treated as ambiguous and is not retried.
