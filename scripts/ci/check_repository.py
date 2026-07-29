#!/usr/bin/env python3
"""Small, dependency-free repository checks used by local and CI runs."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

HIGH_CONFIDENCE_SECRET_PATTERNS = {
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----"),
    "AWS access key": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "GitHub token": re.compile(r"\b(?:gh[opusr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{50,255})\b"),
}

DEFAULT_TOKEN_PATTERN = re.compile(
    r"(?i)(?:change[-_]?me|replace[-_]?me|default[-_]?token|example[-_]?token)"
)

ALLOWED_DEFAULT_TOKEN_LINES = {
    (
        "src/main/resources/application-live.properties",
        "voktrader.executor.api-token=${VOKTRADER_EXECUTOR_API_TOKEN:change-me}",
    ),
    (
        "executor-python/voktrader_executor/config.py",
        'executor_api_token: str = Field(default="change-me", alias="EXECUTOR_API_TOKEN")',
    ),
}

SENSITIVE_DEFAULT_TOKEN_PATHS = (
    re.compile(r"^src/main/resources/application-live(?:-[^/]+)?\.properties$"),
    re.compile(r"^\.github/workflows/.*\.ya?ml$"),
    re.compile(r"^compose(?:\.[^/]+)?\.ya?ml$"),
    re.compile(r"^(?:.*/)?Dockerfile(?:\.[^/]+)?$"),
    re.compile(r"^executor-python/voktrader_executor/.*\.py$"),
)

MERGE_MARKER_PATTERN = re.compile(r"^(?:<<<<<<<|=======|>>>>>>>)", re.MULTILINE)


def tracked_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return [ROOT / item.decode() for item in result.stdout.split(b"\0") if item]


def read_text(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return None


def is_sensitive_default_token_path(relative_path: str) -> bool:
    return any(pattern.match(relative_path) for pattern in SENSITIVE_DEFAULT_TOKEN_PATHS)


def main() -> int:
    failures: list[str] = []

    for path in tracked_files():
        relative_path = path.relative_to(ROOT).as_posix()
        text = read_text(path)
        if text is None:
            continue

        if MERGE_MARKER_PATTERN.search(text):
            failures.append(f"{relative_path}: unresolved merge marker")

        for label, pattern in HIGH_CONFIDENCE_SECRET_PATTERNS.items():
            if pattern.search(text):
                failures.append(f"{relative_path}: possible {label}")

        if is_sensitive_default_token_path(relative_path):
            for line_number, line in enumerate(text.splitlines(), start=1):
                if not DEFAULT_TOKEN_PATTERN.search(line):
                    continue
                normalized = line.strip()
                if (relative_path, normalized) not in ALLOWED_DEFAULT_TOKEN_LINES:
                    failures.append(
                        f"{relative_path}:{line_number}: default token literal is not allowlisted"
                    )

    if failures:
        print("Repository checks failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("Repository checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
