# SpotLED Suite

SpotLED Suite is a set of tools for sending text, images, and animated GIFs to a
64×16 SpotLED display. It includes a Python command-line sender, the Android
SpotLED Companion app, and SpotLED-MQTT, a service that displays MQTT alerts and
clock/weather information. Android also supports an optional foreground
clock/weather/MQTT service. The iOS companion supports foreground text and still
or animated image transfers; MQTT, clock/weather and background notifications
remain Android-only by contract. Each iOS contributor signs and deploys their
own local build.

## Installation

### Python sender

Install Python 3, then clone the repository and install its dependencies:

```sh
git clone https://github.com/plughie/SpotLED-Suite.git
cd SpotLED-Suite
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
```

Send text to a nearby display (the sender scans for `SpotLED_*`):

```sh
python SPOTLED-v3.py "Hello" white left
```

Send a still image or animated GIF:

```sh
python SPOTLED-v3.py --image photo.jpg --rotation 0 --fit contain
```

See [SpotLED-MQTT/README.md](SpotLED-MQTT/README.md) for MQTT setup and service
instructions, and [android/README.md](android/README.md) for Android setup,
permissions, and build details.

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
(v0.19.6)](https://github.com/plughie/SpotLED-Suite/releases/download/v0.19.6/SpotLED-Companion.apk).
The APK is signed for contributor sideloading, not production distribution via
an app store.

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
