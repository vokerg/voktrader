from __future__ import annotations

import logging
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path

from .config import Settings


LOG_FORMAT = "%(asctime)s.%(msecs)03d %(levelname)s [%(name)s] %(message)s"
DATE_FORMAT = "%Y-%m-%dT%H:%M:%S%z"


def configure_executor_logging(settings: Settings) -> None:
    log_file = Path(settings.executor_log_file)
    if not log_file.is_absolute():
        log_file = Path.cwd() / log_file
    log_file.parent.mkdir(parents=True, exist_ok=True)

    formatter = logging.Formatter(LOG_FORMAT, datefmt=DATE_FORMAT)
    console_handler = logging.StreamHandler(sys.stderr)
    console_handler.setFormatter(formatter)

    file_handler = RotatingFileHandler(
        log_file,
        maxBytes=settings.executor_log_max_bytes,
        backupCount=settings.executor_log_backup_count,
        encoding="utf-8",
    )
    file_handler.setFormatter(formatter)

    for logger_name in ("uvicorn.error", "uvicorn.access", "voktrader_executor"):
        configured_logger = logging.getLogger(logger_name)
        configured_logger.handlers.clear()
        configured_logger.setLevel(logging.INFO)
        configured_logger.addHandler(console_handler)
        configured_logger.addHandler(file_handler)
        configured_logger.propagate = False
