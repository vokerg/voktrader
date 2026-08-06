# Phase 2 Durable Order Lifecycle

This ledger contains the detailed task contracts for this phase. Agents claim and update one task section per PR.

The 2026-08-03 checkpoint adds T026 as a mandatory phase-exit integration gate. T020 through T023 are DONE, and T024 is IN_PROGRESS in PR #35. Exchange-truth work must not begin until T020-T025 and T026 are DONE. See `CHECKPOINT-2026-08-03.md`.

---

## T020 - Create transactional order outbox schema

Status: DONE
Priority: P0
Phase: P2 - Durable order lifecycle
Owner: vokerg-gpt-5.6-thinking-20260805
Branch: task/T020-transactional-order-outbox-schema
PR: #31
Started: 2026-08-05T04:53:34Z
Completed: 2026-08-05T05:17:54Z
Depends On: T016
Parallelizable: no

### Objective
Persist accepted entry/exit work before any remote network side effect.

### Implementation steps
1. Add a Flyway migration for order intents/outbox tables.
2. Store immutable clientOrderId, intent hash, risk decision ID, mode, state, attempts, claim lease, and unknown-outcome metadata.
3. Add repositories and domain states.
4. Add a compatibility/backfill path for current order entities if needed.

### Acceptance criteria
- [x] Acceptance transaction writes intent and outbox row without calling executor.
- [x] Clean-database migration test passes.
- [x] Outbox row contains enough data to resume after restart.

### Required report
`transformation/reports/T020-2026-08-05-create-transactional-order-outbox-schema.md`

---

## T021 - Implement outbox worker and claim lease

Status: DONE
Priority: P0
Phase: P2 - Durable order lifecycle
Owner: vokerg-gpt-5.6-thinking-20260805
Branch: task/T021-outbox-worker-claim-lease
PR: #32
Started: 2026-08-05T16:22:01Z
Completed: 2026-08-05T16:41:45Z
Depends On: T020
Parallelizable: no

### Objective
Submit accepted outbox work after commit with bounded concurrency and recoverable leases.

### Implementation steps
1. Claim OUTBOX_READY rows with lease owner and expiry.
2. Persist SUBMITTING before executor call.
3. Persist response, remote IDs, and state transitions.
4. Reclaim/reconcile expired leases safely.
5. Instrument queue latency and submit RTT.

### Acceptance criteria
- [x] Crash before claim leaves row ready.
- [x] Crash after claim eventually reclaims or reconciles.
- [x] No two workers submit the same clientOrderId concurrently.

### Required report
`transformation/reports/T021-2026-08-05-implement-outbox-worker-and-claim-lease.md`

---

## T022 - Make executor idempotency durable

Status: DONE
Priority: P0
Phase: P2 - Durable order lifecycle
Owner: vokerg-gpt-5.6-thinking-20260805
Branch: task/T022-durable-executor-idempotency
PR: #33
Started: 2026-08-05T16:48:28Z
Completed: 2026-08-05T20:06:27Z
Depends On: T021
Parallelizable: no

### Objective
Ensure sidecar/JVM restart cannot duplicate accepted exchange orders.

### Implementation steps
1. Persist idempotency in sidecar storage or make Java clientOrderId authoritative with remote lookup before retry.
2. Define duplicate, pending, accepted, unknown, and rejected response contracts.
3. Add sidecar restart tests.
4. Document storage and cleanup policy.

### Acceptance criteria
- [x] Sidecar restart does not forget accepted clientOrderIds.
- [x] Retry with the same clientOrderId cannot create a second remote order.

### Required report
`transformation/reports/T022-2026-08-05-make-executor-idempotency-durable.md`

---

## T023 - Handle unknown submission outcomes

Status: DONE
Priority: P0
Phase: P2 - Durable order lifecycle
Owner: vokerg-gpt-5.6-thinking-20260805
Branch: task/T023-handle-unknown-submission-outcomes
PR: #34
Started: 2026-08-05T20:12:19Z
Completed: 2026-08-06T04:28:08Z
Depends On: T021, T022
Parallelizable: no

### Objective
Replace blind retries with explicit UNKNOWN, RECONCILE, and MANUAL_REVIEW states.

### Implementation steps
1. Classify timeout, reset, 425, 503, malformed response, and executor crash.
2. Persist unknown-outcome metadata.
3. Reconcile before retrying ambiguous submissions.
4. Enter manual review when remote truth is not unique.

### Acceptance criteria
- [x] Ambiguous submit never blind-retries.
- [x] Unknown state blocks conflicting exposure until resolved.
- [x] Operator status shows unknown orders clearly.

### Required report
`transformation/reports/T023-2026-08-06-handle-unknown-submission-outcomes.md`

---

## T024 - Remove executor calls from DB transactions

Status: IN_PROGRESS
Priority: P0
Phase: P2 - Durable order lifecycle
Owner: vokerg-gpt-5.6-thinking-20260806
Branch: task/T024-remove-executor-calls-from-db-transactions
PR: #35
Started: 2026-08-06T17:22:11Z
Completed:
Depends On: T021
Parallelizable: no

### Objective
Guarantee database transactions do not perform remote exchange calls.

### Implementation steps
1. Audit OrderManager, LiveExecutionService, cancellation, and reconciliation paths.
2. Move submit calls to the outbox worker.
3. Make cancellation durable: persist request first, call remote after commit where appropriate.
4. Add architecture tests detecting executor dependencies in transactional acceptance methods.

### Acceptance criteria
- [ ] No transactional entry acceptance method calls PythonExecutorClient.submit.
- [ ] Test fails if executor submit is reintroduced inside the acceptance transaction.

### Required report
`transformation/reports/T024-2026-08-06-remove-executor-calls-from-db-transactions.md`

---

## T025 - Rebuild cancellation lifecycle

Status: BLOCKED
Priority: P1
Phase: P2 - Durable order lifecycle
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T021, T024
Parallelizable: no

### Objective
Make cancellation evented, recoverable, and always available for open orders.

### Implementation steps
1. Model CANCEL_REQUESTED, CANCEL_SUBMITTING, CANCEL_ACKNOWLEDGED, CANCELLED, CANCEL_UNKNOWN, and CANCEL_RECONCILE.
2. Preserve fill-during-cancel races.
3. Emit cancellation telemetry.
4. Add restart tests around cancel boundaries.

### Acceptance criteria
- [ ] Cancel request survives restart.
- [ ] Fill during cancel converges to correct order/position state.
- [ ] Cancel remains available under kill switch.

### Required report
`transformation/reports/T025-YYYY-MM-DD-rebuild-cancellation-lifecycle.md`

---

## Phase-exit gate

T026 in `CHECKPOINT-2026-08-03.md` validates the integrated output of T020-T025. T030 remains blocked until T026 is DONE.

## Phase-wide safety rule

Do not enable live capital or tune strategy thresholds. Every task must record exact tests, update this task section and the index, and create follow-up tasks rather than silently broadening scope.