# Implementation Report - T013

## Summary

Defined portfolio exposure independently of Strategy V2 inner-strategy ownership and enforced the configured active-exposure policy at the sole central entry-risk boundary.

A new candidate-specific `PortfolioSnapshot` aggregates active trades across sibling strategy IDs for the same bot and execution mode, classifies pending-entry, provisional-position, and exiting-position exposure, and reports market/token/portfolio counts. Closed and other terminal trades do not reserve capacity. Runtime status now names active-position controls separately from the live entry-attempt cooldown.

## Task

- Task ID: T013
- Task section: `transformation/tasks/PHASE-1-stop-the-bleeding.md#t013`
- Branch: `task/T013-portfolio-exposure-invariants`
- PR: #28
- Status at completion: DONE

## Files changed

- Added `PortfolioExposureState` to classify active lifecycle states that reserve entry capacity.
- Added `PortfolioSnapshot` with bot, process-account namespace, mode, market, token/outcome, state counts, and cross-strategy exposure totals.
- Added a portfolio-exposure repository query that deliberately omits `strategyId`.
- Replaced strategy-scoped duplicate checks in `RiskCheckService` with explicit one-position and active-cap checks.
- Added explicitly named configuration for one-position-per-bot-market, one-position-per-token, per-market active positions, portfolio active positions, and live entry-attempt cooldown.
- Kept `maxTradesPerMarket` as a deprecated compatibility alias for existing configuration.
- Exposed the portfolio exposure policy through `/api/runtime/status`.
- Added cross-inner-strategy, terminal-state, lifecycle-state, portfolio-cap, and runtime-status tests.
- Updated the phase ledger and task index.

## Design decisions

1. **Portfolio exposure is not strategy ownership.** The active exposure query is keyed by bot and execution mode and intentionally does not filter by `strategyId`. A position opened by one Strategy V2 inner strategy therefore blocks a sibling strategy when the configured market/token policy requires it.
2. **All nonterminal exposure reserves capacity.** `CREATED` and `ENTRY_PENDING` are pending entry; `PARTIALLY_OPEN` and `OPEN` are provisional position; `EXIT_PENDING` and `PARTIALLY_CLOSED` are exiting position. These states may still create or retain exposure, so all count until a terminal state is persisted.
3. **Active caps and attempt throttles are distinct.** `MAX_ACTIVE_POSITIONS_PER_MARKET` and `MAX_ACTIVE_POSITIONS_PER_PORTFOLIO` inspect current nonterminal exposure. `LIVE_ENTRY_ATTEMPT_COOLDOWN` inspects recent failed/rejected/cancelled/expired/timed-out entry attempts. Runtime status exposes these under separate names.
4. **Existing configuration remains compatible.** The historical `max-trades-per-market` property binds through deprecated accessors to `maxActivePositionsPerMarket`; no silent default change or broad configuration migration is required.
5. **Account identity follows the current single-account process invariant.** The snapshot key records the configured LIVE expected account or a mode-specific non-live namespace. `TradeEntity` does not yet persist account identity, so the database query enforces bot plus mode isolation within the current one-account-per-process architecture. Persisted multi-account row isolation belongs with the durable intent/outbox schema rather than an unrelated migration in this task.
6. **Conservative stale-state behavior is intentional.** An active-status row reserves capacity even if its market end time has elapsed. Reconciliation should terminalize stale rows; entry risk should not infer that exposure disappeared.

## Tests run

### Full CI

GitHub Actions run #237, run ID `30939831562`:

```text
Angular tests and build: success
Clean PostgreSQL migration: success
Python tests: success
Java failure baseline: success
Java and executor compose smoke: success
Static repository checks: success
Secret scan: success
```

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

Result:

```text
Tests represented in Surefire XML: 317
Minimum expected tests: 311
Raw Maven result: 6 failures, 18 errors, 2 skipped
Current failure identities: 24
Temporarily allowed identities: 24
Unexpected or changed identities: 0
Resolved baseline identities: 0
Java evidence artifact: 8904658049
```

Focused T013 coverage inside the suite:

```text
PortfolioSnapshotTest: 1 passed
RiskCheckServiceTest: 7 passed
RuntimeStatusControllerTest: 1 passed
```

The first implementation run #236 (`30939494790`) was correctly rejected by the Java policy with three new `UnfinishedStubbingException` identities in the new risk tests. The fixture helper had been called while Mockito was still constructing an outer repository stub. The trade fixtures were created before repository stubbing; run #237 then passed without changing production behavior or broadening the baseline.

## Safety impact

A sibling inner strategy can no longer open another new position merely because it has a different `strategyId`. Every BUY remains evaluated once at the central boundary, where pending, provisional, and exiting exposure can block the entry according to the configured market, token, and portfolio policy. SELL and cancellation paths are unchanged and remain available for risk reduction.

## Backward compatibility

Existing `max-trades-per-market` configuration continues to work through a deprecated alias. Existing strategy-specific repository methods remain available to attribution and runtime-state callers; only central portfolio-risk evaluation uses the new cross-strategy query. Runtime status retains the legacy `maxTradesPerMarket` field while adding the explicit policy object.

## Remaining risks

- The current schema does not persist account identity on each trade. Portfolio enforcement is safe for the existing one-account-per-process architecture, but a future shared database serving multiple accounts must persist and query account identity. T020 is the appropriate schema boundary for that durable identity.
- Snapshot evaluation and acceptance are not yet one atomic capacity reservation. Concurrent accepted intents could race between the read and durable reservation. T020/T021 must make acceptance and outbox reservation transactional.
- Active rows whose markets have ended remain conservatively blocking until reconciliation terminalizes them.
- The 24 pre-existing durable lifecycle failures remain unchanged and owned by T020-T025.

## Follow-up tasks

- T014 becomes READY and is the next task under the lowest-ID rule.
- T015 and T020 remain READY behind T014.
- No new task or task reordering is required; multi-account persistence and atomic reservation fit existing T020/T021 contracts.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
