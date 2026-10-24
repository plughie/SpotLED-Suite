# SpotLED Suite

SpotLED Suite is a set of tools for sending text, images, and animated GIFs to a
64×16 SpotLED display. It includes a Python command-line sender, the Android
SpotLED Companion app, and SpotLED-MQTT, a service that displays MQTT alerts and
clock/weather information. Android also supports an optional foreground
clock/weather/MQTT service. The iOS companion supports foreground text and still
or animated image transfers; MQTT, clock/weather and background notifications
remain Android-only by contract. Each iOS contributor signs and deploys their
own local build.

Native text preserves captured panel glyphs across platforms. Uncaptured BMP
characters use the shared compact 3×5 fallback scaled to 16×16 while retaining
their codepoint; non-BMP native text is rejected consistently. Raster text
substitutes unsupported characters with `?`.

## Installation

### Python sender

Install Python 3, then clone the repository and install its dependencies:

```sh
git clone https://github.com/plughie/SpotLED-Suite.git
cd SpotLED-Suite
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -e .
```

Send text to a nearby display (the sender scans for `SpotLED_*`):

```sh
python SPOTLED-v3.py "Hello" white left
```

Text orientation is selected with `--text-orientation landscape|portrait`;
Portrait turns native glyphs 270° clockwise and preserves firmware scrolling.
Image viewport framing supports `--zoom 1..4`, `--pan-x -1..1`, and
`--pan-y -1..1`; zero centers the crop. Still-image `--vertical-scroll` loops
through tall 64-pixel-wide content using successive 16-row viewports and the
display's Scroll Up transition. It keeps BLE connected while running; press
Ctrl-C to stop. `--rotation` remains image-only and supports 0°, 90°, 180° and
270°.

Send a still image or animated GIF:

```sh
python SPOTLED-v3.py --image photo.jpg --rotation 0 --fit contain
```

See [SpotLED-MQTT/README.md](SpotLED-MQTT/README.md) for MQTT setup and service
instructions, and [android/README.md](android/README.md) for Android setup,
permissions, and build details.

To coordinate Python CLI and SpotLED-MQTT processes on one host, optionally set
`SPOTLED_LOCK_FILE` to a shared lock-file path before running either program.
This advisory lock does not coordinate Android/iOS or other hosts; stop the
Android background display service before manual control. Android Settings
explains the service-to-manual handoff.

GIF disposal/sampling/timing and intentional Python-versus-mobile resampling
differences are specified in [docs/image-processing.md](docs/image-processing.md).
Weather/moon source assets and attribution are in
[assets/weather-icons](assets/weather-icons/ATTRIBUTION.md); Gradle copies those
tracked GIFs into the APK automatically.

Run all Python tests with `python -m unittest discover -p 'test_*.py'`. Focused
protocol/GIF tests do not import BLE drivers:
`python -m unittest test_spotled_protocol.PureProtocolTests`. Install development
dependencies with `python -m pip install -r requirements-dev.txt`; tested direct
dependency versions are recorded in `requirements-tested.txt`.

For offline inspection, use `python SPOTLED-v3.py --image photo.gif --inspect`.
To rasterize and size-check without connecting or sending, use
`python SPOTLED-v3.py --image photo.gif --dry-run --preview preview.png`. The
offline estimate assumes a 64×16 display and 7,280-byte receive capacity; only a
live send can query the actual display. `--version` reports the installed
SpotLED Suite Python package version.

### Shared glyph data

The canonical compact 3×5 glyphs and captured native panel-glyph overrides are
in `glyph_data/spotled_glyphs.json`. After editing that source, regenerate the
Python, Android, and iOS tables with `python3 tools/generate_glyph_tables.py`;
run `python3 tools/generate_glyph_tables.py --check` to detect stale generated
files. The Python, Android JVM, and iOS tests all compare their tables with this
shared source.

### SpotLED-MQTT service

The service needs BLE radio range to the display. MQTT network access is needed
only for Home Assistant weather or optional AWTRIX alerts; NOAA-only mode can
run without a broker. Create its virtual environment and local configuration:

```sh
cd SpotLED-Suite/SpotLED-MQTT
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
cp env.sh.example env.sh
```

Edit `env.sh` with your broker, topic, and display settings, then start the
service manually with `python spotled_mqtt.py --weather-source noaa` or
`python spotled_mqtt.py --weather-source homeassistant`. The ignored `env.sh`
stays local and is not committed. For NOAA-only clock/weather, `MQTT_HOST` and
notification topic may remain blank; Home Assistant mode needs a broker and
weather topic. See the [MQTT guide](SpotLED-MQTT/README.md) for all options.

### Android Companion

Download and sideload the latest [SpotLED-Companion APK
(v0.19.8)](https://github.com/plughie/SpotLED-Suite/releases/download/v0.19.8/SpotLED-Companion.apk).
The APK is signed for contributor sideloading, not production distribution via
an app store.

## Current work state

Android 0.19.8 (versionCode 32) is the latest contributor APK. Current source
validation passes Python (67 tests), MQTT (38 tests), and Android (48 JVM tests,
lint and release build). Portrait text has only Landscape/Portrait choices;
Portrait turns native glyphs 270°
clockwise and retains native marquee scrolling. The user confirmed the glyph
direction on iOS. Full-scroll behavior and Android/Python physical confirmation
remain open, along with live capability-query checks and physical image-scroll
validation; see [TODO.md](TODO.md).

Android image/GIF decoding runs on a cancellable worker, with limits on encoded
input/decode size and all EXIF orientations applied. Android and iOS text
previews update live and Send text sends the current preview immediately; native
previews show the initial viewport. Images/GIFs keep separate preview and send
actions. GIF timing, resampling, and disposal details are in
[docs/image-processing.md](docs/image-processing.md).
The Android foreground service and Python bridge expose bounded queue/drop
counts and weather freshness. Android release APKs use a local debug signing
identity for contributor sideloading, not production distribution. Owner-key
migration guidance is in [docs/android-signing-migration.md](docs/android-signing-migration.md).

The current iOS development build (0.19.7, build 31) is installed on the paired
iPhone. This is not an iOS release; contributors still build and sign their own
app.

## Attributions

SpotLED Suite is a [Bug-Mag.net](https://bug-mag.net) project by **DuvTheDove**,
with contributions from **clavo** and **OpenAI Codex**. The Wetter.ax weather
and moon icons are by **Hank the Tank**, used with permission; see the
[original flow](https://flows.blueforcer.de/flow/fGEhP0cZZd6E#install).

NOAA/National Weather Service observations are retrieved from the
[NWS API](https://www.weather.gov/documentation/services-web-api). More setup
and source details are documented in the MQTT README.

## Project documentation

- [Python sender](SPOTLED-v3.py)
- [Android Companion](android/README.md)
- [iOS Companion](ios/README.md)
- [SpotLED-MQTT](SpotLED-MQTT/README.md)
- [Changelog](CHANGELOG.md)
- [Prioritized TODO](TODO.md)
