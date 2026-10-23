"""Optional same-host advisory lock for SpotLED BLE senders.

This coordinates only processes that share the same lock path on one host. It
does not claim ownership across phones, computers, or other BLE clients.
"""

from __future__ import annotations

from contextlib import contextmanager
import os
from pathlib import Path


@contextmanager
def ble_process_lock():
    lock_path = os.environ.get("SPOTLED_LOCK_FILE", "").strip()
    if not lock_path:
        yield
        return
    try:
        import fcntl
    except ImportError as exc:  # pragma: no cover - exercised on non-POSIX hosts
        raise RuntimeError("SPOTLED_LOCK_FILE currently requires a POSIX host.") from exc

    path = Path(lock_path).expanduser()
    try:
        descriptor = path.open("a+")
    except OSError as exc:
        raise RuntimeError(f"Cannot open SpotLED BLE lock file {path}: {exc}") from exc
    with descriptor:
        try:
            fcntl.flock(descriptor.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise RuntimeError(
                f"Another SpotLED sender on this host holds {path}; stop it or wait before sending."
            ) from exc
        try:
            yield
        finally:
            fcntl.flock(descriptor.fileno(), fcntl.LOCK_UN)
