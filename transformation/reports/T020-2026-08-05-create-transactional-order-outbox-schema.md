# T020 - Create transactional order outbox schema

## Summary

Implemented an additive transactional order-intent and dispatch-outbox persistence boundary. Accepted work can now be stored durably before any remote exchange side effect, with immutable client identity, restart-resumable payload data, retry/lease fields, and explicit unknown-outcome metadata.

The implementation is intentionally limited to schema, domain/repository support, transactional acceptance, recovery, and focused tests. Existing execution routing is not rewired in this task because T021 owns the post-commit worker and T024 owns removal of the remaining executor calls from transactional paths.

## Task details

- Task: T020
- Phase: P2 - Durable order lifecycle
- Branch: `task/T020-transactional-order-outbox-schema`
- Pull request: #31 (draft)
- Base: `transformation/2.0`
- Started: 2026-08-05T04:53:34Z
- Completed: 2026-08-05T05:17:54Z

## Files changed

- `src/main/resources/db/migration/V26__transactional_order_intent_outbox.sql`
- `src/main/java/com/vokerg/voktrader/trade/outbox/AcceptedOrderIntent.java`
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchOutboxEntity.java`
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchOutboxRepository.java`
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchState.java`
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderIntentEntity.java`
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderIntentRepository.java`
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderIntentState.java`
- `src/main/java/com/vokerg/voktrader/trade/outbox/TransactionalOrderIntentService.java`
- `src/test/java/com/vokerg/voktrader/trade/outbox/TransactionalOrderIntentServiceTest.java`
- `transformation/tasks/PHASE-2-durable-order-lifecycle.md`
- `transformation/tasks/INDEX.md`

## Design decisions

### Separate immutable intent and mutable dispatch state

`order_intents` stores the accepted command identity and immutable replay payload. `order_dispatch_outbox` stores mutable delivery state. A one-to-one foreign key and unique `client_order_id` constraints prevent multiple dispatch rows for one accepted intent.

### Durable identity

The service serializes the complete `TradeIntent`, hashes the payload with SHA-256, and derives a stable `clientOrderId` from the risk-decision ID plus the intent hash. Repeating the same accepted decision is idempotent; a mismatched reuse is rejected as an identity collision.

### Resume metadata

The dispatch row includes state, attempt count, next-attempt time, lease owner and expiry, last-attempt/submission/completion times, remote order ID, last error, and unknown-outcome timestamp/reason/details. This is sufficient for T021 and T023 to resume or reconcile work after restart without reconstructing the original command from memory.

### Transaction boundary

`TransactionalOrderIntentService.accept` writes the immutable intent and `OUTBOX_READY` dispatch row in one Spring transaction. The service has no executor client dependency and performs no network call. A rollback test verifies that an outer transaction failure leaves neither row behind.

### Database timestamp precision

Acceptance timestamps are truncated to microsecond precision before persistence. This matches PostgreSQL timestamp precision and ensures the initially returned acceptance record is identical to an idempotent or restart-recovered record.

### No temporary routing bridge

Current order routing remains unchanged. Wiring accepted rows into the current immediate-submit path would either duplicate remote submissions or stop execution before T021 provides a worker. T021 will consume `OUTBOX_READY`; T024 will remove remaining executor calls from database transactions.

### Compatibility and backfill

No backfill was required. V26 is additive, leaves existing trade/order tables unchanged, and introduces a new authority for orders accepted through the durable boundary. Existing rows remain under the current lifecycle until the T021/T024 integration work lands.

## Tests run

Validation was performed in GitHub Actions because the connector environment did not provide a runnable local repository checkout.

### Final CI evidence

GitHub Actions CI run #264 (`30977604161`) on commit `4918044e3df494f7133044877a50823e92978157`: **PASS**.

- Java suite and no-regression policy:
  - command: `./mvnw -B -ntp test`, followed by `scripts/ci/check_java_failure_baseline.py`
  - result: PASS
  - Surefire tests represented: 329
  - current known baseline failure identities: 24
  - unexpected or changed failure identities: 0
  - resolved baseline identities: 0
  - all five `TransactionalOrderIntentServiceTest` cases passed
- Clean PostgreSQL migration:
  - command: `./mvnw -B -ntp -Dtest=CleanDatabaseMigrationTest test`
  - result: PASS; clean schema migrated through V26
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
  - gitleaks v8.24.3 against the current repository tree
  - result: PASS
- Java/executor compose smoke:
  - command: `bash scripts/ci/smoke-compose.sh`
  - result: PASS

### Defects found during validation

- Run #261 exposed an invalid assumption that the application exported the legacy Jackson `ObjectMapper` as a Spring bean. The service now owns a Java-time-aware mapper instead of adding an application-wide dependency.
- Run #262 exposed missing integration-test fixture wiring for `BotRuntimeManager`. The focused test now imports the repository-standard `ExecutorTestConfig`.
- Run #263 exposed nanosecond-to-microsecond timestamp round-trip drift. The service now normalizes acceptance timestamps to database precision.

## Safety impact

- Accepted order work can be committed locally before any remote side effect.
- The acceptance component cannot call the executor because it has no executor dependency.
- Unique client-order identity and one-to-one dispatch constraints reduce duplicate-work risk.
- Lease and unknown-outcome fields are persisted for later worker/reconciliation tasks.
- No live-capital enablement, strategy threshold change, fee change, or control-plane change was made.

## Backward compatibility

- The migration is additive and passed the clean PostgreSQL migration test.
- Existing order and trade entities are not modified or backfilled.
- Existing execution behavior is unchanged until the durable worker integration tasks land.

## Remaining risks

- `OUTBOX_READY` rows are not yet claimed or submitted; T021 owns worker concurrency and lease recovery.
- Durable executor-side idempotency remains T022.
- Ambiguous remote outcomes remain T023.
- Existing transactional executor calls remain until T024.
- Durable cancellation state remains T025.
- T026 remains the mandatory integrated phase-exit gate.

## Follow-up tasks

No new task was added and no task was reordered. T021 is now READY under the existing durable-lifecycle dependency chain.

## Completion checklist

- [x] Acceptance transaction writes intent and outbox row without calling executor.
- [x] Clean-database migration test passes.
- [x] Outbox row contains enough data to resume after restart.
- [x] Focused rollback, recovery, and idempotency tests pass.
- [x] Full CI no-regression policy passes.
- [x] Required implementation report added.
- [x] Detailed task ledger updated.
- [x] Task index updated.
