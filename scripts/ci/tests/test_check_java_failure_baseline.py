from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import check_java_failure_baseline as baseline_check


SUREFIRE_FAILURE_LOG = (
    "[ERROR] Failed to execute goal "
    "org.apache.maven.plugins:maven-surefire-plugin:3.5.5:test "
    "(default-test) on project demo: There are test failures.\n"
)


class JavaFailureBaselineTest(unittest.TestCase):
    def write_fixture(
        self,
        root: Path,
        *,
        reported_type: str = "org.opentest4j.AssertionFailedError",
        baseline_reported_type: str = "org.opentest4j.AssertionFailedError",
        assertion_class: str = "org.opentest4j.AssertionFailedError",
        status: str = "BLOCKED",
        minimum_tests: int = 1,
        maven_log: str = SUREFIRE_FAILURE_LOG,
    ) -> tuple[Path, Path, Path, Path]:
        reports = root / "reports"
        reports.mkdir()
        (reports / "TEST-example.xml").write_text(
            '<testsuite tests="1" failures="1">'
            '<testcase classname="example.DemoTest" name="fails">'
            f'<failure type="{reported_type}">boom</failure>'
            "</testcase></testsuite>",
            encoding="utf-8",
        )
        baseline = root / "baseline.json"
        baseline.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "source": {"tests": minimum_tests},
                    "expires_when": {"task_id": "T026"},
                    "allowed_failures": [
                        {
                            "test": "example.DemoTest.fails",
                            "kind": "failure",
                            "reported_type": baseline_reported_type,
                            "assertion_class": assertion_class,
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
        log = root / "maven.log"
        log.write_text(maven_log, encoding="utf-8")
        return reports, baseline, ledger, log

    def evaluate_fixture(
        self,
        fixture: tuple[Path, Path, Path, Path],
        *,
        maven_exit_code: int,
    ) -> tuple[int, str]:
        reports, baseline, ledger, log = fixture
        return baseline_check.evaluate(
            report_dir=reports,
            baseline_path=baseline,
            ledger_path=ledger,
            maven_log_path=log,
            maven_exit_code=maven_exit_code,
        )

    def test_exact_failure_baseline_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            fixture = self.write_fixture(Path(temp))
            result, summary = self.evaluate_fixture(fixture, maven_exit_code=1)
            self.assertEqual(0, result)
            self.assertIn("Unexpected or changed identities: `0`", summary)

    def test_non_class_surefire_type_is_compared_exactly(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            fixture = self.write_fixture(
                Path(temp),
                reported_type="Wanted but not invoked",
                baseline_reported_type="Wanted but not invoked",
                assertion_class="org.mockito.exceptions.verification.WantedButNotInvoked",
            )
            result, summary = self.evaluate_fixture(fixture, maven_exit_code=1)
            self.assertEqual(0, result)
            self.assertIn("[Wanted but not invoked]", summary)

    def test_changed_reported_type_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            fixture = self.write_fixture(
                Path(temp),
                reported_type="java.lang.AssertionError",
            )
            result, summary = self.evaluate_fixture(fixture, maven_exit_code=1)
            self.assertEqual(1, result)
            self.assertIn("outside the baseline", summary)

    def test_missing_assertion_class_fails_baseline_validation(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            fixture = self.write_fixture(
                Path(temp),
                assertion_class="",
            )
            result, summary = self.evaluate_fixture(fixture, maven_exit_code=1)
            self.assertEqual(1, result)
            self.assertIn("assertion_class", summary)

    def test_incomplete_surefire_suite_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            fixture = self.write_fixture(Path(temp), minimum_tests=2)
            result, summary = self.evaluate_fixture(fixture, maven_exit_code=1)
            self.assertEqual(1, result)
            self.assertIn("expected at least 2", summary)

    def test_non_test_maven_failure_with_known_xml_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            fixture = self.write_fixture(
                Path(temp),
                maven_log=(
                    "[ERROR] Failed to execute goal "
                    "org.apache.maven.plugins:maven-compiler-plugin:3.14.1:testCompile "
                    "(default-testCompile) on project demo: Compilation failure\n"
                ),
            )
            result, summary = self.evaluate_fixture(fixture, maven_exit_code=1)
            self.assertEqual(1, result)
            self.assertIn("not an ordinary Surefire test-failure exit", summary)

    def test_resolved_failure_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            fixture = self.write_fixture(root)
            reports, _, _, _ = fixture
            (reports / "TEST-example.xml").write_text(
                '<testsuite tests="1"><testcase classname="example.DemoTest" name="passes"/></testsuite>',
                encoding="utf-8",
            )
            result, summary = self.evaluate_fixture(fixture, maven_exit_code=0)
            self.assertEqual(0, result)
            self.assertIn("Resolved baseline identities: `1`", summary)

    def test_t026_done_requires_baseline_removal(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            fixture = self.write_fixture(root, status="DONE")
            reports, _, _, _ = fixture
            (reports / "TEST-example.xml").write_text(
                '<testsuite tests="1"><testcase classname="example.DemoTest" name="passes"/></testsuite>',
                encoding="utf-8",
            )
            result, summary = self.evaluate_fixture(fixture, maven_exit_code=0)
            self.assertEqual(1, result)
            self.assertIn("delete the temporary baseline file", summary)

    def test_t026_done_without_baseline_requires_hard_green(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            fixture = self.write_fixture(root, status="DONE")
            reports, baseline, _, _ = fixture
            baseline.unlink()
            (reports / "TEST-example.xml").write_text(
                '<testsuite tests="1"><testcase classname="example.DemoTest" name="passes"/></testsuite>',
                encoding="utf-8",
            )
            result, summary = self.evaluate_fixture(fixture, maven_exit_code=0)
            self.assertEqual(0, result)
            self.assertIn("Mode: `hard-green`", summary)


if __name__ == "__main__":
    unittest.main()
