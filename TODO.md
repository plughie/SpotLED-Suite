# Prioritized TODO

Reviewed against source and history on 2026-10-19. This is only the remaining
backlog. Completed work and superseded troubleshooting are recorded in
[PROJECT_HISTORY.md](PROJECT_HISTORY.md). Pending validation does not mean a
feature still needs implementation.

## P0 — Reliability fixes

- [ ] Add simulator failure-injection tests for iOS BLE timeout, stale callback,
  disconnect during chunk writes, explicit/background cancellation, power-off,
  and successful subsequent send. Session-scoped timers/callback guards,
  continuation cleanup, and cancellation are implemented; simulator test
  execution has not returned results.

## P1 — Remaining foreground and protocol gaps

- [ ] Move iOS rotation/fit out of the Image-only section into clearly shared
  controls, or separate text/image transform state.
- [ ] Resolve Xcode simulator test-session cleanup stalls and obtain a completed
  test run.
- [ ] Add shared language-neutral packet/font/transform fixtures and automated
  Python/JVM/Swift checks so cross-platform encoding stays aligned.
- [ ] Generate glyph tables/overrides from one source rather than copied tables.
- [ ] Resolve remaining fallback differences: Python native 5×7 versus mobile
  3×5, plus unsupported-BMP replacement/codepoint behavior.
- [ ] Show effective encoded-byte text limits, not just character counts.
- [ ] Move Python validation of both native payloads ahead of modifying setup
  and the first font upload.
- [ ] Add live capability queries to Android/iOS with safe handling of unsupported
  dimensions, color formats and smaller receive buffers.
- [ ] Cancel obsolete iOS image-decoding tasks, rather than only discarding their
  results.
- [ ] Move Android image decoding off the UI thread and audit source-size bounds
  and EXIF/mirrored-orientation handling.

## P2 — Enhancements and packaging

- [ ] Define shared GIF sampling, disposal/transparency and timing rules across
  platforms; report original/sent frame counts and timing changes.
- [ ] Decide/document the Python area-average versus mobile nearest-neighbor
  resampling policy.
- [ ] Bound Python's pending-alert deque and Android's display executor backlog;
  expose queue/drop counts and weather freshness. Python now coalesces bursts of
  clock/weather/speed dirty events into one composed status refresh.
- [ ] Add a clear manual-controller handoff and optional same-host process lock;
  do not present a local lock as cross-phone ownership.
- [ ] Add offline CLI dry-run/inspect and separate phone Preview/Send actions.
- [ ] Label native phone previews as static viewports or implement a simulated
  marquee without claiming firmware-exact playback.
- [ ] Add iOS build identification and keyboard dismissal; improve accessibility
  or permission guidance where validation finds gaps.
- [ ] Align Python package/version diagnostics and tested dependency setup;
  make pure protocol tests independent of BLE imports.
- [ ] Create/back up an owner-controlled Android signing key with an explicit
  migration plan for existing contributor-signed installations.
- [ ] Make the APK's currently ignored weather-icon assets reproducible through
  an authorized source asset set or documented import step.

## Pending validation — implemented features, not development tasks

- [ ] Confirm the iOS text/image/GIF transfer matrix, speed choices and target
  restore/pending-send behavior. Keep the existing icon and capital-I-height
  confirmations; do not repeat them as open tasks.
- [x] User reports manual iOS lifecycle checks passed: unavailable-display
  timeout/recovery, Bluetooth/background interruption, cancellation and a
  successful subsequent send. Late-callback and disconnect/write race injection
  remain in the automated P0 test item above.
- [ ] Test image/GIF edge cases across platforms: transparency, mirrored camera
  orientation, rapid reselection, odd-sized crops and variable GIF delays.
  Preserve the already-confirmed Android/Python rotation, fit and GIF results.
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
- [ ] Add crop/viewport controls, two-line text and modular screen partitions.
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
