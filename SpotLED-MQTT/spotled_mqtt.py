#!/usr/bin/env python3
"""Bridge AWTRIX-NG MQTT notifications and Home Assistant weather to SpotLED."""

from __future__ import annotations

import asyncio
import colorsys
import json
import logging
import os
import signal
from collections import deque
from dataclasses import dataclass
from typing import Any

from spotled_common import send_text, text_effect_mode, text_render_mode, text_speed_ms

try:
    import paho.mqtt.client as mqtt
except ImportError:  # Keep pure parsing helpers importable for tests.
    mqtt = None

LOG = logging.getLogger("spotled_mqtt")
DISMISS = object()
WEATHER_REFRESH = object()


@dataclass(frozen=True)
class Notification:
    text: str
    color: tuple[int, int, int]
    duration_ms: int
    hold: bool = False
    stack: bool = True


@dataclass(frozen=True)
class Weather:
    fahrenheit: float
    condition: str


def parse_color(value: Any) -> tuple[int, int, int]:
    """Read AWTRIX common textColor forms; unsupported forms fall back to white."""
    try:
        if isinstance(value, str):
            raw = value.strip().lstrip("#")
            if len(raw) == 3:
                raw = "".join(ch * 2 for ch in raw)
            if len(raw) == 6:
                number = int(raw, 16)
                return ((number >> 16) & 255, (number >> 8) & 255, number & 255)
        if isinstance(value, int) and 0 <= value <= 0xFFFFFF:
            return ((value >> 16) & 255, (value >> 8) & 255, value & 255)
        if isinstance(value, (list, tuple)) and len(value) == 3:
            return tuple(max(0, min(255, round(float(v)))) for v in value)
        if (isinstance(value, (list, tuple)) and len(value) == 4
                and str(value[0]).upper() == "HSV"):
            hue = (float(value[1]) % 360) / 360
            sat = max(0.0, min(100.0, float(value[2]))) / 100
            val = max(0.0, min(100.0, float(value[3]))) / 100
            return tuple(round(component * 255) for component in colorsys.hsv_to_rgb(hue, sat, val))
    except (TypeError, ValueError, OverflowError):
        pass
    return (255, 255, 255)


def _notification_text(value: Any) -> str:
    if isinstance(value, str):
        text = value
    elif isinstance(value, list):
        parts = []
        for item in value:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict) and isinstance(item.get("text"), str):
                parts.append(item["text"])
        text = "".join(parts)
    else:
        return ""
    # The current SpotLED text path is BMP-only and supports at most 160 units.
    text = "".join(ch if ord(ch) <= 0xFFFF and not 0xD800 <= ord(ch) <= 0xDFFF else "?"
                   for ch in text)
    return text[:160]


def parse_notification(payload: bytes | str) -> Notification | None:
    try:
        data = json.loads(payload)
    except (TypeError, UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(data, dict):
        return None
    text = _notification_text(data.get("text"))
    if not text.strip():
        return None
    try:
        duration = int(data.get("durationMs", 7000))
    except (TypeError, ValueError, OverflowError):
        duration = 7000
    duration = min(600_000, duration if duration > 0 else 7000)
    return Notification(
        text=text,
        color=parse_color(data.get("textColor")),
        duration_ms=duration,
        hold=data.get("hold") is True,
        stack=data.get("stack", True) is not False,
    )


def parse_weather(payload: bytes | str) -> Weather | None:
    try:
        data = json.loads(payload)
        if not isinstance(data, dict):
            return None
        temperature = float(data["temp"])
        condition = data.get("cond", "")
        if not condition:
            condition = "unknown"
        if not isinstance(condition, str):
            return None
        if not (-150 <= temperature <= 150):
            return None
        return Weather(temperature, condition.strip().lower() or "unknown")
    except (TypeError, ValueError, KeyError, UnicodeDecodeError, json.JSONDecodeError):
        return None


def weather_color(fahrenheit: float) -> tuple[int, int, int]:
    """Port Wetter.ax's Celsius temperature color ramp."""
    stops = [
        (-12, 0xD977DF), (-6, 0x9545BC), (-1, 0x4B379C), (0, 0xFEC4FF),
        (4, 0x31B8DB), (10, 0x31DB8B), (15, 0x6ED228), (21, 0xFFFF28),
        (27, 0xF87E27), (32, 0xCF3927), (38, 0xA12527),
    ]
    celsius = (fahrenheit - 32) / 1.8
    if celsius <= stops[0][0]:
        packed = stops[0][1]
    elif celsius >= stops[-1][0]:
        packed = stops[-1][1]
    else:
        for (low_t, low_c), (high_t, high_c) in zip(stops, stops[1:]):
            if celsius <= high_t:
                fraction = (celsius - low_t) / (high_t - low_t)
                low = ((low_c >> 16) & 255, (low_c >> 8) & 255, low_c & 255)
                high = ((high_c >> 16) & 255, (high_c >> 8) & 255, high_c & 255)
                return tuple(round(a + (b - a) * fraction) for a, b in zip(low, high))
        packed = stops[-1][1]
    return ((packed >> 16) & 255, (packed >> 8) & 255, packed & 255)


def format_weather(weather: Weather, unit: str = "F") -> str:
    unit = unit.upper()
    value = weather.fahrenheit
    if unit == "C":
        value = (value - 32) / 1.8
    rounded = int(value + (0.5 if value >= 0 else -0.5))
    temp = f"{rounded}{unit}"
    condition_names = {
        "sunny": "SUNNY", "clear-night": "CLEAR NIGHT", "partlycloudy": "PARTLY",
        "cloudy": "CLOUDY", "fog": "FOG", "rainy": "RAIN", "pouring": "POURING",
        "snowy": "SNOW", "snowy-rainy": "SLEET", "hail": "HAIL",
        "lightning": "STORM", "lightning-rainy": "STORM", "windy": "WIND",
        "windy-variant": "WIND",
    }
    condition = condition_names.get(weather.condition, weather.condition.replace("-", " ").upper())
    return f"{temp} {condition}"[:160]


class SpotLedMqttService:
    def __init__(self) -> None:
        self.host = os.environ.get("MQTT_HOST", "").strip()
        self.port = int(os.environ.get("MQTT_PORT", "1883"))
        self.username = os.environ.get("MQTT_USERNAME", "")
        self.password = os.environ.get("MQTT_PASSWORD", "")
        self.client_id = os.environ.get("MQTT_CLIENT_ID", "spotled-mqtt")
        self.notify_topic = os.environ.get("MQTT_NOTIFY_TOPIC", "").strip()
        self.weather_topic = os.environ.get("MQTT_WEATHER_TOPIC", "").strip()
        self.device_name = os.environ.get("SPOTLED_DEVICE_NAME", "").strip()
        self.weather_interval = max(60, int(os.environ.get("WEATHER_INTERVAL_SECONDS", "900")))
        self.weather_unit = os.environ.get("WEATHER_UNIT", "F").upper()
        if self.weather_unit not in {"F", "C"}:
            raise ValueError("WEATHER_UNIT must be F or C")
        self.text_effect = os.environ.get("SPOTLED_TEXT_EFFECT", "left").lower()
        self.text_effect_mode = text_effect_mode(self.text_effect)
        try:
            self.text_mode = text_render_mode(os.environ.get("SPOTLED_TEXT_MODE", "native"))
            self.text_time_ms = text_speed_ms(os.environ.get("SPOTLED_TEXT_SPEED", "normal"))
        except ValueError as exc:
            raise ValueError(f"Invalid SpotLED text setting: {exc}") from exc
        self.queue: asyncio.Queue[Any] = asyncio.Queue(maxsize=100)
        self.latest_weather: Weather | None = None
        self.weather_ready = asyncio.Event()
        self.loop: asyncio.AbstractEventLoop | None = None
        self.client = None

    def _threadsafe_enqueue(self, item: Any) -> None:
        if self.loop is None:
            return

        def enqueue() -> None:
            try:
                self.queue.put_nowait(item)
            except asyncio.QueueFull:
                LOG.warning("Display queue is full; dropping the newest MQTT event")

        self.loop.call_soon_threadsafe(enqueue)

    def _on_connect(self, client, userdata, flags, reason_code, properties) -> None:
        if getattr(reason_code, "is_failure", False):
            LOG.error("MQTT connection rejected: %s", reason_code)
            return
        topics = [
            (self.notify_topic, 0),
            (self.weather_topic, 1),
            (self.notify_topic + "/dismiss", 0),
            (self.notify_topic + "/dismiss/+", 0),
        ]
        client.subscribe(topics)
        LOG.info("Connected to MQTT; subscribed to %s and %s", self.notify_topic, self.weather_topic)

    def _on_disconnect(self, client, userdata, disconnect_flags, reason_code, properties) -> None:
        if getattr(reason_code, "is_failure", False):
            LOG.warning("MQTT disconnected: %s; client will reconnect", reason_code)

    def _on_message(self, client, userdata, message) -> None:
        if message.topic == self.weather_topic:
            weather = parse_weather(message.payload)
            if weather is not None:
                self.latest_weather = weather
                if self.loop is not None:
                    self.loop.call_soon_threadsafe(self.weather_ready.set)
            else:
                LOG.warning("Ignoring invalid weather MQTT payload on %s", message.topic)
        elif message.topic == self.notify_topic:
            notification = parse_notification(message.payload)
            if notification is None:
                LOG.warning("Ignoring invalid/empty notification on %s", message.topic)
            else:
                if "icon" in _safe_json(message.payload) or "sound" in _safe_json(message.payload):
                    LOG.info("SpotLED currently renders notification text/color only; icon/sound fields are ignored")
                self._threadsafe_enqueue(notification)
        elif message.topic.startswith(self.notify_topic + "/dismiss"):
            self._threadsafe_enqueue(DISMISS)

    async def _weather_schedule(self) -> None:
        # Show the retained MQTT weather once received, then refresh every 15 minutes.
        while True:
            await self.weather_ready.wait()
            self.weather_ready.clear()
            self._threadsafe_enqueue(WEATHER_REFRESH)
            await asyncio.sleep(self.weather_interval)

    async def _send(self, text: str, color: tuple[int, int, int]) -> None:
        LOG.info("Sending to %s with %s effect: %s", self.device_name,
                 self.text_effect, text)
        await send_text(
            text, color=color, mode=self.text_effect_mode, device_name=self.device_name,
            text_mode=self.text_mode, time_ms=self.text_time_ms
        )

    async def _show_weather(self) -> None:
        if self.latest_weather is None:
            LOG.debug("No valid retained weather reading yet")
            return
        await self._send(
            format_weather(self.latest_weather, self.weather_unit),
            weather_color(self.latest_weather.fahrenheit),
        )

    async def _wait_for_notification(self, notification: Notification,
                                     pending: deque[Any]) -> Notification | None:
        deadline = None if notification.hold else asyncio.get_running_loop().time() + notification.duration_ms / 1000
        while True:
            timeout = None if deadline is None else max(0.0, deadline - asyncio.get_running_loop().time())
            try:
                item = await asyncio.wait_for(self.queue.get(), timeout=timeout)
            except asyncio.TimeoutError:
                return None
            if item is DISMISS:
                LOG.info("Active notification dismissed")
                return None
            if isinstance(item, Notification):
                if not item.stack:
                    return item
                pending.append(item)
            else:
                # Coalesce repeated weather refreshes while an alert is active.
                if WEATHER_REFRESH not in pending:
                    pending.append(WEATHER_REFRESH)

    async def _run(self) -> None:
        pending: deque[Any] = deque()
        while True:
            item = pending.popleft() if pending else await self.queue.get()
            if item is WEATHER_REFRESH:
                await self._show_weather()
                continue
            if item is DISMISS:
                await self._show_weather()
                continue

            notification = item
            while isinstance(notification, Notification):
                try:
                    await self._send(notification.text, notification.color)
                except Exception:
                    LOG.exception("Could not display MQTT notification")
                notification = await self._wait_for_notification(notification, pending)

            # Like AWTRIX notifications, return to the base weather display once
            # alerts drain. Stacked notifications run before this refresh.
            if not any(isinstance(queued, Notification) for queued in pending):
                pending = deque(item for item in pending if item is not WEATHER_REFRESH)
                await self._show_weather()

    async def run(self) -> None:
        if not self.host:
            raise RuntimeError("Set MQTT_HOST to the address of your MQTT broker")
        if not self.notify_topic:
            raise RuntimeError("Set MQTT_NOTIFY_TOPIC to your AWTRIX notification command topic")
        if not self.weather_topic:
            raise RuntimeError("Set MQTT_WEATHER_TOPIC to your Home Assistant weather state topic")
        if not self.device_name:
            raise RuntimeError("Set SPOTLED_DEVICE_NAME to the display's advertised name")
        if mqtt is None:
            raise RuntimeError("Missing paho-mqtt; install the SpotLED-MQTT requirements.txt")
        self.loop = asyncio.get_running_loop()
        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=self.client_id,
            protocol=mqtt.MQTTv311,
        )
        if self.username:
            self.client.username_pw_set(self.username, self.password)
        if os.environ.get("MQTT_TLS", "false").lower() in {"1", "true", "yes", "on"}:
            self.client.tls_set()
        self.client.reconnect_delay_set(min_delay=1, max_delay=60)
        self.client.on_connect = self._on_connect
        self.client.on_disconnect = self._on_disconnect
        self.client.on_message = self._on_message
        LOG.info("Connecting to MQTT broker %s:%d", self.host, self.port)
        while True:
            try:
                await asyncio.to_thread(self.client.connect, self.host, self.port, 60)
                break
            except OSError as exc:
                LOG.warning("MQTT broker unavailable (%s); retrying in 5 seconds", exc)
                await asyncio.sleep(5)
        self.client.loop_start()
        stop_event = asyncio.Event()
        for signum in (signal.SIGINT, signal.SIGTERM):
            try:
                self.loop.add_signal_handler(signum, stop_event.set)
            except (NotImplementedError, RuntimeError):
                pass
        worker = asyncio.create_task(self._run(), name="spotled-display-queue")
        scheduler = asyncio.create_task(self._weather_schedule(), name="spotled-weather-refresh")
        try:
            await stop_event.wait()
        finally:
            worker.cancel()
            scheduler.cancel()
            await asyncio.gather(worker, scheduler, return_exceptions=True)
            self.client.disconnect()
            self.client.loop_stop()


def _safe_json(payload: bytes) -> dict[str, Any]:
    try:
        value = json.loads(payload)
        return value if isinstance(value, dict) else {}
    except (TypeError, UnicodeDecodeError, json.JSONDecodeError):
        return {}


async def _main() -> None:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    service = SpotLedMqttService()
    await service.run()


if __name__ == "__main__":
    try:
        asyncio.run(_main())
    except KeyboardInterrupt:
        print("SpotLED-MQTT stopped.")
    except Exception as exc:
        logging.basicConfig(level=logging.ERROR)
        LOG.error("SpotLED-MQTT stopped: %s", exc)
        raise SystemExit(1)
