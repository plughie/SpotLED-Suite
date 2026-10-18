"""Small import-safe API shared by the Python sender applications."""

from typing import Any


async def send_text(text: str, **options: Any) -> None:
    """Send text using the shared BLE sender; import BLE dependencies on demand."""
    from .sender import main

    await main(text, **options)


async def send_rgb_frame(pixels: bytes, **options: Any) -> None:
    """Send a static RGB frame using the shared acknowledged BLE sender."""
    from .sender import send_rgb_frame as sender_send_rgb_frame

    await sender_send_rgb_frame(pixels, **options)
