# Implementation Report

## Summary
- Routed every production LIVE order path through a transactional durable-acceptance coordinator that persists the immutable order intent, READY dispatch row, trade, and linked order before commit.
- Kept `OrderDispatchWorker` as the production authority for `PythonExecutorClient.submit(...)` and projected worker outcomes back into the existing trade/order lifecycle.
- Added a transaction-suspending proxy around every executor client bean so cancellation and reconciliation network calls cannot retain an ambient database transaction.
- Persisted `CANCEL_REQUESTED` and its audit event in a committed transaction before the remote cancel call; T025 still owns the complete restartable cancellation state machine.
- Added source-level architecture coverage and focused unit tests for durable acceptance ordering, exact replay, duplicate-exit blocking, cancellation sequencing, executor transaction suspension, and rejected-exit convergence.

## Task Details
- Task ID: T024
- Title: Remove executor calls from DB transactions
- Phase: P2 - Durable order lifecycle
- Branch: `task/T024-remove-executor-calls-from-db-transactions`
- Pull request: #35
- Base branch: `transformation/2.0`
- Started: 2026-08-06T17:22:11Z
- Completed: pending exact-head validation

## Files Changed
- `src/main/java/com/vokerg/voktrader/trade/DurableOrderAcceptanceService.java`
  - Provides the only transactional production acceptance boundary for LIVE entries and exits.
  - Atomically persists the outbox record plus linked trade/order state without an executor dependency.
  - Replays exact client-order retries and rejects a distinct exit while another exit is pending.
- `src/main/java/com/vokerg/voktrader/trade/LiveOrderGateway.java`
  - Routes submission and cancellation through the durable boundaries.
- `src/main/java/com/vokerg/voktrader/trade/ExecutionRouter.java`
  - Removes the remaining production route to synchronous `LiveExecutionService.execute(...)`.
- `src/main/java/com/vokerg/voktrader/trade/outbox/TransactionalOrderIntentService.java`
  - Exposes deterministic client-order preview for exact pending-exit replay without creating a second outbox row.
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchLifecycleProjector.java`
  - Projects claim, accepted, rejected, filled, unknown, reconcile, and operator-resolution outcomes into linked trade/order entities.
  - Restores position state after deterministic exit rejection.
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchStateService.java`
  - Applies outbox and lifecycle projections in the same `REQUIRES_NEW` state-transition transaction.
- `src/main/java/com/vokerg/voktrader/executor/ExecutorTransactionBoundaryBeanPostProcessor.java`
  - Wraps executor client beans with `PROPAGATION_NOT_SUPPORTED` so remote calls suspend any ambient database transaction.
- `src/main/java/com/vokerg/voktrader/trade/DurableOrderCancellationService.java`
  - Commits cancellation request persistence before remote cancellation and records the response separately.
- Focused tests under `src/test/java/com/vokerg/voktrader/` cover the boundaries above.

## Design Decisions

### Outbox is the production submit authority
Both `ExecutionRouter` and `LiveOrderGateway` now reach `DurableOrderAcceptanceService`. That service contains no executor dependency and calls `TransactionalOrderIntentService.accept(...)` before creating the linked legacy lifecycle records in the same transaction. The dispatch worker remains the production path that invokes `executorClient.submit(command)` after claim state is committed.

### Existing lifecycle remains observable
The T020-T023 outbox was durable but not linked to `TradeEntity` and `TradeOrderEntity`. T024 creates that linkage using the durable `clientOrderId`, then projects worker responses into the existing lifecycle so status APIs and reconciliation continue to observe the order.

### Exact replay without duplicate exits
Entries can replay after acceptance by recovering the existing linked order. Exits require an additional guard because the first acceptance changes the position to `EXIT_PENDING`. Deterministic client-order preview allows the exact retry to replay the pending order while rejecting a distinct exit request until the first one resolves.

### Network transaction suspension is global
Legacy compatibility services and reconciliation still use executor status, fill, cancel, and capability methods. A bean post-processor wraps every `PythonExecutorClient` subtype, including the scripted integration-test client, with a `PROPAGATION_NOT_SUPPORTED` transaction interceptor. This guarantees remote network work does not execute with an ambient database transaction even outside the new production acceptance route.

### Cancellation scope remains narrow
T024 persists `CANCEL_REQUESTED` and its event before the remote call. It does not add the full cancel worker, restart lease, fill-during-cancel state machine, or all T025 states. Those remain explicitly owned by T025.

## Tests Run
- GitHub Actions CI run #295 (`31123107439`): **QUEUED** at report creation; all seven jobs are awaiting runners.
- No exact-head pass is claimed yet.
- Task and index must remain `IN_PROGRESS` until the Java failure-baseline gate and the remaining CI jobs complete successfully.

## Focused Coverage Added
- `ExecutorSubmissionTransactionBoundaryArchitectureTest`
  - Fails if either production LIVE router bypasses durable acceptance.
  - Fails if transactional acceptance imports or invokes `PythonExecutorClient`/`submit`.
  - Confirms the worker owns the production submit call.
  - Confirms executor beans are wrapped with transaction suspension.
- `DurableOrderAcceptanceServiceTest`
  - Verifies outbox acceptance occurs before linked trade/order writes and all writes remain in the acceptance transaction.
  - Verifies exact entry replay does not create duplicate lifecycle records.
- `DurableOrderExitReplayTest`
  - Verifies exact pending-exit replay and rejection of a distinct concurrent exit.
- `DurableOrderCancellationServiceTest`
  - Verifies cancellation request persistence and audit emission occur before the remote call.
- `ExecutorTransactionBoundaryBeanPostProcessorTest`
  - Verifies executor invocation uses `PROPAGATION_NOT_SUPPORTED`.
- `OrderDispatchLifecycleProjectorTest`
  - Verifies deterministic exit rejection restores the open position.

## Safety Impact
- A committed accepted order always exists durably before production remote submission.
- Production LIVE routing cannot submit from the acceptance transaction.
- Executor calls made by cancellation or reconciliation suspend ambient database transactions.
- Exact retries reuse the same client-order identity and do not create a second remote submission.
- Deterministic exit rejection no longer leaves the position stuck in `EXIT_PENDING`.
- No live-capital setting, strategy threshold, T042 content, or PR #12 content changed.

## Backward Compatibility
- Existing `OrderLifecycleResult` and `TradeExecutionResult` surfaces remain in use.
- Existing trade/order tables remain the operator-visible lifecycle and are now projected from durable outbox truth.
- Legacy synchronous execution services remain as compatibility/test surfaces, but production routers no longer use them for submission and executor calls are transaction-detached.
- No database migration was required because T020-T023 already provided the durable schema and the existing order table already stores `clientOrderId`.

## Remaining Risks
- Exact-head CI has not completed because run #295 is queued; compilation and Spring-context behavior remain unverified until it executes.
- T025 must add the complete restartable cancellation worker and fill-during-cancel convergence states.
- The transaction-suspending executor proxy is intentionally broad; CI must confirm all Spring test contexts still replace and inject scripted executor subclasses correctly.
- T026 remains the mandatory integrated phase-exit gate.

## Follow-up Tasks
- T025 remains BLOCKED until T024 passes exact-head validation and is marked DONE.
- No subsequent task was claimed.

## Completion Checklist
- [x] Production LIVE submission routes through the durable outbox.
- [x] Transactional acceptance has no executor dependency.
- [x] Cancellation request persists before the remote call.
- [x] Architecture and focused regression tests added.
- [ ] Exact-head Java failure-baseline gate passing.
- [ ] Remaining exact-head CI jobs passing.
- [x] Required report added in provisional form.
- [ ] Phase ledger and index transitioned to DONE/READY.
- [x] T042 and PR #12 untouched.
