# Changelog

Notable project changes are recorded here. Older implementation and validation
notes are preserved in [PROJECT_HISTORY.md](PROJECT_HISTORY.md).

## [Unreleased]

- Reworked the top-level README to focus on the project overview, installation,
  and attributions; ongoing work notes stay in the changelog, history, and TODO.

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
