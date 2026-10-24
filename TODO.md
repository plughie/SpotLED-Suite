# Prioritized TODO

Reviewed against source and history on 2026-10-19. This is only the remaining
backlog. Completed work and superseded troubleshooting are recorded in
[PROJECT_HISTORY.md](PROJECT_HISTORY.md). Pending validation does not mean a
feature still needs implementation.

## P0 — Reliability fixes

No open P0 items.

## P1 — Remaining foreground and protocol gaps

- [x] Add live capability queries to Android/iOS with safe handling of unsupported
  dimensions, color formats, frame counts, and smaller receive buffers. Protocol
  validation passes on Android JVM and iOS simulator; physical BLE response
  behavior still needs confirmation during normal use.
- [x] Move Android image/GIF decoding and panel rasterization off the UI thread;
  bound encoded files to 50 MB and decoded canvases to 4 MP, sample large stills
  before decoding, and apply EXIF orientations including mirrored variants. 44
  JVM tests and Android lint pass; physical image/GIF regression validation
  remains in the pending-validation section.

## P2 — Enhancements and packaging

- [x] Define shared GIF disposal/transparency, endpoint sampling and bounded
  timing; share fixture vectors across Python, Android, and iOS and report source
  versus sent frame counts and interval.
- [x] Document Python area-average versus mobile nearest-neighbor resampling and
  explain that final geometry is shared but fine image pixels may differ.
- [x] Bound Python incoming/pending alerts and Android's display executor/alert
  queues; expose drops, queue depths and weather freshness. Keep clock refreshes
  coalesced to the latest composed snapshot.
- [x] Add explicit Android service-to-manual handoff guidance and optional
  `SPOTLED_LOCK_FILE` coordination among Python processes on one host. The UI and
  docs explicitly state that this does not reserve the display across phones.
- [x] Add Python offline `--inspect` and `--dry-run`, and distinct phone preview
  and send actions.
- [x] Label native phone previews as the static initial viewport, not firmware
  marquee playback.
- [x] Add iOS version/build identification, keyboard dismissal, VoiceOver labels,
  and actionable Bluetooth-permission guidance.
- [x] Align Python package version diagnostics/dependencies and add focused pure
  protocol/GIF tests that import without BLE drivers.
- [x] Document the Android signing-key migration path and backup safeguards.
- [x] Track authorized Wetter.ax GIF source assets and make Android APK asset
  copying reproducible from that canonical set.
- [x] Add 1–4× zoom and normalized pan controls on Android/iOS plus matching
  Python transforms; preview and send use the same 64×16 crop.
- [x] Add a bounded still-image vertical loop that streams adjacent 16-row views
  with Scroll Up at 250 ms/step and supports cancellation. Animated GIFs remain
  on the existing bounded animation path.

## Pending owner action

- [ ] Create and securely back up the owner-controlled Android signing key.
  Key creation and backup confirmation are intentionally not automated; current
  contributor-signed installations cannot update in place after key migration.

## Pending validation — implemented features, not development tasks

- [x] User confirms Android image regression checks pass: separate Preview/Send,
  mirrored EXIF and large-image handling, and the source/sent GIF-frame/timing
  summary.
- [ ] On iOS, verify the revised Send labels, version/build About panel and
  keyboard-dismiss action in the contributor's signed app.
- [x] User confirms iOS Portrait letters are correctly oriented with the 270°
  clockwise transform.
- [ ] Validate full-message Scroll Left without clipping on iOS; confirm the
  preview shows the initial viewport and image Rotation/Fit does not affect text.
- [ ] Validate Landscape/Portrait orientation and Scroll Left on Android and
  Python, including complete-string scrolling without clipping.
- [ ] Confirm Android/iOS live-capability queries against a physical SpotLED;
  unit tests cover response parsing and incompatible/undersized-device rejection.
- [ ] Confirm the iOS text/image/GIF transfer matrix, speed choices and target
  restore/pending-send behavior. Keep the existing icon and capital-I-height
  confirmations; do not repeat them as open tasks.
- [x] User reports manual iOS lifecycle checks passed: unavailable-display
  timeout/recovery, Bluetooth/background interruption, cancellation and a
  successful subsequent send. Late-callback and disconnect/write race injection
  remain in the automated P0 test item above.
- [x] User confirms the image regression test run passed; no image bugs were
  reported. Android EXIF mirroring and previously confirmed rotation, fit and GIF
  behavior remain part of the validated image path.
- [ ] Resolve intermittent panel-refresh discrepancy: service logs contain one
  composed update at 21:39, 21:42, and 21:45; user observed two visible refreshes
  at 21:39 and one at 21:42. On shutdown, buffered output flushed repeated pairs
  of static-frame and two-frame-animation acknowledgments; their exact minute
  association is unknown. The 21:45 panel result was not observed. Determine
  when icon-animation uploads are triggered and confirm routine updates do not
  cause adjacent duplicate redraws.
- [ ] Complete only the still-unconfirmed Android effects/palette cases and,
  if used, MQTT raster-mode comparison.
- [ ] Review iOS Dynamic Type, VoiceOver and Bluetooth-denied behavior.
- [ ] Verify g/j/p/q/y descenders in the Android preview and on the physical
  display; the latest actual iPhone screenshot shows qgyj reaching row 15, which
  the user thinks will work. Hardware rendering remains untested.
- [x] Capture a separate temporary `gUq` preview for comparison without
  changing the saved `gUg` text; the `q` descender is visible in that screenshot.
- [ ] Validate pinch/drag crop behavior and transformed preview/send parity on
  Android and iOS, plus the equivalent Python viewport flags.
- [ ] Have the colleague validate full vertical scrolling and loop wrap on a
  tall still image from the Android APK; confirm Stop cancels cleanly without
  rapid flashing. Validate iOS/Python scrolling on hardware if used.

## Blocked protocol research

- [ ] Obtain a valid visible-pixel/shape-mask response or capture before enabling
  mask-aware rendering.
- [ ] Establish a firmware-safe None effect before restoring that option; the
  earlier omitted-effect upload triggered the loading icon.

## Wishlist — after the priorities above

- [ ] Add Android Share intents and iOS foreground import with saved content
  presets; design any iOS extension separately.
- [ ] Add user-defined schedules, text-file playlists and log following to
  Python/Android.
- [ ] Add weather/MQTT/network status pixels with alert-composition rules.
- [ ] Add two-line text and modular screen partitions.
- [ ] Add an authenticated HTTPS control endpoint.
- [ ] Add optional NOAA station discovery by ZIP/postal code.
- [ ] Decide how to handle unsupported AWTRIX icon/sound requests.
- [ ] Explore a localized line/outline attention animation.

## Standing constraints

No iOS release binaries or background clock/weather/MQTT/notification service.
Keep developer signing local, Normal/Raster on the main iOS Text screen, Mac
startup manual, and project/artwork attributions intact. No rapid full-panel
flashing or automatic replay of possibly accepted payloads. Verify protocol
changes with evidence and confirm each controlled physical test with the user.
