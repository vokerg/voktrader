# T021 - Implement outbox worker and claim lease

## Summary

Implemented the post-commit order dispatch worker for the durable order outbox introduced by T020. The worker claims ready rows with a committed lease before performing any executor call, persists executor responses and timing evidence, and moves expired or ambiguous submissions to reconciliation rather than retrying blindly.

The implementation is deliberately limited to worker claiming, lease recovery, response persistence, and bounded scheduled processing. Durable sidecar idempotency remains T022, detailed unknown-outcome classification remains T023, and rewiring the existing immediate-submit entry/cancellation paths remains T024/T025.

## Task details

- Task: T021
- Phase: P2 - Durable order lifecycle
- Branch: `task/T021-outbox-worker-claim-lease`
- Pull request: #32 (draft during implementation)
- Base: `transformation/2.0`
- Started: 2026-08-05T16:22:01Z
- Completed: 2026-08-05T16:41:45Z

## Files changed

- `src/main/resources/db/migration/V27__persist_order_dispatch_response.sql`
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderOutboxProperties.java`
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchClaim.java`
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchOutboxEntity.java`
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderIntentEntity.java`
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchOutboxRepository.java`
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchStateService.java`
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchWorker.java`
- `src/test/java/com/vokerg/voktrader/trade/outbox/OrderDispatchWorkerTest.java`
- `transformation/tasks/PHASE-2-durable-order-lifecycle.md`
- `transformation/tasks/INDEX.md`

## Design decisions

### Commit the claim before the network call

`OrderDispatchStateService.claimNext` runs in a `REQUIRES_NEW` transaction. It obtains a pessimistic write lock with skip-locked semantics, transitions one eligible row from `OUTBOX_READY` to `SUBMITTING`, increments attempts, records the lease owner/expiry and queue latency, and returns only after that transaction commits. `OrderDispatchWorker` invokes `PythonExecutorClient.submit` after the claim method returns, so the executor call is outside the claim transaction.

### Bounded claiming and stable worker identity

Each poll processes at most `voktrader.order-outbox.batch-size` rows. A configured worker ID can be supplied; otherwise the process derives one from the hostname and a UUID. Ready rows are ordered by creation time and ID, while concurrent workers skip locked rows rather than waiting on the same command.

### Safe expired-lease handling

A row whose committed `SUBMITTING` lease expires is moved to `RECONCILE`, with explicit unknown-outcome reason and details. It is not returned to `OUTBOX_READY`, because the remote call may have succeeded before the worker crashed. This satisfies recovery without introducing a duplicate submission path before T022/T023.

### Response persistence

V27 adds executor status, raw executor response, queue latency, and submit RTT fields. Accepted responses transition the dispatch to `SUBMITTED`, preserve the remote order ID, and mark the immutable intent lifecycle `DISPATCHED`. Deterministic rejected responses transition the dispatch to `FAILED` and the intent to `REJECTED`.

### Ambiguous exceptions

A runtime failure escaping the executor client is persisted as `RECONCILE` with `EXECUTOR_SUBMISSION_OUTCOME_AMBIGUOUS`. T023 owns the complete classification of timeout, reset, 425, 503, malformed response, executor crash, and manual-review states. T021 only establishes the non-retry quarantine boundary.

### Executor capability guard

The worker remains schedulable by default but does not claim ready rows while `voktrader.executor.enabled=false`. Expired leases are still reconciled. This prevents intentionally disabled executor capability from consuming and rejecting durable work.

### Timing instrumentation

Queue latency and submit RTT are persisted on each dispatch and included in structured logs. The repository does not currently carry a Micrometer dependency, so T021 does not broaden dependency scope merely to add timers.

### No current-route rewiring

The existing order acceptance routes are intentionally unchanged. T024 owns removal of executor calls from database transactions and routing accepted work through this outbox. Introducing a temporary bridge in T021 would risk duplicate submission or partially migrate only some paths.

## Tests run

Validation was performed in GitHub Actions because the connector environment did not provide a runnable local repository checkout.

### Final implementation CI evidence

GitHub Actions CI run #274 (`31026366306`) on commit `835c9c7fc93079757ad0527955d4f62ad9dcbb94`: **PASS**.

- Java suite and no-regression policy:
  - command: `./mvnw -B -ntp test`, followed by `scripts/ci/check_java_failure_baseline.py`
  - result: PASS
  - Surefire tests represented: 335
  - current known baseline failure identities: 24
  - unexpected or changed failure identities: 0
  - resolved baseline identities: 0
  - all six `OrderDispatchWorkerTest` cases passed
- Clean PostgreSQL migration:
  - command: `./mvnw -B -ntp -Dtest=CleanDatabaseMigrationTest test`
  - result: PASS; clean schema migrated through V27
- Python executor tests:
  - command: `python -m pytest -q executor-python/tests`
  - result: PASS
- Dashboard tests and build:
  - commands: `npm test -- --watch=false`; `npm run build`
  - result: PASS
- Static repository checks:
  - repository hygiene, dependency authorities, CI policy tests, and Python compilation
  - result: PASS
- Secret scan:
  - gitleaks against the current repository tree
  - result: PASS
- Java/executor compose smoke:
  - command: `bash scripts/ci/smoke-compose.sh`
  - result: PASS

### Focused worker coverage

- crash before claim leaves the row `OUTBOX_READY` with zero attempts;
- executor-disabled capability leaves ready work unclaimed;
- a successful claim commits `SUBMITTING`, submits with the durable `clientOrderId`, and persists the response;
- an expired `SUBMITTING` lease moves to `RECONCILE` and cannot be reclaimed blindly;
- two concurrent workers produce exactly one claim for a client order;
- a deterministic executor rejection completes as `FAILED` without another claim.

### Defects found during validation

- Self-review found that the worker was initially enabled while the executor is disabled by default. A capability guard and regression test were added so intentionally offline executor state leaves ready work untouched.
- The first lease tests used a fixed timestamp earlier than the row's runtime `nextAttemptAt`, causing the test rows to be correctly ineligible. The test clock was corrected to use an eligible runtime timestamp; no production locking change was required.

## Safety impact

- The durable claim is committed before the remote side effect.
- Concurrent workers cannot claim the same ready row at the same time.
- A crash after `SUBMITTING` does not create a blind retry; the row requires reconciliation.
- Disabled executor capability cannot consume ready rows.
- Executor status, remote ID, raw response, queue latency, and submit RTT survive process restart.
- No live-capital enablement, strategy threshold, fee, control-plane, T042, or PR #12 change was made.

## Backward compatibility

- V27 is additive and passed the clean PostgreSQL migration test.
- Existing T020 rows remain valid; new response/timing columns are nullable.
- Existing immediate order routing remains unchanged until T024.
- Default executor-disabled deployments continue to avoid remote submission.

## Remaining risks

- Executor-side idempotency is not durable across sidecar restart until T022.
- Detailed ambiguous-outcome classification and operator/manual-review handling remain T023.
- Current order acceptance and cancellation paths are not yet routed through the worker; T024/T025 own that integration.
- A late response after another node has already reconciled an expired lease cannot overwrite `RECONCILE`; subsequent remote-truth handling belongs to T023.
- T026 remains the mandatory integrated phase-exit gate.

## Follow-up tasks

No task was added or reordered. T022 is now READY under the existing durable-lifecycle sequence.

## Completion checklist

- [x] Crash before claim leaves the row ready.
- [x] Crash after claim eventually enters reconciliation.
- [x] Concurrent workers cannot submit the same claimed `clientOrderId` concurrently.
- [x] `SUBMITTING` is committed before the executor call.
- [x] Responses, remote IDs, queue latency, and submit RTT are persisted.
- [x] Clean PostgreSQL migration through V27 passes.
- [x] Full CI no-regression policy passes.
- [x] Required implementation report added.
- [x] Detailed task ledger and index updated.
