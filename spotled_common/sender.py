#!/usr/bin/env python3
"""Python reference sender for the SPOTLED 1.9.4 BLE protocol."""
import argparse, asyncio, json, sys
from contextlib import asynccontextmanager
from math import ceil, floor
from bleak import BleakClient, BleakScanner
from .settings import (
    DEFAULT_DEVICE_NAME, TEXT_EFFECTS, TEXT_SPEEDS_MS, text_effect_mode, text_speed_ms,
)
from .font3x5 import FONT3X5

DEVICE_NAME = DEFAULT_DEVICE_NAME
NAMED_TEXT_COLORS = {
    "white": (255, 255, 255), "red": (255, 0, 0),
    "orange": (255, 128, 0), "yellow": (255, 255, 0),
    "green": (0, 255, 0), "cyan": (0, 255, 255),
    "blue": (0, 0, 255), "magenta": (255, 0, 255),
}
CMD = "0000ff21-0000-1000-8000-00805f9b34fb"
DATA = "0000ff22-0000-1000-8000-00805f9b34fb"

@asynccontextmanager
async def connected_device(device_name=DEVICE_NAME, scan_timeout=10,
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

# Captured glyphs from alphabet bluetooth HCI capture to wireshark
CAP_GLYPHS = json.loads('{"97": "0000002f000d0100100010006120000000000000000000003e006600430007007b006300430067007b000000000031", "98": "0000002f000d0100100010006220000000006000600060007e0063006300610041006100630063007e000000000076", "99": "0000002f000d0100100010006320000000000000000000003e0063006000600040006000600063003e00000000001e", "100": "0000002f000d0100100010006420000000000100010001003f00630063004100c1004100630063003f0000000000cf", "101": "0000002f000d0100100010006520000000000000000000003e006300610041807f804000600063003e00000000001b", "102": "0000002f000d010010001000662000000000780060006000f0006000600060006000600060006000600000000000f5", "103": "0000002f000d0100100010006720000000000000000000003f00630063004100c1004100630063003d00010063006d", "104": "0000002f000d0100100010006820000000006000600060007e00660063006300630063006300630063000000000062", "105": "0000002f000d010010001000692000000000600000000000600060006000600060006000600060006000000000005a", "106": "0000002f000d0100100010006a200000000060000000000060006000600060006000600060006000600060004000b9", "107": "0000002f000d0100100010006b200000000060006000600067006e006c00780078006c00660066006300000000002c", "108": "0000002f000d0100100010006c20000000006000600060006000600060006000600060006000600060000000000097", "109": "0000002f000d0100100010006d20000000000000000000007ef06730631863186318631863186318631800000000b4", "110": "0000002f000d0100100010006e20000000000000000000007e0066006300630063006300630063006300000000007c", "111": "0000002f000d0100100010006f20000000000000000000001e0033006100618061806180610033001e00000000000d", "112": "0000002f000d0100100010007020000000000000000000007e0073006300610041004100630063007e0060006000d8", "113": "0000002f000d0100100010007120000000000000000000003f00630063004100c1004100630063001f0001000100e3", "114": "0000002f000d0100100010007220000000000000000000007800600060006000600060006000600060000000000099", "115": "0000002f000d0100100010007320000000000000000000003e006300400060003e000600030042003c00000000000a", "116": "0000002f000d010010001000742000000000200060006000f00060006000600060006000600060007000000000002f", "117": "0000002f000d010010001000752000000000000000000000630063006300630063006300630067003f0000000000b3", "118": "0000002f000d010010001000762000000000000000000000c200c600c60064006c002c00380038001800000000003b", "119": "0000002f000d010010001000772000000000000000000000c660ce604e406e406ac07bc03b803180318000000000fa", "120": "0000002f000d010010001000782000000000000000000000c6006c0028003800180038006c006400c6000000000093", "121": "0000002f000d010010001000792000000000000000000000c600460064006c002c002800380038001000100030001a", "122": "0000002f000d0100100010007a2000000000000000000000fe000c000c001800300030006000c000ff00000000005c", "65": "0000002f000d0100100010004120000000000e000e001b001b001300318031807fc060c040c0c060c06000000000dc", "66": "0000002f000d0100100010004220000000007f00618060c060c061807f8060c060c060c060c060c07f0000000000a2", "67": "0000002f000d0100100010004320000000000f8018c03060600060006000600060006030306018c00f8000000000e2", "68": "0000002f000d0100100010004420000000007f8060c06040606060606060606060606060604060c07f800000000041", "69": "0000002f000d0100100010004520000000007fc060006000600060007fc0600060006000600060007fc00000000021", "70": "0000002f000d0100100010004620000000007fc0600060006000600060007f8060006000600060006000000000003f", "71": "0000002f000d0100100010004720000000000f8038c0306060606000600063e060206020302038e00f80000000006b", "72": "0000002f000d01001000100048200000000060c060c060c060c060c07fc060c060c060c060c060c060c0000000009c", "73": "0000002f000d010010001000492000000000400040004000400040004000400040004000400040004000000000003a", "74": "0000002f000d0100100010004a200000000006000600060006000600060006000600c600460066003c00000000005b", "75": "0000002f000d0100100010004b2000000000606060c06100620064006c00760063006180618060c06060000000004a", "76": "0000002f000d0100100010004c2000000000600060006000600060006000600060006000600060007f800000000018", "77": "0000002f000d0100100010004d2000000000703070707070787068f068b06cb06db0653067306730633000000000ef", "78": "0000002f000d0100100010004e200000000060c070c070c078c06cc06cc066c062c063c061c061c060c00000000058", "79": "0000002f000d0100100010004f20000000000f8038c02060606060206030603060206060306038c00f800000000076", "80": "0000002f000d0100100010005020000000007f8060c060406060604060c07f80600060006000600060000000000015", "81": "0000002f000d0100100010005120000000000f0030c06060606040204020402040206060636031c00fe000200000b0", "82": "0000002f000d0100100010005220000000007f8060c060c060c060c061c07f8063006180618060c060e0000000000d", "83": "0000002f000d0100100010005320000000001f00318060c0600030001e00038000c06040604030c01f000000000000", "84": "0000002f000d010010001000542000000000ff80080008000800080008000800080008000800080008000000000058", "85": "0000002f000d01001000100055200000000060c060c060c060c060c060c060c060c060c060c031801f00000000001e", "86": "0000002f000d010010001000562000000000c060606060c060c03080318031801b001b000e000e000e00000000009b", "87": "0000002f000d010010001000572000000000c386c386638462cc66cc264c244834683c781c38183018300000000041", "88": "0000002f000d01001000100058200000000060e030c031801b000f000e000e001b001980318060c0e060000000003f", "89": "0000002f000d010010001000592000000000c18063006300360036001c001c00080008000800080008000000000057", "90": "0000002f000d0100100010005a20000000007f800300030006000c000c0018003000300060006000ff80000000004f", "49": "0000002f000d01001000100031200000000004000c001c0034000400040004000400040004000400040000000000d2", "50": "0000002f000d0100100010003220000000003e006700630001000300030006000c00380070006000ff000000000029", "51": "0000002f000d0100100010003320000000003c0062006300030006000e00030001000180610063003e0000000000b1", "52": "0000002f000d010010001000342000000000060006000e001e001600360066004600ff80060006000600000000008e", "53": "0000002f000d0100100010003520000000003f002000200060007e006300010001800100610063003e000000000009", "54": "0000002f000d0100100010003620000000001e002300630040005e00e300c100c1804100610063001e000000000003", "55": "0000002f000d0100100010003720000000007f800300060006000c000c000800180018001800100030000000000096", "56": "0000002f000d0100100010003820000000003e0063006300630063001c00630063004100610063003e00000000005c", "57": "0000002f000d0100100010003920000000003c006300630041004180638063803d000100030062003c0000000000a1", "48": "0000002f000d0100100010003020000000001c0022006300630041004100410041006300630022003c000000000027", "32": "0000002f000d010010001000202000000000000000000000000000000000000000000000000000000000000000009d"}')
FONT5X7 = json.loads('{"32": [0, 0, 0, 0, 0, 0, 0], "33": [4, 4, 4, 4, 0, 0, 4], "34": [10, 10, 0, 0, 0, 0, 0], "35": [10, 10, 31, 10, 31, 10, 10], "36": [4, 15, 20, 14, 5, 30, 4], "37": [24, 25, 8, 4, 18, 19, 8], "38": [12, 18, 20, 8, 21, 18, 13], "39": [4, 4, 0, 0, 0, 0, 0], "40": [2, 4, 8, 8, 8, 4, 2], "41": [8, 4, 2, 2, 2, 4, 8], "42": [0, 4, 21, 14, 21, 4, 0], "43": [0, 4, 4, 31, 4, 4, 0], "44": [0, 0, 0, 0, 0, 4, 8], "45": [0, 0, 0, 31, 0, 0, 0], "46": [0, 0, 0, 0, 0, 0, 4], "47": [0, 1, 2, 4, 8, 16, 0], "48": [14, 17, 19, 21, 25, 17, 14], "49": [4, 12, 4, 4, 4, 4, 14], "50": [14, 17, 1, 6, 8, 16, 31], "51": [14, 17, 1, 6, 1, 17, 14], "52": [2, 6, 10, 18, 31, 2, 2], "53": [31, 16, 30, 1, 1, 17, 14], "54": [6, 8, 16, 30, 17, 17, 14], "55": [31, 1, 2, 4, 8, 8, 8], "56": [14, 17, 17, 14, 17, 17, 14], "57": [14, 17, 17, 15, 1, 2, 12], "58": [0, 0, 4, 0, 0, 4, 0], "59": [0, 0, 4, 0, 0, 4, 8], "60": [2, 4, 8, 16, 8, 4, 2], "61": [0, 0, 31, 0, 31, 0, 0], "62": [8, 4, 2, 1, 2, 4, 8], "63": [14, 17, 1, 2, 4, 0, 4], "64": [14, 17, 23, 21, 23, 16, 14], "65": [14, 17, 17, 31, 17, 17, 17], "66": [30, 17, 17, 30, 17, 17, 30], "67": [14, 17, 16, 16, 16, 17, 14], "68": [30, 17, 17, 17, 17, 17, 30], "69": [31, 16, 16, 30, 16, 16, 31], "70": [31, 16, 16, 30, 16, 16, 16], "71": [14, 17, 16, 23, 17, 17, 15], "72": [17, 17, 17, 31, 17, 17, 17], "73": [14, 4, 4, 4, 4, 4, 14], "74": [7, 2, 2, 2, 2, 18, 12], "75": [17, 18, 20, 24, 20, 18, 17], "76": [16, 16, 16, 16, 16, 16, 31], "77": [17, 27, 21, 21, 17, 17, 17], "78": [17, 17, 25, 21, 19, 17, 17], "79": [14, 17, 17, 17, 17, 17, 14], "80": [30, 17, 17, 30, 16, 16, 16], "81": [14, 17, 17, 17, 21, 18, 13], "82": [30, 17, 17, 30, 20, 18, 17], "83": [14, 17, 16, 14, 1, 17, 14], "84": [31, 4, 4, 4, 4, 4, 4], "85": [17, 17, 17, 17, 17, 17, 14], "86": [17, 17, 17, 17, 10, 10, 4], "87": [17, 17, 21, 21, 21, 27, 17], "88": [17, 17, 10, 4, 10, 17, 17], "89": [17, 17, 10, 4, 4, 4, 4], "90": [31, 1, 2, 4, 8, 16, 31], "91": [14, 8, 8, 8, 8, 8, 14], "92": [16, 8, 4, 2, 1, 0, 0], "93": [14, 2, 2, 2, 2, 2, 14], "94": [4, 10, 17, 0, 0, 0, 0], "95": [0, 0, 0, 0, 0, 0, 31], "96": [8, 4, 2, 0, 0, 0, 0], "97": [0, 0, 14, 1, 15, 17, 15], "98": [16, 16, 30, 17, 17, 17, 30], "99": [0, 0, 14, 16, 16, 16, 14], "100": [1, 1, 15, 17, 17, 17, 15], "101": [0, 0, 14, 17, 31, 16, 14], "102": [6, 8, 8, 30, 8, 8, 8], "103": [0, 0, 15, 17, 15, 1, 14], "104": [16, 16, 30, 17, 17, 17, 17], "105": [4, 0, 12, 4, 4, 4, 14], "106": [2, 0, 6, 2, 2, 18, 12], "107": [16, 16, 18, 20, 24, 20, 18], "108": [12, 4, 4, 4, 4, 4, 14], "109": [0, 0, 27, 21, 21, 17, 17], "110": [0, 0, 30, 17, 17, 17, 17], "111": [0, 0, 14, 17, 17, 17, 14], "112": [0, 0, 30, 17, 30, 16, 16], "113": [0, 0, 15, 17, 15, 1, 1], "114": [0, 0, 22, 24, 16, 16, 16], "115": [0, 0, 15, 16, 14, 1, 30], "116": [8, 8, 30, 8, 8, 9, 6], "117": [0, 0, 17, 17, 17, 17, 15], "118": [0, 0, 17, 17, 17, 10, 4], "119": [0, 0, 17, 21, 21, 21, 10], "120": [0, 0, 17, 10, 4, 10, 17], "121": [0, 0, 17, 17, 15, 1, 14], "122": [0, 0, 31, 2, 4, 8, 31], "123": [2, 4, 4, 8, 4, 4, 2], "124": [4, 4, 4, 4, 4, 4, 4], "125": [8, 4, 4, 2, 4, 4, 8], "126": [0, 0, 8, 21, 2, 0, 0]}')
FONT5X7 = {int(k): v for k, v in FONT5X7.items()}

# Independent compact raster alphabet for small-pixel rotated text. Each tuple
# is five rows of three bits; this table is authored here, not copied from
# AWTRIX NG's noncommercial font asset.
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

def make_font_object(ch):
    """Build FontObject (type 13) from captured glyphs or 5x7 font"""
    code = ord(ch)
    # The shared serifed cap-height I replaces the original taller stem glyph.
    if ch == "I":
        return bytes.fromhex(
            "0000002f000d0100100010004920000000001ff001000100010001000100010001000100010001001ff00000000012")
    hex_data = CAP_GLYPHS.get(str(code))
    if hex_data:
        return bytes.fromhex(hex_data)
    # Generate 16x16 glyph from 5x7 font, scaled to fill cell
    rows = FONT5X7.get(code)
    if rows is None:
        rows = FONT5X7[63]  # use a visible question mark for unsupported BMP chars
    bitmap = [0] * 16
    for sr in range(7):
        sb = rows[sr]
        dr = sr * 2  # start at row 0, 2 rows per source row
        if dr + 1 >= 16: continue
        db = 0
        for col in range(5):
            if sb & (0x10 >> col):
                # 3x horizontal scaling: each source pixel = 3 target pixels
                db |= (0x8000 >> (col * 3 + 0))
                db |= (0x8000 >> (col * 3 + 1))
                db |= (0x8000 >> (col * 3 + 2))
        bitmap[dr] = db
        bitmap[dr + 1] = db
    bm = bytearray()
    for row in range(16):
        bm.append((bitmap[row] >> 8) & 0xFF)
        bm.append(bitmap[row] & 0xFF)
    # Build FontObject: type=13, flag=1, w=16, h=16, char_code, offset_x=0x20, bitmap
    entry = bytearray(b'\x00\x00\x00\x2f\x00\x0d\x01\x00\x10\x00\x10')
    entry += code.to_bytes(2, 'big') + b'\x20' + bm
    entry.append(check_sum(entry))
    return bytes(entry)

def build_word_stock(text):
    """WordStockObject (type 5) with all FontObjects as followContent"""
    font_objs = []
    for ch in text:
        font_objs.append(make_font_object(ch))

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

def build_animation_transfer(frames, max_frames, receive_capacity, width=64, height=16):
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
        # Clamp the shared device interval to keep GIFs from becoming rapid flashes.
        interval = max(250, min(1000, round(sum(delays) / len(delays))))
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
        frames = load_image_frames(
            path, rotation=rotation, fit=fit, target_width=width, target_height=height,
            max_frames=capabilities["max_frames"]
        )
        if preview_path:
            save_rgb_preview(preview_path, width, height, frames[0][0])
            print(f"Preview saved: {preview_path}")
        payload, frames_sent, frame_interval = build_animation_transfer(
            frames, capabilities["max_frames"], receive_capacity, width, height
        )
        if "storage_bytes" in capabilities and len(payload) > capabilities["storage_bytes"]:
            raise RuntimeError(
                f"Image transfer is {len(payload)} bytes, exceeding the device's "
                f"{capabilities['storage_bytes']}-byte storage limit"
            )
        await send_set_acknowledged(client, 0x8004, payload)
    print(
        f"Image transfer acknowledged ({width}x{height}, color=0x{screen['color_type']:02X}, "
        f"rotation={rotation}, fit={fit}, frames={frames_sent}, interval={frame_interval}ms)."
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

async def main(text, color=(0xff,0xff,0xff), mode=0, device_name=DEVICE_NAME,
               text_mode="native", time_ms=200):
    """Send text using native CharacterString or an Android-style 3x5 raster frame."""
    if text_mode not in {"native", "raster"}:
        raise ValueError("text_mode must be native or raster")
    if not 1 <= time_ms <= 0xFFFF:
        raise ValueError("time_ms must be from 1 to 65,535")
    if text_mode == "raster":
        return await send_rotated_text(
            text, color, 0, "contain", raster_font="3x5", text_layout="whole",
            device_name=device_name, mode=mode, frame_time_ms=time_ms
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
        if any(ord(ch) > 0xFFFF or 0xD800 <= ord(ch) <= 0xDFFF for ch in text):
            raise ValueError("Text currently supports BMP characters only (no emoji outside BMP)")
        text_units = len(text.encode('utf-16-be')) // 2
        if text_units > capabilities["max_text_characters"]:
            raise ValueError(
                f"Text has {text_units} UTF-16 units; device limit is "
                f"{capabilities['max_text_characters']}"
            )
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
        word_stock = wrap_set_data(build_word_stock(text))
        if len(word_stock) > receive_capacity:
            raise ValueError(f"Font transfer exceeds device receive limit ({receive_capacity} bytes)")
        await send_set_acknowledged(client, 0x8004, word_stock)
        await asyncio.sleep(0.1)

        # Send CharacterString (TextData)
        print("Sending text...")
        char_string = wrap_set_data(
            build_character_string(text, color, time_ms=time_ms, mode=mode)
        )
        if len(char_string) > receive_capacity:
            raise ValueError(f"Text transfer exceeds device receive limit ({receive_capacity} bytes)")
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
                        help="clockwise pixel rotation before fitting to 64x16")
    parser.add_argument("--fit", choices=("contain", "cover", "stretch"), default="contain",
                        help="contain preserves all content with padding; cover crops; stretch distorts")
    parser.add_argument("--preview", help="optional path for a PNG of the exact 64x16 frame to send")
    parser.add_argument("--raster-font", choices=("3x5", "panel16"), default=None,
                        help="explicitly use geometric text: compact 3x5 or captured 16x16 glyphs")
    parser.add_argument("--text-layout", choices=("whole", "glyphs"), default="whole",
                        help="rotate the whole text line or rotate glyphs while keeping horizontal flow")
    parser.add_argument("--query-visible-map", action="store_true",
                        help="read and print the device pixel-shape mask without changing the display")
    args = parser.parse_args()

    if args.query_visible_map:
        if args.image or args.text is not None:
            parser.error("--query-visible-map cannot be combined with text or --image")
    elif args.image:
        if args.text is not None:
            parser.error("provide text or --image, not both")
    else:
        if args.text is None:
            parser.error("provide text or --image")
    return args

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
        if args.query_visible_map:
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
            if (args.text_mode == "raster" or args.rotation != 0 or args.fit != "contain" or args.preview or
                    args.raster_font is not None or args.text_layout != "whole"):
                asyncio.run(send_rotated_text(
                    text, color, args.rotation, args.fit, args.preview,
                    args.raster_font or "3x5", args.text_layout, args.device_name, mode,
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
