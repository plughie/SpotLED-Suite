#!/usr/bin/env python3
"""Python reference sender for the SPOTLED 1.9.4 BLE protocol."""
import argparse, asyncio, sys
from contextlib import asynccontextmanager
from math import ceil, floor
from .ble_lock import ble_process_lock
from .settings import (
    DEFAULT_DEVICE_NAME, TEXT_EFFECTS, TEXT_SPEEDS_MS, text_effect_mode, text_speed_ms,
)
from .font3x5 import FONT3X5
from .generated_glyphs import CAPTURED_PANEL16_HEX

DEVICE_NAME = DEFAULT_DEVICE_NAME
NAMED_TEXT_COLORS = {
    "white": (255, 255, 255), "red": (255, 0, 0),
    "orange": (255, 128, 0), "yellow": (255, 255, 0),
    "green": (0, 255, 0), "cyan": (0, 255, 255),
    "blue": (0, 0, 255), "magenta": (255, 0, 255),
}
CMD = "0000ff21-0000-1000-8000-00805f9b34fb"
DATA = "0000ff22-0000-1000-8000-00805f9b34fb"


class _LazyBleak:
    def __init__(self, symbol): self.symbol = symbol
    def _value(self):
        try:
            import bleak
        except ImportError as exc:
            raise RuntimeError("BLE sending requires Bleak; install the project dependencies.") from exc
        return getattr(bleak, self.symbol)
    def __getattr__(self, name): return getattr(self._value(), name)
    def __call__(self, *args, **kwargs): return self._value()(*args, **kwargs)


BleakClient = _LazyBleak("BleakClient")
BleakScanner = _LazyBleak("BleakScanner")

@asynccontextmanager
async def connected_device(device_name=DEVICE_NAME, scan_timeout=10,
                           connect_timeout=30, attempts=2):
    """Connect while holding an optional same-host sender lock."""
    with ble_process_lock():
        async with _connected_device_unlocked(device_name, scan_timeout,
                                              connect_timeout, attempts) as client:
            yield client


@asynccontextmanager
async def _connected_device_unlocked(device_name=DEVICE_NAME, scan_timeout=10,
                                     connect_timeout=30, attempts=2):
    """Discover/connect with two bounded attempts and actionable failures."""
    if attempts < 1:
        raise ValueError("attempts must be positive")
    device = None
    discovery_error = None
    for attempt in range(attempts):
        try:
            device = await find_spotled_device(device_name, scan_timeout)
            if device is not None:
                break
        except Exception as exc:
            discovery_error = exc
        if attempt + 1 < attempts:
            await asyncio.sleep(0.5)
    if device is None:
        if discovery_error is not None:
            raise RuntimeError(
                f"BLE discovery failed for {device_name!r} after {attempts} attempts: "
                f"{discovery_error}. Check that the display is powered on and advertising"
            ) from discovery_error
        raise RuntimeError(
            f"Could not find BLE device {device_name!r} after {attempts} scans; "
            "check its advertised name, Bluetooth range, and power"
        )

    client = None
    connection_error = None
    for attempt in range(attempts):
        client = BleakClient(device, timeout=connect_timeout)
        try:
            async with asyncio.timeout(connect_timeout):
                await client.connect()
            break
        except Exception as exc:
            connection_error = exc
            try:
                await client.disconnect()
            except Exception:
                pass
            if attempt + 1 < attempts:
                await asyncio.sleep(0.5)
    else:
        raise RuntimeError(
            f"Found {device_name!r} but could not connect after {attempts} attempts: "
            f"{connection_error}. Check that it is not connected to another phone"
        ) from connection_error

    try:
        yield client
    finally:
        if client.is_connected:
            await client.disconnect()

async def find_spotled_device(device_name=DEVICE_NAME, scan_timeout=10):
    """Resolve an exact advertised name or a trailing-asterisk name prefix."""
    if not device_name.endswith("*"):
        return await BleakScanner.find_device_by_name(device_name, timeout=scan_timeout)

    prefix = device_name[:-1]
    advertisements = await BleakScanner.discover(timeout=scan_timeout, return_adv=True)
    matches = []
    for device, advertisement in advertisements.values():
        name = advertisement.local_name or device.name or ""
        if name.startswith(prefix):
            matches.append((name, device))
    unique = {device.address: (name, device) for name, device in matches}
    matches = list(unique.values())
    if not matches:
        return None
    if len(matches) == 1:
        return matches[0][1]
    print("Matching SPOTLED displays:")
    for index, (name, device) in enumerate(matches, start=1):
        print(f"  {index}. {name} (…{device.address[-5:]})")
    while True:
        choice = input(f"Select device [1-{len(matches)}]: ").strip()
        if choice.isdigit() and 1 <= int(choice) <= len(matches):
            return matches[int(choice) - 1][1]
        print("Enter one of the listed numbers.")

CAP_GLYPHS = CAPTURED_PANEL16_HEX
# Uncaptured native BMP glyphs use the shared compact 3x5 '?' fallback.
def check_sum(data):
    """python-spotled Utils.checkSum: twos complement if >255"""
    s = sum(data)
    if s > 255: return ((~s) + 1) & 0xFF
    return s & 0xFF

def get_unicode(ch):
    """Utils.getUnicode: high byte, low byte"""
    c = ord(ch)
    return bytes([((c >> 8) & 0xFF), c & 0xFF])

def normalize_text_colors(text, color):
    """Return one validated RGB triple per character; 'rainbow' is static by glyph."""
    if isinstance(color, str):
        name = color.lower()
        if name == "rainbow":
            count = max(1, len(text))
            return [_rainbow_rgb(360.0 * index / count) for index in range(len(text))]
        if name not in NAMED_TEXT_COLORS:
            raise ValueError(f"Unsupported text color {color!r}")
        return [NAMED_TEXT_COLORS[name]] * len(text)

    if isinstance(color, (tuple, list)) and len(color) == 3 and all(
            isinstance(component, int) for component in color):
        colors = [tuple(color)] * len(text)
    else:
        try:
            colors = [tuple(rgb) for rgb in color]
        except (TypeError, ValueError) as exc:
            raise ValueError("color must be an RGB triple, per-character RGB triples, or a palette name") from exc
        if len(colors) != len(text):
            raise ValueError("per-character colors must match the text length")

    if any(len(rgb) != 3 or any(not isinstance(c, int) or not 0 <= c <= 255 for c in rgb)
           for rgb in colors):
        raise ValueError("every text color must be an RGB triple with components from 0 to 255")
    return colors

def _rainbow_rgb(hue_degrees):
    hue = (hue_degrees % 360.0) / 60.0
    x = 1.0 - abs((hue % 2.0) - 1.0)
    if hue < 1:
        red, green, blue = 1.0, x, 0.0
    elif hue < 2:
        red, green, blue = x, 1.0, 0.0
    elif hue < 3:
        red, green, blue = 0.0, 1.0, x
    elif hue < 4:
        red, green, blue = 0.0, x, 1.0
    elif hue < 5:
        red, green, blue = x, 0.0, 1.0
    else:
        red, green, blue = 1.0, 0.0, x
    return tuple(int(component * 255 + 0.5) for component in (red, green, blue))

def get_multiples_of8(n):
    """Utils.getMultiplesOf8"""
    return n if n % 8 == 0 else ((n // 8) + 1) * 8

def _validate_native_text(text):
    if len(text) > 160:
        raise ValueError("Native text exceeds the 160-character device limit")
    if any(ord(ch) > 0xFFFF or 0xD800 <= ord(ch) <= 0xDFFF for ch in text):
        raise ValueError("Native text supports BMP characters only; emoji are not supported")


def make_font_object(ch, rotation_degrees=0):
    """Build a captured FontObject or scale the shared 3x5 fallback to 16x16."""
    if not isinstance(ch, str) or len(ch) != 1:
        raise ValueError("A native FontObject must encode exactly one character")
    _validate_native_text(ch)
    code = ord(ch)
    hex_data = CAP_GLYPHS.get(str(code))
    if hex_data:
        glyph = bytes.fromhex(hex_data)
        return rotate_font_object(glyph, rotation_degrees) if rotation_degrees else glyph
    fallback_char = ch.upper() if ch.isascii() and len(ch.upper()) == 1 else "?"
    rows = FONT3X5.get(fallback_char, FONT3X5["?"])
    bitmap = [0] * 16
    for y, row in enumerate(rows):
        for x, bit in enumerate(row):
            if bit != "1":
                continue
            px = 2 + x * 4
            py = 3 + y * 2
            for yy in range(py, py + 2):
                for xx in range(px, px + 4):
                    bitmap[yy] |= 0x8000 >> xx
    bm = bytearray()
    for row in range(16):
        bm.append((bitmap[row] >> 8) & 0xFF)
        bm.append(bitmap[row] & 0xFF)
    # Build FontObject: type=13, flag=1, w=16, h=16, char_code, offset_x=0x20, bitmap
    entry = bytearray(b'\x00\x00\x00\x2f\x00\x0d\x01\x00\x10\x00\x10')
    entry += code.to_bytes(2, 'big') + b'\x20' + bm
    entry.append(check_sum(entry))
    glyph = bytes(entry)
    return rotate_font_object(glyph, rotation_degrees) if rotation_degrees else glyph

def rotate_font_object(glyph, degrees):
    """Rotate a native 16×16 glyph bitmap clockwise, preserving its font record."""
    if degrees not in (0, 90, 270):
        raise ValueError("native glyph rotation must be 0, 90, or 270 degrees")
    glyph = bytearray(glyph)
    if len(glyph) != 47:
        raise ValueError("native FontObject must be 16×16")
    if degrees == 0:
        return bytes(glyph)
    source_rows = [int.from_bytes(glyph[14 + y * 2:16 + y * 2], 'big') for y in range(16)]
    target_rows = [0] * 16
    for y, row in enumerate(source_rows):
        for x in range(16):
            if not row & (0x8000 >> x):
                continue
            out_x, out_y = ((15 - y, x) if degrees == 90 else (y, 15 - x))
            target_rows[out_y] |= 0x8000 >> out_x
    for y, row in enumerate(target_rows):
        glyph[14 + y * 2:16 + y * 2] = row.to_bytes(2, 'big')
    glyph[-1] = check_sum(glyph[:-1])
    return bytes(glyph)

def build_word_stock(text, glyph_rotation=0):
    """WordStockObject (type 5) with all FontObjects as followContent"""
    _validate_native_text(text)
    font_objs = []
    for ch in text:
        font_objs.append(make_font_object(ch) if glyph_rotation == 0
                         else make_font_object(ch, rotation_degrees=glyph_rotation))

    # WordStock header
    content = bytearray()
    content += len(font_objs).to_bytes(2, 'big')

    # followContent = all FontObjects concatenated
    follow_content = bytearray()
    for fo in font_objs:
        follow_content += fo

    # WordStock.getData() format
    length = len(content) + 7
    header = bytearray()
    header += length.to_bytes(4, 'big')
    header += (5).to_bytes(2, 'big')  # type=5
    header += content
    header.append(check_sum(header))
    header += follow_content

    return bytes(header)

def build_character_string(text, color=(0xff,0xff,0xff), time_ms=200, speed=3, mode=0):
    """CharacterStringObject with per-character Color/Text and effect timing.

    ``speed`` is retained for compatibility with older callers; the firmware
    timing field is controlled by ``time_ms``.
    """
    _validate_native_text(text)
    if not 1 <= time_ms <= 0xFFFF:
        raise ValueError("time_ms must be from 1 to 65,535")
    character_colors = normalize_text_colors(text, color)
    # Header: text_len(2) + flag(1)
    content = bytearray()
    content += len(text).to_bytes(2, 'big')
    content.append(1)  # flag=1

    # followContent
    follow = bytearray()

    # Color + Text for each char
    for char_index, ch in enumerate(text):
        # ColorObject (type=2)
        co_len = 3 + 7
        co = bytearray()
        co += co_len.to_bytes(4, 'big')
        co += (2).to_bytes(2, 'big')
        co += bytes(character_colors[char_index])
        co.append(check_sum(co))
        follow += co

        # TextObject (type=3)
        to_len = 2 + 7
        to = bytearray()
        to += to_len.to_bytes(4, 'big')
        to += (3).to_bytes(2, 'big')
        to += get_unicode(ch)
        to.append(check_sum(to))
        follow += to

    # TimeObject (type=7)
    tm_len = 3 + 7
    tm = bytearray()
    tm += tm_len.to_bytes(4, 'big')
    tm += (7).to_bytes(2, 'big')
    tm.append(0)  # zero
    tm += time_ms.to_bytes(2, 'big')
    tm.append(check_sum(tm))
    follow += tm

    # SpecialEffectsObject (type=8)
    ef_len = 1 + 7
    ef = bytearray()
    ef += ef_len.to_bytes(4, 'big')
    ef += (8).to_bytes(2, 'big')
    ef.append(mode)
    ef.append(check_sum(ef))
    follow += ef

    # CharacterString.getData()
    length = len(content) + 7
    header = bytearray()
    header += length.to_bytes(4, 'big')
    header += (4).to_bytes(2, 'big')
    header += content
    header.append(check_sum(header))
    header += follow

    return bytes(header)

def build_object(object_id, content, follow_content=b""):
    """Serialize an Android Object: header checksum covers content only."""
    header = bytearray()
    header += (len(content) + 7).to_bytes(4, 'big')
    header += object_id.to_bytes(2, 'big')
    header += content
    header.append(check_sum(header))
    return bytes(header) + follow_content

def build_rgb_frame(width, height, pixels):
    """Build APK-compatible 24-bit RGB DynamicColorFrameObject (id 0x60)."""
    if not 1 <= width <= 0xFFFF or not 1 <= height <= 0xFFFF:
        raise ValueError("frame width and height must fit in nonzero 16-bit fields")
    pixels = bytes(pixels)
    expected = width * height * 3
    if len(pixels) != expected:
        raise ValueError(f"RGB frame needs {expected} bytes, got {len(pixels)}")
    content = width.to_bytes(2, 'big') + height.to_bytes(2, 'big') + b'\x18' + pixels
    return build_object(0x60, content)

def render_text_rgb(text, color=(255, 255, 255), background=(0, 0, 0),
                    raster_font="panel16"):
    """Rasterize text with captured 16x16 glyphs or the compact independent 3x5 font."""
    if not text:
        raise ValueError("text must not be empty")
    if any(ord(ch) > 0xFFFF or 0xD800 <= ord(ch) <= 0xDFFF for ch in text):
        raise ValueError("Text rasterization currently supports BMP characters only")
    character_colors = normalize_text_colors(text, color)
    if len(background) != 3 or any(not 0 <= c <= 255 for c in background):
        raise ValueError("background must be an RGB triple")

    if raster_font == "3x5":
        width, height = 4 * len(text) - 1, 5
        pixels = bytearray(bytes(background) * width * height)
        for char_index, char in enumerate(text):
            key = char.upper() if char.isascii() and len(char.upper()) == 1 else "?"
            rows = FONT3X5.get(key, FONT3X5["?"])
            glyph_color = bytes(character_colors[char_index])
            for y, row in enumerate(rows):
                for x, bit in enumerate(row):
                    if bit == "1":
                        dest = (y * width + char_index * 4 + x) * 3
                        pixels[dest:dest + 3] = glyph_color
        return width, height, bytes(pixels)
    if raster_font != "panel16":
        raise ValueError("raster_font must be 'panel16' or '3x5'")

    width, height = 16 * len(text), 16
    pixels = bytearray(bytes(background) * width * height)
    for char_index, char in enumerate(text):
        glyph = make_font_object(char)
        glyph_color = bytes(character_colors[char_index])
        glyph_width = int.from_bytes(glyph[7:9], 'big')
        glyph_height = int.from_bytes(glyph[9:11], 'big')
        row_bytes = (glyph_width + 7) // 8
        bitmap = glyph[14:14 + row_bytes * glyph_height]
        for y in range(min(height, glyph_height)):
            for x in range(min(16, glyph_width)):
                byte = bitmap[y * row_bytes + x // 8]
                if byte & (0x80 >> (x % 8)):
                    dest_x = char_index * 16 + x
                    dest = (y * width + dest_x) * 3
                    pixels[dest:dest + 3] = glyph_color
    return width, height, bytes(pixels)

def render_rotated_text_glyphs(text, rotation, color=(255, 255, 255),
                               raster_font="3x5", gap=1):
    """Rotate each glyph independently, then keep the word flowing horizontally."""
    if rotation not in (0, 90, 180, 270):
        raise ValueError("rotation must be 0, 90, 180, or 270 degrees")
    if not text:
        raise ValueError("text must not be empty")

    character_colors = normalize_text_colors(text, color)
    glyphs = []
    for char_index, char in enumerate(text):
        width, height, pixels = render_text_rgb(
            char, color=character_colors[char_index], raster_font=raster_font
        )
        glyphs.append(rotate_rgb_pixels(width, height, pixels, rotation))

    output_width = sum(glyph[0] for glyph in glyphs) + gap * (len(glyphs) - 1)
    output_height = max(glyph[1] for glyph in glyphs)
    output = bytearray(output_width * output_height * 3)
    x_offset = 0
    for glyph_width, glyph_height, pixels in glyphs:
        y_offset = (output_height - glyph_height) // 2
        for y in range(glyph_height):
            source = y * glyph_width * 3
            dest = ((y + y_offset) * output_width + x_offset) * 3
            output[dest:dest + glyph_width * 3] = pixels[source:source + glyph_width * 3]
        x_offset += glyph_width + gap
    return output_width, output_height, bytes(output)

def rotate_rgb_pixels(width, height, pixels, degrees):
    """Rotate a row-major RGB image clockwise without scaling or cropping.

    Quarter-turns swap the returned width and height. Fitting that result to
    the physical 64x16 screen is deliberately a separate operation.
    """
    if width <= 0 or height <= 0:
        raise ValueError("image width and height must be positive")
    pixels = bytes(pixels)
    if len(pixels) != width * height * 3:
        raise ValueError("pixel byte count does not match image dimensions")
    degrees %= 360
    if degrees not in (0, 90, 180, 270):
        raise ValueError("rotation must be 0, 90, 180, or 270 degrees")

    out_width, out_height = (height, width) if degrees in (90, 270) else (width, height)
    output = bytearray(len(pixels))
    for y in range(height):
        for x in range(width):
            if degrees == 0:
                out_x, out_y = x, y
            elif degrees == 90:
                out_x, out_y = height - 1 - y, x
            elif degrees == 180:
                out_x, out_y = width - 1 - x, height - 1 - y
            else:  # 270 degrees clockwise
                out_x, out_y = y, width - 1 - x
            source = (y * width + x) * 3
            destination = (out_y * out_width + out_x) * 3
            output[destination:destination + 3] = pixels[source:source + 3]
    return out_width, out_height, bytes(output)

def fit_rgb_pixels(width, height, pixels, target_width=64, target_height=16,
                   fit="contain", background=(0, 0, 0)):
    """Fit row-major RGB pixels using area averaging and center placement.

    ``contain`` preserves the whole image and pads with the background color;
    ``cover`` preserves aspect ratio and center-crops; ``stretch`` fills the
    canvas without preserving aspect ratio.
    """
    if min(width, height, target_width, target_height) <= 0:
        raise ValueError("source and target dimensions must be positive")
    pixels = bytes(pixels)
    if len(pixels) != width * height * 3:
        raise ValueError("pixel byte count does not match source dimensions")
    if len(background) != 3 or any(not 0 <= c <= 255 for c in background):
        raise ValueError("background must be an RGB triple")
    if fit not in ("contain", "cover", "stretch"):
        raise ValueError("fit must be 'contain', 'cover', or 'stretch'")

    if fit == "stretch":
        scaled_width, scaled_height = target_width, target_height
    else:
        scale = (min if fit == "contain" else max)(
            target_width / width, target_height / height
        )
        scaled_width = max(1, round(width * scale))
        scaled_height = max(1, round(height * scale))
        if fit == "contain":
            scaled_width = min(target_width, scaled_width)
            scaled_height = min(target_height, scaled_height)
        else:
            scaled_width = max(target_width, scaled_width)
            scaled_height = max(target_height, scaled_height)

    scaled = bytearray(scaled_width * scaled_height * 3)
    for y in range(scaled_height):
        y0 = y * height / scaled_height
        y1 = (y + 1) * height / scaled_height
        for x in range(scaled_width):
            destination = (y * scaled_width + x) * 3
            x0 = x * width / scaled_width
            x1 = (x + 1) * width / scaled_width
            totals = [0.0, 0.0, 0.0]
            total_area = 0.0
            for source_y in range(floor(y0), ceil(y1)):
                overlap_y = min(y1, source_y + 1) - max(y0, source_y)
                for source_x in range(floor(x0), ceil(x1)):
                    overlap_x = min(x1, source_x + 1) - max(x0, source_x)
                    area = overlap_x * overlap_y
                    source = (source_y * width + source_x) * 3
                    for channel in range(3):
                        totals[channel] += pixels[source + channel] * area
                    total_area += area
            for channel in range(3):
                scaled[destination + channel] = min(
                    255, int(totals[channel] / total_area + 0.5)
                )

    output = bytearray(bytes(background) * (target_width * target_height))
    if fit == "cover":
        crop_x = (scaled_width - target_width) // 2
        crop_y = (scaled_height - target_height) // 2
        for y in range(target_height):
            source = ((y + crop_y) * scaled_width + crop_x) * 3
            destination = y * target_width * 3
            output[destination:destination + target_width * 3] = scaled[source:source + target_width * 3]
    else:
        offset_x = (target_width - scaled_width) // 2
        offset_y = (target_height - scaled_height) // 2
        for y in range(scaled_height):
            source = y * scaled_width * 3
            destination = ((y + offset_y) * target_width + offset_x) * 3
            output[destination:destination + scaled_width * 3] = scaled[source:source + scaled_width * 3]
    return target_width, target_height, bytes(output)

def load_image_pixels(path, rotation=0, fit="contain", target_width=64,
                      target_height=16, background=(0, 0, 0)):
    """Load the first frame of an image, rotate it, and fit it to the display."""
    try:
        from PIL import Image, ImageOps
    except ImportError as exc:
        raise RuntimeError(
            "Image input requires Pillow. Install it with `python -m pip install Pillow`, "
            "or run with uv using `uv run --with bleak --with pillow python SPOTLED-v3.py`."
        ) from exc

    with Image.open(path) as source_image:
        image = ImageOps.exif_transpose(source_image).convert("RGBA")
        matte = Image.new("RGBA", image.size, tuple(background) + (255,))
        image = Image.alpha_composite(matte, image).convert("RGB")
        width, height = image.size
        rgb = image.tobytes()
    width, height, rgb = rotate_rgb_pixels(width, height, rgb, rotation)
    return fit_rgb_pixels(width, height, rgb, target_width, target_height,
                          fit=fit, background=background)

def select_frame_indices(frame_count, limit):
    """Evenly sample a sequence, retaining its first and last frames."""
    if frame_count < 1 or limit < 1:
        raise ValueError("frame_count and limit must be positive")
    if frame_count <= limit:
        return list(range(frame_count))
    if limit == 1:
        return [0]
    return [int(i * (frame_count - 1) / (limit - 1) + 0.5) for i in range(limit)]


def animation_interval_ms(total_duration_ms, sent_frame_count):
    """Shared GIF cadence: source-cycle duration divided by sent frames, clamped safely."""
    if sent_frame_count < 1:
        raise ValueError("sent_frame_count must be positive")
    return max(250, min(1000, int(max(0, total_duration_ms) / sent_frame_count + 0.5)))


def image_animation_metadata(path):
    """Return source frame count and normalized full-cycle delay for GIF reporting."""
    try:
        from PIL import Image
    except ImportError as exc:
        raise RuntimeError("Image input requires Pillow; install the project dependencies.") from exc
    with Image.open(path) as source:
        count = getattr(source, "n_frames", 1)
        total_ms = 0
        for index in range(count):
            source.seek(index)
            delay = int(source.info.get("duration", 100) or 100)
            total_ms += max(20, delay)
        return count, total_ms

def load_image_frames(path, rotation=0, fit="contain", target_width=64,
                      target_height=16, background=(0, 0, 0), max_frames=20):
    """Load and transform up to max_frames, returning RGB frames and GIF delays."""
    try:
        from PIL import Image, ImageOps
    except ImportError as exc:
        raise RuntimeError(
            "Image input requires Pillow. Install it with `python -m pip install Pillow`, "
            "or run with uv using `uv run --with bleak --with pillow python SPOTLED-v3.py`."
        ) from exc

    with Image.open(path) as source:
        total_frames = getattr(source, "n_frames", 1)
        indices = select_frame_indices(total_frames, max_frames)
        output = []
        for index in indices:
            source.seek(index)
            delay_ms = int(source.info.get("duration", 100))
            frame = ImageOps.exif_transpose(source.copy()).convert("RGBA")
            matte = Image.new("RGBA", frame.size, tuple(background) + (255,))
            frame = Image.alpha_composite(matte, frame).convert("RGB")
            width, height, pixels = rotate_rgb_pixels(
                frame.width, frame.height, frame.tobytes(), rotation
            )
            width, height, pixels = fit_rgb_pixels(
                width, height, pixels, target_width, target_height,
                fit=fit, background=background
            )
            output.append((pixels, max(1, delay_ms)))
    return output

def build_animation_transfer(frames, max_frames, receive_capacity, width=64, height=16,
                             total_duration_ms=None):
    """Preserve as many evenly sampled animation frames as the device accepts.

    The protocol supplies one shared TimeObject for a FrameAnimationObject,
    so this uses the average delay of the selected source frames.
    """
    if not frames:
        raise ValueError("animation has no frames")
    if max_frames < 1:
        raise ValueError("max_frames must be positive")
    frame_limit = min(len(frames), max_frames)
    single_frame_size = 0
    for frame_count in range(frame_limit, 0, -1):
        indices = select_frame_indices(len(frames), frame_count)
        selected = [frames[i] for i in indices]
        delays = [delay for _, delay in selected]
        # Keep the source animation's cycle duration approximately constant.
        duration = total_duration_ms if total_duration_ms is not None else sum(delays)
        interval = animation_interval_ms(duration, frame_count)
        frame_objects = [build_rgb_frame(width, height, pixels) for pixels, _ in selected]
        animation = build_frame_animation(frame_objects, frame_time_ms=interval, mode=0)
        payload = wrap_set_data(animation)
        if frame_count == 1:
            single_frame_size = len(payload)
        if len(payload) <= receive_capacity:
            return payload, len(selected), interval
    raise ValueError(
        f"Single-frame transfer is {single_frame_size} bytes, exceeding the "
        f"device receive limit ({receive_capacity} bytes)"
    )

def save_rgb_preview(path, width, height, pixels):
    """Write a PNG preview of a device-sized RGB frame."""
    try:
        from PIL import Image
    except ImportError as exc:
        raise RuntimeError(
            "Saving a preview requires Pillow. Install it with `python -m pip install Pillow`, "
            "or run with uv using `uv run --with bleak --with pillow python SPOTLED-v3.py`."
        ) from exc
    if len(pixels) != width * height * 3:
        raise ValueError("pixel byte count does not match preview dimensions")
    Image.frombytes("RGB", (width, height), bytes(pixels)).save(path)

def build_frame_animation(frames, frame_time_ms=500, mode=0):
    """Build FrameAnimationObject with frames/time/effects in follow-content."""
    frames = list(frames)
    if not 1 <= len(frames) <= 0xFFFF:
        raise ValueError("animation must contain at least one frame")
    if not 0 <= frame_time_ms <= 0xFFFF:
        raise ValueError("frame_time_ms must fit in an unsigned 16-bit field")
    if not 0 <= mode <= 0xFF:
        raise ValueError("mode must fit in one byte")
    time_object = build_object(7, b'\x00' + frame_time_ms.to_bytes(2, 'big'))
    effects_object = build_object(8, bytes((mode,)))
    follow_content = b''.join(frames) + time_object + effects_object
    return build_object(11, len(frames).to_bytes(2, 'big'), follow_content)

def wrap_set_data(object_data):
    """SetDataObjectCommand wrap: [len(4)=15][id(2)][serial(4)][content_len(4)][checksum(1)][object_data]"""
    cmd_id = 32772  # CMD_SET_DATA_OBJECT
    content_len = len(object_data)

    header = bytearray()
    header += (15).to_bytes(4, 'big')
    header += cmd_id.to_bytes(2, 'big')
    header += get_serial().to_bytes(4, 'big')
    header += content_len.to_bytes(4, 'big')
    header.append(check_sum(header))
    header += object_data

    return bytes(header)

serial_num = 1
def get_serial():
    global serial_num
    s = serial_num
    serial_num += 1
    return s

def build_start_notification(cmd_id, total_len):
    """StartSendingNotificationCommand (id=1): [len=10][id=1][serial(2)][cmd_id(2)][total_len(4)]"""
    ser = get_serial()
    payload = bytearray()
    payload.append(10)  # length=10
    payload.append(1)   # id=START
    payload += ser.to_bytes(2, 'big')
    payload += cmd_id.to_bytes(2, 'big')
    payload += total_len.to_bytes(4, 'big')
    return bytes(payload), ser

def build_end_notification(serial, cmd_id, total_len):
    """EndSendingNotificationCommand (id=3): [len=10][id=3][serial(2)][cmd_id(2)][total_len(4)]"""
    payload = bytearray()
    payload.append(10)  # length=10
    payload.append(3)   # id=END
    payload += serial.to_bytes(2, 'big')
    payload += cmd_id.to_bytes(2, 'big')
    payload += total_len.to_bytes(4, 'big')
    return bytes(payload)

async def write_with_flow(client, char_uuid, data, mtu=182):
    """Write data in MTU chunks with 5ms delay (from CommandExecutor.write)"""
    for i in range(0, len(data), mtu):
        chunk = data[i:i+mtu]
        await client.write_gatt_char(char_uuid, chunk, response=False)
        await asyncio.sleep(0.005)  # 5ms delay per chunk

async def send_query(client, query_type):
    """Send Android-compatible query frame: length, id, 16-bit serial."""
    payload = build_query(query_type)
    await client.write_gatt_char(CMD, payload, response=False)


def build_query(query_type, serial=None):
    """Build the two-byte command header and two-byte query serial."""
    if not 0 <= query_type <= 0xFF:
        raise ValueError("query_type must fit in one byte")
    if serial is None:
        serial = get_serial()
    if not 0 <= serial <= 0xFFFF:
        raise ValueError("query serial must fit in two bytes")
    return bytes((4, query_type)) + serial.to_bytes(2, 'big')

async def retry_read_query(operation, retries=1, delay=0.25):
    """Retry only a timed-out read-only query, never a content transfer."""
    if retries < 0:
        raise ValueError("retries must be nonnegative")
    for attempt in range(retries + 1):
        try:
            return await operation()
        except TimeoutError:
            if attempt >= retries:
                raise
            await asyncio.sleep(delay)

def build_command_data(command_id, content, serial):
    """Build Android Command.getData() framing for content-bearing commands."""
    content = bytes(content)
    if not 0 <= command_id <= 0xFFFF:
        raise ValueError("command_id must fit in two bytes")
    if not 0 <= serial <= 0xFFFFFFFF:
        raise ValueError("command serial must fit in four bytes")
    header = bytearray()
    header += (len(content) + 11).to_bytes(4, 'big')
    header += command_id.to_bytes(2, 'big')
    header += serial.to_bytes(4, 'big')
    header += content
    header.append(check_sum(header))
    return bytes(header)

def build_initial_playlist_query():
    """QueryPlaylistObjectCommand(type=0, playlist id=0), matching prior bytes."""
    return build_command_data(0x800C, bytes(9), serial=1)

def build_visible_pixel_map_query():
    """QueryDeviceObjectCommand for ScreenVisiblePointObject id 0x10."""
    return build_command_data(0x8000, b'\x00\x10', serial=3)

def parse_visible_pixel_map_response(frame):
    """Parse Android's QueryDeviceObjectResponse for ScreenVisiblePointObject."""
    frame = bytes(frame)
    if len(frame) < 22 or int.from_bytes(frame[:4], 'big') != len(frame):
        raise ValueError("not a complete device-object response")
    if int.from_bytes(frame[4:6], 'big') != 0x8001:
        raise ValueError("not a QueryDeviceObject response")
    if int.from_bytes(frame[16:18], 'big') != 0x0010:
        raise ValueError("device-object response is not the visible-pixel map")
    width = int.from_bytes(frame[18:20], 'big')
    height = int.from_bytes(frame[20:22], 'big')
    pixel_count = width * height
    if not width or not height or pixel_count % 8:
        raise ValueError("visible-pixel map dimensions must be nonzero and byte aligned")
    bitmap_length = pixel_count // 8
    if len(frame) < 22 + bitmap_length:
        raise ValueError("visible-pixel map bitmap is truncated")
    bitmap = frame[22:22 + bitmap_length]
    mask = tuple((byte >> shift) & 1 for byte in bitmap for shift in range(7, -1, -1))
    return {"width": width, "height": height, "mask": mask}

def build_device_control(onoff, sequence):
    """Build DeviceControlObjectCommand (id 0x80FE) with a two-byte value."""
    if not 0 <= onoff <= 0xFFFF:
        raise ValueError("device-control value must fit in two bytes")
    return build_command_data(0x80FE, onoff.to_bytes(2, 'big'), sequence)

def parse_screen_information_response(frame, expected_serial=None):
    """Parse Android QueryDeviceScreenInformation response (command 0x13)."""
    frame = bytes(frame)
    if len(frame) < 14 or frame[0] != len(frame) or frame[1] != 0x13:
        raise ValueError("not a complete screen-information response")
    serial = int.from_bytes(frame[2:4], 'big')
    if expected_serial is not None and serial != expected_serial:
        raise ValueError("screen-information response serial mismatch")
    if frame[4] != 0:
        raise RuntimeError(f"Device rejected screen-information query: status {frame[4]}")
    return {
        "width": int.from_bytes(frame[5:7], 'big'),
        "height": int.from_bytes(frame[7:9], 'big'),
        "color_type": frame[9],
        "max_frames": frame[10],
        "brightness": frame[11],
        "word_stock": frame[12],
        "light_up": frame[13],
    }

async def _query_screen_information_once(client, timeout=6):
    """Query current dimensions/color format without changing the display."""
    notifications = asyncio.Queue()
    def on_notification(_, data):
        notifications.put_nowait(bytes(data))

    await client.start_notify(CMD, on_notification)
    serial = get_serial() & 0xFFFF
    stream = bytearray()
    try:
        await client.write_gatt_char(CMD, build_query(0x12, serial), response=False)
        async with asyncio.timeout(timeout):
            while True:
                stream.extend(await notifications.get())
                while len(stream) >= 2:
                    frame_length = stream[0]
                    if frame_length < 2:
                        raise RuntimeError("Invalid BLE response frame length")
                    if len(stream) < frame_length:
                        break
                    frame = bytes(stream[:frame_length])
                    del stream[:frame_length]
                    if frame[1] == 0x13 and int.from_bytes(frame[2:4], 'big') == serial:
                        return parse_screen_information_response(frame, serial)
    except TimeoutError as exc:
        raise TimeoutError(
            f"No screen-information response (0x13) within {timeout}s"
        ) from exc
    finally:
        await client.stop_notify(CMD)

async def query_screen_information(client, timeout=6, retries=1):
    """Query dimensions/color, retrying once only when the read times out."""
    return await retry_read_query(
        lambda: _query_screen_information_once(client, timeout), retries=retries
    )

async def _query_simple_response_once(client, query_id, response_id, timeout=6):
    """Send a simple query and return its matching length-prefixed response."""
    notifications = asyncio.Queue()
    def on_notification(_, data):
        notifications.put_nowait(bytes(data))

    await client.start_notify(CMD, on_notification)
    serial = get_serial() & 0xFFFF
    stream = bytearray()
    try:
        await client.write_gatt_char(
            CMD, build_query(query_id, serial), response=False
        )
        async with asyncio.timeout(timeout):
            while True:
                stream.extend(await notifications.get())
                while len(stream) >= 2:
                    frame_length = stream[0]
                    if frame_length < 2:
                        raise RuntimeError("Invalid BLE response frame length")
                    if len(stream) < frame_length:
                        break
                    frame = bytes(stream[:frame_length])
                    del stream[:frame_length]
                    if (frame[1] == response_id and
                            int.from_bytes(frame[2:4], 'big') == serial):
                        return frame
    except TimeoutError as exc:
        raise TimeoutError(
            f"No response (0x{response_id:02X}) to query 0x{query_id:02X} "
            f"within {timeout}s"
        ) from exc
    finally:
        await client.stop_notify(CMD)

async def query_simple_response(client, query_id, response_id, timeout=6, retries=1):
    """Query a simple response, retrying once only on timeout."""
    return await retry_read_query(
        lambda: _query_simple_response_once(client, query_id, response_id, timeout),
        retries=retries,
    )

async def query_visible_pixel_map(client, timeout=8):
    """Read the panel's shape mask without changing its displayed content."""
    notifications = asyncio.Queue()
    captured = []

    def on_notification(_, data):
        notifications.put_nowait(bytes(data))

    await client.start_notify(CMD, on_notification)
    payload = build_visible_pixel_map_query()
    start, serial = build_start_notification(0x8000, len(payload))

    async def wait_ack(response_id, ack_timeout=8):
        async with asyncio.timeout(ack_timeout):
            while True:
                packet = await notifications.get()
                captured.append(packet)
                if (len(packet) >= 5 and packet[1] == response_id and
                        int.from_bytes(packet[2:4], 'big') == serial):
                    if packet[4] != 0:
                        raise RuntimeError(f"Device rejected shape-map query: {packet.hex()}")
                    return

    try:
        await client.write_gatt_char(CMD, start, response=False)
        await wait_ack(2)
        characteristic = client.services.get_characteristic(DATA)
        mtu = min(182, characteristic.max_write_without_response_size)
        await write_with_flow(client, DATA, payload, mtu=mtu)
        end = build_end_notification(serial, 0x8000, len(payload))
        await client.write_gatt_char(CMD, end, response=False)
        await wait_ack(4, ack_timeout=12)

        async with asyncio.timeout(timeout):
            while True:
                stream = b''.join(captured)
                for offset in range(max(0, len(stream) - 5)):
                    if stream[offset + 4:offset + 6] != b'\x80\x01':
                        continue
                    response_length = int.from_bytes(stream[offset:offset + 4], 'big')
                    if response_length < 22 or response_length > 4096:
                        continue
                    if len(stream) >= offset + response_length:
                        return parse_visible_pixel_map_response(
                            stream[offset:offset + response_length]
                        )
                try:
                    captured.append(await notifications.get())
                except TimeoutError as exc:
                    raise TimeoutError(
                        "No visible-pixel-map object response arrived after the transfer acknowledgment"
                    ) from exc
    finally:
        await client.stop_notify(CMD)

def parse_animation_capabilities_response(frame):
    frame = bytes(frame)
    if len(frame) < 9 or frame[0] != len(frame) or frame[1] != 0x1B:
        raise ValueError("not a complete animation/text capability response")
    if frame[4] != 0:
        raise RuntimeError(f"Device rejected capability query: status {frame[4]}")
    result = {
        "max_frames": int.from_bytes(frame[5:7], 'big'),
        "max_text_characters": int.from_bytes(frame[7:9], 'big'),
    }
    if len(frame) >= 13:
        result["storage_bytes"] = int.from_bytes(frame[9:13], 'big')
    return result

def parse_receive_capacity_response(frame):
    frame = bytes(frame)
    if len(frame) < 9 or frame[0] != len(frame) or frame[1] != 0x15:
        raise ValueError("not a complete receive-capacity response")
    if frame[4] != 0:
        raise RuntimeError(f"Device rejected receive-capacity query: status {frame[4]}")
    return int.from_bytes(frame[5:9], 'big')

async def query_image_capabilities(client):
    """Read and validate current screen/transfer capabilities for RGB output."""
    screen = await query_screen_information(client)
    capabilities = parse_animation_capabilities_response(
        await query_simple_response(client, 0x1A, 0x1B)
    )
    receive_capacity = parse_receive_capacity_response(
        await query_simple_response(client, 0x14, 0x15)
    )
    if screen["color_type"] != 0xFF:
        raise RuntimeError(
            f"RGB image transfer supports color type 0xFF; device reports "
            f"0x{screen['color_type']:02X}"
        )
    if capabilities["max_frames"] < 1:
        raise RuntimeError("Device reports no animation-frame capacity")
    return screen, capabilities, receive_capacity

async def send_set(client, cmd_type, payload):
    """Send Set command with Start/Data/End flow"""
    total_len = len(payload)

    # Start notification
    start_cmd, ser = build_start_notification(cmd_type, total_len)
    await client.write_gatt_char(CMD, start_cmd, response=False)
    await asyncio.sleep(0.05)

    # Send data to DATA char with chunking
    await write_with_flow(client, DATA, payload)

    # End notification
    end_cmd = build_end_notification(ser, cmd_type, total_len)
    await client.write_gatt_char(CMD, end_cmd, response=False)
    await asyncio.sleep(0.05)

async def send_set_acknowledged(client, cmd_type, payload):
    """Send a Set transfer and require start/end protocol acknowledgments."""
    queue = asyncio.Queue()
    def on_notification(_, data):
        queue.put_nowait(bytes(data))

    await client.start_notify(CMD, on_notification)
    start_cmd, serial = build_start_notification(cmd_type, len(payload))

    async def wait_for_ack(response_id, timeout=8):
        async with asyncio.timeout(timeout):
            while True:
                response = await queue.get()
                if (len(response) >= 5 and response[1] == response_id and
                        int.from_bytes(response[2:4], 'big') == serial):
                    if response[4] != 0:
                        raise RuntimeError(f"Device rejected transfer: {response.hex()}")
                    return

    try:
        await client.write_gatt_char(CMD, start_cmd, response=False)
        try:
            await wait_for_ack(2)
        except TimeoutError as exc:
            raise TimeoutError(
                f"No start acknowledgment for transfer command 0x{cmd_type:04X}; "
                "payload was not sent"
            ) from exc
        data_characteristic = client.services.get_characteristic(DATA)
        mtu = min(182, data_characteristic.max_write_without_response_size)
        await write_with_flow(client, DATA, payload, mtu=mtu)
        end_cmd = build_end_notification(serial, cmd_type, len(payload))
        await client.write_gatt_char(CMD, end_cmd, response=False)
        try:
            await wait_for_ack(4, timeout=12)
        except TimeoutError as exc:
            raise TimeoutError(
                f"No final acknowledgment for transfer command 0x{cmd_type:04X}; "
                "the device may have received the payload, so it was not retried"
            ) from exc
    finally:
        await client.stop_notify(CMD)

async def send_image(path, rotation=0, fit='contain', preview_path=None,
                     device_name=DEVICE_NAME):
    async with connected_device(device_name) as client:
        screen, capabilities, receive_capacity = await query_image_capabilities(client)
        width, height = screen["width"], screen["height"]
        original_frame_count, source_duration_ms = image_animation_metadata(path)
        frames = load_image_frames(
            path, rotation=rotation, fit=fit, target_width=width, target_height=height,
            max_frames=capabilities["max_frames"]
        )
        if preview_path:
            save_rgb_preview(preview_path, width, height, frames[0][0])
            print(f"Preview saved: {preview_path}")
        payload, frames_sent, frame_interval = build_animation_transfer(
            frames, capabilities["max_frames"], receive_capacity, width, height,
            total_duration_ms=source_duration_ms
        )
        if "storage_bytes" in capabilities and len(payload) > capabilities["storage_bytes"]:
            raise RuntimeError(
                f"Image transfer is {len(payload)} bytes, exceeding the device's "
                f"{capabilities['storage_bytes']}-byte storage limit"
            )
        await send_set_acknowledged(client, 0x8004, payload)
    print(
        f"Image transfer acknowledged ({width}x{height}, color=0x{screen['color_type']:02X}, "
        f"rotation={rotation}, fit={fit}, frames={frames_sent}/{original_frame_count}, "
        f"interval={frame_interval}ms)."
    )

async def send_rgb_frame(pixels, width=64, height=16, device_name=DEVICE_NAME,
                         frame_time_ms=1000):
    """Send an already-rasterized static RGB frame with transfer acknowledgments."""
    pixels = bytes(pixels)
    if len(pixels) != width * height * 3:
        raise ValueError("RGB byte count does not match frame dimensions")
    if not 1 <= frame_time_ms <= 0xFFFF:
        raise ValueError("frame_time_ms must be from 1 to 65,535")
    async with connected_device(device_name) as client:
        screen, capabilities, receive_capacity = await query_image_capabilities(client)
        if (screen["width"], screen["height"]) != (width, height):
            raise RuntimeError(
                f"Raster dimensions {width}x{height} do not match the display "
                f"{screen['width']}x{screen['height']}"
            )
        frame = build_rgb_frame(width, height, pixels)
        animation = build_frame_animation([frame], frame_time_ms=frame_time_ms, mode=0)
        payload = wrap_set_data(animation)
        if len(payload) > receive_capacity:
            raise RuntimeError("Raster frame exceeds the display receive limit")
        if len(payload) > capabilities.get("storage_bytes", len(payload)):
            raise RuntimeError("Raster frame exceeds the display storage limit")
        await send_set_acknowledged(client, 0x8004, payload)
    print(f"RGB frame transfer acknowledged ({width}x{height}).")

async def send_rgb_animation(frames, width=64, height=16, device_name=DEVICE_NAME,
                             frame_time_ms=500):
    """Send an already-rasterized RGB animation within the device receive limit."""
    frames = [bytes(frame) for frame in frames]
    if not frames:
        raise ValueError("RGB animation must contain at least one frame")
    if not 1 <= frame_time_ms <= 0xFFFF:
        raise ValueError("Frame interval must be from 1 to 65,535")
    for frame in frames:
        if len(frame) != width * height * 3:
            raise ValueError("RGB bytes do not match frame dimensions")
    async with connected_device(device_name) as client:
        screen, capabilities, receive_capacity = await query_image_capabilities(client)
        if (screen["width"], screen["height"]) != (width, height):
            raise RuntimeError(
                f"Raster dimensions {width}x{height} do not match the display "
                f"{screen['width']}x{screen['height']}"
            )
        if screen["color_type"] != 0xFF:
            raise RuntimeError(
                f"RGB animation requires color type 0xFF; device reports "
                f"0x{screen['color_type']:02X}"
            )
        source_frames = [(frame, frame_time_ms) for frame in frames]
        payload, frames_sent, interval = build_animation_transfer(
            source_frames, capabilities["max_frames"], receive_capacity, width, height
        )
        if "storage_bytes" in capabilities and len(payload) > capabilities["storage_bytes"]:
            raise RuntimeError("RGB animation exceeds the device storage limit")
        await send_set_acknowledged(client, 0x8004, payload)
    print(
        f"RGB animation transfer acknowledged ({width}x{height}, "
        f"frames={frames_sent}, interval={interval}ms)."
    )

async def send_rotated_text(text, color, rotation, fit='contain', preview_path=None,
                            raster_font='3x5', text_layout='whole',
                            device_name=DEVICE_NAME, mode=0, frame_time_ms=200):
    """Rasterize text, rotate its pixels, fit to screen, and send as one frame."""
    async with connected_device(device_name) as client:
        screen, capabilities, receive_capacity = await query_image_capabilities(client)
        text_units = len(text.encode('utf-16-be', errors='surrogatepass')) // 2
        if text_units > capabilities["max_text_characters"]:
            raise ValueError(
                f"Text has {text_units} UTF-16 units; device limit is "
                f"{capabilities['max_text_characters']}"
            )
        if text_layout == "glyphs":
            width, height, pixels = render_rotated_text_glyphs(
                text, rotation, color=color, raster_font=raster_font
            )
        elif text_layout == "whole":
            width, height, pixels = render_text_rgb(
                text, color=color, raster_font=raster_font
            )
            width, height, pixels = rotate_rgb_pixels(width, height, pixels, rotation)
        else:
            raise ValueError("text_layout must be 'whole' or 'glyphs'")
        width, height, pixels = fit_rgb_pixels(
            width, height, pixels, screen["width"], screen["height"], fit=fit
        )
        if preview_path:
            save_rgb_preview(preview_path, width, height, pixels)
            print(f"Preview saved: {preview_path}")
        frame = build_rgb_frame(width, height, pixels)
        animation = build_frame_animation([frame], frame_time_ms=frame_time_ms, mode=mode)
        payload = wrap_set_data(animation)
        if len(payload) > receive_capacity:
            raise RuntimeError(f"Text image exceeds device receive limit ({receive_capacity} bytes)")
        if len(payload) > capabilities.get("storage_bytes", len(payload)):
            raise RuntimeError("Text image exceeds the device storage limit")
        await send_set_acknowledged(client, 0x8004, payload)
    print(f"Raster text transfer acknowledged ({rotation}°, fit={fit}, interval={frame_time_ms}ms).")

def render_native_text_preview(text, color=(255, 255, 255), glyph_rotation=0):
    """Render the native font's initial 64×16 viewport, optionally turning glyphs."""
    _validate_native_text(text)
    if glyph_rotation not in (0, 90, 270):
        raise ValueError("glyph_rotation must be 0, 90, or 270 degrees")
    character_colors = normalize_text_colors(text, color)
    output = bytearray(64 * 16 * 3)
    for index, char in enumerate(text[:4]):
        glyph = make_font_object(char, rotation_degrees=glyph_rotation)
        for y in range(16):
            bits = int.from_bytes(glyph[14 + y * 2:16 + y * 2], 'big')
            for x in range(16):
                if bits & (0x8000 >> x):
                    offset = (y * 64 + index * 16 + x) * 3
                    output[offset:offset + 3] = bytes(character_colors[index])
    return 64, 16, bytes(output)

async def main(text, color=(0xff,0xff,0xff), mode=0, device_name=DEVICE_NAME,
               text_mode="native", time_ms=200, text_orientation="landscape"):
    """Send text using native CharacterString or an Android-style 3x5 raster frame."""
    if text_mode not in {"native", "raster"}:
        raise ValueError("text_mode must be native or raster")
    if text_orientation not in {"landscape", "portrait"}:
        raise ValueError("text_orientation must be landscape or portrait")
    if not 1 <= time_ms <= 0xFFFF:
        raise ValueError("time_ms must be from 1 to 65,535")
    if text_mode == "raster" and text_orientation == "landscape":
        return await send_rotated_text(
            text, color, 0, "contain", raster_font="3x5", text_layout="whole",
            device_name=device_name, mode=mode, frame_time_ms=time_ms
        )
    # Validate and encode both native transfers before connecting or issuing any
    # display setup commands; a bad CharacterString must not leave a new font
    # installed or the display partially reconfigured.
    text_units = len(text.encode('utf-16-be')) // 2
    word_data = (build_word_stock(text, glyph_rotation=270)
                 if text_orientation == "portrait" else build_word_stock(text))
    word_stock = wrap_set_data(word_data)
    char_string = wrap_set_data(
        build_character_string(text, color, time_ms=time_ms, mode=mode)
    )
    async with connected_device(device_name) as client:
        print("Connected!")

        # Read live screen and transfer capabilities before sending content.
        await send_query(client, 0x10)
        await asyncio.sleep(0.1)
        screen = await query_screen_information(client)
        capabilities = parse_animation_capabilities_response(
            await query_simple_response(client, 0x1A, 0x1B)
        )
        receive_capacity = parse_receive_capacity_response(
            await query_simple_response(client, 0x14, 0x15)
        )
        if text_units > capabilities["max_text_characters"]:
            raise ValueError(
                f"Text has {text_units} UTF-16 units; device limit is "
                f"{capabilities['max_text_characters']}"
            )
        if len(word_stock) > receive_capacity:
            raise ValueError(f"Font transfer exceeds device receive limit ({receive_capacity} bytes)")
        if len(char_string) > receive_capacity:
            raise ValueError(f"Text transfer exceeds device receive limit ({receive_capacity} bytes)")
        print(
            f"Text: {text!r} ({text_units}/{capabilities['max_text_characters']} chars); "
            f"screen {screen['width']}x{screen['height']}; native timing={time_ms}ms"
        )

        # Init commands
        init_data = build_initial_playlist_query()
        await send_set(client, 0x800c, init_data)
        await asyncio.sleep(0.2)

        setup_data = build_visible_pixel_map_query()
        await send_set(client, 0x8000, setup_data)
        await asyncio.sleep(0.2)

        # Send WordStock (FontData)
        print("Sending font...")
        await send_set_acknowledged(client, 0x8004, word_stock)
        await asyncio.sleep(0.1)

        # Send CharacterString (TextData)
        print("Sending text...")
        await send_set_acknowledged(client, 0x8004, char_string)
        await asyncio.sleep(0.5)

        print("Done!")

def parse_cli_args():
    parser = argparse.ArgumentParser(description="Send text or an image to SPOTLED")
    parser.add_argument("text", nargs="?", help="text to display (maximum 160 characters)")
    parser.add_argument("color", nargs="?", default="white",
                        help="text color: white, red, orange, yellow, green, cyan, blue, magenta, rainbow")
    parser.add_argument("mode", nargs="?", default="slide", type=str.lower,
                        choices=tuple(TEXT_EFFECTS),
                        help="text effect: slide, up, down, left, right, snow, scroll, laser")
    parser.add_argument("--text-mode", choices=("native", "raster"), default="native",
                        help="native panel font or Android-style 3x5 raster text (default: native)")
    parser.add_argument("--text-speed", choices=tuple(TEXT_SPEEDS_MS), default="normal",
                        help="text timing: slow (300 ms), 250ms, normal (200 ms), 150ms, fast (100 ms)")
    parser.add_argument("--image", help="image path (animated GIFs are sent as frame animations)")
    parser.add_argument("--device-name", default=DEVICE_NAME,
                        help=f"BLE advertised device name (default: {DEVICE_NAME})")
    parser.add_argument("--rotation", type=int, choices=(0, 90, 180, 270), default=0,
                        help="image-only clockwise rotation before fitting to 64x16")
    parser.add_argument("--text-orientation", choices=("landscape", "portrait"),
                        default="landscape",
                        help="text orientation; portrait rotates native 16x16 glyphs and preserves scrolling")
    parser.add_argument("--fit", choices=("contain", "cover", "stretch"), default="contain",
                        help="contain preserves all content with padding; cover crops; stretch distorts")
    parser.add_argument("--preview", help="optional path for a PNG of the exact 64x16 frame to send")
    parser.add_argument("--raster-font", choices=("3x5", "panel16"), default=None,
                        help="explicitly use geometric text: compact 3x5 or captured 16x16 glyphs")
    parser.add_argument("--query-visible-map", action="store_true",
                        help="read and print the device pixel-shape mask without changing the display")
    parser.add_argument("--dry-run", action="store_true",
                        help="prepare text/image offline, report estimated packet sizes, and send nothing")
    parser.add_argument("--inspect", action="store_true",
                        help="inspect image metadata offline without decoding or connecting to BLE")
    parser.add_argument("--version", action="version", version=f"SpotLED Suite {__import__('spotled_common').__version__}")
    args = parser.parse_args()

    if args.query_visible_map:
        if args.image or args.text is not None or args.dry_run or args.inspect:
            parser.error("--query-visible-map cannot be combined with text or --image")
    elif args.inspect:
        if not args.image or args.text is not None or args.dry_run:
            parser.error("--inspect requires --image and cannot be combined with --dry-run")
    elif args.image:
        if args.text is not None:
            parser.error("provide text or --image, not both")
    else:
        if args.text is None:
            parser.error("provide text or --image")
        if args.rotation != 0:
            parser.error("--rotation is image-only; use --text-orientation landscape or portrait for text")
    return args


def inspect_image_file(path):
    try:
        from PIL import Image
    except ImportError as exc:
        raise RuntimeError("Image inspection requires Pillow; install the project dependencies.") from exc
    with Image.open(path) as image:
        return {
            "format": image.format or "unknown", "width": image.width, "height": image.height,
            "frames": getattr(image, "n_frames", 1),
            "orientation": image.getexif().get(274, 1),
            "bytes": __import__("os").path.getsize(path),
        }


def dry_run(args):
    assumed_limit = 7280
    if args.image:
        info = inspect_image_file(args.image)
        original_frame_count, source_duration_ms = image_animation_metadata(args.image)
        frames = load_image_frames(args.image, args.rotation, args.fit,
                                   max_frames=min(2, info["frames"]))
        payload, sent_count, interval = build_animation_transfer(
            frames, max_frames=2, receive_capacity=assumed_limit,
            total_duration_ms=source_duration_ms)
        if args.preview:
            save_rgb_preview(args.preview, 64, 16, frames[0][0])
        sampled_text = f"frames {original_frame_count} → {sent_count}"
        print(f"Offline dry run: {args.image} ({info['width']}×{info['height']}, {info['format']}); "
              f"{sampled_text}; rotation={args.rotation}°, fit={args.fit}; "
              f"interval={interval} ms; packet={len(payload)}/{assumed_limit} bytes; no BLE connection.")
        if args.preview: print(f"Preview saved: {args.preview}")
        return

    text = args.text
    if (args.text_orientation == "portrait" or
            (args.text_mode == "native" and args.raster_font is None and args.fit == "contain")):
        glyph_rotation = 270 if args.text_orientation == "portrait" else 0
        word_data = (build_word_stock(text, glyph_rotation=270)
                     if glyph_rotation else build_word_stock(text))
        word = wrap_set_data(word_data)
        string = wrap_set_data(build_character_string(
            text, color=args.color, time_ms=text_speed_ms(args.text_speed),
            mode=text_effect_mode(args.mode)))
        orientation = "portrait" if glyph_rotation else "landscape"
        print(f"Offline dry run: native {orientation} text ({len(text)} characters); "
              f"WordStock={len(word)} B, CharacterString={len(string)} B each ≤ {assumed_limit}; "
              "screen assumed 64×16; live capabilities were not queried; no BLE connection.")
        if args.preview:
            width, height, pixels = render_native_text_preview(
                text, color=args.color, glyph_rotation=glyph_rotation
            )
            save_rgb_preview(args.preview, width, height, pixels)
            print(f"Preview saved: {args.preview}")
        return

    width, height, pixels = render_text_rgb(text, color=args.color,
                                            raster_font=args.raster_font or "3x5")
    width, height, pixels = fit_rgb_pixels(width, height, pixels, 64, 16, fit=args.fit)
    frame = build_rgb_frame(64, 16, pixels)
    payload = wrap_set_data(build_frame_animation([frame],
                             frame_time_ms=text_speed_ms(args.text_speed),
                             mode=text_effect_mode(args.mode)))
    if args.preview: save_rgb_preview(args.preview, 64, 16, pixels)
    print(f"Offline dry run: raster text ({len(text)} characters); rotation={args.rotation}°, "
          f"fit={args.fit}; packet={len(payload)}/{assumed_limit} bytes; "
          "screen assumed 64×16; live capabilities were not queried; no BLE connection.")
    if args.preview: print(f"Preview saved: {args.preview}")

async def print_visible_pixel_map(device_name=DEVICE_NAME):
    async with connected_device(device_name) as client:
        # Match the Android device-info query sequence before asking for object 0x10.
        await send_set(client, 0x800C, build_initial_playlist_query())
        await asyncio.sleep(0.2)
        shape = await query_visible_pixel_map(client)
    lit_count = sum(shape["mask"])
    print(f"Visible-pixel map: {shape['width']}x{shape['height']}, {lit_count} active points")
    for row in range(shape["height"]):
        start = row * shape["width"]
        print(''.join('#' if bit else '.' for bit in shape["mask"][start:start + shape["width"]]))

def cli():
    args = parse_cli_args()
    try:
        if args.inspect:
            info = inspect_image_file(args.image)
            print(f"Image: {info['format']} {info['width']}×{info['height']}; "
                  f"frames={info['frames']}; EXIF orientation={info['orientation']}; "
                  f"file={info['bytes']} bytes; no BLE connection.")
        elif args.dry_run:
            dry_run(args)
        elif args.query_visible_map:
            asyncio.run(print_visible_pixel_map(args.device_name))
        elif args.image:
            asyncio.run(send_image(
                args.image, args.rotation, args.fit, args.preview, args.device_name
            ))
        else:
            text = args.text
            color = args.color.lower()
            mode = text_effect_mode(args.mode)
            time_ms = text_speed_ms(args.text_speed)
            if args.text_orientation == "portrait":
                if args.preview:
                    width, height, pixels = render_native_text_preview(
                        text, color=color, glyph_rotation=270
                    )
                    save_rgb_preview(args.preview, width, height, pixels)
                    print(f"Preview saved: {args.preview}")
                asyncio.run(main(
                    text, color, mode, args.device_name, text_mode=args.text_mode,
                    time_ms=time_ms, text_orientation="portrait"
                ))
            elif (args.text_mode == "raster" or args.fit != "contain" or args.preview or
                    args.raster_font is not None):
                asyncio.run(send_rotated_text(
                    text, color, 0, args.fit, args.preview,
                    args.raster_font or "3x5", "whole", args.device_name, mode,
                    frame_time_ms=time_ms
                ))
            else:
                asyncio.run(main(
                    text, color, mode, args.device_name,
                    text_mode=args.text_mode, time_ms=time_ms
                ))
    except KeyboardInterrupt:
        print("Cancelled.", file=sys.stderr)
        raise SystemExit(130)
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        raise SystemExit(1)

if __name__ == "__main__":
    cli()
