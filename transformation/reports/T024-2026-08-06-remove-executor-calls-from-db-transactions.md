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
- Completed: 2026-08-07T16:27:15Z

## Files Changed
- `src/main/java/com/vokerg/voktrader/trade/DurableOrderAcceptanceService.java`
  - Provides the transactional production acceptance boundary for LIVE entries and exits.
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

## Self Review
- Reviewed all changed files against the T024 contract and confirmed the diff remains scoped to durable acceptance, transaction-detached executor I/O, lifecycle projection, cancellation sequencing, regression tests, and task metadata.
- Confirmed there are no unresolved PR review threads or PR comments.
- Confirmed `transformation/2.0` remained at `cceef8e8e876015205b7b962bd74757219dc898b` during validation, so the branch had no base drift.
- CI exposed two stale test assumptions during review:
  - `LiveKillSwitchRouteMatrixTest` still used the old gateway/router constructors; the fixtures were updated to exercise the durable boundaries.
  - `CentralEntryRiskArchitectureTest` still required `LiveOrderGateway` to call `OrderManager.submitOrder`; it now asserts the stronger T024 invariant that no production source calls `orderManager.submitOrder(...)`.
- Neither correction weakened a production safety boundary or changed strategy behavior.

## Validation
- GitHub Actions CI run #299 (`31197436648`) validated implementation head `819f28468e3447c8fa1fcf1f2026dacd3d586548` through PR merge commit `0be441296fcec6447f8ab598674be7506a45b694`.
- All seven jobs passed:
  - Java failure baseline
  - Python tests
  - Angular tests and build
  - Static repository checks
  - Secret scan
  - Clean PostgreSQL migration
  - Java and executor compose smoke
- Java no-regression evidence:
  - 349 tests represented in Surefire XML.
  - 24 current failure identities, exactly matching the 24 temporary baseline identities.
  - 0 unexpected or changed identities.
  - 0 resolved baseline identities.
  - Maven itself still reports the known baseline failures; the repository policy gate passes because no failure identity regressed.
- Focused T024/T023-adjacent coverage on the validated head passed, including:
  - `LiveKillSwitchRouteMatrixTest`: 3/3
  - `CentralEntryRiskArchitectureTest`: 5/5
  - `ExecutorSubmissionTransactionBoundaryArchitectureTest`: 4/4
  - `DurableOrderAcceptanceServiceTest`: 2/2
  - `DurableOrderExitReplayTest`: 1/1
  - `DurableOrderCancellationServiceTest`: 1/1
  - `OrderDispatchLifecycleProjectorTest`: 1/1
  - `ExecutorTransactionBoundaryBeanPostProcessorTest`: 1/1
  - `UnknownSubmissionOutcomeTest`: 4/4
  - `OrderDispatchWorkerTest`: 6/6
  - `LiveOrderGatewayRiskBoundaryTest`: 3/3
  - `ExecutionRouterTest`: 3/3
  - `ExecutionRouterRiskBoundaryTest`: 2/2

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
- Legacy synchronous execution services remain compatibility/test surfaces, but production routers no longer use them for submission and executor calls are transaction-detached.
- No database migration was required because T020-T023 already provided the durable schema and the existing order table already stores `clientOrderId`.

## Remaining Risks
- T025 must add the complete restartable cancellation worker and fill-during-cancel convergence states.
- The 24 existing Java failure identities remain unchanged and are owned by the checkpoint remediation sequence, with T026 as the mandatory integrated phase-exit gate.
- The transaction-suspending executor proxy is intentionally broad; exact-head Spring-context and compose validation passed, but future executor bean changes must preserve that boundary.

## Follow-up Tasks
- T025 is READY after T024 completion.
- T026 remains BLOCKED until T025 is DONE.

## Completion Checklist
- [x] Production LIVE submission routes through the durable outbox.
- [x] Transactional acceptance has no executor dependency.
- [x] Cancellation request persists before the remote call.
- [x] Architecture and focused regression tests added.
- [x] Exact-head Java failure-baseline gate passing with 0 unexpected/changed identities.
- [x] All remaining exact-head CI jobs passing.
- [x] Required report finalized.
- [x] Phase ledger and index transitioned to DONE/READY.
- [x] T042 and PR #12 untouched.
