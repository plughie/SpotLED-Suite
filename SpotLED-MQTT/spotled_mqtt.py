#!/usr/bin/env python3
"""Bridge AWTRIX-NG notifications and ClockTemp002-style status to SpotLED."""

from __future__ import annotations

import asyncio
import argparse
import colorsys
import json
import logging
import os
import re
import signal
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from spotled_common import send_rgb_animation, send_rgb_frame, send_text, text_effect_mode, text_render_mode, text_speed_ms
from spotled_common.font3x5 import FONT3X5

try:
    import paho.mqtt.client as mqtt
except ImportError:  # Keep pure parsing helpers importable for tests.
    mqtt = None

LOG = logging.getLogger("spotled_mqtt")
DISMISS = object()
CLOCK_REFRESH = object()
ICON_X = 56
ICON_SIZE = 8
ICON_FRAME_INTERVAL_MS = 500

WEATHER_ICON_NAMES = {
    "sunny": "sunny", "clear-night": "clear-night", "partlycloudy": "partlycloudy",
    "cloudy": "cloudy", "fog": "fog", "rainy": "rainy", "pouring": "pouring",
    "snowy": "snowy", "snowy-rainy": "snowy-rainy", "hail": "hail",
    "lightning": "lightning", "lightning-rainy": "lightning-rainy",
    "windy": "windy", "windy-variant": "windy-variant", "exceptional": "exceptional",
}


def wetter_moon_icon_id(day: datetime) -> str:
    """Return the icon ID selected by Wetter.ax's integer Julian-day logic."""
    month = day.month
    a = int((14 - month) / 12)
    year = day.year + 4800 - a
    adjusted_month = month + 12 * a - 3
    phase = (day.day + (153 * adjusted_month + 2) // 5 + 365 * year
             + year // 4 - year // 100 + year // 400 - 2483595) / 29.53058867
    phase -= int(phase)
    if phase < 0.03 or phase >= 0.97:
        return "2318"
    if phase < 0.22:
        return "2321"
    if phase < 0.28:
        return "2320"
    if phase < 0.48:
        return "2319"
    if phase < 0.52:
        return "2314"
    if phase < 0.72:
        return "2315"
    if phase < 0.78:
        return "2316"
    return "2317"


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


def _noaa_condition(description: str) -> str:
    text = description.lower()
    if any(word in text for word in ("thunder", "lightning")):
        return "lightning-rainy"
    if "snow" in text or "blizzard" in text or "flurr" in text:
        return "snowy"
    if any(word in text for word in ("rain", "shower", "drizzle")):
        return "pouring" if any(word in text for word in ("heavy", "downpour")) else "rainy"
    if any(word in text for word in ("fog", "mist", "haze")):
        return "fog"
    if "partly" in text or "few clouds" in text:
        return "partlycloudy"
    if any(word in text for word in ("cloud", "overcast")):
        return "cloudy"
    if any(word in text for word in ("clear", "sunny", "fair")):
        return "sunny"
    return "unknown"


def parse_noaa_observation(payload: bytes | str) -> Weather | None:
    """Parse a NOAA/NWS latest observation; API temperatures are Celsius."""
    try:
        data = json.loads(payload)
        properties = data["properties"]
        temperature_c = float(properties["temperature"]["value"])
        description = properties.get("textDescription", "")
        if not isinstance(description, str) or not (-100 <= temperature_c <= 70):
            return None
        return Weather(temperature_c * 1.8 + 32, _noaa_condition(description))
    except (TypeError, ValueError, KeyError, UnicodeDecodeError, json.JSONDecodeError):
        return None


def parse_clocktemp_indoor(payload: bytes | str, unit: str = "F") -> float | None:
    """Read ClockTemp002's remote TC001 API temperature, returned in Celsius."""
    try:
        data = json.loads(payload)
        if not isinstance(data, dict):
            return None
        temperature_c = float(data["temperature"])
        if not (-100 <= temperature_c <= 100):
            return None
        return temperature_c if unit.upper() == "C" else temperature_c * 1.8 + 32
    except (TypeError, ValueError, KeyError, UnicodeDecodeError, json.JSONDecodeError):
        return None


def parse_speed(payload: bytes | str) -> float | None:
    try:
        speed = float(payload)
        return speed if 0 <= speed <= 1_000_000 else None
    except (TypeError, ValueError, UnicodeDecodeError, OverflowError):
        return None


def seconds_until_next_minute(now: datetime) -> float:
    return 60 - now.second - now.microsecond / 1_000_000


def seconds_until_next_quarter_hour(now: datetime) -> float:
    next_minute = ((now.minute // 15) + 1) * 15
    target = now.replace(second=0, microsecond=0)
    if next_minute == 60:
        target = (target + timedelta(hours=1)).replace(minute=0)
    else:
        target = target.replace(minute=next_minute)
    return max(0.0, (target - now).total_seconds())


def format_clocktemp(now: datetime, indoor: float | None, weather: Weather | None,
                     speed_mbps: float | None, unit: str = "F") -> str:
    """Text-only marquee approximation of ClockTemp002's date/clock/readings."""
    unit = unit.upper()

    def rounded(value: float) -> int:
        return int(value + (0.5 if value >= 0 else -0.5))

    parts = [f"{now.month:02d}/{now.day:02d}", f"{now.hour}:{now.minute:02d}"]
    if indoor is not None:
        parts.append(f"IN {rounded(indoor)}{unit}")
    if weather is not None:
        outdoor = weather.fahrenheit
        if unit == "C":
            outdoor = (outdoor - 32) / 1.8
        condition_names = {
            "sunny": "SUNNY", "clear-night": "CLEAR", "partlycloudy": "PARTLY",
            "cloudy": "CLOUDY", "fog": "FOG", "rainy": "RAIN", "pouring": "POURING",
            "snowy": "SNOW", "snowy-rainy": "SLEET", "hail": "HAIL",
            "lightning": "STORM", "lightning-rainy": "STORM", "windy": "WIND",
            "windy-variant": "WIND",
        }
        parts.append(f"OUT {rounded(outdoor)}{unit}")
        parts.append(condition_names.get(weather.condition, weather.condition.upper()))
    if speed_mbps is not None:
        parts.append(f"NET {rounded(speed_mbps)}M")
    return "  ".join(parts)[:160]


def _draw_clock_text(pixels: bytearray, text: str, x: int, y: int,
                     color: tuple[int, int, int]) -> None:
    for index, char in enumerate(text.upper()):
        glyph = FONT3X5.get(char, FONT3X5["?"])
        for row, bits in enumerate(glyph):
            for column, bit in enumerate(bits):
                if bit == "1":
                    px = x + index * 4 + column
                    py = y + row
                    if 0 <= px < 64 and 0 <= py < 16:
                        offset = (py * 64 + px) * 3
                        pixels[offset:offset + 3] = bytes(color)


def _clock_pixel(pixels: bytearray, x: int, y: int,
                 color: tuple[int, int, int]) -> None:
    if 0 <= x < 64 and 0 <= y < 16:
        offset = (y * 64 + x) * 3
        pixels[offset:offset + 3] = bytes(color)


def _draw_clock_condition(pixels: bytearray, condition: str) -> None:
    ox, oy = 59, 0
    yellow = (255, 255, 0)
    cloud = (102, 204, 255)
    rain = (51, 153, 255)
    white = (255, 255, 255)
    if condition in {"sunny", "clear-night"}:
        for x, y in ((2, 2), (2, 0), (2, 4), (0, 2), (4, 2),
                     (1, 1), (3, 1), (1, 3), (3, 3)):
            _clock_pixel(pixels, ox + x, oy + y, yellow)
        return
    if condition in {"lightning", "lightning-rainy"}:
        for y in (1, 2):
            for x in range(4):
                _clock_pixel(pixels, ox + x, oy + y, (170, 170, 170))
        for x, y in ((2, 3), (1, 4), (3, 3)):
            _clock_pixel(pixels, ox + x, oy + y, yellow)
        return

    for x in range(5):
        _clock_pixel(pixels, ox + x, oy + 2, cloud)
        _clock_pixel(pixels, ox + x, oy + 3, cloud)
    for x, y in ((1, 1), (2, 0), (3, 1)):
        _clock_pixel(pixels, ox + x, oy + y, cloud)
    if condition in {"rainy", "pouring"}:
        _clock_pixel(pixels, ox + 1, oy + 4, rain)
        _clock_pixel(pixels, ox + 3, oy + 4, rain)
    elif condition in {"snowy", "snowy-rainy", "hail"}:
        _clock_pixel(pixels, ox + 1, oy + 4, white)
        _clock_pixel(pixels, ox + 3, oy + 4, white)


def _clocktemp_speed_color(percent: float) -> tuple[int, int, int]:
    percent = max(0.0, min(100.0, percent))
    if percent <= 33.333:
        return (255, int(percent * 128 / 33.333), 0)
    if percent <= 66.666:
        return (255, int(128 + (percent - 33.333) * 127 / 33.333), 0)
    return (int(255 - (percent - 66.666) * 255 / 33.334), 255, 0)


def render_clocktemp_frame(now: datetime, indoor: float | None, weather: Weather | None,
                           speed_mbps: float | None, unit: str = "F",
                           speed_max_mbps: float = 1000.0) -> bytes:
    """Rasterize ClockTemp002's date, clock, temperatures, condition icon and speed bar."""
    pixels = bytearray(64 * 16 * 3)
    unit = unit.upper()
    white = (255, 255, 255)
    _draw_clock_text(pixels, f"{now.month:02d}/{now.day:02d}", 0, 0, white)
    if weather is not None:
        outdoor = weather.fahrenheit
        if unit == "C":
            outdoor = (outdoor - 32) / 1.8
        _draw_clock_condition(pixels, weather.condition)
        _draw_clock_text(pixels, f"{int(outdoor + (0.5 if outdoor >= 0 else -0.5))}{unit}",
                         38, 9, white)
    else:
        _draw_clock_text(pixels, "--", 38, 9, white)
    if indoor is not None:
        _draw_clock_text(pixels, f"{int(indoor + (0.5 if indoor >= 0 else -0.5))}{unit}",
                         22, 9, (0, 255, 0))
    else:
        _draw_clock_text(pixels, "--", 22, 9, white)
    _draw_clock_text(pixels, f"{now.hour}:{now.minute:02d}", 0, 9, white)

    # ClockTemp002 uses a red-yellow-green throughput bar on the bottom row.
    for x in range(64):
        _clock_pixel(pixels, x, 15, (0, 0, 0))
    if speed_mbps is not None and speed_max_mbps > 0:
        # Reserve the rightmost eight columns for the stacked 8x8 moon/weather icons.
        span = 55
        finish = int(max(0.0, min(1.0, speed_mbps / speed_max_mbps)) * span)
        for x in range(finish + 1):
            _clock_pixel(pixels, x, 15, _clocktemp_speed_color(x * 100 / span))
    return bytes(pixels)


def wetter_icon_filename(condition: str) -> str:
    """Resolve an HA condition to the local Wetter.ax 8x8 icon preview name."""
    return WEATHER_ICON_NAMES.get(condition.strip().lower(), "unknown")


def select_icon_animation_frames(frames: list[bytes]) -> list[bytes]:
    """Keep the first icon frame and the frame most visually distinct from it."""
    if not frames:
        return []
    first = bytes(frames[0])
    best, best_score = None, 0
    for candidate in frames[1:]:
        candidate = bytes(candidate)
        if len(candidate) != len(first):
            continue
        score = sum(abs(left - right) for left, right in zip(first, candidate))
        if score > best_score:
            best, best_score = candidate, score
    return [first] if best is None else [first, best]


def compose_clocktemp_icons(frame: bytes, moon_icon: bytes, weather_icon: bytes) -> bytes:
    """Place 8x8 moon and weather RGB icons in a vertical stack at the right edge."""
    expected = ICON_SIZE * ICON_SIZE * 3
    if len(frame) != 64 * 16 * 3 or len(moon_icon) != expected or len(weather_icon) != expected:
        raise ValueError("ClockTemp frame and 8x8 RGB icon dimensions do not match")
    result = bytearray(frame)
    for y in range(ICON_SIZE):
        for x in range(ICON_SIZE):
            icon_offset = (y * ICON_SIZE + x) * 3
            moon_target = (y * 64 + ICON_X + x) * 3
            weather_target = ((y + ICON_SIZE) * 64 + ICON_X + x) * 3
            result[moon_target:moon_target + 3] = moon_icon[icon_offset:icon_offset + 3]
            result[weather_target:weather_target + 3] = weather_icon[icon_offset:icon_offset + 3]
    return bytes(result)


def _gif_icon_frames(path: Path, crop_box: tuple[int, int, int, int]) -> list[bytes]:
    try:
        from PIL import Image
    except ImportError:
        return []
    if not path.is_file():
        return []
    try:
        frames = []
        with Image.open(path) as image:
            for index in range(getattr(image, "n_frames", 1)):
                image.seek(index)
                frame = image.convert("RGB")
                if frame.size != (64, 16):
                    return []
                crop = frame.crop(crop_box)
                frames.append(crop.tobytes())
        return frames
    except (OSError, ValueError):
        return []


def render_clocktemp_animation_frames(now: datetime, indoor: float | None,
                                     weather: Weather | None, speed_mbps: float | None,
                                     unit: str = "F", icon_dir: Path | None = None) -> list[bytes]:
    """Render a static status frame or two safe, distinct Wetter icon frames."""
    frame = render_clocktemp_frame(now, indoor, weather, speed_mbps, unit)
    if weather is None:
        return [frame]
    asset_dir = icon_dir or Path(__file__).resolve().parent
    moon_frames = _gif_icon_frames(
        asset_dir / f"moon_{wetter_moon_icon_id(now)}.gif", (ICON_X, 0, 64, ICON_SIZE)
    )
    weather_frames = _gif_icon_frames(
        asset_dir / f"{wetter_icon_filename(weather.condition)}.gif",
        (ICON_X, ICON_SIZE, 64, 16),
    )
    if not moon_frames or not weather_frames:
        return [frame]
    moon_icon = moon_frames[0]
    return [compose_clocktemp_icons(frame, moon_icon, icon_frame)
            for icon_frame in select_icon_animation_frames(weather_frames)]


def noaa_observation_url(station: str) -> str:
    return f"https://api.weather.gov/stations/{station}/observations/latest"


def fetch_noaa_observation(station: str, timeout: float = 10.0) -> Weather | None:
    request = Request(
        noaa_observation_url(station),
        headers={
            "User-Agent": "SpotLED-Suite/https://github.com/plughie/SpotLED-Suite",
            "Accept": "application/geo+json",
        },
    )
    with urlopen(request, timeout=timeout) as response:
        return parse_noaa_observation(response.read(64_000))


def fetch_clocktemp_indoor(url: str, unit: str = "F", timeout: float = 5.0) -> float | None:
    request = Request(url, headers={"User-Agent": "SpotLED-Suite/0.16"})
    with urlopen(request, timeout=timeout) as response:
        return parse_clocktemp_indoor(response.read(16_000), unit)


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
    def __init__(self, weather_source: str = "noaa") -> None:
        self.host = os.environ.get("MQTT_HOST", "").strip()
        self.port = int(os.environ.get("MQTT_PORT", "1883"))
        self.username = os.environ.get("MQTT_USERNAME", "")
        self.password = os.environ.get("MQTT_PASSWORD", "")
        self.client_id = os.environ.get("MQTT_CLIENT_ID", "spotled-mqtt")
        self.notify_topic = os.environ.get("MQTT_NOTIFY_TOPIC", "").strip()
        self.weather_topic = os.environ.get("MQTT_WEATHER_TOPIC", "").strip()
        self.speed_topic = os.environ.get("MQTT_SPEED_TOPIC", "").strip()
        self.device_name = os.environ.get("SPOTLED_DEVICE_NAME", "").strip()
        self.weather_unit = os.environ.get("WEATHER_UNIT", "F").upper()
        if self.weather_unit not in {"F", "C"}:
            raise ValueError("WEATHER_UNIT must be F or C")
        if weather_source not in {"noaa", "homeassistant"}:
            raise ValueError("weather_source must be 'noaa' or 'homeassistant'")
        self.weather_source = weather_source
        self.noaa_station = os.environ.get("NOAA_WEATHER_STATION", "").strip().upper()
        if (self.weather_source == "noaa" and self.noaa_station
                and not re.fullmatch(r"[A-Z0-9]{3,5}", self.noaa_station)):
            raise ValueError("NOAA_WEATHER_STATION must be a 3-5 character station identifier")
        if self.weather_source == "homeassistant" and not self.weather_topic:
            raise ValueError("Set MQTT_WEATHER_TOPIC when using Home Assistant weather")
        self.indoor_url = os.environ.get("CLOCKTEMP_INDOOR_URL", "").strip()
        timezone_name = os.environ.get("CLOCK_TIMEZONE", "").strip()
        try:
            self.clock_timezone = ZoneInfo(timezone_name) if timezone_name else None
        except ZoneInfoNotFoundError as exc:
            raise ValueError(f"Unknown CLOCK_TIMEZONE: {timezone_name}") from exc
        self.text_effect = os.environ.get("SPOTLED_TEXT_EFFECT", "scroll").lower()
        self.text_effect_mode = text_effect_mode(self.text_effect)
        try:
            self.text_mode = text_render_mode(os.environ.get("SPOTLED_TEXT_MODE", "native"))
            self.text_time_ms = text_speed_ms(os.environ.get("SPOTLED_TEXT_SPEED", "normal"))
        except ValueError as exc:
            raise ValueError(f"Invalid SpotLED text setting: {exc}") from exc
        self.queue: asyncio.Queue[Any] = asyncio.Queue(maxsize=100)
        self.latest_weather: Weather | None = None
        self.latest_indoor_temperature: float | None = None
        self.latest_speed_mbps: float | None = None
        self._noaa_config_warning_shown = False
        self.loop: asyncio.AbstractEventLoop | None = None
        self.client = None

    def _now(self) -> datetime:
        return datetime.now(self.clock_timezone) if self.clock_timezone else datetime.now().astimezone()

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
        topics = [(self.notify_topic, 0),
                  (self.notify_topic + "/dismiss", 0),
                  (self.notify_topic + "/dismiss/+", 0)]
        if self.weather_source == "homeassistant" and self.speed_topic:
            topics.append((self.speed_topic, 0))
        if self.weather_source == "homeassistant":
            topics.append((self.weather_topic, 0))
        client.subscribe(topics)
        extra_topics = [topic for topic in (self.speed_topic if self.weather_source == "homeassistant" else "",
                                            self.weather_topic if self.weather_source == "homeassistant" else "")
                        if topic]
        LOG.info("Connected to MQTT; subscribed to notifications%s",
                 f" and {', '.join(extra_topics)}" if extra_topics else "")

    def _on_disconnect(self, client, userdata, disconnect_flags, reason_code, properties) -> None:
        if getattr(reason_code, "is_failure", False):
            LOG.warning("MQTT disconnected: %s; client will reconnect", reason_code)

    def _on_message(self, client, userdata, message) -> None:
        if self.weather_source == "homeassistant" and self.speed_topic and message.topic == self.speed_topic:
            speed = parse_speed(message.payload)
            if speed is not None:
                self.latest_speed_mbps = speed
                self._threadsafe_enqueue(CLOCK_REFRESH)
            else:
                LOG.warning("Ignoring invalid ClockTemp002 speed payload")
        elif self.weather_source == "homeassistant" and message.topic == self.weather_topic:
            weather = parse_weather(message.payload)
            if weather is not None:
                self.latest_weather = weather
                self._threadsafe_enqueue(CLOCK_REFRESH)
            else:
                LOG.warning("Ignoring invalid Home Assistant weather payload")
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

    async def _clock_schedule(self) -> None:
        # ClockTemp002 polls the TC001; refresh it on startup and with each minute tick.
        while True:
            await self._refresh_indoor_temperature()
            self._threadsafe_enqueue(CLOCK_REFRESH)
            await asyncio.sleep(seconds_until_next_minute(self._now()))

    async def _weather_schedule(self) -> None:
        if self.weather_source == "homeassistant":
            # MQTT retained state supplies the initial value; further updates are pushed.
            return
        # Fetch once on startup, then on wall-clock quarter hours (00, 15, 30, 45).
        await self._refresh_noaa()
        while True:
            await asyncio.sleep(seconds_until_next_quarter_hour(self._now()))
            await self._refresh_noaa()

    async def _refresh_noaa(self) -> None:
        if not self.noaa_station:
            if not self._noaa_config_warning_shown:
                LOG.warning("NOAA weather is disabled; configure NOAA_WEATHER_STATION")
                self._noaa_config_warning_shown = True
            return
        try:
            weather = await asyncio.to_thread(
                fetch_noaa_observation, self.noaa_station,
            )
        except HTTPError as exc:
            LOG.warning("NOAA observation endpoint returned HTTP %d", exc.code)
            return
        except (URLError, TimeoutError, OSError) as exc:
            LOG.warning("NOAA observation request failed (%s)", type(exc).__name__)
            return
        if weather is None:
            LOG.warning("NOAA returned invalid or unavailable latest observation")
            return
        self.latest_weather = weather
        self._threadsafe_enqueue(CLOCK_REFRESH)

    async def _refresh_indoor_temperature(self) -> None:
        if not self.indoor_url:
            return
        try:
            indoor = await asyncio.to_thread(
                fetch_clocktemp_indoor, self.indoor_url, self.weather_unit
            )
        except (HTTPError, URLError, TimeoutError, OSError) as exc:
            LOG.warning("ClockTemp002 indoor refresh failed (%s)", type(exc).__name__)
            return
        if indoor is not None:
            self.latest_indoor_temperature = indoor

    async def _show_clock(self) -> None:
        now = self._now()
        speed = self.latest_speed_mbps if self.weather_source == "homeassistant" else None
        status = format_clocktemp(
            now, self.latest_indoor_temperature, self.latest_weather,
            speed, self.weather_unit,
        )
        frames = render_clocktemp_animation_frames(
            now, self.latest_indoor_temperature, self.latest_weather,
            speed, self.weather_unit,
        )
        LOG.info("Updating ClockTemp status: %s", status)
        if len(frames) > 1:
            await send_rgb_animation(
                frames, width=64, height=16, device_name=self.device_name,
                frame_time_ms=ICON_FRAME_INTERVAL_MS,
            )
        else:
            await send_rgb_frame(
                frames[0], width=64, height=16, device_name=self.device_name,
                frame_time_ms=1000,
            )

    async def _send(self, text: str, color: tuple[int, int, int]) -> None:
        LOG.info("Sending to %s with %s effect: %s", self.device_name,
                 self.text_effect, text)
        await send_text(
            text, color=color, mode=self.text_effect_mode, device_name=self.device_name,
            text_mode=self.text_mode, time_ms=self.text_time_ms
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
                # Coalesce minute/weather/speed refreshes while an alert is active.
                if CLOCK_REFRESH not in pending:
                    pending.append(CLOCK_REFRESH)

    async def _run(self) -> None:
        pending: deque[Any] = deque()
        while True:
            item = pending.popleft() if pending else await self.queue.get()
            if item is CLOCK_REFRESH:
                await self._show_clock()
                continue
            if item is DISMISS:
                await self._show_clock()
                continue

            notification = item
            while isinstance(notification, Notification):
                try:
                    await self._send(notification.text, notification.color)
                except Exception:
                    LOG.exception("Could not display MQTT notification")
                notification = await self._wait_for_notification(notification, pending)

            # Like AWTRIX notifications, return to the base ClockTemp display once
            # alerts drain. Stacked notifications run before this refresh.
            if not any(isinstance(queued, Notification) for queued in pending):
                pending = deque(item for item in pending if item is not CLOCK_REFRESH)
                await self._show_clock()

    async def run(self) -> None:
        if not self.host:
            raise RuntimeError("Set MQTT_HOST to the address of your MQTT broker")
        if not self.notify_topic:
            raise RuntimeError("Set MQTT_NOTIFY_TOPIC to your AWTRIX notification command topic")
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
        LOG.info("Using %s weather source", self.weather_source)
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
        clock_scheduler = asyncio.create_task(self._clock_schedule(), name="spotled-clock-refresh")
        weather_scheduler = asyncio.create_task(self._weather_schedule(), name="spotled-weather-refresh")
        try:
            await stop_event.wait()
        finally:
            worker.cancel()
            clock_scheduler.cancel()
            weather_scheduler.cancel()
            await asyncio.gather(worker, clock_scheduler, weather_scheduler, return_exceptions=True)
            self.client.disconnect()
            self.client.loop_stop()


def _safe_json(payload: bytes) -> dict[str, Any]:
    try:
        value = json.loads(payload)
        return value if isinstance(value, dict) else {}
    except (TypeError, UnicodeDecodeError, json.JSONDecodeError):
        return {}


async def _main(weather_source: str) -> None:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    service = SpotLedMqttService(weather_source=weather_source)
    await service.run()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Bridge AWTRIX MQTT alerts/weather to SpotLED")
    parser.add_argument(
        "--weather-source", choices=("noaa", "homeassistant"), default="noaa",
        help="weather input: NOAA latest station observation (default) or retained Home Assistant MQTT topic",
    )
    arguments = parser.parse_args()
    try:
        asyncio.run(_main(arguments.weather_source))
    except KeyboardInterrupt:
        print("SpotLED-MQTT stopped.")
    except Exception as exc:
        logging.basicConfig(level=logging.ERROR)
        LOG.error("SpotLED-MQTT stopped: %s", exc)
        raise SystemExit(1)
