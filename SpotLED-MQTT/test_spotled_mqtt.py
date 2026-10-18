import asyncio
import unittest
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from spotled_mqtt import (
    Notification,
    SpotLedMqttService,
    Weather,
    format_weather,
    parse_color,
    parse_notification,
    parse_weather,
    text_effect_mode,
    weather_color,
)


class PayloadTests(unittest.TestCase):
    def test_parses_awtrix_notification_text_color_and_timing(self):
        result = parse_notification(
            b'{"text":"Front Door on","textColor":"#FFD166","durationMs":5000,"stack":false}'
        )
        self.assertEqual(result, Notification("Front Door on", (255, 209, 102), 5000,
                                               hold=False, stack=False))

    def test_color_accepts_awtrix_hex_array_integer_and_hsv(self):
        self.assertEqual(parse_color("F00"), (255, 0, 0))
        self.assertEqual(parse_color([1, 2, 3]), (1, 2, 3))
        self.assertEqual(parse_color(0x123456), (0x12, 0x34, 0x56))
        self.assertEqual(parse_color(["HSV", 0, 100, 100]), (255, 0, 0))

    def test_notification_defaults_and_bmp_sanitization(self):
        result = parse_notification('{"text":["Door ",{"text":"bell 🛎"}]}')
        self.assertEqual(result.text, "Door bell ?")
        self.assertEqual(result.duration_ms, 7000)
        self.assertTrue(result.stack)

    def test_invalid_notifications_are_ignored(self):
        self.assertIsNone(parse_notification(b"not-json"))
        self.assertIsNone(parse_notification('{"text":"  "}'))

    def test_retained_weather_payload_matches_awtrix_ha_contract(self):
        weather = parse_weather('{"temp":72.5,"cond":"partlycloudy"}')
        self.assertEqual(weather, Weather(72.5, "partlycloudy"))
        self.assertEqual(format_weather(weather), "73F PARTLY")
        self.assertEqual(format_weather(weather, "C"), "23C PARTLY")

    def test_weather_rejects_invalid_or_unavailable_payloads(self):
        self.assertIsNone(parse_weather('{"temp":null,"cond":"sunny"}'))
        self.assertEqual(parse_weather('{"temp":72,"cond":null}'), Weather(72.0, "unknown"))
        self.assertIsNone(parse_weather("unavailable"))

    def test_weather_color_matches_wetter_temperature_ramp_endpoints(self):
        self.assertEqual(weather_color(10.4), (217, 119, 223))  # -12 C
        self.assertEqual(weather_color(100.4), (161, 37, 39))  # 38 C

    def test_default_service_scroll_is_explicit_left_marquee(self):
        self.assertEqual(text_effect_mode("left"), 3)
        self.assertEqual(text_effect_mode("scroll"), 6)
        with self.assertRaises(ValueError):
            text_effect_mode("wipe")

    def test_service_reads_shared_python_text_mode_and_speed_settings(self):
        fake_sender = SimpleNamespace(main=AsyncMock())
        with patch.dict("os.environ", {
                "SPOTLED_TEXT_MODE": "raster", "SPOTLED_TEXT_SPEED": "fast"}), \
             patch("spotled_mqtt.send_text", new=fake_sender.main):
            service = SpotLedMqttService()
            self.assertEqual(service.text_mode, "raster")
            self.assertEqual(service.text_time_ms, 100)

            async def send_notification():
                await service._send("HELLO", (255, 0, 0))

            asyncio.run(send_notification())
        fake_sender.main.assert_awaited_once_with(
            "HELLO", color=(255, 0, 0), mode=3, device_name="",
            text_mode="raster", time_ms=100,
        )


if __name__ == "__main__":
    unittest.main()
