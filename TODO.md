# Prioritized TODO

## P1 — Android app polish

- [x] Add an Android-only foreground clock/weather/MQTT service with explicit start/stop controls and a persistent notification; keep it out of the planned iOS app scope.
- [x] Add NOAA/NWS station and Home Assistant retained-weather choices, optional indoor-temperature polling, MQTT Scroll alerts, and a Home Assistant-only speed bar.
- [x] Allow NOAA clock/weather to run with every MQTT field blank; keep broker/weather-topic requirements for Home Assistant mode and make AWTRIX alerts optional.
- [x] Validate Android MQTT notification receipt after matching TLS to the broker's plain MQTT port (1883).
- [ ] Validate service start/stop, weather source selection, and periodic clock display on the Pixel/SpotLED.
- [x] Add opt-in Android notification forwarding from a user-selected app allowlist through the foreground service's Scroll alert path; check and request Android Notification Access.
- [x] On the Pixel, grant Notification Access, select an app, and validate phone-notification forwarding.

- [x] Use the supplied SpotLED artwork as the launcher icon for adaptive and legacy Android launchers.
- [x] Add persisted System/Light/Dark themes and a gear menu with Settings and About.
- [x] Add a persisted native/raster text switch; keep native text as the default and require raster for rotation.
- [x] Remember the last selected Android display without maintaining an idle BLE connection; prompt for discovery when a send has no remembered target.
- [x] Build and install v0.14.0 on the Pixel 6a; ADB screenshots confirmed the branded title, gear, and dark settings panel.
- [x] Rename the in-app title to “SPOTLED Companion”.
- [x] User confirmed the launcher icon, explicit theme choices, raster-mode option, About placement, and branded app title on the Pixel 6a.
- [x] Persist the last selected Android display; if none is remembered, prompt to scan and continue the pending send after selection without holding an idle BLE connection.
- [x] User verified on the Pixel that the display is remembered across app restarts and that sends with no remembered display prompt for discovery; tested with both text and images.
- [x] Persist image rotation and fit settings across system Photo Picker activity returns.
- [x] Add Slow/Normal/Fast native-text timing and encode the selected 100/200/300 ms value in CharacterString.
- [x] Stop SpotLED-MQTT and send `WWW.HYBRID.NET` / Scroll left with Slow (300 ms); BLE transfer was acknowledged.
- [x] User confirmed the Slow (300 ms) setting makes the message move slowly.
- [x] Send the same text/effect at Fast (100 ms); user confirmed it is about 3× faster than Slow.
- [x] Send `WWW.HYBRID.NET` / Scroll left in raster mode at Fast (100 ms); BLE transfer was acknowledged.
- [x] Send the same raster text/effect at Slow (300 ms); BLE transfer was acknowledged.
- [x] User confirmed raster Slow matches native Slow.
- [x] User confirmed Fast raster is faster than Normal raster but slower than Fast native.
- [x] Add 150 ms and 250 ms text-speed presets to the shared Python library, MQTT setting, CLI, and Android selector.

## P1 — Validate Android native marquee text

- [x] Route Scroll up/down/left/right and Scroll through native WordStock/CharacterString transfers at 0° so the full message is sent without image-fit cropping.
- [x] Preserve selected solid/rainbow character colors and add packet, setup-command, and effect-routing tests.
- [x] Route all 0° text effects through the native text engine and render the same captured 16×16 glyphs in the preview; retain raster text only for rotated output.
- [x] Install the updated v0.13.0 APK on the Pixel 6a.
- [x] User confirmed `WWW.HYBRID.NET` with Scroll left behaves as expected, including improved W/N glyphs.
- [ ] Decide whether to add native font overrides for unsupported/non-ASCII characters after validating the standard ASCII marquee path.

## P1 — Configure and validate SpotLED-MQTT

- [x] Configure an anonymous MQTT broker and confirm the configured retained weather topic is reachable from the service host.
- [x] Confirm the service host can discover a `SpotLED_*` display and send the current weather reading.
- [x] Receive an AWTRIX notification payload, transfer it, and return to the cached weather reading after its duration; user physically confirmed notification behavior.
- [x] Confirm the user-preferred explicit Scroll-left effect displays as scrolling on the SpotLED.
- [x] User confirmed the temperature refreshed after 15 minutes.
- [x] User confirmed `SPOTLED_TEXT_SPEED` applies to both MQTT notifications and periodic weather text.
- [x] Make MQTT alerts use the explicit Scroll effect (mode 6) by default for a more prominent visual alert; retain configurable effects and alert lifecycle.
- [x] Add `--weather-source noaa|homeassistant`; NOAA reads its station from `NOAA_WEATHER_STATION`, while Home Assistant uses retained `MQTT_WEATHER_TOPIC` updates.
- [x] Keep the speed bar enabled only with the Home Assistant source; NOAA mode omits it.
- [ ] Set the NOAA station identifier and validate NOAA observations plus the raster status display on-device.
- [ ] Validate the Home Assistant retained weather source and speed bar on-device.
- [x] Scrub machine-specific broker/topic/device/readout values from GitHub-facing documentation and require MQTT/device settings through environment variables.
- [x] Keep SpotLED-MQTT manually started to avoid contention with the companion app; do not configure auto-start.
- [ ] Decide whether unsupported AWTRIX icon/sound behavior needs an alternate SpotLED approximation.

## P1 — Validate Android animated GIF transfer

- [x] Install the updated APK and send an animated GIF from the Photo Picker to a SpotLED; user confirmed Android GIF animation.
- [x] User confirmed Android GIF handling works the same as the CLI, including its safe pacing and frame transforms.
- [x] Animate the GIF preview using the same sampled, transformed frames and safe interval as the display transfer.
- [x] User confirmed the animated GIF preview works on the Android phone.

## P1 — Sync the current Python sender to the collaborator

- [ ] Provide the updated `SPOTLED-v3.py` (and `requirements.txt`) to the collaborator. Their successful transfer output reported `interval=80ms`, which is impossible with the current 250 ms minimum clamp; confirm their next run reports at least 250 ms/frame. The `zsh: command not found` numeric fragments in the screenshot appear to be unrelated pasted terminal text.

## P0 — Verify rotation controls

- [x] Confirm corrected one-frame RGB image renders on the physical device.
- [x] Confirm red/green/blue/white corner mapping, magenta center, yellow top edge, and cyan left edge.
- [x] Verify RGB encoding and top-left/row-major pixel orientation with the physical pattern.
- [x] Verify 180° rotation with screen-flip state 3; restore normal state 0.
- [x] Test horizontal mode 1; verified left-right reflection across the vertical axis.
- [x] Test horizontal mode 2; verified top-bottom reflection across the horizontal axis.
- [x] Test state 0x10 on the current and newly uploaded pattern; neither visibly rotated. Dimensions remained 64 × 16; restored state 0.
- [x] Test vertical-screen modes 0x11–0x13 with fresh pattern uploads: observed 180° rotation in 0x11/0x13 and horizontal-axis reflection in 0x12; no quarter-turn.
- [x] Restore normal mode 0 and retransmit the baseline test pattern.
- [x] Implement and unit-test pure 0°/90°/180°/270° row-major RGB transforms; quarter-turns correctly swap image dimensions.
- [x] Validate a physical 90° clockwise transform using a portrait source and asymmetric markers.
- [x] Validate physical 270° clockwise rotation with the same portrait source pattern.
- [x] Restore normal screen mode 0 and the baseline 0° pattern.
- [x] Implement selectable `contain` (default), `cover`, and `stretch` policies; physically compare them on rotated image input.
- [x] Connect image decoding, rotation, fit, preview output, and acknowledged image transfer to the CLI; physically verify a 90° file-based upload.
- [x] Keep `contain` as the Python default because it avoids crop/distortion; phone UI should expose all fit choices and preview them.
- [x] Validate file-based 180° image rotation through the CLI; user confirmed the two-axis flip.
- [x] Validate file-based 270° image rotation through the CLI; user confirmed expected markers.
- [x] Validate file-based 0° image upload through the CLI; user confirmed the original orientation.
- [x] Validate file-based 90° image upload after live capability preflight; user confirmed expected marker layout.

## P1 — Make the Python reference dependable

- [x] Create installable `spotled_common` settings/API/sender modules shared by the CLI and MQTT bridge; keep `SPOTLED-v3.py` as a compatibility entry point.
- [x] Install the common package from the MQTT app's requirements and remove dynamic loading of the hyphenated script.
- [x] Add Android-compatible `--text-mode native|raster` and Slow/Normal/Fast timing options to the Python sender.
- [x] Expose matching `SPOTLED_TEXT_MODE` and `SPOTLED_TEXT_SPEED` configuration in SpotLED-MQTT.
- [x] User physically tested Python native/raster scroll speed; MQTT's `SPOTLED_TEXT_SPEED` applies to notifications and periodic weather text.
- [ ] Physically compare MQTT raster mode against native/Android if the raster MQTT setting will be used.

- [x] Parse screen-information response and start/end acknowledgments in image path.
- [x] Audit Android response/status flow: send-end ACK confirms transfer status, not render completion. A recipient-status command class exists but is not called by the decompiled app; no render-complete signal is evidenced. Continue to require visual confirmation for render claims.
- [x] Default Python discovery to the `SpotLED_*` prefix, prompt when multiple displays match, and retain exact-name override with `--device-name`.
- [x] Physically validate slow, localized outlined-rectangle animation (two positions, 1,000 ms/frame); no rapid full-frame flashing tests.
- [x] Discover the tested device limits (64×16, RGB type 0xFF, 20 frames, 160 text chars, 7,280-byte receive capacity).
- [x] Query and use live screen width, height, and color type before image transfer.
- [x] Query/use live frame, text, storage, and receive limits for image transfer guardrails.
- [x] Query/use live frame, text, storage, and receive limits in the text and image paths.
- [x] Require transfer start/end acknowledgments for font/text payloads; verify the `A` path still renders.
- [ ] Use the visible-pixel/shape mask. Android requests object `0x10` and decodes an MSB-first width×height mask. Python now parses the documented response structure and has a read-only diagnostic, but two hardware queries got no map object despite transfer acknowledgments; mask-aware rasterization remains blocked on obtaining a valid device response.
- [x] Replace opaque initializer literals with named builders and verify they match the prior working packet bytes.
- [x] Remove repeated automatic device-control toggles from text send after source audit; keep control builder for explicit operations.
- [x] Add image input path: decode with EXIF/alpha handling, rotate, selectable fit, preview, capability queries, and 24-bit RGB transfer.
- [x] Validate asymmetric corner markers and RGB behavior physically; observations are in `PROJECT_HISTORY.md`.

## P2 — Expand content and interaction support

- [x] Verify global screen-flip mode 3 rotates native text 180°; it also moves the glyph to the opposite side of the display.
- [x] Add rasterized text rotation using captured/fallback 16×16 glyphs; physically verify 90° `A` against its preview.
- [x] Physically verify raster-text 270° `A` in normal screen mode `0`.
- [x] Physically verify raster-text 180° `A` in normal screen mode `0`.
- [x] Verify two-character `AB` at 90°/`contain`; user reports it is readable.
- [x] Add an independent 3×5 ASCII raster font; AWTRIX NG was used only as a dimension/readability reference because its BDF is under a noncommercial license.
- [x] Add whole-line versus glyph-by-glyph raster text layout options.
- [x] Verify 3×5 `HELLO` glyph-layout is readable at 90° and 270°.
- [x] Visually confirm the static 3×5 `!?+-=()@` punctuation sample on the device.
- [x] Expand the independent compact font to common printable-ASCII punctuation; unsupported BMP glyphs visibly substitute `?`.
- [x] Define/test Unicode policy: BMP characters without a glyph substitute `?`; non-BMP characters and unpaired surrogate code points are rejected until the protocol's UTF-16 glyph semantics are verified.
- [x] Validate multi-character native text (`HELLO`) with captured glyphs on hardware.
- [x] Validate native text color and a scroll effect (`HELLO`, red, scroll) on hardware.
- [x] Implement GIF/multi-frame loading, shared-delay animation packets, and receive-capacity guardrails; retain every frame that fits, otherwise evenly sample the largest frame count that fits. Per-frame timing is not representable in the observed protocol. A slow two-frame outline animation was visually confirmed; avoid rapid/full-screen flashing.
- [x] Physically validate Python GIF sending with `gators.gif`: SpotLED visibly animated the sampled two-frame transfer at 250 ms/frame. User noted rendering differs slightly from the phone GIF, expected with two-frame sampling and 64×16 rasterization.
- [x] Centralize BLE discovery/connection, allow `--device-name`, and provide contextual discovery/connection/query/transfer timeout errors. Do not automatically retry transfers after a possible payload acceptance.
- [x] Retry read-only screen/capability queries once after timeout; never retry rejected queries or content transfers automatically.
- [x] Add two bounded discovery/connection attempts with a fresh client on retry; keep post-payload retries disabled and explicit.
- [x] Align Python text with Android additions: shared 3×5 glyphs, solid/rainbow per-character color support, and raster-frame effect IDs.
- [x] Allow explicit 3×5 raster text at 0° via `--raster-font 3x5`; native CharacterString text remains the default when the option is omitted.
- [ ] Continue replacing protocol constants only when verified from the Android reference or physical captures.

## P3 — Mobile applications

- [x] Create the Android project and first vertical slice: text/image input, rotation, preview, BLE discovery/connection, and single-frame RGB transfer.
- [x] Port the RGB frame-animation wrapper and start/end notification packets; verify builders with Android JVM tests against the Python reference vectors.
- [x] Add tested row-major rotation plus `contain`, `cover`, and `stretch` choices; retry Android writes only when GATT explicitly reports status 201 (busy).
- [x] Build and install/launch the debug app on the selected Pixel 6a.
- [x] Build and verify a signed internal-sideload release APK for contributor review; it uses the local debug signer, not a production key.
- [x] Publish v0.15.0 with the animated GIF preview and shared Python library changes.
- [x] Publish v0.16.0 with remembered-display selection and send-time discovery prompts.
- [x] Publish v0.16.1 with Photo Picker rotation/fit persistence.
- [x] Publish v0.16.2 with 150 ms and 250 ms speed presets across Android and Python apps.
- [x] Create `plughie/SpotLED-Suite`, push the project and signed v0.12.0 `SpotLED-Companion.apk` release, scrub installation-specific values from history, and make the repository public.
- [x] Add the project attributions to the Android About panel and publish the updated v0.12.1 companion APK.
- [x] User confirms the About panel and attributions on the Android phone.
- [ ] Create/back up an owner-controlled release signing key before public distribution or handing off compatible app updates.
- [x] User grants Nearby Devices permission and validates SpotLED BLE discovery/GATT connection in the Android app.
- [x] User confirms Android text and 0° full-screen image work; 90° image works with `stretch` after GATT busy-write handling.
- [x] Re-ran 90° Android rotation with persisted picker state; user confirmed the transformed marker layout.
- [x] User confirmed Android 180° rotation using the asymmetric test PNG and Contain.
- [x] User confirmed Android 270° rotation using the asymmetric test PNG and Contain.
- [x] User confirmed 0°/Cover fills the panel with cyan left, magenta center, and yellow right, cropping the portrait frame.
- [x] User confirmed 0°/Stretch fills the panel as expected; Contain, Cover, and Stretch fit behaviors are now physically compared.
- [x] Create and upload the asymmetric rotation/fit PNG; user confirmed the 0°/Contain baseline.
- [x] Replace Android system-font rasterization with the tested independent Python 3×5 glyph table, including B, ASCII case folding, punctuation, and `?` fallback.
- [ ] Compare Android B on the physical panel against the user's reference photo and adjust only if it still differs.
- [x] Route image selection through Android's system Photo Picker on API 33+; retain the document picker as a compatibility fallback.
- [ ] User validates that image selection returns to SPOTLED and previews/sends the selected image without opening an associated app.
- [x] Add the eight APK-verified Android text effects: Slide, Scroll up/down/left/right, Snow, Scroll, Laser.
- [x] Add Android text colors: white, red, orange, yellow, green, cyan, blue, magenta, and a static per-character rainbow gradient.
- [ ] Find a firmware-safe representation for text-effect None. Omitting `SpecialEffectsObject` caused a flashing loading icon; the app no longer sends that encoding.
- [x] Broaden Android discovery to `SpotLED_*` and show a chooser when multiple matching displays are found, avoiding silent connection to the wrong unit.
- [x] Show the Android chooser 1.2s after the first match; continue scanning at 3s intervals for up to 15s if no result appears.
- [x] Hold no GATT connection while idle: device selection stores the target; each send connects, transfers, and disconnects in `finally`.
- [x] Add one bounded fresh-GATT retry after failed initial BLE connection and clear stale characteristics on reconnect.
- [ ] User revalidates connection/text send after Pixel logcat showed GATT status 133 before transfer began.
- [ ] Physically validate Android text effects on the SpotLED device; the command IDs are source-verified but raster-text animation behavior is not yet hardware-confirmed.
- [ ] Visually validate solid and rainbow text colors on the SpotLED device.
- [ ] Freeze shared protocol test vectors and transfer behavior for cross-platform parity.
- [ ] Implement the corresponding iPhone app with Bluetooth discovery, permissions, connection lifecycle, content preview, image/text rotation, and transfer feedback.
- [ ] Validate both apps against the same hardware test patterns and text cases.

## Wishlist — defer until current priorities are complete

Ideas from the colleague's `sign-ideas` notes. The app icon, appearance themes, native/raster switch, and text-speed setting are already implemented and are not repeated here.

- Add a timed push/schedule mode for refreshing and displaying updates such as time and weather.
- Read a user-selected `.txt` file as a playlist, with per-line text, color, and display duration.
- Follow a changing text/log file and display its head or tail, with a configurable line count and scroll speed.
- Add optional background delivery for scheduled updates using a user-visible, controllable foreground mode; avoid an indefinite hidden wake lock and preserve the current manual MQTT start/stop behavior.
- Add persistent status-pixel indicators for weather freshness, MQTT state, and network/ping reachability; define how indicators compose with incoming content.
- Register as an Android Share target for images and text.
- Offer a small authenticated HTTPS control endpoint for local apps and agents, with explicit access controls and no unauthenticated commands.
- Add optional NOAA station discovery by ZIP/postal code so users do not need to look up a station ID manually.
- Explore a two-line text layout and a modular screen split for persistent time/weather plus a scrolling-message area, within the 64×16 panel limits.
- Add image crop/viewport selection to choose which region is fitted to the display.
- Use original or permissively licensed pixel-art assets; do not copy AWTRIX artwork without compatible reuse rights.
- Explore a localized, non-flashing attention animation (such as a line or outline). Full-panel white on/off strobing remains out of scope because rapid full-frame flashing is not acceptable.
