# SpotLED-MQTT

`SpotLED-MQTT` is a long-running Python service that bridges AWTRIX-NG
notifications and a ClockTemp002-style clock/weather status to a nearby SpotLED
over BLE using the tested parent-project sender.

SpotLED Suite is a [Bug-Mag.net](https://bug-mag.net) project by **DuvTheDove**,
with contributions from **clavo** and **OpenAI Codex**.

- `MQTT_NOTIFY_TOPIC` (required): set this to the AWTRIX notification topic,
  commonly `<awtrix-prefix>/cmd/notify`. It reads notification `text`, `textColor`,
  `durationMs`, `stack`, and `hold`. Stacked notifications queue, `stack:false`
  replaces the active notification, and the ClockTemp screen returns after an
  alert expires or is dismissed.
- `NOAA_WEATHER_STATION` (required for the default NOAA mode): a National Weather
  Service station identifier such as `KSFO`. NOAA observations are fetched on
  startup and every quarter-hour.
- `MQTT_WEATHER_TOPIC` (required for `--weather-source homeassistant`): retained
  Home Assistant weather JSON with Fahrenheit `temp` and a `cond` string.
- The base display is a 64×16 RGB raster approximation of ClockTemp002: date/time,
  optional TC001 indoor temperature, and outdoor temperature with a bitmap
  condition icon. The clock refreshes every minute and the TC001 temperature at
  startup and once per minute. MQTT alerts remain scrolling text.
- Select the weather source at launch with `--weather-source noaa` (default) or
  `--weather-source homeassistant`. NOAA mode reads the latest observation from
  the station named by `NOAA_WEATHER_STATION`, once at startup and every quarter
  hour. NOAA mode does not show the Home Assistant speed bar. The NOAA path is
  implemented but has not yet been validated with the running service or on the
  SpotLED; colleague testing is pending.
- Home Assistant mode subscribes to `MQTT_WEATHER_TOPIC`, expecting retained
  JSON with Fahrenheit `temp` and condition string `cond`. It also enables the
  optional `MQTT_SPEED_TOPIC` throughput bar. Both settings are only used in HA mode.
- NOAA's NWS API requires an identifying `User-Agent`. SpotLED Suite sends its
  project identity and credits NOAA/National Weather Service here in the README;
  weather-source attribution is not printed on the display.
- `CLOCK_TIMEZONE` (optional): IANA timezone for clock updates; defaults to the
  service host's local timezone.
- `CLOCKTEMP_INDOOR_URL` (optional): TC001 device JSON endpoint returning a
  Celsius `temperature` field, for example the endpoint configured in ClockTemp002.
- `MQTT_SPEED_TOPIC` (optional, Home Assistant mode only): ClockTemp002
  speed-result topic; numeric payloads are included in the status bar as Mbps.
- Notifications use the explicit `scroll` effect by default for prominence; set
  `SPOTLED_TEXT_EFFECT` to `slide`, `up`, `down`, `left`, `right`, `snow`,
  `scroll`, or `laser` to select another tested protocol effect.
- `SPOTLED_TEXT_MODE` selects `native` (default) or `raster` text rendering;
  `SPOTLED_TEXT_SPEED` selects `slow` (300 ms), `250ms`, `normal` (default, 200 ms),
  `150ms`, or `fast` (100 ms). Higher intervals move more slowly. The selected speed
  applies to scrolling notifications; the ClockTemp screen is a static raster frame.
- `SPOTLED_DEVICE_NAME` (required): use the exact advertised name shown during
  display startup: `SpotLED_` followed by the four-character hexadecimal ID,
  for example `SpotLED_AB12`.

SpotLED is a text-oriented 64×16 display and does not implement AWTRIX
notification icon or sound assets. Notification text and color are rendered;
`icon`, sound, `wakeup`, and scrolling `repeat` semantics are ignored. The
ClockTemp condition glyph and speed bar are separately raster-drawn from the
ClockTemp002 layout. AWTRIX notification lifecycle and SpotLED scrolling are
similar, not pixel- or timing-identical.

## Install and run

Use a Python 3 environment on a host that has both MQTT network access and BLE
radio range to the SpotLED:

```sh
git clone https://github.com/plughie/SpotLED-Suite.git
cd SpotLED-Suite/SpotLED-MQTT
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
```

The requirements install the parent repository's editable `spotled_common`
package so the bridge uses the same sender, effect mapping, text modes, and
timing implementation as the CLI.

Create a local `env.sh` in this directory with your installation's settings.
The file is git-ignored so broker details and credentials stay out of source
control. For example:

```sh
cp env.sh.example env.sh
. ./env.sh
python spotled_mqtt.py --weather-source noaa
# Or use the retained Home Assistant weather topic and its speed bar:
python spotled_mqtt.py --weather-source homeassistant
```

Edit the copied `env.sh` with your local values before sourcing it. The example
file is generic; never put passwords or other local-only values in it.

For anonymous MQTT, leave username and password empty. Set `MQTT_TLS=true` for
TLS. `WEATHER_UNIT=C` changes both temperature labels to Celsius; Fahrenheit is
the default. NOAA weather is a latest-station observation, so update cadence and
reported conditions depend on the selected station. Logs go to stdout/stderr for
service-manager capture. BLE opens only while sending and closes after each transfer.

The NOAA weather feed is from the U.S. National Weather Service API. NOAA content
is generally public domain, and NOAA requests that its source/program be credited;
see [NOAA's credit guidance](https://www.noaa.gov/office-education/outreach-communication/faq)
and the [NWS API documentation](https://www.weather.gov/documentation/services-web-api).
No machine-specific broker address, indoor-device address, station identifier,
display identifier, or weather reading is included in this guide. MQTT notifications
were previously validated. The new NOAA path is untested, and both weather-source
paths plus the ClockTemp-style frame still need live validation in this implementation.
Start it manually
when the display should be reserved for MQTT; stop it with Ctrl-C before using
the companion app. Automatic startup is intentionally not configured, avoiding
display contention and host-specific network permissions.

## Tests

```sh
python -m unittest -v test_spotled_mqtt.py
```
