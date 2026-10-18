#!/usr/bin/env python3
"""Backward-compatible command-line entry point for the shared SpotLED sender."""

from spotled_common.sender import *
from spotled_common.sender import cli


if __name__ == "__main__":
    cli()
