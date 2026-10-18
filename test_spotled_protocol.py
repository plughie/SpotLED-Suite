"""Offline regression checks for the reconstructed SPOTLED wire format."""

import sys
import unittest
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

from spotled_common import sender as spotled


class CliTests(unittest.TestCase):
    def test_device_name_defaults_to_spotled_prefix_and_can_be_overridden(self):
        with patch.object(sys, "argv", ["SPOTLED-v3.py", "HELLO"]):
            self.assertEqual(spotled.parse_cli_args().device_name, "SpotLED_*")
        with patch.object(sys, "argv", [
            "SPOTLED-v3.py", "--image", "art.gif", "--device-name", "MySpotLED"
        ]):
            self.assertEqual(spotled.parse_cli_args().device_name, "MySpotLED")

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
