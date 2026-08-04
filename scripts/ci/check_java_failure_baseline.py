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
    reported_type: str

    @classmethod
    def from_json(cls, value: object) -> "FailureIdentity":
        if not isinstance(value, dict):
            raise ValueError("failure entries must be JSON objects")
        required = ("test", "kind", "reported_type", "assertion_class")
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
            reported_type=value["reported_type"],
        )

    def display(self) -> str:
        return f"{self.kind}: {self.test} [{self.reported_type}]"


@dataclass(frozen=True)
class Baseline:
    allowed: set[FailureIdentity]
    expiry_task: str
    minimum_tests: int


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _reported_type_from_node(node: ET.Element) -> str:
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
                        reported_type=_reported_type_from_node(child),
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


def load_baseline(path: Path) -> Baseline:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise ValueError("unsupported Java failure baseline schema_version")

    source = data.get("source")
    minimum_tests = source.get("tests") if isinstance(source, dict) else None
    if not isinstance(minimum_tests, int) or minimum_tests <= 0:
        raise ValueError("baseline source.tests must be a positive integer")

    expiry = data.get("expires_when")
    if not isinstance(expiry, dict) or not isinstance(expiry.get("task_id"), str):
        raise ValueError("baseline expires_when.task_id is required")
    entries = data.get("allowed_failures")
    if not isinstance(entries, list):
        raise ValueError("baseline allowed_failures must be a list")
    parsed = [FailureIdentity.from_json(entry) for entry in entries]
    if len(set(parsed)) != len(parsed):
        raise ValueError("baseline contains duplicate failure identities")
    return Baseline(
        allowed=set(parsed),
        expiry_task=expiry["task_id"],
        minimum_tests=minimum_tests,
    )


def _strip_ansi(text: str) -> str:
    return re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", text)


def is_surefire_test_failure_exit(maven_log_path: Path) -> bool:
    text = _strip_ansi(maven_log_path.read_text(encoding="utf-8", errors="replace"))
    failed_goal_lines = [
        line.strip()
        for line in text.splitlines()
        if line.strip().startswith("[ERROR] Failed to execute goal ")
    ]
    if len(failed_goal_lines) != 1:
        return False
    failed_goal = failed_goal_lines[0]
    return (
        "maven-surefire-plugin" in failed_goal
        and ":test " in failed_goal
        and "There are test failures." in failed_goal
    )


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
    minimum_tests: int | None,
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
        f"- Minimum expected tests: `{minimum_tests if minimum_tests is not None else 'n/a'}`",
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
    maven_log_path: Path,
    maven_exit_code: int,
) -> tuple[int, str]:
    actual, tests_seen, parse_errors = parse_surefire_reports(report_dir)
    problems = list(parse_errors)

    allowed: set[FailureIdentity] = set()
    expiry_task = "T026"
    minimum_tests: int | None = None
    baseline_exists = baseline_path.exists()
    if baseline_exists:
        try:
            baseline = load_baseline(baseline_path)
            allowed = baseline.allowed
            expiry_task = baseline.expiry_task
            minimum_tests = baseline.minimum_tests
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
        if baseline_exists:
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
        if minimum_tests is not None and tests_seen < minimum_tests:
            problems.append(
                f"Surefire XML represents only {tests_seen} tests; expected at least {minimum_tests} for a complete suite"
            )
        unexpected = actual - allowed
        resolved = allowed - actual
        if unexpected:
            problems.append(
                f"Java introduced or changed {len(unexpected)} failure identities outside the baseline"
            )

    if maven_exit_code != 0:
        if not actual:
            problems.append(
                f"Maven exited {maven_exit_code} without a parsed test failure; treat this as a build/infrastructure failure"
            )
        else:
            try:
                approved_test_exit = is_surefire_test_failure_exit(maven_log_path)
            except OSError as exc:
                problems.append(f"cannot read Maven log {maven_log_path}: {exc}")
            else:
                if not approved_test_exit:
                    problems.append(
                        f"Maven exited {maven_exit_code}, but the terminal failure was not an ordinary Surefire test-failure exit"
                    )
    if maven_exit_code == 0 and actual:
        problems.append(
            "Maven exited successfully while Surefire XML contains failures or errors"
        )

    summary = build_summary(
        mode=mode,
        tests_seen=tests_seen,
        minimum_tests=minimum_tests,
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
    parser.add_argument("--maven-log", type=Path, required=True)
    parser.add_argument("--maven-exit-code", type=int, required=True)
    parser.add_argument("--summary", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    status, summary = evaluate(
        report_dir=args.reports,
        baseline_path=args.baseline,
        ledger_path=args.ledger,
        maven_log_path=args.maven_log,
        maven_exit_code=args.maven_exit_code,
    )
    print(summary)
    if args.summary:
        args.summary.write_text(summary, encoding="utf-8")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
