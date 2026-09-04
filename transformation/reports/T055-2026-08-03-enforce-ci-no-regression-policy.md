# Implementation Report - T055

## Summary

Implemented the repository-controlled portion of the Voktrader 2.0 CI no-regression policy. CI now runs the full Java suite, compares every current Java failure against an exact machine-readable baseline, fails on new or changed identities, publishes exact evidence, and automatically switches to hard-green enforcement when T026 is marked DONE.

The implementation is intentionally narrow and does not modify runtime trading behavior. T055 remains PARTIAL because required branch checks are GitHub repository-administration state and could not be applied or verified through the available repository interface. The exact administrator procedure and required check names are committed in `docs/ci.md`.

## Task

- Task ID: T055
- Task section: `transformation/tasks/CHECKPOINT-2026-08-03.md#t055---enforce-transformation-ci-no-regression-policy`
- Branch: `task/T055-ci-no-regression`
- PR: #25
- Status at completion: PARTIAL

## Files changed

- `.github/ci/java-failure-baseline.json`
  - Records all 25 temporarily allowed Java failure identities from the checkpoint run: 7 failures and 18 errors across 301 tests.
  - Stores full test identity, failure/error kind, exact Surefire `reported_type`, and explicit `assertion_class` metadata.
  - Ties expiration to T026 becoming DONE.
- `scripts/ci/check_java_failure_baseline.py`
  - Parses Surefire XML and compares exact failure identities.
  - Allows only a shrinking subset of the baseline.
  - Rejects new tests, kind changes, reported-type changes, invalid baseline metadata, incomplete test execution, non-test Maven failures, and non-ordinary Surefire termination.
  - Switches to hard-green enforcement from the T026 ledger status.
  - Emits current, unexpected, and resolved identities and counts as Markdown evidence.
- `scripts/ci/tests/test_check_java_failure_baseline.py`
  - Covers exact matches, changed identities, resolved failures, assertion-class metadata, non-class Surefire types, incomplete-suite rejection, non-test Maven failure rejection, and the T026 hard-green transition.
- `.github/workflows/ci.yml`
  - Renames the Java check to `Java failure baseline`.
  - Runs the full Maven suite and then propagates the policy comparator result without `continue-on-error` or equivalent workflow suppression.
  - Passes the raw Maven log to the policy gate so the terminal failure class is verified.
  - Publishes the Maven log, policy summary, and Surefire XML.
  - Runs CI policy unit tests in the static job.
- `docs/ci.md`
  - Documents local reproduction, failure identity semantics, complete-suite and Maven-exit validation, remediation evidence requirements, T026 baseline removal, and exact branch-protection administrator steps.
- `transformation/tasks/CHECKPOINT-2026-08-03.md`
  - Records the claim and final PARTIAL status accurately.
  - Keeps T054 and T056 blocked on T055.
- `transformation/tasks/INDEX.md`
  - Records T055 as PARTIAL without changing T042 or its PR.
- `transformation/reports/T055-2026-08-03-enforce-ci-no-regression-policy.md`
  - This report.

## Design decisions

1. **Exact identities, not a numeric budget.** The comparator keys each allowed result by full test identity, failure/error kind, and exact Surefire-reported type. A reduction passes; a new or changed identity fails.
2. **Explicit assertion metadata.** Surefire normally reports an exception class, but Mockito emitted the diagnostic type `Wanted but not invoked`. The baseline therefore retains both the machine-comparable `reported_type` and the underlying `assertion_class` required by the task contract.
3. **Fail closed on incomplete or non-test Maven failures.** The baseline's recorded test count is a minimum completeness floor. A non-zero Maven result is accepted only when the terminal failure is the ordinary Surefire `There are test failures.` result. Compilation, incomplete discovery, forked-JVM termination, plugin, or infrastructure failures therefore remain hard failures even if earlier XML contains known identities.
4. **Ledger-driven hard-green transition.** When T026 is DONE, any Java failure fails and any remaining temporary baseline file also fails with an instruction to delete it.
5. **Stable required-check names.** All seven safety checks have explicit job names documented for branch protection. Actual ruleset configuration remains an administrator action outside repository contents.

## Tests run

```bash
python -m unittest discover -s scripts/ci/tests -p 'test_*.py' -v
python -m py_compile \
  scripts/ci/check_java_failure_baseline.py \
  scripts/ci/tests/test_check_java_failure_baseline.py
```

Self-review hardening result:

```text
SUCCESS
All 9 Java-baseline policy unit tests passed.
New coverage proves an incomplete Surefire suite fails and a compiler/plugin-style Maven failure cannot be accepted merely because known XML already exists.
```

The hardened comparator was also replayed against the captured 301-test CI artifact and raw Maven log:

```text
SUCCESS
Tests represented in Surefire XML: 301
Minimum expected tests: 301
Current failure identities: 25
Temporarily allowed identities: 25
Unexpected or changed identities: 0
Resolved baseline identities: 0
```

```bash
./mvnw -B -ntp test 2>&1 | tee java-test.log
python scripts/ci/check_java_failure_baseline.py \
  --reports target/surefire-reports \
  --baseline .github/ci/java-failure-baseline.json \
  --ledger transformation/tasks/CHECKPOINT-2026-08-03.md \
  --maven-log java-test.log \
  --maven-exit-code 1 \
  --summary java-failure-summary.md
```

Original implementation result in CI run #185 (`30838946164`), job `Java failure baseline`:

```text
SUCCESS
Tests represented in Surefire XML: 301
Current failure identities: 25
Temporarily allowed identities: 25
Unexpected or changed identities: 0
Resolved baseline identities: 0
Maven summary: 7 failures, 18 errors, 2 skipped
```

The first branch run, #178 (`30838388465`), failed the comparator because the original Mockito baseline value did not exactly match Surefire's `type` attribute. That failure confirmed that the gate rejects changed metadata rather than silently accepting it. The baseline schema was then corrected to preserve both the exact reported type and explicit assertion class.

Full CI runs #185 (`30838946164`) and #188 (`30839273410`) passed all seven jobs before the self-review hardening. The PR body records the final-head CI run for the hardened implementation.

## Self-review finding

Self-review identified one blocking fail-open case in the first implementation: a non-test Maven failure could have been accepted if earlier Surefire XML already contained only baseline-approved failures. The fix requires both a complete baseline-sized suite and an ordinary terminal Surefire test-failure exit. The PR was not merged until this finding was corrected and revalidated.

## Safety impact

This change does not alter entry, exit, cancellation, order lifecycle, settlement, replay, or operator-control runtime code. It makes merge evidence stricter: an unrelated change cannot introduce a new Java failure, mutate an existing failure identity, truncate the Java suite, or mask a non-test Maven failure while the known remediation set remains red. Existing exit and cancellation permissiveness is unaffected.

## Backward compatibility

- The existing red Java suite remains temporarily accepted only when its exact 25 identities match the committed baseline and at least 301 tests are represented.
- Previously green tests must remain green.
- Existing successful Python, Angular, migration, static/dependency, secret, and smoke jobs are unchanged except for stable display names and added policy-script tests.
- Repository administrators must require the new exact Java check name, `Java failure baseline`, rather than any previous Java job display name.

## Remaining risks

- Required-check rules for `main` and `transformation/2.0` have not been applied or verified because the available GitHub interface exposes repository contents, PRs, issues, and Actions evidence but no branch-protection or ruleset mutation capability.
- Until an administrator applies the documented rules, repository settings may still permit merging without every successful check.
- The T026 completion PR must delete `.github/ci/java-failure-baseline.json`; the comparator will fail if T026 is DONE while the baseline file remains.

## Follow-up tasks

- A repository administrator must apply and verify the exact required checks documented in `docs/ci.md` for both protected branches. This is the remaining work for T055 to become DONE.
- T026 must remove the temporary baseline and retain the hard-green Java check.
- No new transformation task or ordering change is proposed.

## Completion checklist

- [ ] Acceptance criteria met — repository-admin required checks remain unapplied/unverified
- [x] Tests added/updated
- [x] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
