# Implementation Report - T012

## Summary

Centralized new-position entry risk behind `EntryAcceptanceService` / `StrategyIntentBoundary`. The boundary now creates a typed `EntryRiskRequest`, evaluates `RiskCheckService.assessEntry` exactly once, persists the complete structured decision before routing, and carries a short-lived approval context through the existing synchronous compatibility and order-layer adapters.

Raw BUY calls fail closed in the compatibility router, replay override path, legacy execution-service assertion, and order-layer gateway. SELL and cancel paths are unchanged and remain available for exposure reduction.

## Task

- Task ID: T012
- Task section: `transformation/tasks/PHASE-1-stop-the-bleeding.md#t012`
- Branch: `task/T012-centralize-entry-risk-policy`
- PR: #9
- Status at completion: DONE
- Started: 2026-07-25T06:36:53Z
- Completed: 2026-07-25T06:54:40Z

## Files changed

- Added `EntryRiskRequest` as the typed input to the sole entry policy.
- Added `EntryRiskDecisionContext` to carry a matching approved decision through synchronous adapters without re-evaluating policy.
- Refactored `RiskCheckService` to expose `assessEntry`; the legacy `assess` method is now only a fail-closed compatibility assertion.
- Updated `StrategyIntentBoundary` to assess, persist, reject or route in that order.
- Guarded `ExecutionRouter` before execution overrides and guarded `LiveOrderGateway` before `OrderManager` submission.
- Expanded `RiskAssessment` with explicit blocking, warning, and informational result views.
- Added `correlation_id` to `TradeRiskCheckEntity` and Flyway migration `V17__add_entry_risk_correlation.sql`.
- Added route, architecture, replay-override, order-layer, severity, correlation, and mutation-equivalent tests.
- Updated task ledgers and dependency statuses.

## Design decisions

1. **One policy method:** only `RiskCheckService.assessEntry(EntryRiskRequest)` evaluates entry policy. Existing paper/live services retain their call shape temporarily, but the deprecated `assess` method only verifies that a matching central approval context exists; it performs no risk evaluation.
2. **Persist before routing:** `StrategyIntentBoundary` saves all checks before either `ExecutionRouter` or `OrderGateway` receives the BUY.
3. **Exact intent binding:** approval matches the complete immutable `TradeIntent` plus execution mode, preventing an approved request from authorizing a modified amount, price, order type, or market.
4. **Pre-order correlation:** every check carries a deterministic correlation ID created before trade/order persistence. T020 will promote this into the durable intent/outbox and order linkage.
5. **Severity semantics:** failed `BLOCK` checks reject; failed `WARN` checks are visible but nonblocking; passed checks are `INFO`. Missing market expiry is now an explicit warning rather than an invisible pass.
6. **Route graph enforcement:** architecture tests restrict the central policy to one production caller and restrict `OrderManager.submitOrder` production calls to the guarded gateway.

## Tests run

### Focused Java contract harness

Command:

```text
javac --release 21 -d /tmp/t012-contract/out $(find /tmp/t012-contract/src -name '*.java')
java -cp /tmp/t012-contract/out com.vokerg.voktrader.trade.T012Harness
```

Result:

```text
T012 contract harness: PASS (correlation, severity, exact approval context, fail-closed bypass, duplicate block)
```

The harness compiled the changed policy/request/context contracts against focused repository-compatible stubs and exercised:

- deterministic correlation propagation to every check;
- INFO/WARN/BLOCK behavior;
- direct raw BUY rejection without boundary approval;
- matching approval consumption and guaranteed ThreadLocal cleanup;
- duplicate active-position blocking.

### Repository route and mutation-equivalent coverage added

- `StrategyIntentBoundaryTest`: verifies assess -> persist -> route ordering, exactly one policy invocation, blocked routing, both execution modes, and exit bypass of entry policy.
- `ExecutionRouterRiskBoundaryTest`: verifies raw replay/backtest override cannot bypass policy and an approved replay entry can execute.
- `LiveOrderGatewayRiskBoundaryTest`: verifies raw order-layer BUY is rejected while approved BUY and SELL are allowed.
- `RiskCheckServiceTest`: verifies correlation, severity categories, live-arm blocking, active exposure statuses, duplicate blocking, and fail-closed legacy calls.
- `CentralEntryRiskArchitectureTest`: verifies the sole policy caller and guarded production `OrderManager` call site.

### Environment limitation

A full Maven repository test run was not possible in this execution environment: outbound DNS to `github.com` is unavailable, so the branch could not be cloned, and the repository currently has no `.github/workflows` CI pipeline (tracked separately by T050). The focused harness passed; the committed JUnit and architecture tests are ready for the normal repository environment.

## Safety impact

- A BUY cannot reach paper, live, order-layer, or replay execution through the supported production routes without a persisted central risk decision.
- Removing the boundary risk call causes route tests and the architecture test to fail.
- Kill switch, live enabled, live arm, exposure limits, spread, freshness, expiry, whitelist, and size checks now share one policy implementation.
- SELL and cancellation behavior is not coupled to entry gates.

## Backward compatibility

- Strategy APIs remain `EntryAcceptanceService` and `ExitSubmissionService`.
- Paper and live execution services retain their existing public signatures for current tests and compatibility callers.
- The legacy risk method remains temporarily, but it rejects raw BUY unless the central boundary supplied a matching approval.
- Existing risk rows remain readable; `correlation_id` is nullable for historical data.

## Remaining risks

- The approval context is intentionally synchronous and process-local. T020 must persist the risk decision/correlation in the durable intent and outbox before asynchronous submission is introduced.
- Existing execution adapters still perform remote work inside current lifecycle transactions; T020 and T024 own that refactor.
- Full Maven/Spring/Flyway integration execution must run in a normal checkout or after T050 supplies CI.

## Follow-up tasks

No new task was required. Completion unblocks existing tasks:

- T013 - define portfolio exposure invariants;
- T015 - expose effective risk gate chain;
- T020 - create transactional order outbox schema.

T014 remains blocked on T013 as declared.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
