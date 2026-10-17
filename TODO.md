# Prioritized TODO

## P1 — Configure and validate SpotLED-MQTT

- [x] Configure broker `<broker-address>:1883` with anonymous access and confirm its retained `<weather-state-topic>` payload is reachable from this Mac.
- [x] Confirm this host can discover `SpotLED_AB12`; start the service and complete the retained `[weather-summary]` weather text transfer.
- [x] Receive an `<awtrix-prefix>/cmd/notify` payload, transfer it, and return to the cached weather reading after its duration; user physically confirmed notification behavior.
- [x] Confirm the user-preferred explicit Scroll-left effect displays as scrolling on the SpotLED.
- [ ] Observe at least one complete 15-minute weather refresh interval.
- [ ] Resolve macOS LaunchAgent local-network access or select another service manager/host. Current service is running in the active host session but is not configured to auto-start after logout/reboot.
- [ ] Decide whether unsupported AWTRIX icon/sound behavior needs an alternate SpotLED approximation.

## P1 — Validate Android animated GIF transfer

- [ ] Install the updated APK and send `gators.gif` from the Photo Picker to a SpotLED; confirm visible motion and no loading/error pattern. (Python path is physically validated; Android remains pending.)
- [ ] Confirm frame pacing is comfortable (minimum 250 ms/frame) and that rotation/fit controls affect both sampled frames consistently.
- [ ] Decide whether GIF preview should remain first-frame-only or gain a play/animation preview.

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
- [x] Implement GIF/multi-frame loading, frame sampling, shared-delay animation packets, and receive-capacity guardrails; per-frame timing is not representable in the observed protocol. A slow two-frame outline animation was visually confirmed; avoid rapid/full-screen flashing.
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
- [x] Build and install/launch the debug app on the selected Pixel 6a (`[ADB-device-serial]`).
- [x] Build and verify a signed internal-sideload release APK for contributor review; it uses the local debug signer, not a production key.
- [ ] Create/back up an owner-controlled release signing key before public distribution or handing off compatible app updates.
- [x] User grants Nearby Devices permission and validates SpotLED BLE discovery/GATT connection in the Android app.
- [x] User confirms Android text and 0° full-screen image work; 90° image works with `stretch` after GATT busy-write handling.
- [ ] Physically verify Android image rotations at 180° and 270°, and compare `contain`/`cover`/`stretch` as desired.
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
