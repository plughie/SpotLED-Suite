# SpotLED Suite

SpotLED Suite combines the Python BLE reference sender, an Android companion app, and the SpotLED-MQTT notification/weather bridge. It supports text and image transfers, rotation, and slow GIF animation to SpotLED displays. An iPhone app remains a longer-term goal.

SpotLED Suite is a [Bug-Mag.net](https://bug-mag.net) project by **DuvTheDove**, with contributions from **clavo** and **OpenAI Codex**.

## Current work state

- BLE discovery matches the `SpotLED_*` advertised-name prefix; set `--device-name` when targeting one specific nearby display.
- BLE discovery/connection is centralized with actionable errors and up to two attempts; `--device-name` can override the advertised name. Read-only device queries retry once after a timeout. Transfers are not retried after an ambiguous final acknowledgment, to avoid duplicating content or restarting playback.
- Device information queried successfully: 64 × 16 pixels, color type `0xFF`, 20 maximum frames, 160 text characters, 7,280-byte receive capacity, and 16,352-byte storage field. The reported firmware field was `0x0230404A`.
- Text baseline: the sender queries live capabilities before transfer, enforces the reported character/receive limits, and rejects non-BMP characters. Native font/text transfers require start/end acknowledgments; `A` and multi-character `HELLO` render. Red `HELLO` with the native `scroll` effect was also confirmed. Geometric text offers whole-line rotation or glyph-by-glyph rotation; 3×5 `HELLO` is confirmed readable at 90° and 270°. Solid colors and a static per-character `rainbow` palette are available; raster text can encode the eight Android reference effects. The device reports a 160-character limit.
- Image path: file loading, EXIF orientation, alpha compositing, rotation, selectable fit, 64×16 preview, live capability queries, and acknowledged transfer are implemented. File-based 0°/90°/180°/270° outputs and all three fit modes were physically exercised.
- Animation path: GIF frames and delays are loaded, sampled to device limits, and sent with the protocol's shared frame interval, clamped to 250–1,000 ms/frame to avoid rapid flashing. The cyan outline (1,000 ms/frame) and `gators.gif` (two sampled frames, 250 ms/frame) visibly animated on hardware. The gator rendering differs slightly from the phone GIF because of 64×16 rasterization and two-frame sampling. Android GIF animation has now also been physically confirmed; it uses two sampled frames. Because the display's receive limit is 7,280 bytes, large RGB animations may be reduced to two frames.
- Fit tradeoffs observed: `contain` preserves proportions but the narrow rotated strip dims or loses isolated dots; `cover` crops while keeping the center as a visible 4×4 magenta square; `stretch` fills the panel but distorts shape and blends some colors. `contain` remains the Python default to avoid crop/distortion; the phone UI should expose all choices and preview them.
- Physical mapping confirmed with the asymmetric test pattern. Native screen-flip modes 1/2 mirror axes; mode 3 rotates 180°. File-based 0°/90°/180°/270° output is confirmed. Vertical modes `0x10`–`0x13` did not provide quarter-turns. Mode `3` also rotates native text globally; the last observed screen mode was `0`.
- Latest hardware validation: user confirmed the Python-sent `gators.gif` is visibly animated at 250 ms/frame; its rendering is slightly simplified by two-frame sampling. The static 3×5 punctuation sample and cyan outlined rectangle animation (1,000 ms/frame) are also confirmed; no rapid full-panel flashing was used.
- Collaborator's later terminal screenshot shows an acknowledged GIF transfer at 80 ms/frame. The current `SPOTLED-v3.py` clamps this to at least 250 ms, so that run used an older sender copy; share the current script and `requirements.txt` before further animation tests. Numeric `zsh: command not found` output appears unrelated to the BLE transfer.
- `SpotLED-MQTT/` contains a configurable long-running MQTT-to-BLE bridge. Set broker, topic, and display-name values through environment variables; documentation uses generic placeholders. It mirrors the AWTRIX temperature ramp but cannot render AWTRIX icon/sound assets. MQTT notifications, left-scroll, and 15-minute weather refresh are physically confirmed. Start it manually when the display should be reserved for MQTT; automatic startup is intentionally not configured.
- Android reference audit: the send-end response reports transfer status, not render completion. A recipient-status command exists in the APK but has no call site in the decompiled app. The app requests object `0x10` as a screen-visible-point mask; Python now has a parser and a read-only diagnostic, but the device returned no map object in two attempts despite acknowledging the request. The mask is not applied to images.
- BLE transfer acknowledgments confirm receipt, not successful rendering. Do not treat a completed write as proof of display output.
- Offline protocol, rotation, fit, response-parser, animation-limit, BLE lifecycle, retry, font, color/effect, discovery, and CLI tests: 42 passing. Run with `uv run --with bleak python -m unittest -v test_spotled_protocol.py`.
- Android companion: unrotated text uses the display's native CharacterString/WordStock engine with captured panel glyphs shared with Python; rotated text uses RGB frames for orientation control. Images, two-frame GIFs, eight effects, solid/rainbow colors, preview/fit, and `SpotLED_*` discovery are supported. The user confirmed the v0.13.0 Scroll-left text and improved W/N rendering on the SpotLED. See [android/README.md](android/README.md) for build and signer caveats.
- Download the signed Android companion from the [SpotLED-Companion v0.14.0 release](https://github.com/plughie/SpotLED-Suite/releases/download/v0.14.0/SpotLED-Companion.apk). It uses a local Android debug signing identity for internal sideloading, not a production release key; see [android/README.md](android/README.md).
- Android v0.14.0 includes the supplied launcher icon, System/Light/Dark appearance settings, a native-vs-raster text switch, and a gear menu containing Settings and About. Native text remains the default; the user confirmed these changes on the Pixel 6a.
- Unreleased Android v0.15.0 adds Slow/Normal/Fast text timing choices (300/200/100 ms) for native and raster text. The user confirmed raster Slow matches native Slow; Fast raster is faster than Normal raster but slower than Fast native.
- `rotate_rgb_pixels()` performs pure clockwise 0°/90°/180°/270° transforms. `fit_rgb_pixels()` supports `contain` (default), `cover`, and `stretch`; quarter-turns swap dimensions before fitting.
- Raster text offers captured/fallback 16×16 glyphs or an independently authored 3×5 printable-ASCII set (letters, digits, and common punctuation). Unsupported BMP characters become `?`; non-BMP characters and unpaired surrogates are rejected pending a defined multi-code-unit font model. Whole-line rotation with `contain` can shrink longer text substantially; glyph layout keeps rotated letters horizontal and fills more of the screen.
- AWTRIX NG's compact 5-row/mostly-3-pixel-wide font informed the size target, but its BDF is part of the project's PolyForm Noncommercial license; no AWTRIX glyph data has been copied.

## Run

Install Python 3 and the project dependencies, then run:

```sh
python3 -m pip install -r requirements.txt
python3 SPOTLED-v3.py "A"
```

For geometric text rotation, the sender rasterizes captured/fallback 16×16 glyphs and sends an RGB frame:

```sh
uv run --with bleak --with pillow python SPOTLED-v3.py "A" --rotation 90 --fit contain --preview rotated-text.png
```

Without geometric options, text uses the native CharacterString path and its scrolling/entrance effects. Raster text can choose `--raster-font 3x5` or `panel16` and `--text-layout whole` or `glyphs`.

To send text using the Android app's same 3×5 glyphs, static rainbow colors, and a text effect even at 0°:

```sh
uv run --with bleak python SPOTLED-v3.py "HYBRID" rainbow scroll --raster-font 3x5
```

Image input (including GIF) requires Pillow; the requirements file installs it. Example:

```sh
uv run --with bleak --with pillow python SPOTLED-v3.py --image photo.jpg --rotation 90 --fit contain --preview preview.png
```

The preview is the exact first 64×16 RGB frame sent. `contain` preserves the whole image with black padding; `cover` crops to fill the display; `stretch` fills the display while changing aspect ratio. Animated GIFs are transferred as frame animations; the protocol applies one shared interval based on sampled frames' average delay, clamped to 250–1,000 ms/frame, so timing is approximate. Prefer localized changes; avoid rapid full-screen flashing.

The sender scans for `SpotLED_*` by default and prompts if more than one matching device is found. Use `--device-name SpotLED_Example` to select a specific advertised name. Optional arguments are text, color, and animation effect, for example:

```sh
python3 SPOTLED-v3.py "HELLO" rainbow scroll
```

Supported color names and effect names are listed in the script's usage output. The device currently reports a 160-character text limit. The script's existing initialization and text flow are still being audited; avoid assuming support for arbitrary Unicode or arbitrary-length text.

## Files

- `SPOTLED-v3.py` — BLE sender, text/object encoders, and current RGB frame-animation builders.
- `SpotLED-MQTT/` — MQTT-to-SpotLED bridge, tests, configuration instructions, and service-manager deployment notes.
- `requirements.txt` — Python dependencies (`bleak` and `Pillow`).
- `test_spotled_protocol.py` — offline packet-layout and checksum regression tests.
- The locally held decompiled vendor app was used as a protocol reference and is intentionally excluded from this repository.
- `PROJECT_HISTORY.md` — dated accomplishment and experiment log.
- `TODO.md` — prioritized remaining work.

## Safety / validation note

Uploads replace the active display content. Earlier malformed frame packets caused the display to remain on a flashing triangle until power-cycled; the previously saved iPhone patterns returned after restart. The corrected image packet rendered successfully.
