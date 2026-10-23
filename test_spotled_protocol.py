"""Offline regression checks for the reconstructed SPOTLED wire format."""

import sys
import hashlib
import json
import subprocess
import tempfile
import unittest
from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

from spotled_common.font3x5 import FONT3X5
from spotled_common import sender as spotled
from spotled_common.ble_lock import ble_process_lock

SHARED_FIXTURES = json.loads(
    (Path(__file__).parent / "test_fixtures" / "spotled_protocol_vectors.json").read_text()
)
SHARED_GLYPH_DATA = json.loads(
    (Path(__file__).parent / "glyph_data" / "spotled_glyphs.json").read_text()
)
SHARED_GIF_VECTORS = json.loads(
    (Path(__file__).parent / "test_fixtures" / "gif_processing_vectors.json").read_text()
)


class PureProtocolTests(unittest.TestCase):
    """Focused protocol/font/GIF rules are runnable without importing BLE drivers."""

    def test_image_and_gif_contract_vectors(self):
        for vector in SHARED_GIF_VECTORS["cases"]:
            self.assertEqual(
                spotled.select_frame_indices(vector["source_frames"], vector["limit"]),
                vector["indices"],
            )
            self.assertEqual(
                spotled.animation_interval_ms(vector["duration_ms"], len(vector["indices"])),
                vector["interval_ms"],
            )


class CliTests(unittest.TestCase):
    def test_offline_cli_modes_parse_without_device_selection(self):
        with patch.object(sys, "argv", ["SPOTLED-v3.py", "--image", "a.gif", "--inspect"]):
            self.assertTrue(spotled.parse_cli_args().inspect)
        with patch.object(sys, "argv", ["SPOTLED-v3.py", "--image", "a.gif", "--dry-run"]):
            self.assertTrue(spotled.parse_cli_args().dry_run)

    def test_sender_protocol_import_does_not_require_bleak(self):
        code = (
            "import builtins; old=builtins.__import__; "
            "builtins.__import__=lambda name,*a,**k: (_ for _ in ()).throw(ImportError()) "
            "if name == 'bleak' else old(name,*a,**k); "
            "import spotled_common.sender as s; "
            "assert len(s.build_rgb_frame(1,1,b'\\0\\0\\0')) > 0"
        )
        result = subprocess.run([sys.executable, "-c", code], cwd=Path(__file__).parent,
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_optional_ble_lock_is_nonblocking_and_same_host_only(self):
        with tempfile.TemporaryDirectory() as directory:
            lock_path = str(Path(directory) / "spotled.lock")
            with patch.dict("os.environ", {"SPOTLED_LOCK_FILE": lock_path}):
                with ble_process_lock():
                    with self.assertRaisesRegex(RuntimeError, "Another SpotLED sender"):
                        with ble_process_lock():
                            pass

    def test_device_name_defaults_to_spotled_prefix_and_can_be_overridden(self):
        with patch.object(sys, "argv", ["SPOTLED-v3.py", "HELLO"]):
            self.assertEqual(spotled.parse_cli_args().device_name, "SpotLED_*")
        with patch.object(sys, "argv", [
            "SPOTLED-v3.py", "--image", "art.gif", "--device-name", "MySpotLED"
        ]):
            self.assertEqual(spotled.parse_cli_args().device_name, "MySpotLED")

    def test_text_orientation_is_landscape_or_portrait_and_rotation_is_image_only(self):
        with patch.object(sys, "argv", ["SPOTLED-v3.py", "HELLO", "--text-orientation", "portrait"]):
            self.assertEqual(spotled.parse_cli_args().text_orientation, "portrait")
        with patch.object(sys, "argv", ["SPOTLED-v3.py", "HELLO", "--text-orientation", "270"]):
            with self.assertRaises(SystemExit):
                spotled.parse_cli_args()
        with patch.object(sys, "argv", ["SPOTLED-v3.py", "--image", "a.png", "--rotation", "270"]):
            self.assertEqual(spotled.parse_cli_args().rotation, 270)
        with patch.object(sys, "argv", ["SPOTLED-v3.py", "HELLO", "--rotation", "270"]):
            with self.assertRaises(SystemExit):
                spotled.parse_cli_args()

    def test_raster_font_is_explicit_so_native_text_stays_the_default(self):
        with patch.object(sys, "argv", ["SPOTLED-v3.py", "HYBRID"]):
            self.assertIsNone(spotled.parse_cli_args().raster_font)
            self.assertEqual(spotled.parse_cli_args().text_mode, "native")
            self.assertEqual(spotled.parse_cli_args().text_speed, "normal")
        with patch.object(sys, "argv", [
            "SPOTLED-v3.py", "HYBRID", "rainbow", "scroll", "--raster-font", "3x5"
        ]):
            self.assertEqual(spotled.parse_cli_args().raster_font, "3x5")

    def test_android_text_mode_and_speed_options(self):
        with patch.object(sys, "argv", [
            "SPOTLED-v3.py", "HYBRID", "white", "left",
            "--text-mode", "raster", "--text-speed", "fast"
        ]):
            args = spotled.parse_cli_args()
        self.assertEqual(args.text_mode, "raster")
        self.assertEqual(spotled.text_speed_ms(args.text_speed), 100)
        for speed, expected in (("slow", 300), ("250ms", 250),
                                ("normal", 200), ("150ms", 150), ("fast", 100)):
            self.assertEqual(spotled.text_speed_ms(speed), expected)
        self.assertEqual(spotled.text_speed_ms("slow"), 300)
        self.assertEqual(spotled.text_speed_ms("normal"), 200)
        for speed in ("150ms", "250ms"):
            with patch.object(sys, "argv", ["SPOTLED-v3.py", "HELLO", "--text-speed", speed]):
                self.assertEqual(spotled.parse_cli_args().text_speed, speed)
        with self.assertRaises(ValueError):
            spotled.text_speed_ms("turbo")


class DiscoveryTests(unittest.IsolatedAsyncioTestCase):
    async def test_prefix_discovery_resolves_spotled_names(self):
        device = SimpleNamespace(address="00:11:22:33:44:55", name="cached-name")
        advertisement = SimpleNamespace(local_name="SpotLED_CD34")
        with patch.object(spotled.BleakScanner, "discover",
                          new=AsyncMock(return_value={device.address: (device, advertisement)})):
            found = await spotled.find_spotled_device("SpotLED_*", scan_timeout=1)
        self.assertIs(found, device)

    async def test_prefix_discovery_prompts_when_multiple_spotleds_are_found(self):
        devices = [
            SimpleNamespace(address="00:00:00:00:00:01", name="cached1"),
            SimpleNamespace(address="00:00:00:00:00:02", name="cached2"),
        ]
        advertisements = {
            device.address: (device, SimpleNamespace(local_name=name))
            for device, name in zip(devices, ("SpotLED_Example", "SpotLED_Other"))
        }
        with patch.object(spotled.BleakScanner, "discover",
                          new=AsyncMock(return_value=advertisements)), \
             patch("builtins.input", return_value="2"):
            found = await spotled.find_spotled_device("SpotLED_*", scan_timeout=1)
        self.assertIs(found, devices[1])


class TextModeDispatchTests(unittest.IsolatedAsyncioTestCase):
    async def test_main_raster_mode_uses_android_style_raster_sender_and_interval(self):
        with patch.object(spotled, "send_rotated_text", new=AsyncMock()) as raster_send:
            await spotled.main(
                "A", color="red", mode=3, device_name="SpotLED_AB12",
                text_mode="raster", time_ms=100,
            )
        raster_send.assert_awaited_once_with(
            "A", "red", 0, "contain", raster_font="3x5", text_layout="whole",
            device_name="SpotLED_AB12", mode=3, frame_time_ms=100,
        )


class NativeTextPreflightTests(unittest.IsolatedAsyncioTestCase):
    async def test_invalid_unicode_fails_before_connecting(self):
        connected = []

        @asynccontextmanager
        async def fake_connection(*args, **kwargs):
            connected.append(True)
            yield object()

        with patch.object(spotled, "connected_device", new=fake_connection):
            with self.assertRaisesRegex(ValueError, "BMP characters only"):
                await spotled.main("🦆")
        self.assertEqual(connected, [])

    async def test_both_payloads_are_built_before_connect_and_oversize_stops_before_setup(self):
        client = object()
        events = []
        original_font_builder = spotled.build_word_stock
        original_text_builder = spotled.build_character_string

        def build_font(text):
            events.append("font")
            return original_font_builder(text)

        def build_text(text, *args, **kwargs):
            events.append("text")
            return original_text_builder(text, *args, **kwargs)

        @asynccontextmanager
        async def fake_connection(*args, **kwargs):
            events.append("connect")
            yield client

        with patch.object(spotled, "build_word_stock", side_effect=build_font), \
             patch.object(spotled, "build_character_string", side_effect=build_text), \
             patch.object(spotled, "connected_device", new=fake_connection), \
             patch.object(spotled, "send_query", new=AsyncMock()), \
             patch.object(spotled, "query_screen_information",
                          new=AsyncMock(return_value={"width": 64, "height": 16})), \
             patch.object(spotled, "query_simple_response",
                          new=AsyncMock(side_effect=[b"capabilities", b"capacity"])), \
             patch.object(spotled, "parse_animation_capabilities_response",
                          return_value={"max_text_characters": 160}), \
             patch.object(spotled, "parse_receive_capacity_response", return_value=7280), \
             patch("spotled_common.sender.asyncio.sleep", new=AsyncMock()), \
             patch.object(spotled, "send_set", new=AsyncMock()) as setup_send, \
             patch.object(spotled, "send_set_acknowledged", new=AsyncMock()) as content_send:
            with self.assertRaisesRegex(ValueError, "Font transfer exceeds"):
                await spotled.main("A" * 155)

        self.assertEqual(events, ["font", "text", "connect"])
        setup_send.assert_not_awaited()
        content_send.assert_not_awaited()


class ConnectionTests(unittest.IsolatedAsyncioTestCase):
    async def test_discovery_and_disconnect_lifecycle(self):
        device = object()
        client = MagicMock()
        client.connect = AsyncMock()
        client.disconnect = AsyncMock()
        client.is_connected = True
        with patch.object(spotled.BleakScanner, "find_device_by_name",
                          new=AsyncMock(return_value=device)) as find_device, \
             patch.object(spotled, "BleakClient", return_value=client) as make_client:
            async with spotled.connected_device("Panel", scan_timeout=1) as connected:
                self.assertIs(connected, client)
            find_device.assert_awaited_once_with("Panel", timeout=1)
            make_client.assert_called_once_with(device, timeout=30)
            client.connect.assert_awaited_once()
            client.disconnect.assert_awaited_once()

    async def test_missing_device_has_actionable_error(self):
        with patch.object(spotled.BleakScanner, "find_device_by_name",
                          new=AsyncMock(return_value=None)):
            with self.assertRaisesRegex(RuntimeError, "advertised name"):
                async with spotled.connected_device("Missing", scan_timeout=1, attempts=1):
                    self.fail("should not connect")

    async def test_connection_error_has_manual_recovery_guidance(self):
        client = MagicMock()
        client.connect = AsyncMock(side_effect=OSError("busy"))
        with patch.object(spotled.BleakScanner, "find_device_by_name",
                          new=AsyncMock(return_value=object())), \
             patch.object(spotled, "BleakClient", return_value=client):
            with self.assertRaisesRegex(RuntimeError, "connected to another phone"):
                async with spotled.connected_device("Panel", scan_timeout=1, attempts=1):
                    self.fail("connection should fail")

    async def test_discovery_retries_once_for_transient_miss(self):
        device = object()
        finder = AsyncMock(side_effect=[None, device])
        client = MagicMock()
        client.connect = AsyncMock()
        client.disconnect = AsyncMock()
        client.is_connected = True
        with patch.object(spotled.BleakScanner, "find_device_by_name", new=finder), \
             patch.object(spotled, "BleakClient", return_value=client):
            async with spotled.connected_device("Panel", scan_timeout=1) as connected:
                self.assertIs(connected, client)
        self.assertEqual(finder.await_count, 2)

    async def test_connection_retries_once_with_fresh_client(self):
        first = MagicMock()
        first.connect = AsyncMock(side_effect=OSError("transient"))
        first.disconnect = AsyncMock()
        second = MagicMock()
        second.connect = AsyncMock()
        second.disconnect = AsyncMock()
        second.is_connected = True
        with patch.object(spotled.BleakScanner, "find_device_by_name",
                          new=AsyncMock(return_value=object())), \
             patch.object(spotled, "BleakClient", side_effect=[first, second]):
            async with spotled.connected_device("Panel", scan_timeout=1) as connected:
                self.assertIs(connected, second)
        first.disconnect.assert_awaited_once()
        second.connect.assert_awaited_once()
        second.disconnect.assert_awaited_once()


class RgbFrameSenderTests(unittest.IsolatedAsyncioTestCase):
    async def test_sends_clock_style_rgb_frame_as_one_acknowledged_image(self):
        client = object()

        @asynccontextmanager
        async def connected(*args, **kwargs):
            yield client

        with patch.object(spotled, "connected_device", new=connected), \
             patch.object(spotled, "query_image_capabilities", new=AsyncMock(return_value=(
                 {"width": 64, "height": 16, "color_type": 0xff},
                 {"storage_bytes": 16352}, 7280))), \
             patch.object(spotled, "send_set_acknowledged", new=AsyncMock()) as transfer:
            await spotled.send_rgb_frame(bytes(64 * 16 * 3), frame_time_ms=1000)

        transfer.assert_awaited_once()
        self.assertIs(transfer.await_args.args[0], client)
        self.assertEqual(transfer.await_args.args[1], 0x8004)
        self.assertLessEqual(len(transfer.await_args.args[2]), 7280)

    async def test_sends_sampled_rgb_animation_with_two_safe_frames(self):
        client = object()

        @asynccontextmanager
        async def connected(*args, **kwargs):
            yield client

        first = bytes(64 * 16 * 3)
        second = bytearray(first)
        marker = (8 * 64 + 60) * 3
        second[marker:marker + 3] = bytes((255, 255, 0))
        with patch.object(spotled, "connected_device", new=connected), \
             patch.object(spotled, "query_image_capabilities", new=AsyncMock(return_value=(
                 {"width": 64, "height": 16, "color_type": 0xff},
                 {"max_frames": 20, "storage_bytes": 16352}, 7280))), \
             patch.object(spotled, "send_set_acknowledged", new=AsyncMock()) as transfer:
            await spotled.send_rgb_animation([first, bytes(second)], frame_time_ms=500)

        transfer.assert_awaited_once()
        payload = transfer.await_args.args[2]
        self.assertLessEqual(len(payload), 7280)
        animation = payload[15:]
        self.assertEqual(animation[6:8], bytes.fromhex("0002"))


class ReadRetryTests(unittest.IsolatedAsyncioTestCase):
    async def test_read_query_retries_timeout_once(self):
        operation = AsyncMock(side_effect=[TimeoutError(), {"width": 64}])
        result = await spotled.retry_read_query(operation, retries=1, delay=0)
        self.assertEqual(result, {"width": 64})
        self.assertEqual(operation.await_count, 2)

    async def test_read_query_does_not_retry_non_timeout_errors(self):
        operation = AsyncMock(side_effect=RuntimeError("device rejected query"))
        with self.assertRaisesRegex(RuntimeError, "rejected"):
            await spotled.retry_read_query(operation, retries=1, delay=0)
        operation.assert_awaited_once()

    async def test_read_query_reports_final_timeout_after_retry(self):
        operation = AsyncMock(side_effect=TimeoutError("no response"))
        with self.assertRaisesRegex(TimeoutError, "no response"):
            await spotled.retry_read_query(operation, retries=1, delay=0)
        self.assertEqual(operation.await_count, 2)


class QueryFrameTests(unittest.TestCase):
    def test_matches_observed_device_query_frames(self):
        self.assertEqual(spotled.build_query(0x10, 0), bytes.fromhex("04100000"))
        self.assertEqual(spotled.build_query(0x12, 1), bytes.fromhex("04120001"))
        self.assertEqual(spotled.build_query(0x1A, 2), bytes.fromhex("041a0002"))
        self.assertEqual(spotled.build_query(0x14, 3), bytes.fromhex("04140003"))

    def test_rejects_query_ids_outside_wire_field(self):
        with self.assertRaises(ValueError):
            spotled.build_query(0x100, 0)

    def test_rejects_serials_outside_wire_field(self):
        with self.assertRaises(ValueError):
            spotled.build_query(0x10, 0x10000)


class ObjectEncodingTests(unittest.TestCase):
    def test_checksum_is_ones_complement_twos_complement(self):
        self.assertEqual(spotled.check_sum(bytes.fromhex("0000000d8000000000030010")), 0xA0)

    def test_query_does_not_append_checksum(self):
        frame = spotled.build_query(0x10, 0)
        self.assertEqual(len(frame), frame[0])
        self.assertEqual(frame, bytes.fromhex("04100000"))

    def test_shared_native_glyph_vectors_match_fixture(self):
        captured = SHARED_GLYPH_DATA["captured_panel16_hex"]
        compact = SHARED_GLYPH_DATA["compact_3x5"]
        self.assertEqual(spotled.CAP_GLYPHS, captured)
        self.assertEqual(FONT3X5, {key: tuple(rows) for key, rows in compact.items()})
        for character_code, expected_hex in captured.items():
            character = chr(int(character_code))
            with self.subTest(character=character):
                self.assertEqual(spotled.make_font_object(character).hex(), expected_hex)

    def test_generated_glyph_tables_are_current(self):
        result = subprocess.run(
            [sys.executable, "tools/generate_glyph_tables.py", "--check"],
            cwd=Path(__file__).parent, capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr or result.stdout)

    def test_native_bmp_fallback_preserves_original_codepoint(self):
        vector = SHARED_FIXTURES["native_text_unicode"]
        character = chr(vector["bmp_fallback_codepoint"])
        glyph = spotled.make_font_object(character)
        question = spotled.make_font_object("?")
        self.assertEqual(glyph.hex(), vector["glyph_object_hex"])
        self.assertEqual(int.from_bytes(glyph[11:13], "big"), vector["bmp_fallback_codepoint"])
        self.assertEqual(glyph[14:46], question[14:46])
        text = spotled.build_character_string(character)
        self.assertIn(bytes.fromhex(vector["character_text_object_hex"]), text)

    def test_portrait_native_glyphs_rotate_clockwise_and_keep_all_wordstock_entries(self):
        source = spotled.make_font_object("O")
        rotated = spotled.rotate_font_object(source, 270)
        self.assertEqual(len(rotated), 47)
        self.assertEqual(rotated[11:13], source[11:13])
        self.assertEqual(rotated[-1], spotled.check_sum(rotated[:-1]))
        self.assertNotEqual(rotated[14:46], source[14:46])
        word_stock = spotled.build_word_stock("HELLO", glyph_rotation=270)
        fifth_glyph_offset = 9 + 4 * 47
        self.assertEqual(word_stock[fifth_glyph_offset:fifth_glyph_offset + 47], rotated)
        self.assertEqual(int.from_bytes(word_stock[6:8], "big"), 5)

    def test_native_text_rejects_non_bmp_and_unpaired_surrogates(self):
        for text in (SHARED_FIXTURES["native_text_unicode"]["non_bmp_example"], "\ud800"):
            with self.subTest(text=repr(text)):
                with self.assertRaisesRegex(ValueError, "BMP characters only"):
                    spotled.build_character_string(text)
                with self.assertRaisesRegex(ValueError, "BMP characters only"):
                    spotled.build_word_stock(text)

    def test_shared_rgb_frame_packet_matches_fixture_digest(self):
        vector = SHARED_FIXTURES["black_frame_64x16_1000ms"]
        previous_serial = spotled.serial_num
        try:
            spotled.serial_num = 1
            frame = spotled.build_rgb_frame(
                vector["width"], vector["height"],
                bytes(vector["width"] * vector["height"] * 3),
            )
            animation = spotled.build_frame_animation(
                [frame], frame_time_ms=vector["interval_ms"], mode=vector["effect_mode"]
            )
            packet = spotled.wrap_set_data(animation)
        finally:
            spotled.serial_num = previous_serial

        self.assertEqual(len(packet), vector["payload_length"])
        self.assertEqual(hashlib.sha256(packet).hexdigest(), vector["payload_sha256"])
        self.assertEqual(packet[:len(bytes.fromhex(vector["payload_prefix_hex"]))].hex(),
                         vector["payload_prefix_hex"])

    def test_parses_observed_device_screen_information(self):
        frame = bytes.fromhex("0e1300420000400010ff14640101")
        info = spotled.parse_screen_information_response(frame, expected_serial=0x42)
        self.assertEqual(info["width"], 64)
        self.assertEqual(info["height"], 16)
        self.assertEqual(info["color_type"], 0xFF)
        self.assertEqual(info["max_frames"], 20)
        self.assertEqual(info["brightness"], 100)
        self.assertEqual(info["word_stock"], 1)
        self.assertEqual(info["light_up"], 1)

    def test_screen_information_parser_rejects_mismatched_serial(self):
        frame = bytes.fromhex("0e1300420000400010ff14640101")
        with self.assertRaises(ValueError):
            spotled.parse_screen_information_response(frame, expected_serial=0x43)

    def test_parses_observed_device_transfer_limits(self):
        capabilities = spotled.parse_animation_capabilities_response(
            bytes.fromhex("0d1b000200001400a000003fe0")
        )
        self.assertEqual(capabilities["max_frames"], 20)
        self.assertEqual(capabilities["max_text_characters"], 160)
        self.assertEqual(capabilities["storage_bytes"], 16352)
        self.assertEqual(
            spotled.parse_receive_capacity_response(
                bytes.fromhex("091500030000001c70")
            ),
            7280,
        )

    def test_parses_visible_pixel_map_in_android_bit_order(self):
        # Android's response object has a 22-byte header, then MSB-first mask bits.
        frame = bytearray(24)
        frame[0:4] = (24).to_bytes(4, "big")
        frame[4:6] = (0x8001).to_bytes(2, "big")
        frame[16:18] = (0x10).to_bytes(2, "big")
        frame[18:20] = (4).to_bytes(2, "big")
        frame[20:22] = (4).to_bytes(2, "big")
        frame[22:24] = bytes.fromhex("8142")

        result = spotled.parse_visible_pixel_map_response(frame)
        self.assertEqual((result["width"], result["height"]), (4, 4))
        self.assertEqual(result["mask"], tuple(int(bit) for bit in "1000000101000010"))

    def test_visible_pixel_map_parser_rejects_wrong_object(self):
        frame = bytearray(24)
        frame[0:4] = (24).to_bytes(4, "big")
        frame[4:6] = (0x8001).to_bytes(2, "big")
        frame[16:18] = (0x11).to_bytes(2, "big")
        frame[18:20] = (4).to_bytes(2, "big")
        frame[20:22] = (4).to_bytes(2, "big")
        with self.assertRaises(ValueError):
            spotled.parse_visible_pixel_map_response(frame)

    def test_character_string_matches_android_object_layout(self):
        encoded = spotled.build_character_string("A", (0x12, 0x34, 0x56), time_ms=200, mode=3)

        # CharacterString header (10 bytes), followed by Color, Text, Time,
        # and SpecialEffects objects. These IDs and field sizes come from the
        # corresponding decompiled Android object classes.
        self.assertEqual(len(encoded), 47)
        self.assertEqual(encoded[:6], bytes.fromhex("0000000a0004"))
        self.assertEqual(encoded[6:9], bytes.fromhex("000101"))
        self.assertEqual(encoded[10:16], bytes.fromhex("0000000a0002"))
        self.assertEqual(encoded[16:19], bytes.fromhex("123456"))
        self.assertEqual(encoded[20:26], bytes.fromhex("000000090003"))
        self.assertEqual(encoded[26:28], bytes.fromhex("0041"))
        self.assertEqual(encoded[29:35], bytes.fromhex("0000000a0007"))
        self.assertEqual(encoded[35:38], bytes.fromhex("0000c8"))
        self.assertEqual(encoded[39:45], bytes.fromhex("000000080008"))
        self.assertEqual(encoded[45], 3)
        slow_text = spotled.build_character_string("A", time_ms=300, mode=3)
        self.assertEqual(slow_text[35:38], bytes.fromhex("00012c"))
        with self.assertRaises(ValueError):
            spotled.build_character_string("A", time_ms=0)

        for start, length in ((0, 10), (10, 10), (20, 9), (29, 10), (39, 8)):
            object_data = encoded[start : start + length]
            self.assertEqual(int.from_bytes(object_data[:4], "big"), length)
            self.assertEqual(object_data[-1], spotled.check_sum(object_data[:-1]))

    def test_rainbow_text_color_matches_android_static_per_character_palette(self):
        colors = spotled.normalize_text_colors("AB", "rainbow")
        self.assertEqual(colors, [(255, 0, 0), (0, 255, 255)])

        native = spotled.build_character_string("AB", color="rainbow")
        self.assertEqual(native[16:19], bytes((255, 0, 0)))
        self.assertEqual(native[35:38], bytes((0, 255, 255)))

        width, height, pixels = spotled.render_text_rgb("AB", color="rainbow", raster_font="3x5")
        self.assertEqual((width, height), (7, 5))
        self.assertEqual(pixels[3:6], bytes((255, 0, 0)))
        self.assertEqual(pixels[12:15], bytes((0, 255, 255)))

    def test_captured_font_glyph_is_preserved_in_word_stock(self):
        encoded = spotled.build_word_stock("A")
        glyph = bytes.fromhex(spotled.CAP_GLYPHS["65"])

        self.assertEqual(int.from_bytes(encoded[:4], "big"), 9)
        self.assertEqual(int.from_bytes(encoded[4:6], "big"), 5)
        self.assertEqual(int.from_bytes(encoded[6:8], "big"), 1)
        self.assertEqual(encoded[9:], glyph)
        self.assertEqual(int.from_bytes(glyph[:4], "big"), len(glyph))
        self.assertEqual(int.from_bytes(glyph[4:6], "big"), 13)

    def test_capital_i_native_glyph_has_serifs(self):
        glyph = spotled.make_font_object("I")
        row = lambda y: int.from_bytes(glyph[14 + y * 2:16 + y * 2], "big")

        self.assertEqual(len(glyph), 47)
        self.assertEqual(row(0), 0)
        self.assertEqual(row(1), 0)
        self.assertEqual(row(2), 0x1FF0)
        self.assertEqual(row(3), 0x0100)
        self.assertEqual(row(12), 0x0100)
        self.assertEqual(row(13), 0x1FF0)
        self.assertEqual(row(14), 0)
        self.assertEqual(row(15), 0)
        self.assertEqual(spotled.check_sum(glyph[:-1]), glyph[-1])

    def test_set_data_wrapper_lengths_and_checksum(self):
        object_data = spotled.build_character_string("A")
        wrapped = spotled.wrap_set_data(object_data)

        self.assertEqual(int.from_bytes(wrapped[:4], "big"), 15)
        self.assertEqual(int.from_bytes(wrapped[4:6], "big"), 0x8004)
        self.assertEqual(int.from_bytes(wrapped[10:14], "big"), len(object_data))
        self.assertEqual(wrapped[15:], object_data)
        self.assertEqual(wrapped[14], spotled.check_sum(wrapped[:14]))

    def test_named_text_path_initializers_match_previous_wire_packets(self):
        self.assertEqual(
            spotled.build_initial_playlist_query(),
            bytes.fromhex("00000014800c00000001000000000000000000a1"),
        )
        self.assertEqual(
            spotled.build_visible_pixel_map_query(),
            bytes.fromhex("0000000d8000000000030010a0"),
        )
        self.assertEqual(
            spotled.build_device_control(1, sequence=7),
            bytes.fromhex("0000000d80fe0000000700016d"),
        )

    def test_rgb_frame_animation_uses_follow_content(self):
        pixels = bytes.fromhex("ff000000ff00")
        frame = spotled.build_rgb_frame(2, 1, pixels)
        animation = spotled.build_frame_animation([frame], frame_time_ms=200, mode=0)

        # Android Object.getData() declares only the two-byte frame count in
        # the FrameAnimation header. Frames, time, and effects follow it.
        self.assertEqual(len(animation), 45)
        self.assertEqual(animation[:6], bytes.fromhex("00000009000b"))
        self.assertEqual(animation[6:8], bytes.fromhex("0001"))
        self.assertEqual(animation[9:15], bytes.fromhex("000000120060"))
        self.assertEqual(animation[15:20], bytes.fromhex("0002000118"))
        self.assertEqual(animation[20:26], pixels)
        self.assertEqual(animation[27:33], bytes.fromhex("0000000a0007"))
        self.assertEqual(animation[33:36], bytes.fromhex("0000c8"))
        self.assertEqual(animation[37:43], bytes.fromhex("000000080008"))
        self.assertEqual(animation[43], 0)

    def test_frame_animation_can_encode_known_text_effect_modes(self):
        frame = spotled.build_rgb_frame(2, 1, bytes((255, 255, 255)) * 2)
        animation = spotled.build_frame_animation([frame], frame_time_ms=500, mode=6)
        self.assertEqual(animation[43], 6)

        for start, length in ((0, 9), (9, 18), (27, 10), (37, 8)):
            object_header = animation[start : start + length]
            self.assertEqual(int.from_bytes(object_header[:4], "big"), length)
            self.assertEqual(object_header[length - 1], spotled.check_sum(object_header[:-1]))

    def test_frame_animation_encodes_android_text_speed_interval(self):
        frame = spotled.build_rgb_frame(2, 1, bytes((255, 255, 255)) * 2)
        for interval in (100, 200, 300):
            with self.subTest(interval=interval):
                animation = spotled.build_frame_animation([frame], frame_time_ms=interval, mode=3)
                self.assertEqual(int.from_bytes(animation[34:36], "big"), interval)
                self.assertEqual(animation[43], 3)

    def test_rgb_frame_rejects_wrong_pixel_count(self):
        with self.assertRaises(ValueError):
            spotled.build_rgb_frame(2, 1, b"\0\0\0")

    def test_rgb_rotation_preserves_expected_corner_order(self):
        # 2x3 source, with each pixel labeled by a distinct single-byte red
        # component: A B / C D / E F.
        source = bytes(value for value in (1, 2, 3, 4, 5, 6) for _ in range(3))

        width, height, clockwise = spotled.rotate_rgb_pixels(2, 3, source, 90)
        self.assertEqual((width, height), (3, 2))
        self.assertEqual(clockwise, bytes(value for value in (5, 3, 1, 6, 4, 2) for _ in range(3)))

        width, height, half_turn = spotled.rotate_rgb_pixels(2, 3, source, 180)
        self.assertEqual((width, height), (2, 3))
        self.assertEqual(half_turn, bytes(value for value in (6, 5, 4, 3, 2, 1) for _ in range(3)))

        width, height, counterclockwise = spotled.rotate_rgb_pixels(2, 3, source, 270)
        self.assertEqual((width, height), (3, 2))
        self.assertEqual(counterclockwise, bytes(value for value in (2, 4, 6, 1, 3, 5) for _ in range(3)))

    def test_shared_rgb_rotation_vectors_match_fixture(self):
        vector = SHARED_FIXTURES["rotations_rgb"]
        source = bytes.fromhex(vector["input_hex"])
        for degrees, expected in vector["expected"].items():
            with self.subTest(degrees=degrees):
                width, height, pixels = spotled.rotate_rgb_pixels(
                    vector["width"], vector["height"], source, int(degrees)
                )
                self.assertEqual((width, height), (expected["width"], expected["height"]))
                self.assertEqual(pixels.hex(), expected["hex"])

    def test_rgb_rotation_rejects_invalid_angle(self):
        with self.assertRaises(ValueError):
            spotled.rotate_rgb_pixels(1, 1, b"\0\0\0", 45)

    def test_text_raster_uses_captured_glyph_and_rotates(self):
        width, height, pixels = spotled.render_text_rgb("A", color=(255, 0, 0))
        self.assertEqual((width, height), (16, 16))
        self.assertTrue(any(pixels))
        self.assertTrue(any(pixels[i:i + 3] == bytes((255, 0, 0)) for i in range(0, len(pixels), 3)))
        rotated_width, rotated_height, rotated = spotled.rotate_rgb_pixels(width, height, pixels, 90)
        self.assertEqual((rotated_width, rotated_height), (16, 16))
        self.assertNotEqual(rotated, pixels)

    def test_text_raster_keeps_one_sixteen_pixel_cell_per_character(self):
        width, height, pixels = spotled.render_text_rgb("AB")
        self.assertEqual((width, height), (32, 16))
        self.assertEqual(len(pixels), 32 * 16 * 3)
        rotated_width, rotated_height, _ = spotled.rotate_rgb_pixels(width, height, pixels, 90)
        self.assertEqual((rotated_width, rotated_height), (16, 32))

    def test_animation_frame_sampling_and_receive_limit(self):
        self.assertEqual(spotled.select_frame_indices(10, 3), [0, 5, 9])
        colors = [bytes((r, 0, 0)) * 2 for r in (32, 96, 224)]
        frames = [(pixels, delay) for pixels, delay in zip(colors, (50, 100, 200))]

        payload, frame_count, interval = spotled.build_animation_transfer(
            frames, max_frames=2, receive_capacity=100, width=2, height=1
        )
        self.assertEqual(frame_count, 2)
        self.assertEqual(interval, 250)
        self.assertEqual(int.from_bytes(payload[21:23], "big"), 2)

        payload, frame_count, interval = spotled.build_animation_transfer(
            frames, max_frames=3, receive_capacity=80, width=2, height=1
        )
        self.assertEqual(frame_count, 2)
        self.assertLessEqual(len(payload), 80)
        self.assertEqual(int.from_bytes(payload[21:23], "big"), 2)

    def test_animation_uses_maximum_frame_count_that_fits_receive_capacity(self):
        colors = [bytes((r, 0, 0)) * 2 for r in (32, 96, 160, 224)]
        frames = [(pixels, 300) for pixels in colors]
        def payload_size(selected_frames):
            frame_objects = [spotled.build_rgb_frame(2, 1, pixels)
                             for pixels, _ in selected_frames]
            animation = spotled.build_frame_animation(
                frame_objects, frame_time_ms=300, mode=0
            )
            return len(spotled.wrap_set_data(animation))

        four_frame_capacity = payload_size(frames)
        _, frame_count, _ = spotled.build_animation_transfer(
            frames, max_frames=4, receive_capacity=four_frame_capacity, width=2, height=1
        )
        self.assertEqual(frame_count, 4)

        two_frame_capacity = payload_size((frames[0], frames[-1]))
        _, frame_count, _ = spotled.build_animation_transfer(
            frames, max_frames=4, receive_capacity=two_frame_capacity, width=2, height=1
        )
        self.assertEqual(frame_count, 2)

    def test_independent_three_by_five_font_and_glyph_rotation(self):
        width, height, pixels = spotled.render_text_rgb("A", raster_font="3x5")
        self.assertEqual((width, height), (3, 5))
        self.assertEqual(sum(1 for i in range(0, len(pixels), 3) if any(pixels[i:i + 3])), 10)
        self.assertEqual(spotled.FONT3X5["A"], ("010", "101", "111", "101", "101"))

        width, height, rotated = spotled.render_rotated_text_glyphs(
            "HELLO", 90, raster_font="3x5"
        )
        self.assertEqual((width, height), (29, 3))
        self.assertEqual(len(rotated), width * height * 3)

    def test_compact_font_glyphs_are_five_rows_and_three_columns(self):
        for character, rows in spotled.FONT3X5.items():
            with self.subTest(character=character):
                self.assertEqual(len(rows), 5)
                self.assertTrue(all(len(row) == 3 and set(row) <= {"0", "1"} for row in rows))

    def test_unsupported_bmp_characters_render_as_question_mark(self):
        for font in ("3x5", "panel16"):
            with self.subTest(font=font):
                replacement = spotled.render_text_rgb("?", raster_font=font)
                unsupported = spotled.render_text_rgb("é", raster_font=font)
                self.assertEqual(unsupported, replacement)

    def test_non_bmp_and_unpaired_surrogate_are_rejected(self):
        for text in ("😀", "\ud800"):
            with self.subTest(text=repr(text)):
                with self.assertRaisesRegex(ValueError, "BMP characters only"):
                    spotled.render_text_rgb(text, raster_font="3x5")

    def test_contain_preserves_aspect_and_centers_padding(self):
        red = bytes((255, 0, 0))
        blue = bytes((0, 0, 255))
        width, height, fitted = spotled.fit_rgb_pixels(2, 1, red + blue, 4, 4)

        self.assertEqual((width, height), (4, 4))
        self.assertEqual(fitted[:12], bytes(12))
        self.assertEqual(fitted[12:24], red + red + blue + blue)
        self.assertEqual(fitted[24:36], red + red + blue + blue)
        self.assertEqual(fitted[36:], bytes(12))

    def test_cover_center_crops_and_stretch_fills_canvas(self):
        colors = [bytes((n, 0, 0)) for n in (1, 2, 3, 4)]
        source = b"".join(colors + colors)
        _, _, covered = spotled.fit_rgb_pixels(4, 2, source, 2, 2, fit="cover")
        self.assertEqual(covered, b"".join(colors[1:3] * 2))

        _, _, stretched = spotled.fit_rgb_pixels(4, 2, source, 2, 2, fit="stretch")
        self.assertEqual(stretched, colors[1] + colors[3] + colors[1] + colors[3])

    def test_contain_downscale_does_not_drop_a_sparse_marker(self):
        source = bytearray(16 * 64 * 3)
        source[:3] = bytes((255, 0, 0))
        _, _, fitted = spotled.fit_rgb_pixels(16, 64, source, 64, 16, fit="contain")
        self.assertTrue(any(fitted))


if __name__ == "__main__":
    unittest.main()
