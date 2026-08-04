# Implementation Report - T012

## Summary

Reconstructed T012 on the current `transformation/2.0` head after closing stale, conflicted PR #9. The typed strategy boundary is now the sole evaluator and persistence point for new-position entry risk. Raw BUY calls through compatibility and order-layer gateways fail closed without correlated approval, while SELL and cancellation remain available through typed risk-reducing boundaries.

The integrated Java suite improved from the checkpoint baseline of 25 failure identities to 24. `StrategyExecutionBoundaryArchitectureTest` is now green, and no new or changed failure identity was introduced.

## Task

- Task ID: T012
- Task section: `transformation/tasks/PHASE-1-stop-the-bleeding.md#t012`
- Branch: `task/T012-central-risk-reconstruction`
- PR: #26
- Status at completion: DONE

## Files changed

- Added typed entry-risk request, assessment, and approved-decision context contracts.
- Centralized risk evaluation and persistence in `StrategyIntentBoundary`.
- Made raw BUY routing fail closed in `ExecutionRouter` and `LiveOrderGateway`.
- Added a cancellation-only strategy boundary so Strategy V2 does not depend on generic order submission plumbing.
- Added `correlation_id` to persisted risk checks through Flyway migration `V25__add_entry_risk_correlation.sql`.
- Added architecture, route, policy, persistence-order, and mutation-equivalent negative tests.
- Updated the phase ledger, checkpoint ledger, and task index.

## Design decisions

1. `StrategyIntentBoundary.accept` creates one `EntryRiskRequest`, invokes `RiskCheckService.assessEntry` exactly once, persists all resulting checks, and only then exposes a package-private approved context to an execution adapter.
2. `ExecutionRouter` and `LiveOrderGateway` reject BUY calls without the exact approved request and assessment context. This prevents compatibility, replay override, or order-layer code from silently bypassing the central policy.
3. SELL uses the typed exit boundary without entry exposure checks. Strategy cancellation uses `CancellationSubmissionService`, which exposes only `cancelOrder` and does not allow a strategy to select or invoke generic order submission plumbing.
4. The stale PR's `V17` migration number was not reused. The current migration chain already reaches V24, so the reconstruction uses V25 and passes migration-version uniqueness and clean PostgreSQL migration checks.
5. The temporary Java baseline was not edited. CI records the resolved identity automatically, preserving an auditable before/after comparison until T026 removes the baseline.

## Tests run

### Full CI

GitHub Actions run #200, run ID `30935934251`:

```text
Angular tests and build: success
Clean PostgreSQL migration: success
Python tests: success
Java failure baseline: success
Java and executor compose smoke: success
Static repository checks: success
Secret scan: success
```

Duplicate verification run #201, run ID `30936076450`, completed with the same seven successful jobs.

### Java suite and baseline evidence

Command executed by CI:

```bash
./mvnw -B -ntp test
python scripts/ci/check_java_failure_baseline.py \
  --reports target/surefire-reports \
  --baseline .github/ci/java-failure-baseline.json \
  --ledger transformation/tasks/CHECKPOINT-2026-08-03.md \
  --maven-log java-test.log \
  --maven-exit-code "$maven_status" \
  --summary java-failure-summary.md
```

Result from run #200:

```text
Tests represented in Surefire XML: 311
Minimum expected tests: 301
Raw Maven result: 6 failures, 18 errors, 2 skipped
Current failure identities: 24
Temporarily allowed identities: 25
Unexpected or changed identities: 0
Resolved baseline identities: 1
Resolved: StrategyExecutionBoundaryArchitectureTest.strategySourcesCannotSelectExecutionPlumbing
Java evidence artifact: 8903107536
```

Focused T012 results inside the full suite:

```text
CentralEntryRiskArchitectureTest: 4 passed
StrategyExecutionBoundaryArchitectureTest: 1 passed
StrategyIntentBoundaryTest: 4 passed
LiveOrderGatewayRiskBoundaryTest: 3 passed
ExecutionRouterRiskBoundaryTest: 2 passed
RiskCheckServiceTest: 5 passed
MigrationVersionUniquenessTest: 1 passed
```

The first reconstruction run exposed one test-fixture regression: Mockito restubbing invoked an existing dynamic answer with a null argument. The test was changed to `doReturn(...).when(...)`; the two subsequent complete CI runs passed.

## Safety impact

Every accepted new-position BUY now crosses one central risk policy invocation with correlated checks persisted before execution routing. Removing the risk invocation, routing a raw BUY, or calling the live gateway without the approved context fails tests and fails closed at runtime. SELL and cancellation remain outside new-exposure gating, preserving risk reduction while preventing strategies from accessing generic submission plumbing.

## Backward compatibility

Existing typed entry and exit callers retain their public interfaces. Compatibility and order-layer adapters still operate, but BUY calls must originate from the approved typed boundary. Existing `OrderGateway` implementations remain compatible because `OrderGateway` extends the new cancellation-only interface.

## Remaining risks

Twenty-four pre-existing order-lifecycle failure identities remain. They are concentrated in `OrderManagerTest`, `OrderLifecycleIntegrationTest`, `StrategyV2OrderLifecycleIntegrationTest`, and one Strategy V2 runtime-state test. T016 must validate their integrated entry setup and assign each remaining defect explicitly to T020-T025 or a newly justified task before downstream work proceeds.

Repository-admin branch protection required by T055 remains outside this task and is still unresolved.

## Follow-up tasks

- T016 becomes READY and is the only task directly unblocked by T012.
- T013, T015, T020, T042, and their downstream tasks remain BLOCKED until T016 is DONE.
- No new task or task reordering was required.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
