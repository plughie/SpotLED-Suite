# SPOTLED Android prototype

Native Java Android app. It provides text/image input, solid and static-rainbow text colors, system Photo Picker on API 33+, image-only 0°/90°/180°/270° rotation and `contain`/`cover`/`stretch` fit choices, image zoom (1–4×) and horizontal/vertical pan controls, plus a 64×16 preview. Still images also have an optional looping vertical-scroll mode: the image is fitted to 64 pixels wide, then the app streams successive 16-row viewports through the firmware Scroll Up effect at a safe 250 ms cadence. This mode keeps BLE connected while running, can be stopped, and is limited to 4 MP source decode/4,096 scroll rows; animated GIFs retain their existing two-frame animation mode. Text orientation has only Landscape and Portrait. Portrait turns each existing native 16×16 glyph 270° clockwise within its cell, sends the full message through the native WordStock/CharacterString path, and retains firmware effects such as Scroll Left. The text preview updates live as the message and options change; **Send text** sends the current preview immediately. It shows the initial 64×16 viewport while firmware scrolls the complete string. Images and GIFs retain a separate preview-then-send step. Old saved clockwise/counterclockwise text selections migrate to Portrait. Image settings never change text output. Landscape text uses native WordStock/CharacterString transfers unless raster is selected; captured 16×16 glyphs are shared with Python, and uncaptured BMP glyphs use the shared compact 3×5 fallback scaled to 16×16 while retaining their codepoint. Non-BMP native text is rejected consistently. Settings include System/Light/Dark appearance, a native/raster text switch, and text intervals of 300/250/200/150/100 ms for both native CharacterString and raster FrameAnimation. The launcher icon uses the supplied artwork, and the gear menu contains Settings and About. Animated GIFs are sampled to two frames, transformed with image rotation/fit, previewed at the transfer pace, and sent at 250–1,000 ms/frame; the 7,280-byte receive limit allows at most two full-color 64×16 frames. The last selected display name/address is remembered locally; no GATT connection stays open while idle, and sending with no remembered target prompts discovery and continues the send after selection. The About dialog credits DuvTheDove, Bug-Mag.net, clavo, OpenAI Codex, and Wetter.ax icon artist Hank the Tank (used with permission). Scanning continues in 3-second intervals for up to 15 seconds. None remains unavailable because omitting the effect object triggered the firmware loading icon.

v0.18.0 added opt-in forwarding of Android notifications from user-selected apps, using the Scroll effect and the existing user-started foreground display service. v0.19.5 also lets users select SPOTLED Companion, forwarding only its MQTT connection, reconnection, and topic-subscription errors; repeated errors in the same category are suppressed until recovery. Routine Companion status notifications remain suppressed. Notification Access is a sensitive special permission that the user grants in Android Settings; the app checks whether it is enabled and provides a direct link. Forwarding works only while the foreground service is running. Android 13+ also requires the normal notification permission for the service's persistent status notification. Manual sends are blocked while the service is active to avoid BLE contention.

v0.19.0 animates the 8×8 Wetter.ax condition icon in a sampled two-frame status animation, with the date-calculated moon phase stacked above it and the speed bar kept clear of the icon column. v0.19.1 stores the composed icon frame before playback; the user confirmed the moon/weather stack persists afterward on both Android and Mac. The icon artwork is by Hank the Tank and is used with permission; see the [original Wetter.ax flow](https://flows.blueforcer.de/flow/fGEhP0cZZd6E#install). Canonical, tracked source GIFs are in `assets/weather-icons`; the Android `preBuild` task copies them into APK assets automatically, so local ignored exports are no longer required.

The foreground service also provides a ClockTemp-style clock/weather display and AWTRIX MQTT notifications. It supports NOAA/NWS latest-station observations or a retained Home Assistant weather topic, optional TC001 indoor readings, and the Home Assistant speed bar; MQTT alerts use Scroll. This service is Android-only and explicitly excluded from the planned iOS app scope. NOAA source credit appears in this README rather than on the LED display: [NOAA credit guidance](https://www.noaa.gov/office-education/outreach-communication/faq) and [NWS API documentation](https://www.weather.gov/documentation/services-web-api).

Configure the weather source and selected display in the app's Settings → Time, weather & MQTT panel. NOAA mode needs a valid four-character station ID and can run with every MQTT field blank. The AWTRIX notification topic is optional in either mode; enter a broker to enable alerts. Home Assistant mode requires a broker and retained weather topic, and can optionally subscribe to its speed topic. The TC001 field accepts a bare host/IP and adds `/api/v1/device`, or a full HTTP(S) URL. On Android 17, allow the app's **Network** permission in Android app settings; otherwise NOAA, TC001, and MQTT requests are blocked. Credentials are stored in app-private preferences. The service starts only after an explicit user action and has no boot auto-start. For manual control, stop the service in Settings, wait for its stopped status, then use the main-screen display controls; this does not lock out other phones or computers.

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

Latest contributor release: [SpotLED-Companion.apk v0.19.8](https://github.com/plughie/SpotLED-Suite/releases/download/v0.19.8/SpotLED-Companion.apk), package `com.spotled.app`.

Version 0.16.0 (versionCode 17) remembers the last selected display and prompts to discover one when a send has no remembered target; the user validated both flows across app restarts using text and images. Version 0.16.1 (versionCode 18) persists image rotation and fit across the system Photo Picker. Version 0.16.2 (versionCode 19) adds 150 ms and 250 ms choices alongside 100/200/300 ms in both native and raster timing. The user physically validated Android image rotations at 0°/90°/180°/270° and compared Contain/Cover/Stretch using the asymmetric test pattern. Version 0.15.0 added the animated GIF preview, which animates the same two transformed frames sent to the display and pauses in the background.

The v0.19.8 signed APK is at `app/build/outputs/apk/release/app-release.apk`; SHA-256: `1309dbc56170b31d9d033196abf45d999454383a7109ca0f9e2ab86166c2addb`. The APK is signed with the local Android debug identity for contributor sideloading, not production distribution.

The v0.19.5 release was confirmed by the user to show Companion MQTT failure messages on the SpotLED. Native Fast (100 ms) was confirmed about 3× faster than native Slow (300 ms). Raster Slow (300 ms) matches native Slow; Fast raster is faster than Normal raster but slower than Fast native. SpotLED-MQTT was stopped during those speed tests.

For this contributor-test build, `release` uses the local Android debug signing identity so the APK can update the currently installed prototype. The APK is signed and sideloadable, but this is **not a production release key**. No private signing key or password is in the project. To distribute future compatible updates or publish publicly, create and securely back up an owner-controlled release key, then use that same key for every update. The collaborator can install this APK fresh; an existing app signed by a different key cannot be updated in place.

## Current validation

- Current source queries the selected display's screen/color format, animation
  frame limit, and receive capacity before each text/image send. Unsupported
  RGB dimensions/formats, excess frame counts, and payloads above the live limit
  are rejected before setup or content writes. Android JVM protocol tests cover
  response matching and malformed/unsupported limits; physical BLE validation
  of these new mobile preflight queries is still pending.
- Image/GIF reads, decoding and panel rasterization now run on a cancellable
  worker. Compressed inputs are bounded to 50 MB, still images are sampled to a
  maximum 4 MP decode, GIF canvases above 4 MP are rejected before animation
  decode, and all EXIF orientations including mirrored cases are applied.
  47 current-source JVM tests and `lintRelease` pass. Physical edge-case
  validation is pending.
- GIF sampling and timing use shared cross-platform vectors. The Android decoder
  counts source frames/delays, selects endpoint samples, and reports original vs
  sent frames plus its bounded interval. Canonical weather GIFs are now packaged
  reproducibly from tracked assets rather than host-local ignored copies.

- The v0.16.2 release passed 23 JVM tests, including five text-speed presets, native/raster timing encoding, WordStock/CharacterString framing, captured W/N glyphs, effect routing, settings text-mode selection, and setup commands.
- The v0.18.0 build passes 30 JVM tests, including notification formatting/bounds, MQTT payload/color parsing, NOAA and Home Assistant weather parsing, Scroll alert packet encoding, optional-MQTT source selection, TC001 URL normalization, and both ClockTemp raster variants. `lintRelease` passes. The user installed the release candidate on the Pixel 6a and confirmed Android notification forwarding.
- Release APK: `app/build/outputs/apk/release/app-release.apk` (versionCode 23, v0.18.0); signed with the local debug identity for contributor sideloading and installed on the Pixel 6a.
- The v0.19.0 release passes 32 JVM tests and `lintRelease`; the signed APK was installed on the Pixel 6a.
- The v0.19.1 patch stores the fully composed icon frame before animation playback; the user confirmed the persistent moon/weather stack from Android and Mac.
- The v0.19.5 release passed 33 JVM tests and `lintRelease`; it requests the internal signature permission required for notification forwarding.
- The v0.19.6 release passes 34 JVM tests and `lintRelease`. The APK verifies with APK Signature Scheme v2 and includes the native serifed capital-I regression fix.
- SpotLED-MQTT was stopped before speed tests to avoid BLE contention. User confirmed native Slow (300 ms) was slow and Fast (100 ms) about 3× faster; raster Slow matches native Slow and Fast raster is faster than Normal raster, but slower than Fast native.
- User confirmed the v0.14.0 launcher icon, “SPOTLED Companion” header, gear-based Settings/About, appearance options, and native/raster text switch on the Pixel 6a.
- User confirmed the v0.13.0 native Scroll-left text output and improved glyph rendering on the SpotLED; other native effect choices have not yet been individually checked.
- User confirmed the About SpotLED Companion dialog and project attributions on the Android phone; this is included in signed v0.12.1.
- The debug app builds and is installed/launched on the Pixel 6a.
- User has validated Nearby Devices permission, BLE discovery/connection, text, a 0° full-screen image, and a 90° image using `stretch` on the Pixel 6a. 180°/270° and other fit-mode combinations remain unverified.
- The API 33+ image chooser uses the system Photo Picker and returns the selected image URI to SPOTLED; user validation of the chooser flow remains pending.
- Animated GIF selection is decoded into two sampled frames; the preview animates those same transformed frames at the shared transfer interval, clamped to 250–1,000 ms. Preview playback pauses while the app is backgrounded and resumes when it returns. User confirmed the preview works on the Android phone and that SpotLED GIF behavior matches the CLI.
- Signed v0.12.0 is installed/launched on the Pixel 6a for validation; no LED content was sent during the build/install.
- Landscape Normal text encodes its selected effect ID in CharacterString `SpecialEffectsObject`; Scroll-left is physically confirmed. Portrait text rotates native glyphs 270° clockwise and uses the same CharacterString path to preserve scrolling.
- None is currently unavailable: omitting the effect object was observed to trigger a flashing loading icon, so the implementation was removed from the UI and sender.
- Android status 201 means `ERROR_GATT_WRITE_REQUEST_BUSY`; the sender now retries that explicitly rejected write briefly, without retrying accepted/ambiguous payloads.
- The 3×5 glyph table is ported from Python, but visual confirmation of the user's B reference remains pending. A landscape image rotated 90° with `contain` is expected to have black side padding; select `stretch` to fill (distorting) or `cover` to fill (cropping).

## Safety/behavior

Static images/text send one RGB frame. Animated GIF previews animate the same two sampled, rotated/fitted frames sent to the display, at the transfer's deliberately bounded pace (minimum 250 ms/frame). The display's receive capacity limits full-color animation length. A transfer acknowledgment is shown as receipt only; check the physical display to verify rendering. A missing final acknowledgment is treated as ambiguous and is not retried.
