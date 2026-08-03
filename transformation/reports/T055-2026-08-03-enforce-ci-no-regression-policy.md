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
  - Rejects new tests, kind changes, reported-type changes, invalid baseline metadata, and non-test Maven failures.
  - Switches to hard-green enforcement from the T026 ledger status.
  - Emits current, unexpected, and resolved identities and counts as Markdown evidence.
- `scripts/ci/tests/test_check_java_failure_baseline.py`
  - Covers exact matches, changed identities, resolved failures, assertion-class metadata, non-class Surefire types, and the T026 hard-green transition.
- `.github/workflows/ci.yml`
  - Renames the Java check to `Java failure baseline`.
  - Runs the full Maven suite and then propagates the policy comparator result without `continue-on-error` or equivalent workflow suppression.
  - Publishes the Maven log, policy summary, and Surefire XML.
  - Runs CI policy unit tests in the static job.
- `docs/ci.md`
  - Documents local reproduction, failure identity semantics, remediation evidence requirements, T026 baseline removal, and exact branch-protection administrator steps.
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
3. **No build-failure suppression.** The workflow captures Maven's exit code only so the comparator can distinguish an approved test-failure exit from compilation, discovery, JVM, plugin, or infrastructure failure. The comparator's exit code is the job result.
4. **Ledger-driven hard-green transition.** When T026 is DONE, any Java failure fails and a populated temporary baseline also fails with an instruction to delete it.
5. **Stable required-check names.** All seven safety checks have explicit job names documented for branch protection. Actual ruleset configuration remains an administrator action outside repository contents.

## Tests run

```bash
python -m unittest discover -s scripts/ci/tests -p 'test_*.py' -v
python -m compileall -q executor-python/voktrader_executor scripts/ci
```

Result in CI run #185 (`30838946164`), job `Static repository checks`:

```text
SUCCESS
All 7 Java-baseline policy unit tests passed.
Repository hygiene, dependency authority, unit-test discovery, and Python compilation passed.
```

```bash
./mvnw -B -ntp test
python scripts/ci/check_java_failure_baseline.py \
  --reports target/surefire-reports \
  --baseline .github/ci/java-failure-baseline.json \
  --ledger transformation/tasks/CHECKPOINT-2026-08-03.md \
  --maven-exit-code 1 \
  --summary java-failure-summary.md
```

Result in CI run #185 (`30838946164`), job `Java failure baseline`:

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

Full CI run #185 (`30838946164`) result:

```text
SUCCESS - Java failure baseline
SUCCESS - Python tests
SUCCESS - Angular tests and build
SUCCESS - Clean PostgreSQL migration
SUCCESS - Static repository checks
SUCCESS - Secret scan
SUCCESS - Java and executor compose smoke
```

## Safety impact

This change does not alter entry, exit, cancellation, order lifecycle, settlement, replay, or operator-control runtime code. It makes merge evidence stricter: an unrelated change cannot introduce a new Java failure or mutate an existing failure identity while the known remediation set remains red. Existing exit and cancellation permissiveness is unaffected.

## Backward compatibility

- The existing red Java suite remains temporarily accepted only when its exact 25 identities match the committed baseline.
- Previously green tests must remain green.
- Existing successful Python, Angular, migration, static/dependency, secret, and smoke jobs are unchanged except for stable display names and added policy-script tests.
- Repository administrators must require the new exact Java check name, `Java failure baseline`, rather than any previous Java job display name.

## Remaining risks

- Required-check rules for `main` and `transformation/2.0` have not been applied or verified because the available GitHub interface exposes repository contents, PRs, issues, and Actions evidence but no branch-protection or ruleset mutation capability.
- Until an administrator applies the documented rules, repository settings may still permit merging without every successful check.
- The T026 completion PR must delete `.github/ci/java-failure-baseline.json`; the comparator will fail if T026 is DONE while the populated baseline remains.

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
