# SpotLED-MQTT

`SpotLED-MQTT` is a long-running Python bridge. It listens to the AWTRIX-NG
notification topic and Home Assistant's retained weather topic, then sends
content to a nearby SpotLED over BLE using the tested parent-project sender.

- `MQTT_NOTIFY_TOPIC` (required): set this to the AWTRIX notification topic,
  commonly `<awtrix-prefix>/cmd/notify`. It reads notification `text`, `textColor`,
  `durationMs`, `stack`, and `hold`. Stacked notifications queue, `stack:false`
  replaces the active notification, and the weather screen returns after an
  alert expires or is dismissed.
- `MQTT_WEATHER_TOPIC` (required): set this to a retained Home Assistant weather
  topic. It reads Fahrenheit `temp`
  and Home Assistant `cond`. The retained value is shown at startup and refreshed
  every 15 minutes; MQTT weather changes update the cached value for the next
  refresh. The temperature color ramp and condition names follow `Wetter.ax`.
- Text uses the explicit `left` marquee effect by default; set
  `SPOTLED_TEXT_EFFECT` to `slide`, `up`, `down`, `left`, `right`, `snow`,
  `scroll`, or `laser` to select another tested protocol effect.
- `SPOTLED_DEVICE_NAME` (required): use the exact advertised name shown during
  display startup: `SpotLED_` followed by the four-character hexadecimal ID,
  for example `SpotLED_AB12`.

SpotLED is a text-oriented 64×16 display and does not implement AWTRIX icon or
sound assets. This bridge therefore renders notification text and its color,
but currently ignores `icon`, sound, `wakeup`, and scrolling `repeat` semantics.
The weather condition is shown as a short text label rather than a MDI icon.
AWTRIX notification lifecycle and SpotLED scrolling are similar, not pixel- or
timing-identical.

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

Replace the placeholder values with your broker address, MQTT topics, and BLE
advertised display name. The service requires these settings and does not
contain installation-specific defaults. Never put broker passwords in source
control.

```sh
export MQTT_HOST="your-broker-host"
export MQTT_PORT=1883
export MQTT_USERNAME=""
export MQTT_PASSWORD=""
export MQTT_NOTIFY_TOPIC="<awtrix-prefix>/cmd/notify"
export MQTT_WEATHER_TOPIC="<weather-state-topic>"
export SPOTLED_DEVICE_NAME="SpotLED_AB12"
export WEATHER_INTERVAL_SECONDS=900
export SPOTLED_TEXT_EFFECT=left
python spotled_mqtt.py
```

For anonymous MQTT, leave username and password empty. Set `MQTT_TLS=true` for
TLS. `WEATHER_UNIT=C` changes the weather label to Celsius; Fahrenheit is the
default, matching the Home Assistant automation. Logs go to stdout/stderr for
service-manager capture. BLE opens only while sending and closes after each
transfer.

No machine-specific broker address, MQTT topic, display identifier, or weather
reading is included in this guide. The service has been validated for MQTT
notifications, left-scroll behavior, and 15-minute weather refreshes. Start it
manually when the display should be reserved for MQTT; stop it with Ctrl-C
before using the companion app. Automatic startup is intentionally not
configured, avoiding display contention and host-specific network permissions.

## Tests

```sh
python -m unittest -v test_spotled_mqtt.py
```
