"""Shared SpotLED text, effect, and device settings for Python applications."""

DEFAULT_DEVICE_NAME = "SpotLED_*"

TEXT_SPEEDS_MS = {"slow": 300, "250ms": 250, "normal": 200, "150ms": 150, "fast": 100}

TEXT_EFFECTS = {
    "slide": 0,
    "up": 1,
    "down": 2,
    "left": 3,
    "right": 4,
    "snow": 5,
    "scroll": 6,
    "laser": 7,
}


def text_speed_ms(speed: str) -> int:
    """Map Android-compatible speed labels to their protocol timing values."""
    if not isinstance(speed, str) or speed.strip().lower() not in TEXT_SPEEDS_MS:
        raise ValueError("text speed must be slow, 250ms, normal, 150ms, or fast")
    return TEXT_SPEEDS_MS[speed.strip().lower()]


def text_effect_mode(name: str) -> int:
    """Map a supported SpotLED effect name to its protocol identifier."""
    if not isinstance(name, str) or name.strip().lower() not in TEXT_EFFECTS:
        raise ValueError("text effect must be slide, up, down, left, right, snow, scroll, or laser")
    return TEXT_EFFECTS[name.strip().lower()]


def text_render_mode(name: str) -> str:
    """Validate the shared native/raster rendering choice."""
    if not isinstance(name, str) or name.strip().lower() not in {"native", "raster"}:
        raise ValueError("text mode must be native or raster")
    return name.strip().lower()
