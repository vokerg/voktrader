from __future__ import annotations

import os


RESET = "\033[0m"
COLORS = {
    "CONFIG": "\033[36m",
    "REQUEST": "\033[34m",
    "RAW": "\033[35m",
    "RESPONSE": "\033[32m",
    "FAILED": "\033[31m",
    "ERROR": "\033[31m",
    "UNSUPPORTED": "\033[33m",
}


def event_label(label: str) -> str:
    if os.getenv("NO_COLOR") is not None or os.getenv("EXECUTOR_COLOR_LOGS", "true").lower() in {"0", "false", "no"}:
        return label
    color = next((value for key, value in COLORS.items() if key in label), "\033[37m")
    return f"{color}{label}{RESET}"
