from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import check_java_failure_baseline as baseline_check


class JavaFailureBaselineTest(unittest.TestCase):
    def write_fixture(
        self,
        root: Path,
        *,
        exception: str = "org.opentest4j.AssertionFailedError",
        baseline_exception: str = "org.opentest4j.AssertionFailedError",
        status: str = "BLOCKED",
    ) -> tuple[Path, Path, Path]:
        reports = root / "reports"
        reports.mkdir()
        (reports / "TEST-example.xml").write_text(
            '<testsuite tests="1" failures="1">'
            '<testcase classname="example.DemoTest" name="fails">'
            f'<failure type="{exception}">boom</failure>'
            "</testcase></testsuite>",
            encoding="utf-8",
        )
        baseline = root / "baseline.json"
        baseline.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "expires_when": {"task_id": "T026"},
                    "allowed_failures": [
                        {
                            "test": "example.DemoTest.fails",
                            "kind": "failure",
                            "exception": baseline_exception,
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )
        ledger = root / "ledger.md"
        ledger.write_text(
            f"## T026 - Gate\nStatus: {status}\n---\n",
            encoding="utf-8",
        )
        return reports, baseline, ledger

    def test_exact_failure_baseline_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            reports, baseline, ledger = self.write_fixture(Path(temp))
            result, summary = baseline_check.evaluate(
                report_dir=reports,
                baseline_path=baseline,
                ledger_path=ledger,
                maven_exit_code=1,
            )
            self.assertEqual(0, result)
            self.assertIn("Unexpected or changed identities: `0`", summary)

    def test_non_class_surefire_type_is_compared_exactly(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            reports, baseline, ledger = self.write_fixture(
                Path(temp),
                exception="Wanted but not invoked",
                baseline_exception="Wanted but not invoked",
            )
            result, summary = baseline_check.evaluate(
                report_dir=reports,
                baseline_path=baseline,
                ledger_path=ledger,
                maven_exit_code=1,
            )
            self.assertEqual(0, result)
            self.assertIn("[Wanted but not invoked]", summary)

    def test_changed_exception_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            reports, baseline, ledger = self.write_fixture(
                Path(temp),
                exception="java.lang.AssertionError",
            )
            result, summary = baseline_check.evaluate(
                report_dir=reports,
                baseline_path=baseline,
                ledger_path=ledger,
                maven_exit_code=1,
            )
            self.assertEqual(1, result)
            self.assertIn("outside the baseline", summary)

    def test_resolved_failure_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            reports, baseline, ledger = self.write_fixture(root)
            (reports / "TEST-example.xml").write_text(
                '<testsuite tests="1"><testcase classname="example.DemoTest" name="passes"/></testsuite>',
                encoding="utf-8",
            )
            result, summary = baseline_check.evaluate(
                report_dir=reports,
                baseline_path=baseline,
                ledger_path=ledger,
                maven_exit_code=0,
            )
            self.assertEqual(0, result)
            self.assertIn("Resolved baseline identities: `1`", summary)

    def test_t026_done_requires_baseline_removal(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            reports, baseline, ledger = self.write_fixture(root, status="DONE")
            (reports / "TEST-example.xml").write_text(
                '<testsuite tests="1"><testcase classname="example.DemoTest" name="passes"/></testsuite>',
                encoding="utf-8",
            )
            result, summary = baseline_check.evaluate(
                report_dir=reports,
                baseline_path=baseline,
                ledger_path=ledger,
                maven_exit_code=0,
            )
            self.assertEqual(1, result)
            self.assertIn("delete the temporary baseline file", summary)

    def test_t026_done_without_baseline_requires_hard_green(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            reports, baseline, ledger = self.write_fixture(root, status="DONE")
            baseline.unlink()
            (reports / "TEST-example.xml").write_text(
                '<testsuite tests="1"><testcase classname="example.DemoTest" name="passes"/></testsuite>',
                encoding="utf-8",
            )
            result, summary = baseline_check.evaluate(
                report_dir=reports,
                baseline_path=baseline,
                ledger_path=ledger,
                maven_exit_code=0,
            )
            self.assertEqual(0, result)
            self.assertIn("Mode: `hard-green`", summary)


if __name__ == "__main__":
    unittest.main()
