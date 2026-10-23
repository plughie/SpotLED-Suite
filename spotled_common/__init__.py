"""Reusable Python API for SpotLED Suite applications."""

__version__ = "0.19.6"

from .api import send_rgb_animation, send_rgb_frame, send_text
from .settings import (
    DEFAULT_DEVICE_NAME,
    TEXT_EFFECTS,
    TEXT_SPEEDS_MS,
    text_effect_mode,
    text_render_mode,
    text_speed_ms,
)

__all__ = [
    "DEFAULT_DEVICE_NAME",
    "TEXT_EFFECTS",
    "TEXT_SPEEDS_MS",
    "send_text",
    "send_rgb_frame",
    "send_rgb_animation",
    "text_effect_mode",
    "text_render_mode",
    "text_speed_ms",
]
