# T022 - Make executor idempotency durable

## Summary

Replaced the executor sidecar's process-local TTL cache with a durable SQLite idempotency authority keyed by the JVM-provided `clientOrderId`. A new key is reserved as `PENDING` before any exchange submission is allowed, terminal responses are replayed exactly across process restart, concurrent duplicates cannot obtain a second submission grant, and orphaned or non-terminal outcomes fail closed as `UNKNOWN` for reconciliation.

The implementation is deliberately limited to executor-side submission identity. Detailed JVM classification and reconciliation of timeout, reset, 425, 503, malformed response, and non-unique remote truth remain T023. Routing the remaining immediate-submit paths through the durable outbox remains T024.

## Task details

- Task: T022
- Phase: P2 - Durable order lifecycle
- Branch: `task/T022-durable-executor-idempotency`
- Pull request: #33 (draft during implementation)
- Base: `transformation/2.0`
- Started: 2026-08-05T16:48:28Z
- Completed: 2026-08-05T20:06:27Z

## Files changed

- `executor-python/voktrader_executor/idempotency.py`
- `executor-python/tests/test_durable_idempotency.py`
- `executor-python/IDEMPOTENCY.md`
- `transformation/tasks/PHASE-2-durable-order-lifecycle.md`
- `transformation/tasks/INDEX.md`

## Design decisions

### SQLite is the durable sidecar authority

The sidecar now stores submission identities in a local SQLite database. The default path is `data/executor-idempotency.sqlite3`, with `EXECUTOR_IDEMPOTENCY_DB_PATH` available for a persistent deployment volume. SQLite adds no new package dependency, works with the current Python 3.11+ deployment, and provides a transactional uniqueness constraint for one sidecar host or replicas sharing the same lock-capable filesystem.

### Reserve before the remote side effect

`IdempotencyStore.get` begins an immediate transaction and inserts a unique `PENDING` row before returning permission to submit. Only the caller holding that row's owner token can persist its terminal result. A second caller observing `PENDING` waits for the first caller's durable result and never receives a second submission grant.

### Replay exact terminal responses

Accepted and explicitly rejected responses are serialized as the normalized `OrderResponse` JSON. Repeated calls return that stored response exactly, including status, remote order identifier, fill fields, fee fields, and message. This preserves the current HTTP and Java response contract without adding a parallel API shape.

### Fail closed after ownership loss

Every orphaned `PENDING` row is changed to `UNKNOWN` when a new sidecar process initializes the database. The remote exchange may have accepted an order before the old process failed to persist the response, so restart cannot safely reinterpret that identifier as new work. `UNKNOWN`, ownership loss, and non-terminal failures raise through the existing non-2xx executor path; the T021 worker therefore moves the JVM dispatch to `RECONCILE` rather than recording a rejection or retrying blindly.

### Explicit durable states

The storage contract distinguishes:

- `PENDING`: exactly one active caller owns submission permission;
- `SUCCEEDED`: an accepted executor response is durable and replayable;
- `REJECTED`: an explicit deterministic rejection is durable and replayable;
- `UNKNOWN`: the prior remote outcome is not safely retryable and requires reconciliation.

### Submission identities are not a cache

The former `ttl_seconds` and `max_entries` constructor arguments remain source-compatible but are ignored. Accepted client order identifiers are retained indefinitely because age- or capacity-based eviction would recreate permission for a duplicate remote order. The operational document requires any future cleanup to prove the identifier can never be retried and to archive it before deletion.

### No endpoint or Java contract expansion

The order endpoint continues to call `get` before placement and `put` afterward. Terminal repeats remain successful responses. Pending and unknown states use the existing exception handler, producing a non-2xx response that the Java client already treats as an ambiguous executor failure. T023 owns detailed error taxonomy and operator reconciliation behavior.

## Tests run

Validation was performed in GitHub Actions because the connector environment did not provide a runnable local repository checkout.

### Implementation CI evidence

GitHub Actions CI run #282 (`31042245455`) on commit `b92841d0c5621acb200c5789fbeb5587d980b54e`: **PASS**.

- Python executor tests:
  - command: `python -m pytest -q executor-python/tests`
  - result: 29 passed, 3 warnings
  - all five focused durable-idempotency tests passed
- Java suite and no-regression policy:
  - command: `./mvnw -B -ntp test`, followed by `scripts/ci/check_java_failure_baseline.py`
  - result: PASS
  - Surefire tests represented: 335
  - current known baseline failure identities: 24
  - unexpected or changed failure identities: 0
  - resolved baseline identities: 0
- Clean PostgreSQL migration:
  - command: `./mvnw -B -ntp -Dtest=CleanDatabaseMigrationTest test`
  - result: PASS through V27; T022 adds no PostgreSQL migration
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

### Focused durable-idempotency coverage

- a successful response survives construction of a new store against the same database;
- two concurrent store instances receive exactly one submission grant and the same final response;
- a process restart changes an orphaned `PENDING` reservation to `UNKNOWN` and blocks resubmission;
- explicit rejection and unknown failure remain distinct durable states;
- legacy TTL and capacity arguments cannot evict accepted submission identifiers.

### Defects found during implementation

- Repository inspection confirmed the prior implementation used an in-memory TTL cache and a non-atomic `get`/remote-submit/`put` sequence. It forgot accepted identifiers on restart and allowed concurrent duplicates to observe the same key as absent.
- No implementation defect was reported by the focused or full CI run after the durable store was committed.

## Safety impact

- Submission permission is committed before the exchange side effect.
- Process restart cannot forget an accepted or rejected client order identifier.
- Concurrent duplicate requests cannot both obtain permission to submit the same identifier.
- A crash after remote acceptance but before response persistence becomes `UNKNOWN`, never new work.
- Unknown outcomes propagate to the JVM's existing reconciliation quarantine.
- No live-capital enablement, strategy threshold, fee, control-plane, T042, or PR #12 change was made.

## Backward compatibility

- The existing `IdempotencyStore.get` and `put` call pattern remains valid.
- Existing constructor arguments remain accepted.
- Accepted and rejected duplicate HTTP responses preserve the shared `OrderResponse` contract.
- No Java source, shared executor contract, or PostgreSQL schema changed.
- The default database directory is already ignored by the repository's `data/` rule.

## Remaining risks

- Production must place `EXECUTOR_IDEMPOTENCY_DB_PATH` on storage that survives the intended process/container replacement model.
- Multiple sidecars on different hosts cannot coordinate through separate local SQLite files. They must share one lock-capable durable filesystem, use a single sidecar authority, or migrate this schema to a networked transactional store before horizontal distribution.
- Starting another store while a submission is active conservatively changes the orphanable reservation to `UNKNOWN`; this can reduce availability but cannot create a duplicate order.
- T023 still owns detailed ambiguous-outcome classification, remote-truth reconciliation, manual-review escalation, and operator visibility.
- T024 still owns removal of executor calls from remaining transactional paths.
- T026 remains the mandatory integrated phase-exit gate.

## Follow-up tasks

No task was added or reordered. T023 is now READY under the existing durable-lifecycle sequence.

## Completion checklist

- [x] Sidecar restart does not forget accepted client order identifiers.
- [x] Retry with the same client order identifier cannot obtain a second remote submission grant.
- [x] Pending, succeeded, rejected, and unknown states are durable and documented.
- [x] Concurrent duplicate behavior is tested.
- [x] Restart-orphan behavior is tested.
- [x] Storage and cleanup policy is documented.
- [x] Full CI no-regression policy passes.
- [x] Required implementation report added.
- [x] Detailed task ledger and index updated.