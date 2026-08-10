# Implementation Report - T025

## Summary

Rebuilt live-order cancellation as a durable, evented state machine that survives JVM restart and never treats a remote cancel acknowledgement as terminal exchange truth. Cancellation intent is persisted before the executor side effect, dispatch is lock-claimed in `CANCEL_SUBMITTING`, ambiguous outcomes reconcile before any retry, and a scheduled recovery worker resumes persisted cancellation obligations. Existing reconciliation remains authoritative for fill-during-cancel races, so observed fills win over a later remote cancellation terminal status.

## Task

- Task ID: T025
- Task section: `transformation/tasks/PHASE-2-durable-order-lifecycle.md#t025---rebuild-cancellation-lifecycle`
- Branch: `task/T025-rebuild-cancellation-lifecycle`
- PR: #36
- Status at completion: DONE

## Files changed

- `src/main/java/com/vokerg/voktrader/trade/DurableOrderCancellationService.java` - implements the durable cancellation coordinator and restart/retry rules.
- `src/main/java/com/vokerg/voktrader/trade/DurableOrderCancellationWorker.java` - resumes persisted cancellation obligations on the order-layer reconciliation cadence.
- `src/main/java/com/vokerg/voktrader/trade/OrderCancellationEventEmitter.java` - emits per-transition cancellation telemetry.
- `src/main/java/com/vokerg/voktrader/trade/model/TradeOrderStatus.java` - adds the required intermediate cancellation states and active-state semantics.
- `src/main/java/com/vokerg/voktrader/trade/persistence/TradeOrderRepository.java` - adds durable cancellation lookup and lock-protected dispatch claims.
- `src/test/java/com/vokerg/voktrader/trade/DurableOrderCancellationServiceTest.java` - covers commit-before-call, restart, ambiguous dispatch, and reconcile-before-retry boundaries.
- `src/test/java/com/vokerg/voktrader/trade/CancellationFillRaceTest.java` - proves fill-during-cancel convergence with the real reconciliation service.
- `transformation/tasks/PHASE-2-durable-order-lifecycle.md` - records T025 completion evidence.
- `transformation/tasks/CHECKPOINT-2026-08-03.md` - unblocks T026 after T025 completion.
- `transformation/tasks/INDEX.md` - advances the ordered source of truth from T025 to T026.
- `transformation/reports/T025-2026-08-10-rebuild-cancellation-lifecycle.md` - this implementation report.

## Design decisions

### Durable state machine

The order lifecycle now models the task contract exactly:

- `CANCEL_REQUESTED` - durable cancellation intent exists and a dispatch may be claimed.
- `CANCEL_SUBMITTING` - a worker committed ownership before crossing the executor boundary.
- `CANCEL_ACKNOWLEDGED` - the executor accepted the cancel request; this is not terminal exchange evidence.
- `CANCEL_UNKNOWN` - the cancel call threw, failed, returned no usable response, or a persisted submitting state became stale after restart.
- `CANCEL_RECONCILE` - remote truth must be reconciled before retry or terminal resolution.
- `CANCELLED` - terminal state reached only from authoritative reconciliation.

All nonterminal cancellation states remain active. No database migration is required because `trade_orders.status` already persists enum names as strings.

### Persist before side effect

`cancel(...)` commits `CANCEL_REQUESTED` and the cancel reason before any executor call. A second transaction obtains a pessimistic row lock and commits `CANCEL_SUBMITTING` before invoking `PythonExecutorClient.cancelOrder(...)` outside the database transaction. This preserves T024's no-remote-I/O-in-transaction guarantee while preventing concurrent workers from claiming the same request.

### Restart and ambiguous-outcome recovery

Persisted `cancelReason` is the durable cancellation obligation. The scheduled worker finds nonterminal orders with that intent and resumes them even when generic reconciliation temporarily projects the lifecycle back to an open/partial status.

A fresh `CANCEL_SUBMITTING` is treated as an in-flight call and is not duplicated. Once it is older than the configured executor timeout plus two seconds, it is classified as `CANCEL_UNKNOWN` and then `CANCEL_RECONCILE`. The recovery path reconciles remote truth before any retry; it never blindly resends a cancel after an ambiguous boundary.

A successfully acknowledged remote cancel also moves through `CANCEL_ACKNOWLEDGED` to `CANCEL_RECONCILE`. The acknowledgement alone cannot write `CANCELLED`.

If reconciliation successfully proves that the remote order is still genuinely open, the order returns to `CANCEL_REQUESTED` for a later retry. A remote pending-cancel state is deliberately not considered "still open" for this purpose, preventing a duplicate cancel while the exchange is already processing the request.

### Fill-during-cancel precedence

The existing reconciliation service imports fills before resolving terminal order status. T025 preserves that ordering. The new race test supplies remote `CANCELLED` plus a one-share fill against a five-share entry and proves convergence to `PARTIALLY_FILLED_DONE`, with the trade remaining `PARTIALLY_OPEN` for the filled position rather than losing exposure information.

### Telemetry

`LIVE_ORDER_CANCEL_STATE_CHANGED` is emitted for cancellation state transitions with order/trade identifiers, previous/resolved status, cancel reason, timestamps/lifetime, and raw remote response. Unlike the existing once-only request/terminal events, transition telemetry is intentionally not deduplicated so retries and recovery transitions remain observable.

### Kill-switch behavior

Cancellation remains routed through `LiveOrderGateway.cancelOrder(...)` independently of new-entry kill-switch gating. Existing `LiveKillSwitchRouteMatrixTest.sellAndCancelRemainAvailableWithKillSwitchEnabled()` continues to prove that the kill switch blocks new entry submission while SELL and cancel remain available.

## Tests run

Validation used GitHub Actions because this runtime has no repository-local network/checkout execution path.

Exact implementation head `5eed0ec6d117187ec2bebe8cdccccd11cad95a18` passed CI #317 (`31427668954`) with all seven jobs successful:

- Python tests - passed.
- Java full test report - passed as a reporting job; 354 tests represented, with 11 failures and 13 errors belonging to the existing known baseline.
- Java failure baseline - passed; exact 24 known failure identities matched, with 0 new and 0 removed.
- Docs guardrail - passed.
- Generated artifact guard - passed.
- Executor contract smoke - passed.
- Compose smoke - passed.

The T025-focused Java coverage added in this PR passed within that run, including cancellation restart boundaries and the real fill-during-cancel reconciliation race.

## Safety impact

This change strengthens a risk-reducing path. Cancellation remains available while live entry is kill-switched, cancellation intent is durable before network I/O, ambiguous remote outcomes reconcile before retry, and fills observed during cancellation continue to update position state. The implementation does not enable live capital, loosen risk gates, or tune strategy thresholds.

## Backward compatibility

- No Flyway/schema change is required.
- Existing terminal order states and executor cancel request/response contracts are unchanged.
- Existing `CANCEL_REQUESTED` rows remain resumable; newly introduced enum values are additive.
- Existing reconciliation remains the source of terminal exchange truth.

## Remaining risks

- The repository still carries the checkpoint's exact 24 known Java failure identities; T026 owns closing the integrated durable-order-lifecycle failures and removing the temporary baseline.
- Cancellation recovery depends on the existing reconciliation retry/backoff/manual-review controls when remote truth is unavailable or non-unique.

## Follow-up tasks

- T026 is now eligible as the mandatory durable-order-lifecycle phase-exit integration gate.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
