# Changelog

Notable project changes are recorded here. Older implementation and validation
notes are preserved in [PROJECT_HISTORY.md](PROJECT_HISTORY.md).

## [Unreleased]

- Reworked the top-level README to focus on the project overview, installation,
  and attributions; ongoing work notes stay in the changelog, history, and TODO.
- User confirmed Android foreground service start/stop, weather source selection,
  periodic clock updates, and highly visible AWTRIX notification scrolling.

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
