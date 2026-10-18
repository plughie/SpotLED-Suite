"""Small import-safe API shared by the Python sender applications."""

from typing import Any


async def send_text(text: str, **options: Any) -> None:
    """Send text using the shared BLE sender; import BLE dependencies on demand."""
    from .sender import main

    await main(text, **options)
