# SpotLED-MQTT

`SpotLED-MQTT` is a long-running Python bridge. It listens to the AWTRIX-NG
notification topic and Home Assistant's retained weather topic, then sends
content to a nearby SpotLED over BLE using the tested parent-project sender.

- `<awtrix-prefix>/cmd/notify` (configurable): reads notification `text`, `textColor`,
  `durationMs`, `stack`, and `hold`. Stacked notifications queue, `stack:false`
  replaces the active notification, and the weather screen returns after an
  alert expires or is dismissed.
- `<weather-state-topic>` (configurable): reads Fahrenheit `temp`
  and Home Assistant `cond`. The retained value is shown at startup and refreshed
  every 15 minutes; MQTT weather changes update the cached value for the next
  refresh. The temperature color ramp and condition names follow `Wetter.ax`.
- Text uses the explicit `left` marquee effect by default; set
  `SPOTLED_TEXT_EFFECT` to `slide`, `up`, `down`, `left`, `right`, `snow`,
  `scroll`, or `laser` to select another tested protocol effect.
- Default display is `SpotLED_AB12`; change `SPOTLED_DEVICE_NAME` for another
  nearby `SpotLED_*` unit.

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
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
```

Configure the broker and topics through environment variables (never put broker
passwords in source control):

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

Current host configuration uses the anonymous broker at `<broker-address>:1883`
and `SpotLED_AB12`; no broker credentials are stored in this app. The retained
weather payload was received and its `[weather-summary]` text transfer completed. One
AWTRIX notification was also received and transferred, after which the service
restored the weather reading. Default text effect is now explicit left-scroll
(protocol effect ID 3), rather than generic Scroll (ID 6).

The service is running in the active host session. A user LaunchAgent was
attempted, but macOS denied that background process local-LAN access even though
interactive Python could reach the broker. Its failed plist was removed rather
than left as a broken auto-start entry. A proper auto-start setup still needs a
macOS app/permission wrapper or a different service host/manager. The current
process stops when its host session is ended; the user has physically confirmed
AWTRIX notification rendering, left-scroll behavior, and a temperature refresh
after 15 minutes.

## Tests

```sh
python -m unittest -v test_spotled_mqtt.py
```
