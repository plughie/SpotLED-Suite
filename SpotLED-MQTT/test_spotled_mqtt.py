import asyncio
import tempfile
import unittest
import sys
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from spotled_mqtt import (
    CLOCK_REFRESH,
    CLOCK_REFRESH_COALESCE_SECONDS,
    Notification,
    SpotLedMqttService,
    Weather,
    format_clocktemp,
    format_weather,
    noaa_observation_url,
    parse_color,
    parse_clocktemp_indoor,
    parse_notification,
    parse_noaa_observation,
    parse_weather,
    parse_speed,
    compose_clocktemp_icons,
    render_clocktemp_animation_frames,
    render_clocktemp_frame,
    select_icon_animation_frames,
    seconds_until_next_minute,
    seconds_until_next_quarter_hour,
    text_effect_mode,
    wetter_moon_icon_id,
    weather_color,
)


class PayloadTests(unittest.TestCase):
    def test_wetter_moon_phase_matches_source_date_logic(self):
        self.assertEqual(wetter_moon_icon_id(datetime(2026, 10, 18)), "2320")

    def test_icon_animation_sampling_keeps_first_and_most_distinct_frame(self):
        quiet = bytes(8 * 8 * 3)
        weak_bolt = bytes((255, 255, 0)) + bytes(8 * 8 * 3 - 3)
        strong_bolt = bytearray(8 * 8 * 3)
        for pixel in (0, 27, 54, 81):
            strong_bolt[pixel:pixel + 3] = bytes((255, 255, 0))
        sampled = select_icon_animation_frames([quiet, weak_bolt, bytes(strong_bolt)])
        self.assertEqual(sampled, [quiet, bytes(strong_bolt)])

    def test_moon_and_weather_icons_stack_in_reserved_right_edge(self):
        frame = render_clocktemp_frame(
            datetime(2026, 10, 18, 12, 0), 70, Weather(68, "sunny"), 1000
        )
        moon = bytes((1, 2, 3)) * (8 * 8)
        weather_icon = bytes((4, 5, 6)) * (8 * 8)
        combined = compose_clocktemp_icons(frame, moon, weather_icon)
        top_right = (0 * 64 + 56) * 3
        bottom_right = (8 * 64 + 56) * 3
        speed_bar_end = (15 * 64 + 55) * 3
        reserved_bar = (15 * 64 + 56) * 3
        self.assertEqual(combined[top_right:top_right + 3], bytes((1, 2, 3)))
        self.assertEqual(combined[bottom_right:bottom_right + 3], bytes((4, 5, 6)))
        self.assertNotEqual(combined[speed_bar_end:speed_bar_end + 3], bytes(3))
        self.assertEqual(combined[reserved_bar:reserved_bar + 3], bytes((4, 5, 6)))

    def test_wetter_gif_frames_compose_moon_and_sampled_lightning(self):
        from PIL import Image

        with tempfile.TemporaryDirectory() as directory:
            moon = Image.new("RGB", (64, 16), (0, 0, 0))
            moon.putpixel((56, 0), (255, 255, 255))
            moon.save(Path(directory) / "moon_2320.gif")
            cloud = Image.new("RGB", (64, 16), (0, 0, 0))
            bolt = cloud.copy()
            bolt.putpixel((60, 10), (255, 255, 0))
            cloud.save(Path(directory) / "lightning.gif", save_all=True,
                       append_images=[bolt], duration=[500, 500], loop=0, disposal=2)

            frames = render_clocktemp_animation_frames(
                datetime(2026, 10, 18, 12, 0), 70, Weather(68, "lightning"),
                None, "F", Path(directory)
            )

        self.assertEqual(len(frames), 2)
        moon_offset = (56 * 3)
        bolt_offset = ((10 * 64 + 60) * 3)
        self.assertEqual(frames[0][moon_offset:moon_offset + 3], bytes((255, 255, 255)))
        self.assertEqual(frames[1][bolt_offset:bolt_offset + 3], bytes((255, 255, 0)))

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

    def test_noaa_latest_observation_converts_temperature_and_maps_condition(self):
        weather = parse_noaa_observation(
            '{"properties":{"temperature":{"value":10},"textDescription":"Light Rain"}}'
        )
        self.assertEqual(weather, Weather(50.0, "rainy"))
        self.assertEqual(parse_noaa_observation(
            '{"properties":{"temperature":{"value":0},"textDescription":"Thunderstorms"}}'
        ), Weather(32.0, "lightning-rainy"))
        self.assertIsNone(parse_noaa_observation('{"properties":{}}'))

    def test_noaa_station_url_uses_latest_observation_endpoint(self):
        self.assertEqual(
            noaa_observation_url("KSFO"),
            "https://api.weather.gov/stations/KSFO/observations/latest",
        )

    def test_noaa_station_setting_is_normalized_and_validated(self):
        with patch.dict("os.environ", {"NOAA_WEATHER_STATION": "ksfo"}):
            self.assertEqual(SpotLedMqttService(weather_source="noaa").noaa_station, "KSFO")
        with patch.dict("os.environ", {"NOAA_WEATHER_STATION": "bad/station"}):
            with self.assertRaisesRegex(ValueError, "NOAA_WEATHER_STATION"):
                SpotLedMqttService(weather_source="noaa")

    def test_clocktemp_indoor_celsius_converts_and_speed_payload_parses(self):
        self.assertAlmostEqual(parse_clocktemp_indoor('{"temperature":21}', "F"), 69.8)
        self.assertEqual(parse_clocktemp_indoor('{"temperature":21}', "C"), 21)
        self.assertEqual(parse_speed(b"425.5"), 425.5)
        self.assertIsNone(parse_speed(b"unavailable"))

    def test_clock_status_includes_date_time_indoor_outdoor_condition_and_speed(self):
        message = format_clocktemp(
            datetime(2026, 10, 18, 12, 5), 70,
            Weather(68, "partlycloudy"), 425,
        )
        self.assertEqual(message, "10/18  12:05  IN 70F  OUT 68F  PARTLY  NET 425M")

    def test_clocktemp_raster_frame_contains_bitmap_icon_text_and_throughput_bar(self):
        now = datetime(2026, 10, 18, 12, 5)
        sunny = render_clocktemp_frame(now, 70, Weather(68, "sunny"), 500)
        rainy = render_clocktemp_frame(now, 70, Weather(68, "rainy"), 500)
        no_speed = render_clocktemp_frame(now, 70, Weather(68, "sunny"), None)
        no_weather = render_clocktemp_frame(now, 70, None, 500)
        self.assertEqual(len(sunny), 64 * 16 * 3)
        self.assertNotEqual(sunny, rainy)
        self.assertNotEqual(sunny, no_speed)
        icon_start = 59 * 3
        icon_end = (5 * 64 + 64) * 3
        self.assertNotEqual(sunny[icon_start:icon_end], no_weather[icon_start:icon_end])
        bottom_row = 15 * 64 * 3
        self.assertTrue(any(sunny[bottom_row:bottom_row + 32 * 3]))
        self.assertEqual(sunny[bottom_row + 40 * 3:bottom_row + 41 * 3], b"\0\0\0")

    def test_clock_scheduler_targets_minute_and_quarter_hour_boundaries(self):
        self.assertEqual(seconds_until_next_minute(datetime(2026, 1, 1, 12, 34, 15)), 45)
        self.assertEqual(seconds_until_next_quarter_hour(datetime(2026, 1, 1, 12, 14, 30)), 30)
        self.assertEqual(seconds_until_next_quarter_hour(datetime(2026, 1, 1, 12, 30)), 900)
        self.assertEqual(seconds_until_next_quarter_hour(datetime(2026, 1, 1, 12, 59, 30)), 30)

    def test_noaa_is_gathered_with_the_clock_refresh_at_quarter_hours(self):
        service = SpotLedMqttService(weather_source="noaa")
        now_values = iter((datetime(2026, 1, 1, 12, 14, 30),
                           datetime(2026, 1, 1, 12, 14, 30),
                           datetime(2026, 1, 1, 12, 15, 5),
                           datetime(2026, 1, 1, 12, 15, 5)))
        service._now = lambda: next(now_values)
        service._refresh_indoor_temperature = AsyncMock()
        service._refresh_noaa = AsyncMock()
        service._threadsafe_enqueue = Mock()
        with patch("spotled_mqtt.asyncio.sleep",
                   new=AsyncMock(side_effect=[None, asyncio.CancelledError])) as sleep:
            with self.assertRaises(asyncio.CancelledError):
                asyncio.run(service._clock_schedule())
        self.assertEqual(service._refresh_noaa.await_count, 2)
        self.assertEqual(service._refresh_indoor_temperature.await_count, 2)
        self.assertEqual(service._threadsafe_enqueue.call_count, 2)
        self.assertEqual([call.args[0] for call in sleep.await_args_list], [30, 55])

    def test_clock_scheduler_fetches_indoor_on_startup_and_waits_to_minute(self):
        service = SpotLedMqttService()
        service._now = lambda: datetime(2026, 1, 1, 12, 34, 15, 500_000)
        service._refresh_indoor_temperature = AsyncMock()
        service._refresh_noaa = AsyncMock()
        service._threadsafe_enqueue = Mock()
        with patch("spotled_mqtt.asyncio.sleep",
                   new=AsyncMock(side_effect=asyncio.CancelledError)) as sleep:
            with self.assertRaises(asyncio.CancelledError):
                asyncio.run(service._clock_schedule())
        service._refresh_indoor_temperature.assert_awaited_once()
        service._threadsafe_enqueue.assert_called_once()
        sleep.assert_awaited_once_with(44.5)
        service._refresh_noaa.assert_awaited_once()

    def test_burst_of_clock_refresh_triggers_is_coalesced(self):
        async def exercise():
            service = SpotLedMqttService(weather_source="noaa")
            service.loop = asyncio.get_running_loop()
            first_started = asyncio.Event()
            allow_first_finish = asyncio.Event()
            second_finished = asyncio.Event()
            calls = 0

            async def show_clock():
                nonlocal calls
                calls += 1
                if calls == 1:
                    first_started.set()
                    await allow_first_finish.wait()
                elif calls == 2:
                    second_finished.set()

            service._show_clock = show_clock
            worker = asyncio.create_task(service._run())
            service._threadsafe_enqueue(CLOCK_REFRESH)
            await asyncio.wait_for(first_started.wait(), timeout=1)
            for _ in range(5):
                service._threadsafe_enqueue(CLOCK_REFRESH)
            allow_first_finish.set()
            await asyncio.wait_for(second_finished.wait(), timeout=1)
            await asyncio.sleep(0)
            worker.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await worker
            self.assertEqual(calls, 2)

        asyncio.run(exercise())

    def test_pre_send_clock_refresh_burst_renders_one_snapshot(self):
        async def exercise():
            service = SpotLedMqttService(weather_source="noaa")
            service.loop = asyncio.get_running_loop()
            rendered = asyncio.Event()
            calls = 0

            async def show_clock():
                nonlocal calls
                calls += 1
                rendered.set()

            service._show_clock = show_clock
            for _ in range(6):
                service._threadsafe_enqueue(CLOCK_REFRESH)
            worker = asyncio.create_task(service._run())
            await asyncio.wait_for(rendered.wait(), timeout=1)
            await asyncio.sleep(CLOCK_REFRESH_COALESCE_SECONDS * 2)
            worker.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await worker
            self.assertEqual(calls, 1)

        asyncio.run(exercise())

    def test_weather_color_matches_wetter_temperature_ramp_endpoints(self):
        self.assertEqual(weather_color(10.4), (217, 119, 223))  # -12 C
        self.assertEqual(weather_color(100.4), (161, 37, 39))  # 38 C

    def test_default_notification_effect_is_prominent_scroll(self):
        self.assertEqual(text_effect_mode("scroll"), 6)
        with patch.dict("os.environ", {}, clear=True):
            service = SpotLedMqttService()
        self.assertEqual(service.text_effect, "scroll")
        self.assertEqual(service.text_effect_mode, 6)
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
            "HELLO", color=(255, 0, 0), mode=6, device_name="",
            text_mode="raster", time_ms=100,
        )

    def test_service_accepts_intermediate_text_speed_presets(self):
        for preset, expected_ms in (("150ms", 150), ("250ms", 250)):
            with self.subTest(preset=preset), patch.dict("os.environ", {
                    "SPOTLED_TEXT_SPEED": preset}):
                service = SpotLedMqttService()
                self.assertEqual(service.text_time_ms, expected_ms)

    def test_home_assistant_source_subscribes_weather_and_speed_and_applies_payload(self):
        with patch.dict("os.environ", {
                "MQTT_WEATHER_TOPIC": "weather/state",
                "MQTT_SPEED_TOPIC": "speed/results",
        }):
            service = SpotLedMqttService(weather_source="homeassistant")
        client = Mock()
        service._on_connect(client, None, None, SimpleNamespace(is_failure=False), None)
        subscriptions = client.subscribe.call_args.args[0]
        self.assertIn((service.weather_topic, 0), subscriptions)
        self.assertIn((service.speed_topic, 0), subscriptions)

        service._threadsafe_enqueue = Mock()
        message = SimpleNamespace(
            topic=service.weather_topic,
            payload=b'{"temp":72,"cond":"partlycloudy"}',
        )
        service._on_message(None, None, message)
        self.assertEqual(service.latest_weather, Weather(72.0, "partlycloudy"))
        service._threadsafe_enqueue.assert_called_once()

    def test_noaa_source_omits_home_assistant_speed_bar(self):
        with patch.dict("os.environ", {
            "MQTT_SPEED_TOPIC": "speed/results",
            "MQTT_NOTIFY_TOPIC": "awtrix/notify",
        }):
            service = SpotLedMqttService(weather_source="noaa")
        client = Mock()
        service._on_connect(client, None, None, SimpleNamespace(is_failure=False), None)
        subscriptions = client.subscribe.call_args.args[0]
        self.assertNotIn((service.speed_topic, 0), subscriptions)
        service.latest_speed_mbps = 900
        service.latest_weather = Weather(60, "cloudy")
        with patch("spotled_mqtt.send_rgb_frame", new=AsyncMock()) as static_sender, \
             patch("spotled_mqtt.send_rgb_animation", new=AsyncMock()) as animation_sender:
            asyncio.run(service._show_clock())
        static_sender.assert_awaited_once()
        if animation_sender.await_count:
            frames = animation_sender.await_args.args[0]
        else:
            frames = [static_sender.await_args.args[0]]
        for pixels in frames:
            self.assertEqual(pixels[15 * 64 * 3:15 * 64 * 3 + 56 * 3], bytes(56 * 3))

    def test_noaa_only_service_runs_without_broker_or_notification_topic(self):
        async def run_noaa_only():
            real_event = asyncio.Event

            def scheduled_stop_event():
                event = real_event()
                asyncio.get_running_loop().call_later(0.05, event.set)
                return event

            with patch.dict("os.environ", {
                    "SPOTLED_DEVICE_NAME": "SpotLED_TEST",
                    "MQTT_HOST": "",
                    "MQTT_NOTIFY_TOPIC": "",
                    "NOAA_WEATHER_STATION": "",
            }, clear=False), \
                 patch("spotled_mqtt.mqtt", None), \
                 patch("spotled_mqtt.asyncio.Event", side_effect=scheduled_stop_event):
                service = SpotLedMqttService(weather_source="noaa")
                service._refresh_noaa = AsyncMock()
                service._show_clock = AsyncMock()
                await service.run()
            self.assertIsNone(service.client)
            service._refresh_noaa.assert_awaited_once()

        asyncio.run(run_noaa_only())

    def test_home_assistant_requires_mqtt_but_notification_topic_is_optional(self):
        with patch.dict("os.environ", {
            "SPOTLED_DEVICE_NAME": "SpotLED_TEST",
            "MQTT_HOST": "", "MQTT_NOTIFY_TOPIC": "",
            "MQTT_WEATHER_TOPIC": "weather/state",
        }):
            service = SpotLedMqttService(weather_source="homeassistant")
            with self.assertRaisesRegex(RuntimeError, "MQTT_HOST"):
                asyncio.run(service.run())

    def test_empty_notification_topic_does_not_subscribe_or_match_dismiss(self):
        with patch.dict("os.environ", {"MQTT_NOTIFY_TOPIC": ""}):
            service = SpotLedMqttService(weather_source="noaa")
        client = Mock()
        service._on_connect(client, None, None, SimpleNamespace(is_failure=False), None)
        client.subscribe.assert_not_called()
        service._threadsafe_enqueue = Mock()
        service._on_message(None, None, SimpleNamespace(topic="/dismiss/all", payload=b"{}"))
        service._threadsafe_enqueue.assert_not_called()

    def test_mqtt_start_uses_async_reconnect_without_blocking_service_start(self):
        async def start_and_stop():
            stop_event = asyncio.Event()
            stop_event.set()
            client = Mock()
            fake_mqtt = SimpleNamespace(
                Client=Mock(return_value=client),
                CallbackAPIVersion=SimpleNamespace(VERSION2=2),
                MQTTv311=4,
            )
            with patch.dict("os.environ", {
                    "SPOTLED_DEVICE_NAME": "SpotLED_TEST",
                    "MQTT_HOST": "broker.invalid",
                    "MQTT_NOTIFY_TOPIC": "",
            }, clear=False), \
                 patch("spotled_mqtt.mqtt", fake_mqtt), \
                 patch("spotled_mqtt.asyncio.Event", return_value=stop_event):
                service = SpotLedMqttService(weather_source="noaa")
                await service.run()
            client.connect_async.assert_called_once_with("broker.invalid", 1883, 60)
            client.connect.assert_not_called()
            client.loop_start.assert_called_once()
            client.disconnect.assert_called_once()

        asyncio.run(start_and_stop())

    def test_clock_upload_failure_does_not_terminate_display_worker(self):
        async def exercise():
            service = SpotLedMqttService(weather_source="noaa")
            first_failed = asyncio.Event()
            recovered = asyncio.Event()
            calls = 0

            async def flaky_show_clock():
                nonlocal calls
                calls += 1
                if calls == 1:
                    first_failed.set()
                    raise OSError("temporary BLE failure")
                recovered.set()

            service._show_clock = flaky_show_clock
            worker = asyncio.create_task(service._run())
            await service.queue.put(CLOCK_REFRESH)
            await asyncio.wait_for(first_failed.wait(), timeout=1)
            await service.queue.put(CLOCK_REFRESH)
            await asyncio.wait_for(recovered.wait(), timeout=1)
            worker.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await worker
            self.assertEqual(calls, 2)

        with self.assertLogs("spotled_mqtt", level="ERROR"):
            asyncio.run(exercise())

    def test_worker_supervisor_restarts_unexpected_worker_failure(self):
        service = SpotLedMqttService(weather_source="noaa")
        calls = 0

        async def flaky_worker():
            nonlocal calls
            calls += 1
            if calls == 1:
                raise RuntimeError("unexpected scheduler failure")

        with self.assertLogs("spotled_mqtt", level="ERROR"), \
             patch("spotled_mqtt.asyncio.sleep", new=AsyncMock()) as sleep:
            asyncio.run(service._supervise("test", flaky_worker))
        self.assertEqual(calls, 2)
        sleep.assert_awaited_once_with(5)

    def test_clock_animation_primes_persistent_composed_frame_before_playback(self):
        service = SpotLedMqttService(weather_source="noaa")
        service._set_latest_weather(Weather(60, "cloudy"))
        first = bytes([1]) * (64 * 16 * 3)
        second = bytes([2]) * (64 * 16 * 3)
        events = []

        async def send_static(*args, **kwargs):
            events.append(("static", args[0], kwargs))

        async def send_animation(*args, **kwargs):
            events.append(("animation", args[0], kwargs))

        with patch("spotled_mqtt.render_clocktemp_animation_frames",
                   return_value=[first, second]), \
             patch("spotled_mqtt.send_rgb_frame", new=AsyncMock(side_effect=send_static)), \
             patch("spotled_mqtt.send_rgb_animation", new=AsyncMock(side_effect=send_animation)):
            asyncio.run(service._show_clock())

        self.assertEqual([event[0] for event in events], ["static", "animation"])
        self.assertEqual(events[0][1], first)
        self.assertEqual(events[1][1], [first, second])

    def test_minute_clock_refresh_sends_one_static_frame_after_weather_animation(self):
        service = SpotLedMqttService(weather_source="noaa")
        service._set_latest_weather(Weather(60, "cloudy"))
        service._animated_weather_revision = service._weather_revision
        frame = bytes([3]) * (64 * 16 * 3)
        with patch("spotled_mqtt.render_clocktemp_animation_frames", return_value=[frame, bytes([4]) * len(frame)]), \
             patch("spotled_mqtt.send_rgb_frame", new=AsyncMock()) as static_sender, \
             patch("spotled_mqtt.send_rgb_animation", new=AsyncMock()) as animation_sender:
            asyncio.run(service._show_clock())
        static_sender.assert_awaited_once()
        animation_sender.assert_not_awaited()

    def test_new_weather_condition_requests_icon_animation(self):
        service = SpotLedMqttService(weather_source="noaa")
        initial_revision = service._weather_revision
        service._set_latest_weather(Weather(60, "sunny"))
        self.assertGreater(service._weather_revision, initial_revision)
        first_revision = service._weather_revision
        service._set_latest_weather(Weather(61, "sunny"))
        self.assertEqual(service._weather_revision, first_revision)
        service._set_latest_weather(Weather(61, "rainy"))
        self.assertGreater(service._weather_revision, first_revision)


if __name__ == "__main__":
    unittest.main()
