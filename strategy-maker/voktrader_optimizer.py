#!/usr/bin/env python3
"""
Compatibility entrypoint for the strategy-maker optimizer.

The reusable optimizer engine now lives in optimizer_engine.py.
Strategy-specific configuration lives in optimizer_profiles.py.
"""

from optimizer_engine import main


if __name__ == "__main__":
    raise SystemExit(main())
