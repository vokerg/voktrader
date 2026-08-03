#!/usr/bin/env python3
"""Enforce the temporary, exact Java test-failure baseline used during 2.0 remediation."""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True, order=True)
class FailureIdentity:
    test: str
    kind: str
    exception: str

    @classmethod
    def from_json(cls, value: object) -> "FailureIdentity":
        if not isinstance(value, dict):
            raise ValueError("failure entries must be JSON objects")
        required = ("test", "kind", "exception")
        missing = [
            key
            for key in required
            if not isinstance(value.get(key), str) or not value[key]
        ]
        if missing:
            raise ValueError(
                f"failure entry is missing non-empty fields: {', '.join(missing)}"
            )
        kind = value["kind"]
        if kind not in {"failure", "error"}:
            raise ValueError(f"unsupported failure kind: {kind}")
        return cls(
            test=value["test"],
            kind=kind,
            exception=value["exception"],
        )

    def display(self) -> str:
        return f"{self.kind}: {self.test} [{self.exception}]"


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _exception_from_node(node: ET.Element) -> str:
    declared = (node.get("type") or "").strip()
    if declared:
        return declared
    text = (node.text or "").strip()
    first_line = text.splitlines()[0].strip() if text else ""
    candidate = first_line.split(":", 1)[0].strip()
    if re.fullmatch(r"(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*", candidate):
        return candidate
    return "unknown"


def parse_surefire_reports(
    report_dir: Path,
) -> tuple[set[FailureIdentity], int, list[str]]:
    failures: set[FailureIdentity] = set()
    tests_seen = 0
    parse_errors: list[str] = []
    report_paths = sorted(report_dir.glob("TEST-*.xml"))
    if not report_paths:
        return failures, tests_seen, [
            f"no Surefire XML reports found under {report_dir}"
        ]

    for report_path in report_paths:
        try:
            root = ET.parse(report_path).getroot()
        except (ET.ParseError, OSError) as exc:
            parse_errors.append(f"{report_path}: {exc}")
            continue

        for testcase in root.iter():
            if _local_name(testcase.tag) != "testcase":
                continue
            tests_seen += 1
            class_name = (testcase.get("classname") or "").strip()
            method_name = (testcase.get("name") or "").strip()
            test_id = f"{class_name}.{method_name}" if class_name else method_name
            for child in testcase:
                kind = _local_name(child.tag)
                if kind not in {"failure", "error"}:
                    continue
                failures.add(
                    FailureIdentity(
                        test=test_id,
                        kind=kind,
                        exception=_exception_from_node(child),
                    )
                )
    return failures, tests_seen, parse_errors


def read_task_status(ledger_path: Path, task_id: str) -> str:
    text = ledger_path.read_text(encoding="utf-8")
    section_match = re.search(
        rf"^##\s+{re.escape(task_id)}\b(?P<section>.*?)(?=^---\s*$|^##\s+T\d+\b|\Z)",
        text,
        flags=re.MULTILINE | re.DOTALL,
    )
    if not section_match:
        raise ValueError(f"task {task_id} not found in {ledger_path}")
    status_match = re.search(
        r"^Status:\s*(\S+)\s*$",
        section_match.group("section"),
        re.MULTILINE,
    )
    if not status_match:
        raise ValueError(f"task {task_id} has no Status field in {ledger_path}")
    return status_match.group(1)


def load_baseline(path: Path) -> tuple[set[FailureIdentity], str]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise ValueError("unsupported Java failure baseline schema_version")
    expiry = data.get("expires_when")
    if not isinstance(expiry, dict) or not isinstance(expiry.get("task_id"), str):
        raise ValueError("baseline expires_when.task_id is required")
    entries = data.get("allowed_failures")
    if not isinstance(entries, list):
        raise ValueError("baseline allowed_failures must be a list")
    parsed = [FailureIdentity.from_json(entry) for entry in entries]
    if len(set(parsed)) != len(parsed):
        raise ValueError("baseline contains duplicate failure identities")
    return set(parsed), expiry["task_id"]


def _render_identities(
    title: str,
    entries: Iterable[FailureIdentity],
) -> list[str]:
    values = sorted(entries)
    lines = [f"### {title} ({len(values)})"]
    if values:
        lines.extend(f"- `{entry.display()}`" for entry in values)
    else:
        lines.append("- none")
    return lines


def build_summary(
    *,
    mode: str,
    tests_seen: int,
    actual: set[FailureIdentity],
    allowed: set[FailureIdentity],
    unexpected: set[FailureIdentity],
    resolved: set[FailureIdentity],
    problems: list[str],
) -> str:
    lines = [
        "# Java failure baseline result",
        "",
        f"- Mode: `{mode}`",
        f"- Tests represented in Surefire XML: `{tests_seen}`",
        f"- Current failure identities: `{len(actual)}`",
        f"- Temporarily allowed identities: `{len(allowed)}`",
        f"- Unexpected or changed identities: `{len(unexpected)}`",
        f"- Resolved baseline identities: `{len(resolved)}`",
        "",
    ]
    if problems:
        lines.append("### Gate problems")
        lines.extend(f"- {problem}" for problem in problems)
        lines.append("")
    lines.extend(_render_identities("Current failures", actual))
    lines.append("")
    lines.extend(
        _render_identities("Unexpected or changed failures", unexpected)
    )
    lines.append("")
    lines.extend(_render_identities("Resolved baseline failures", resolved))
    lines.append("")
    return "\n".join(lines)


def evaluate(
    *,
    report_dir: Path,
    baseline_path: Path,
    ledger_path: Path,
    maven_exit_code: int,
) -> tuple[int, str]:
    actual, tests_seen, parse_errors = parse_surefire_reports(report_dir)
    problems = list(parse_errors)

    allowed: set[FailureIdentity] = set()
    expiry_task = "T026"
    baseline_exists = baseline_path.exists()
    if baseline_exists:
        try:
            allowed, expiry_task = load_baseline(baseline_path)
        except (OSError, json.JSONDecodeError, ValueError) as exc:
            problems.append(f"invalid baseline: {exc}")

    try:
        expiry_status = read_task_status(ledger_path, expiry_task)
    except (OSError, ValueError) as exc:
        expiry_status = "UNKNOWN"
        problems.append(str(exc))

    hard_green = expiry_status == "DONE"
    mode = "hard-green" if hard_green else "temporary-baseline"

    if hard_green:
        unexpected = set(actual)
        resolved = set(allowed) - actual
        if baseline_exists and allowed:
            problems.append(
                f"{expiry_task} is DONE; delete the temporary baseline file and require zero Java failures"
            )
        if actual:
            problems.append(
                f"{expiry_task} is DONE but Java still has {len(actual)} failure identities"
            )
    else:
        if not baseline_exists:
            problems.append(
                f"temporary baseline is required until {expiry_task} is DONE"
            )
        unexpected = actual - allowed
        resolved = allowed - actual
        if unexpected:
            problems.append(
                f"Java introduced or changed {len(unexpected)} failure identities outside the baseline"
            )

    if maven_exit_code != 0 and not actual:
        problems.append(
            f"Maven exited {maven_exit_code} without a parsed test failure; treat this as a build/infrastructure failure"
        )
    if maven_exit_code == 0 and actual:
        problems.append(
            "Maven exited successfully while Surefire XML contains failures or errors"
        )

    summary = build_summary(
        mode=mode,
        tests_seen=tests_seen,
        actual=actual,
        allowed=allowed,
        unexpected=unexpected,
        resolved=resolved,
        problems=problems,
    )
    return (1 if problems else 0), summary


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--ledger", type=Path, required=True)
    parser.add_argument("--maven-exit-code", type=int, required=True)
    parser.add_argument("--summary", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    status, summary = evaluate(
        report_dir=args.reports,
        baseline_path=args.baseline,
        ledger_path=args.ledger,
        maven_exit_code=args.maven_exit_code,
    )
    print(summary)
    if args.summary:
        args.summary.write_text(summary, encoding="utf-8")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
