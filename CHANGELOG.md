# Changelog

Notable project changes are recorded here. Older implementation and validation
notes are preserved in [PROJECT_HISTORY.md](PROJECT_HISTORY.md).

## [0.19.7] - 2026-10-23

- Replace text's 0°/90°/270° selector with Landscape/Portrait. Portrait uses
  one 270° clockwise native-glyph rotation and sends the full message as
  WordStock/CharacterString, preserving firmware effects such as Scroll Left.
  Keep image rotation angles unchanged. Android, iOS, and Python now share this
  behavior. User confirms the 270° Portrait glyph direction is correct on iOS;
  full-message scrolling and Android/Python physical checks remain pending.
- Android Companion v0.19.7 (versionCode 31) includes mobile live-capability
  preflight, cancellable bounded Android image/GIF decoding, shared GIF
  sampling/timing rules, canonical Wetter.ax assets, notification/service queue
  limits, and the Landscape/Portrait text selector. The contributor APK is
  signed with the local debug identity. Verification: Python 63 tests, MQTT 38
  tests, Android 44 JVM tests, `lintRelease`, and `assembleRelease` pass. iOS
  app/test targets compile; no iOS release was created.
- Add mobile live-capability preflight: Android and iOS query dimensions, color
  format, animation frame limit, and receive capacity, then reject unsupported
  or oversized sends before content writes. Android JVM tests pass (40); all 25
  iOS simulator unit tests pass. The UI-test runner terminated during startup,
  and physical BLE behavior remains unvalidated.
- Move Android image/GIF loading and rasterization to a cancellable background
  worker. Bound encoded input to 50 MB and decoded canvases to 4 MP, sample
  oversized stills before decode, reject oversized GIF canvases, and honor all
  eight EXIF orientations, including mirrored transforms. 41 Android JVM tests
  and `lintDebug` pass; no APK was built or installed.
- Define one animated-GIF contract across Python, Android and iOS: compositor-
  rendered frames flattened over black, uniform endpoint sampling, 20 ms minimum
  source delays (100 ms when missing/zero), and cycle-duration-derived intervals
  clamped to 250–1,000 ms. Show original/sent frame counts and selected timing.
  Shared vectors cover sampling and interval boundaries.
- Document Python area averaging versus mobile nearest-neighbor resampling.
  Add offline CLI `--inspect` and `--dry-run`, package version reporting, a
  Python >=3.11 dependency setup, pure protocol tests without BLE-driver import,
  and optional same-host `SPOTLED_LOCK_FILE` coordination.
- Separate mobile preview and send actions, label native previews as static
  initial viewports, add iOS version/build and keyboard/permission guidance, and
  bound Python/Android display queues while exposing drop counts and weather age.
- Track the authorized Wetter.ax GIF set and package it reproducibly from one
  canonical asset directory. Document the owner-controlled Android signing-key
  migration; key creation and backups remain owner actions.
- Verification: 61 Python tests, 38 MQTT tests, 44 Android JVM tests, Android
  `lintDebug`/`assembleDebug`, and 26 iOS simulator unit tests pass. The iOS UI
  test reports 1 pass after checking keyboard dismissal; Xcode then stalls while
  saving the result bundle. No physical device was used.
- Scope image Rotation/Fit to the Image section and keep it from changing text.
  Add separate Landscape/Portrait CW/Portrait CCW text modes; portrait uses a
  16×64 logical canvas mapped to 64×16 wire order for a sideways-mounted sign.
- Verification for the scope correction: Android 44 JVM tests, lint and debug
  assembly pass; iOS 27 simulator unit tests pass. The iOS UI test printed a pass
  for image/text independence and portrait selection, but its xcodebuild process
  ended interrupted during result-bundle finalization. No physical validation.
- Fix the iOS text-speed protocol test's expected timing-object bytes; all eight
  current iOS simulator protocol tests pass.
- Add a session-scoped iOS BLE transfer-state helper and simulator failure
  injection for timeouts, stale callbacks, chunk-write disconnect, cancellation,
  Bluetooth power-off, and a successful subsequent transfer; all 15 simulator
  tests pass.
- Cancel superseded iOS photo loading and image/GIF rasterization work, with
  cooperative cancellation checkpoints; all 18 simulator tests pass.
- Move iOS rotation/fit into shared controls for text and images, and add a UI
  test for the rotated-text preview guidance. The complete iOS simulator suite
  now passes 19 tests.
- Add shared packet/font/rotation vectors consumed by Python, Android JVM, and
  Swift tests; all platform suites pass against the common fixtures.
- Make `glyph_data/spotled_glyphs.json` the canonical compact/native glyph source
  and generate the Python, Java, and Swift tables from it.
- Align native fallback glyphs and BMP codepoint preservation across platforms;
  reject non-BMP native text consistently.
- Show native font/text payload byte usage and the effective 154-character cap;
  show raster-frame size and disable native sends that exceed protocol limits.
- Prebuild both Python native-text payloads and check their live receive limit
  before any display-changing setup or content upload.
- Resolve the core P0 reliability gaps: NOAA-only SpotLED-MQTT runs without MQTT,
  its clock worker survives upload failures, and broker reconnect is
  asynchronous. Add iOS transfer-session tokens, cleanup on disconnect,
  cancel/background/power-off handling and peripheral quarantine after a
  disconnect timeout. Python MQTT tests pass (32); iOS app and test targets
  compile, while simulator test execution remains inconclusive. No iOS release
  artifact was created.
- Coalesce bursts of minute/weather/speed dirty events before rasterizing and
  sending a ClockTemp status snapshot. NOAA quarter-hour polling now runs inside
  the minute refresh cycle to avoid a second nearby clock redraw. The finite
  icon animation runs only on a weather-condition change; ordinary minute ticks
  use one static frame. The weather-change animation still primes its persistent
  base frame before the two-frame playback. All 36 MQTT tests pass.
- Tighten the remaining backlog: remove completed-feature descriptions from
  open tasks, retire superseded troubleshooting into history, and list pending
  validation separately from missing implementation.
- Reconcile TODO with current source and recorded validations: move 151 completed
  checklist entries into PROJECT_HISTORY, separate completed implementations
  from outstanding acceptance checks, correct stale iOS descriptions, and merge
  duplicate remaining work into one prioritized backlog. No new runtime or
  hardware validation is claimed by this documentation update.
- Bring the iOS foreground Companion closer to Android parity: persist the five
  text speeds, themes, rendering choice and selected display; add About credits,
  send-time discovery and a bounded safe connection retry; preserve native
  lowercase and match Android's full compact raster glyph table. Add bounded,
  orientation-aware ImageIO input and matching nearest-neighbor fitting, with a
  two-frame animated GIF preview/transfer at the Android-safe shared interval.
  App and simulator-test targets compile; Xcode simulator test execution is
  inconclusive because its test-session cleanup stalls. No iOS release or
  display-content validation is claimed. Background service features remain
  excluded by contract.
- Audit iOS, Android and Python/bridge differences and add a dependency-ordered
  cross-platform roadmap to TODO: transfer cleanup, service outage recovery,
  shared regression fixtures, text/rendering consistency, iOS workflow parity,
  capability-aware animation and grouped Wishlist follow-ons. Source review
  only; no runtime changes or additional hardware validation in this audit.
- Reuse the Android launcher artwork for the iOS app icon through an iOS asset
  catalog; install the development build on the paired iPhone. No iOS release
  binary was produced.

## [0.19.6] - 2026-10-18

- Add a foreground-only iOS companion source project with `SpotLED_*` discovery,
  separate text/image previews, Normal/Raster text selection, still-image fit and
  rotation, and acknowledged BLE transfers. Each developer signs and deploys
  their own iOS build; no iOS release binary is produced.
- Use the shared captured 16×16 native glyphs for iOS Normal text, and give the
  capital I serif bars with a one-pixel stem matching T and the same 12-row cap
  height across native iOS, Android, and Python output. The user confirmed the
  updated I height on iOS; Android unit tests and the targeted Python glyph test
  pass.
- Android build 30 aligns the native capital-I glyph and includes a regression
  test. All 34 release unit tests and `lintRelease` pass; the contributor APK is
  signed with the local Android debug identity. No iOS release was created.

## [0.19.5] - 2026-10-18

- Allow users to select SPOTLED Companion for Android notification forwarding,
  but forward only MQTT connection, reconnection, and topic-subscription errors.
  Suppress routine service status and repeat alerts per error category until
  reconnection.
- Normalize Companion MQTT errors to concise uppercase ASCII messages (for
  example, `MQTT CONNECTION FAILED`) for the small display.
- Request the internal signature permission required to deliver notification
  events from the listener to the foreground BLE service; add listener-to-BLE
  transfer diagnostics.
- User confirmed the MQTT connection-failure message displays on the SpotLED.
  Build 29; 33 Android tests pass and `lintRelease` passes. APK is signed with
  the local Android debug identity for contributor sideloading.

## [0.19.1] - 2026-10-18

- Prime the persistent display frame with the composed moon/weather icons before
  playing the sampled animation, so the display returns to the new icons when
  playback finishes. Applied to Android and SpotLED-MQTT.
- Publish Android build 25 with the persistence fix. The user confirmed the fix
  works on the SpotLED from both Android and the Mac MQTT service.

## [0.19.0] - 2026-10-18

- Add sampled Wetter.ax condition-icon animation with a date-calculated moon
  phase stacked above the weather icon in Android and SpotLED-MQTT status frames.
- Credit Hank the Tank for the Wetter.ax icons, used with permission.
- Keep local weather/moon GIF assets out of Git while including them in the
  contributor APK.

## [0.18.0] - 2026-10-18

- Add opt-in Android notification forwarding from a user-selected app allowlist
  through the foreground display service.
- Add Android notification-access checks and explicit user controls.
