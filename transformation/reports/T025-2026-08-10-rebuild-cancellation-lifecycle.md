# Implementation Report - T025

## Summary

Rebuilt live-order cancellation as a durable, evented state machine that survives JVM restart and never treats a remote cancel acknowledgement as terminal exchange truth. Cancellation intent is persisted before the executor side effect, dispatch is lock-claimed in `CANCEL_SUBMITTING`, ambiguous outcomes reconcile before any retry, and a scheduled recovery worker resumes persisted cancellation obligations. Existing reconciliation remains authoritative for fill-during-cancel races, so observed fills win over a later remote cancellation terminal status.

A two-pass post-implementation review found and remediated three cancellation-boundary races before PR #36 was returned to review: pre-submit cancellation now serializes against the submission outbox claim, immutable cancel-request events backstop recovery if a stale JPA snapshot erases `cancelReason`, and cancelled exit orders restore the held-position trade state instead of remaining `EXIT_PENDING`.

## Task

- Task ID: T025
- Task section: `transformation/tasks/PHASE-2-durable-order-lifecycle.md#t025---rebuild-cancellation-lifecycle`
- Branch: `task/T025-rebuild-cancellation-lifecycle`
- PR: #36
- Status at completion: DONE

## Files changed

- `src/main/java/com/vokerg/voktrader/trade/DurableOrderCancellationService.java` - implements the durable cancellation coordinator, pre-submit suppression, event-backed recovery, restart/retry rules, and cancelled-exit position restoration.
- `src/main/java/com/vokerg/voktrader/trade/DurableOrderCancellationWorker.java` - resumes persisted cancellation obligations on the order-layer reconciliation cadence.
- `src/main/java/com/vokerg/voktrader/trade/OrderCancellationEventEmitter.java` - emits durable request/terminal events plus per-transition cancellation telemetry.
- `src/main/java/com/vokerg/voktrader/trade/model/TradeOrderStatus.java` - adds the required intermediate cancellation states and active-state semantics.
- `src/main/java/com/vokerg/voktrader/trade/persistence/TradeOrderRepository.java` - adds lock-protected cancellation dispatch claims and event-backed recovery lookup.
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchOutboxEntity.java` - permits `OUTBOX_READY` work to become `CANCELLED` before submission.
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchOutboxRepository.java` - exposes a pessimistically locked lookup used to serialize cancel versus submit claim.
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchStateService.java` - atomically cancels queued submission work and exposes its durable result to cancellation recovery.
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderIntentEntity.java` - records accepted intent as `CANCELLED` when submission is suppressed.
- `src/test/java/com/vokerg/voktrader/trade/DurableOrderCancellationServiceTest.java` - covers commit-before-call, restart, event-backed recovery, pre-submit cancellation, ambiguous dispatch, and reconcile-before-retry boundaries.
- `src/test/java/com/vokerg/voktrader/trade/CancellationFillRaceTest.java` - proves fill-during-cancel convergence with the real reconciliation service.
- `src/test/java/com/vokerg/voktrader/trade/CancelledExitTradeRecoveryTest.java` - proves a cancelled queued exit restores the position instead of leaving `EXIT_PENDING`.
- `src/test/java/com/vokerg/voktrader/trade/outbox/OrderDispatchCancellationTest.java` - proves a cancelled ready outbox cannot subsequently be claimed for submission.
- `transformation/tasks/PHASE-2-durable-order-lifecycle.md` - records T025 completion and final validation evidence.
- `transformation/tasks/CHECKPOINT-2026-08-03.md` - unblocks T026 after T025 completion.
- `transformation/tasks/INDEX.md` - advances the ordered source of truth from T025 to T026.
- `transformation/reports/T025-2026-08-10-rebuild-cancellation-lifecycle.md` - this implementation report.

## Design decisions

### Durable state machine

The order lifecycle models the task contract exactly:

- `CANCEL_REQUESTED` - durable cancellation intent exists and a dispatch may be claimed.
- `CANCEL_SUBMITTING` - a worker committed ownership before crossing the executor boundary.
- `CANCEL_ACKNOWLEDGED` - the executor accepted the cancel request; this is not terminal exchange evidence.
- `CANCEL_UNKNOWN` - the cancel call threw, failed, returned no usable response, or a persisted submitting state became stale after restart.
- `CANCEL_RECONCILE` - remote truth must be reconciled before retry or terminal resolution.
- `CANCELLED` - terminal state reached from authoritative reconciliation or from proving the corresponding submission outbox was cancelled before any remote submit.

All nonterminal cancellation states remain active. No database migration is required because `trade_orders.status`, order-intent state, and dispatch state already persist enum names as strings and the outbox/intent enums already contained `CANCELLED`.

### Persist before side effect

For an already-submitted remote order, `cancel(...)` commits `CANCEL_REQUESTED` and the cancel reason before any executor cancel call. A second transaction obtains a pessimistic order-row lock and commits `CANCEL_SUBMITTING` before invoking `PythonExecutorClient.cancelOrder(...)` outside the database transaction. This preserves T024's no-remote-I/O-in-transaction guarantee while preventing concurrent cancellation workers from claiming the same request.

For an accepted order that is still `OUTBOX_READY`, cancellation first locks the same outbox row used by the submission worker's claim. If cancellation wins, the dispatch and accepted intent become `CANCELLED`, the local order becomes terminal without any remote submit, and the executor submit worker can no longer claim it. If submission has already won and committed `SUBMITTING`, cancellation cannot suppress that ambiguous boundary and instead follows reconciliation before any cancel retry. Both paths acquire the outbox lock before projecting the order, avoiding lock inversion between submit and cancel.

### Restart and stale-write recovery

The scheduled worker treats either persisted `cancelReason` or the immutable `LIVE_ORDER_CANCEL_REQUESTED` event as evidence of a durable cancellation obligation. The event fallback is deliberate: `TradeOrderEntity` has no optimistic version column, so a reconciliation transaction that loaded an older order snapshot can legally flush its stale `cancelReason` after the cancel transaction commits. If that occurs, the worker reconstructs the cancellation intent from the durable request event into `CANCEL_RECONCILE` and reconciles before any retry.

A fresh `CANCEL_SUBMITTING` is treated as an in-flight call and is not duplicated. Once it is older than the configured executor timeout plus two seconds, it is classified as `CANCEL_UNKNOWN` and then `CANCEL_RECONCILE`. A successfully acknowledged remote cancel also moves through `CANCEL_ACKNOWLEDGED` to `CANCEL_RECONCILE`; acknowledgement alone cannot write terminal remote truth.

If reconciliation proves that the remote order is still genuinely open, the order returns to `CANCEL_REQUESTED` for a later retry. A remote pending-cancel state is deliberately not considered "still open" for this purpose, preventing a duplicate cancel while the exchange is already processing the request.

### Fill-during-cancel and exit-state precedence

Reconciliation imports fills before resolving terminal order status. The fill-race test supplies remote `CANCELLED` plus a one-share fill against a five-share entry and proves convergence to `PARTIALLY_FILLED_DONE`, with the trade remaining `PARTIALLY_OPEN` for the filled position rather than losing exposure information.

For a no-fill exit cancellation, the order may be terminal while the trade still says `EXIT_PENDING`. T025 now restores the trade to `OPEN`, `PARTIALLY_OPEN`, or `PARTIALLY_CLOSED` from its persisted entry/exit totals. This restoration is cancellation-specific and does not override an already-terminal trade.

### Telemetry

`LIVE_ORDER_CANCEL_STATE_CHANGED` is emitted for cancellation state transitions with order/trade identifiers, previous/resolved status, cancel reason, timestamps/lifetime, and raw remote response. Unlike the existing once-only request/terminal events, transition telemetry is intentionally not deduplicated so retries and recovery transitions remain observable. The once-only request event also serves as immutable recovery evidence.

### Kill-switch behavior

Cancellation remains routed through `LiveOrderGateway.cancelOrder(...)` independently of new-entry kill-switch gating. Existing `LiveKillSwitchRouteMatrixTest.sellAndCancelRemainAvailableWithKillSwitchEnabled()` continues to prove that the kill switch blocks new entry submission while SELL and cancel remain available.

## Double-review findings

Two independent review passes were performed after the initial green implementation head.

1. Concurrency/lifecycle pass: found that `cancelReason` alone was not durable against a stale unversioned JPA order snapshot, and that queued submission could race cancellation. Remediation added immutable-event-backed recovery and same-row outbox serialization.
2. Adversarial restart/integration pass: found that terminal cancellation of an EXIT order could leave the trade `EXIT_PENDING`. Remediation restores the persisted position state and adds a focused regression test.

The remediation was then reviewed again for lock ordering, blind retries, remote pending-cancel handling, fill precedence, terminal-state overwrite protection, kill-switch routing, and scope. No additional blocking findings remained on code head `23d41e474b8b12f01141240c40e1d6771fe48ec7`.

## Tests run

Validation used GitHub Actions because this runtime has no repository-local checkout/network execution path.

Initial implementation head `5eed0ec6d117187ec2bebe8cdccccd11cad95a18` passed CI #317 (`31427668954`). The post-review remediation head `23d41e474b8b12f01141240c40e1d6771fe48ec7` passed CI #331 (`31457779703`) with all seven current jobs successful:

- Angular tests and build - passed.
- Python tests - passed.
- Secret scan - passed.
- Clean PostgreSQL migration - passed.
- Static repository checks - passed.
- Java failure baseline - passed.
- Java and executor compose smoke - passed.

The Java evidence for CI #331 represented 357 tests with the exact 24 temporarily allowed baseline identities, 0 unexpected/changed identities, and 0 resolved baseline identities. Focused T025 coverage was green:

- `DurableOrderCancellationServiceTest` - 6 tests, 0 failures/errors.
- `CancellationFillRaceTest` - 1 test, 0 failures/errors.
- `CancelledExitTradeRecoveryTest` - 1 test, 0 failures/errors.
- `OrderDispatchCancellationTest` - 1 test, 0 failures/errors.

## Safety impact

This change strengthens a risk-reducing path. Cancellation remains available while live entry is kill-switched, queued exposure can be cancelled before submission, cancellation intent has immutable recovery evidence before remote cancel I/O, ambiguous remote outcomes reconcile before retry, fills observed during cancellation continue to update position state, and cancelled exits no longer strand a position in `EXIT_PENDING`. The implementation does not enable live capital, loosen risk gates, or tune strategy thresholds.

## Backward compatibility

- No Flyway/schema change is required.
- Existing terminal order states and executor cancel request/response contracts are unchanged.
- Existing `CANCEL_REQUESTED` rows remain resumable; newly introduced order status values are additive.
- Existing `CANCELLED` values in the outbox and intent enums are now used for their intended pre-submit terminal path.
- Existing reconciliation remains the source of terminal exchange truth once a remote submission may have occurred.

## Remaining risks

- The repository still carries the checkpoint's exact 24 known Java failure identities; T026 owns closing the integrated durable-order-lifecycle failures and removing the temporary baseline.
- Cancellation recovery still depends on the existing reconciliation retry/backoff/manual-review controls when remote truth is unavailable or non-unique.

## Follow-up tasks

- T026 is now eligible as the mandatory durable-order-lifecycle phase-exit integration gate.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and results recorded
- [x] Double review completed and blocking findings remediated
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
